/*
 * #%L
 * ACS AEM Tools Bundle
 * %%
 * Copyright (C) 2014 Adobe
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.adobe.acs.commons.wcm.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.Calendar;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.commons.io.IOUtils;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.commons.JcrUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.commons.mime.MimeTypeService;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.polling.importer.ImportException;
import com.day.cq.polling.importer.Importer;

@Component(label = "ACS AEM Commons - File Importer", description = "Importer which can import a file from the file system into the content repository.",
    metatype = true)
@Service
@Property(label = "Display Name", description = "Label which will be displayed in the Polling Importer Add... dialog",
        name = "displayName", value = "File")
public class FileImporter implements Importer {

    private static final Logger log = LoggerFactory.getLogger(FileImporter.class);

    private static final String DEFAULT_SCHEME = "file";

    @Reference
    private MimeTypeService mimeTypeService;

    @Property(label = "Importer Scheme",
            description = "Scheme value that will be used for this importer. Must be unique across importers.",
            value = DEFAULT_SCHEME)
    private static final String PROP_SCHEME = Importer.SCHEME_PROPERTY;

    private String scheme;

    @Activate
    protected void activate(Map<String, Object> properties) {
        this.scheme = PropertiesUtil.toString(properties.get(PROP_SCHEME), DEFAULT_SCHEME);
    }

    @Override
    public void importData(String schemeValue, String dataSource, Resource target) throws ImportException {
        if (scheme.equals(schemeValue)) {
            File file = new File(dataSource);
            if (file.exists()) {
                Calendar fileLastMod = Calendar.getInstance();
                fileLastMod.setTimeInMillis(file.lastModified());
                String fileName = file.getName();
                String mimeType = mimeTypeService.getMimeType(fileName);

                final Node targetParent;
                final String targetName;

                FileInputStream stream = null;

                Node node = target.adaptTo(Node.class);
                if (node != null) {
                    try {
                        if (node.isNodeType(JcrConstants.NT_FILE)) {
                            // assume that we are intending to replace this file
                            targetParent = node.getParent();
                            targetName = node.getName();
                            Calendar nodeLastMod = JcrUtils.getLastModified(node);
                            if (!nodeLastMod.before(fileLastMod)) {
                                log.info("File '{}' does not have a newer timestamp than '{}'. Skipping import.", dataSource, target);
                                return;
                            }
                        } else {
                            // assume that we are creating a new file under the current node
                            targetParent = node;
                            targetName = fileName;
                            if (targetParent.hasNode(targetName)) {
                                Node targetNode = targetParent.getNode(targetName);
                                Calendar nodeLastMod = JcrUtils.getLastModified(targetNode);
                                if (!nodeLastMod.before(fileLastMod)) {
                                    log.info("File '{}' does not have a newer timestamp than '{}'. Skipping import.", dataSource, targetNode.getPath());
                                    return;
                                }
                            }
                        }

                        stream = new FileInputStream(file);
                        JcrUtils.putFile(targetParent, targetName, mimeType, stream);
                        node.getSession().save();
                    } catch (RepositoryException e) {
                        throw new ImportException("Unable to import from file '" + dataSource + "' to '"
                                + target.getPath() + "'", e);
                    } catch (FileNotFoundException e) {
                        throw new ImportException("Unexpected FileNotFoundException while importing", e);
                    } finally {
                        if (stream != null) {
                            IOUtils.closeQuietly(stream);
                        }
                    }
                } else {
                    log.warn("Target '{}' is not a JCR node. Skipping import from '{}'.", target.getPath(), dataSource);
                }
            } else {
                log.warn("File at '{}' does not exist. Skipping import.", dataSource);
            }
        } else {
            log.warn("Unrecognized scheme '{}' passed to importData()", schemeValue);
        }
    }
}