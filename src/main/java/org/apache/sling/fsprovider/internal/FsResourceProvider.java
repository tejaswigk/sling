/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.fsprovider.internal;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.collections.IteratorUtils;
import org.apache.commons.collections.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceProvider;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.fsprovider.internal.mapper.ContentFileResourceMapper;
import org.apache.sling.fsprovider.internal.mapper.FileResourceMapper;
import org.apache.sling.fsprovider.internal.parser.ContentFileCache;
import org.apache.sling.fsprovider.internal.parser.ContentFileTypes;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * The <code>FsResourceProvider</code> is a resource provider which maps
 * file system files and folders into the virtual resource tree. The provider is
 * implemented in terms of a component factory, that is multiple instances of
 * this provider may be created by creating respective configuration.
 * <p>
 * Each provider instance is configured with two properties: The location in the
 * resource tree where resources are provided (provider.root)
 * and the file system path from where files and folders are mapped into the
 * resource (provider.file).
 */
@Component(name="org.apache.sling.fsprovider.internal.FsResourceProvider",
           service=ResourceProvider.class,
           configurationPolicy=ConfigurationPolicy.REQUIRE,
           property={
                   Constants.SERVICE_DESCRIPTION + "=Sling Filesystem Resource Provider",
                   Constants.SERVICE_VENDOR + "=The Apache Software Foundation"
           })
@Designate(ocd=FsResourceProvider.Config.class, factory=true)
public final class FsResourceProvider implements ResourceProvider {
    
    @ObjectClassDefinition(name = "Apache Sling Filesystem Resource Provider",
            description = "Configure an instance of the filesystem " +
                          "resource provider in terms of provider root and filesystem location")
    public @interface Config {
        /**
         * The name of the configuration property providing file system path of
         * files and folders mapped into the resource tree (value is
         * "provider.file").
         */
        @AttributeDefinition(name = "Filesystem Root",
                description = "Filesystem directory mapped to the virtual " +
                        "resource tree. This property must not be an empty string. If the path is " +
                        "relative it is resolved against sling.home or the current working directory. " +
                        "The path may be a file or folder. If the path does not address an existing " +
                        "file or folder, an empty folder is created.")
        String provider_file();

        /**
         * The name of the configuration property providing the check interval
         * for file changes (value is "provider.checkinterval").
         */
        @AttributeDefinition(name = "Check Interval",
                             description = "If the interval has a value higher than 100, the provider will " +
             "check the file system for changes periodically. This interval defines the period in milliseconds " +
             "(the default is 1000). If a change is detected, resource events are sent through the event admin.")
        long provider_checkinterval() default 1000;

        @AttributeDefinition(name = "Provider Roots",
                description = "Locations in the virtual resource tree where the " +
                "filesystem resources are mapped in. This property must contain at least one non-empty string.")
        String[] provider_roots();
        
        @AttributeDefinition(name = "Mount json",
                description = "Mount .json files as content in the resource hierarchy.")
        boolean provider_json_content();
       
        @AttributeDefinition(name = "Mount jcr.xml",
                description = "Mount .jcr.xml files as content in the resource hierarchy.")
        boolean provider_jcrxml_content();
        
        @AttributeDefinition(name = "Cache Size",
                description = "Max. number of content files cached in memory.")
        int provider_cache_size() default 1000;

        /**
         * Internal Name hint for web console.
         */
        String webconsole_configurationFactory_nameHint() default "Root paths: {" + ResourceProvider.ROOTS + "}";
    }

    // The location in the resource tree where the resources are mapped
    private String providerRoot;

    // The "root" file or folder in the file system
    private File providerFile;

    // The monitor to detect file changes.
    private FileMonitor monitor;
    
    // maps filesystem to resources
    private FsResourceMapper fileMapper;
    private FsResourceMapper contentFileMapper;
    
    // cache for parsed content files
    private ContentFileCache contentFileCache;

    @Reference(cardinality=ReferenceCardinality.OPTIONAL, policy=ReferencePolicy.DYNAMIC)
    private volatile EventAdmin eventAdmin;
    
    @Override
    public Resource getResource(ResourceResolver resourceResolver, HttpServletRequest request, String path) {
        return getResource(resourceResolver, path);
    }

    /**
     * Returns a resource wrapping a file system file or folder for the given
     * path. If the <code>path</code> is equal to the configured resource tree
     * location of this provider, the configured file system file or folder is
     * used for the resource. Otherwise the configured resource tree location
     * prefix is removed from the path and the remaining relative path is used
     * to access the file or folder. If no such file or folder exists, this
     * method returns <code>null</code>.
     */
    @Override
    public Resource getResource(ResourceResolver resolver, String path) {
        Resource rsrc = contentFileMapper.getResource(resolver, path);
        if (rsrc == null) {
            rsrc = fileMapper.getResource(resolver, path);
        }
        return rsrc;
    }
    
    /**
     * Returns an iterator of resources.
     */
    @SuppressWarnings("unchecked")
    @Override
    public Iterator<Resource> listChildren(Resource parent) {
        ResourceResolver resolver = parent.getResourceResolver();
        
        List<Iterator<Resource>> allChildren = new ArrayList<>();
        Iterator<Resource> children;
        
        children = contentFileMapper.getChildren(resolver, parent);
        if (children != null) {
            allChildren.add(children);
        }
        
        children = fileMapper.getChildren(resolver, parent);
        if (children != null) {
            allChildren.add(children);
        }
        
    	if (allChildren.isEmpty()) {
    	    return null;
    	}
    	else if (allChildren.size() == 1) {
    	    return allChildren.get(0);
    	}
    	else {
    	    // merge all children from the different iterators, but filter out potential duplicates with same resource name
    	    return IteratorUtils.filteredIterator(IteratorUtils.chainedIterator(allChildren), new Predicate() {
    	        private Set<String> names = new HashSet<>();
                @Override
                public boolean evaluate(Object object) {
                    Resource resource = (Resource)object;
                    return names.add(resource.getName());
                }
            });
    	}
    }

    // ---------- SCR Integration
    @Activate
    protected void activate(BundleContext bundleContext, final Config config) {
        String[] providerRoots = config.provider_roots();
        if (providerRoots == null || providerRoots.length != 1 || StringUtils.isBlank(providerRoots[0])) {
            throw new IllegalArgumentException("provider.roots property must be set to exactly one entry.");
        }
        String providerRoot = config.provider_roots()[0];

        String providerFileName = config.provider_file();
        if (providerFileName == null || providerFileName.length() == 0) {
            throw new IllegalArgumentException("provider.file property must be set");
        }

        this.providerRoot = providerRoot;
        this.providerFile = getProviderFile(providerFileName, bundleContext);
        
        List<String> contentFileSuffixes = new ArrayList<>();
        if (config.provider_json_content()) {
            contentFileSuffixes.add(ContentFileTypes.JSON_SUFFIX);
        }
        if (config.provider_jcrxml_content()) {
            contentFileSuffixes.add(ContentFileTypes.JCR_XML_SUFFIX);
        }
        ContentFileExtensions contentFileExtensions = new ContentFileExtensions(contentFileSuffixes);
        
        this.contentFileCache = new ContentFileCache(config.provider_cache_size());
        this.fileMapper = new FileResourceMapper(this.providerRoot, this.providerFile, contentFileExtensions);
        this.contentFileMapper = new ContentFileResourceMapper(this.providerRoot, this.providerFile,
                contentFileExtensions, this.contentFileCache);
        
        // start background monitor if check interval is higher than 100
        if ( config.provider_checkinterval() > 100 ) {
            this.monitor = new FileMonitor(this, config.provider_checkinterval(),
                    contentFileExtensions, this.contentFileCache);
        }
    }

    @Deactivate
    protected void deactivate() {
        if ( this.monitor != null ) {
            this.monitor.stop();
            this.monitor = null;
        }
        this.providerRoot = null;
        this.providerFile = null;
        this.fileMapper = null;
        this.contentFileMapper = null;
        if (this.contentFileCache != null) {
            this.contentFileCache.clear();
            this.contentFileCache = null;
        }
    }

    EventAdmin getEventAdmin() {
        return this.eventAdmin;
    }

    File getRootFile() {
        return this.providerFile;
    }

    String getProviderRoot() {
        return this.providerRoot;
    }

    // ---------- internal

    private File getProviderFile(String providerFileName,
            BundleContext bundleContext) {

        // the file object from the plain name
        File providerFile = new File(providerFileName);

        // resolve relative file name against sling.home or current
        // working directory
        if (!providerFile.isAbsolute()) {
            String home = bundleContext.getProperty("sling.home");
            if (home != null && home.length() > 0) {
                providerFile = new File(home, providerFileName);
            }
        }

        // resolve the path
        providerFile = providerFile.getAbsoluteFile();

        // if the provider file does not exist, create an empty new folder
        if (!providerFile.exists() && !providerFile.mkdirs()) {
            throw new IllegalArgumentException(
                    "Cannot create provider file root " + providerFile);
        }

        return providerFile;
    }

}