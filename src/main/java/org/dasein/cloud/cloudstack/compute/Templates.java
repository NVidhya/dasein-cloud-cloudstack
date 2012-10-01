/**
 * Copyright (C) 2009-2012 enStratus Networks Inc
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */

package org.dasein.cloud.cloudstack.compute;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletResponse;

import org.dasein.cloud.AsynchronousTask;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.CloudProvider;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.OperationNotSupportedException;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.cloudstack.CloudstackException;
import org.dasein.cloud.cloudstack.CloudstackMethod;
import org.dasein.cloud.cloudstack.CloudstackProvider;
import org.dasein.cloud.cloudstack.Param;
import org.dasein.cloud.cloudstack.Zones;
import org.dasein.cloud.compute.Architecture;
import org.dasein.cloud.compute.MachineImage;
import org.dasein.cloud.compute.MachineImageFormat;
import org.dasein.cloud.compute.MachineImageState;
import org.dasein.cloud.compute.MachineImageSupport;
import org.dasein.cloud.compute.MachineImageType;
import org.dasein.cloud.compute.Platform;
import org.dasein.cloud.compute.Snapshot;
import org.dasein.cloud.compute.SnapshotState;
import org.dasein.cloud.compute.VirtualMachine;
import org.dasein.cloud.compute.VmState;
import org.dasein.cloud.identity.ServiceAction;
import org.dasein.util.CalendarWrapper;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class Templates implements MachineImageSupport {
    static private final String CREATE_TEMPLATE             = "createTemplate";
    static private final String DELETE_TEMPLATE             = "deleteTemplate";
    static private final String LIST_OS_TYPES               = "listOsTypes";
    static private final String LIST_TEMPLATES              = "listTemplates";
    static private final String REGISTER_TEMPLATE           = "registerTemplate";
    static private final String UPDATE_TEMPLATE_PERMISSIONS = "updateTemplatePermissions";
    
    private CloudstackProvider provider;
    
    public Templates(CloudstackProvider provider) {
        this.provider = provider;
    }
    
    @Override
    public void downloadImage(String machineImageId, OutputStream toOutput) throws CloudException, InternalException {
        throw new OperationNotSupportedException("Images are not downloadable from Cloudstack.");
    }
    
    @Override
    public MachineImage getMachineImage(String templateId) throws InternalException, CloudException {
        CloudstackMethod method = new CloudstackMethod(provider);
        String url = method.buildUrl(LIST_TEMPLATES, new Param[] { new Param("id", templateId), new Param("templateFilter", "executable"), new Param("zoneId", provider.getContext().getRegionId()) });
        Document doc;
        try {
            doc = method.get(url);
            if( doc == null ) {
                return null;
            }
        }
        catch( CloudstackException e ) {
            if( e.getHttpCode() == 431 ) {
                return null;
            }
            if( e.getMessage() != null && e.getMessage().contains("specify a valid template ID") ) {
                return null;
            }
            throw e;
        }
        NodeList matches = doc.getElementsByTagName("template");
        for( int i=0; i<matches.getLength(); i++ ) {
            Node node = matches.item(i);
            
            MachineImage image = toImage(node, false);
            
            if( image != null ) {
                return image;
            }
        }
        return null;
    }
    
    @Override
    public String getProviderTermForImage(Locale locale) {
        return "template";
    }

    private String getRootVolume(String serverId) throws InternalException, CloudException {
        return provider.getComputeServices().getVolumeSupport().getRootVolumeId(serverId);
    }
    
    private Architecture guess(String desc) {
        Architecture arch = Architecture.I64;
        
        if( desc.contains("x64") ) {
            arch = Architecture.I64;
        }
        else if( desc.contains("x32") ) {
            arch = Architecture.I32;
        }
        else if( desc.contains("64 bit") ) {
            arch = Architecture.I64;
        }
        else if( desc.contains("32 bit") ) {
            arch = Architecture.I32;
        }
        else if( desc.contains("i386") ) {
            arch = Architecture.I32;
        }
        else if( desc.contains("64") ) {
            arch = Architecture.I64;
        }
        else if( desc.contains("32") ) {
            arch = Architecture.I32;
        }
        return arch;
    }
    
    private void guessSoftware(MachineImage image) {
        String str = (image.getName() + " " + image.getDescription()).toLowerCase();
        StringBuilder software = new StringBuilder();
        boolean comma = false;
        
        if( str.contains("sql server") ) {
            if( comma ) {
                software.append(",");
            }
            if( str.contains("sql server 2008") ) {
                software.append("SQL Server 2008");
            }
            else if( str.contains("sql server 2005") ) {
                software.append("SQL Server 2005");
            }
            else {
                software.append("SQL Server 2008");
            }
            comma = true;
        }
        image.setSoftware(software.toString());
    }
    
    @Override
    public boolean hasPublicLibrary() {
        return true;
    }
    
    @Override
    public AsynchronousTask<String> imageVirtualMachine(String vmId, String name, String description) throws CloudException, InternalException {
        final VirtualMachine server = provider.getComputeServices().getVirtualMachineSupport().getVirtualMachine(vmId);
        
        if( !server.getCurrentState().equals(VmState.PAUSED) ) {
            throw new CloudException("The server must be paused in order to create an image.");
        }
        final AsynchronousTask<String> task = new AsynchronousTask<String>();
        final String fname = name;
        final String fdesc = description;
        
        Thread t = new Thread() {
            public void run() {
                try {
                    MachineImage image = imageVirtualMachine(server, fname, fdesc, task);
                
                    task.completeWithResult(image.getProviderMachineImageId());
                }
                catch( Throwable t ) {
                    task.complete(t);
                }
            }
        };

        t.start();
        return task;
    }
    

    @Override
    public AsynchronousTask<String> imageVirtualMachineToStorage(String vmId, String name, String description, String directory) throws CloudException, InternalException {
        throw new OperationNotSupportedException("Cloudstack does not image to storage.");
    }
    
    private MachineImage imageVirtualMachine(VirtualMachine server, String name, String description, AsynchronousTask<String> task) throws CloudException, InternalException {
        CloudstackMethod method = new CloudstackMethod(provider);
        Document doc;
        
        String rootVolumeId = getRootVolume(server.getProviderVirtualMachineId());
        
        if( rootVolumeId == null ) {
            throw new CloudException("No root volume is attached to the target server.");
        }
        String snapshotId = provider.getComputeServices().getSnapshotSupport().create(rootVolumeId, "Template Bundling at " + (new Date()));
        
        Snapshot s = provider.getComputeServices().getSnapshotSupport().getSnapshot(snapshotId);
        long timeout = System.currentTimeMillis() + (CalendarWrapper.MINUTE * 30L);
        
        while( !s.getCurrentState().equals(SnapshotState.AVAILABLE) ) {
            if( System.currentTimeMillis() > timeout ) {
                throw new CloudException("Timeout in snapshotting root volume.");
            }
            try { Thread.sleep(15000L); }
            catch( InterruptedException e ) { }
            s = provider.getComputeServices().getSnapshotSupport().getSnapshot(snapshotId);
        }
        MachineImage img = getMachineImage(server.getProviderMachineImageId());
        String osId = (img == null ? null : (String)img.getTag("cloud.com.os.typeId"));
        name = validateName(name);
        Param[] params = new Param[8];
        
        params[0] = new Param("name", name);
        params[1] = new Param("displayText", name);
        params[2] = new Param("osTypeId", osId == null ? toOs(server.getPlatform(),server.getArchitecture()) : osId);
        params[3] = new Param("zoneId", provider.getContext().getRegionId());
        params[4] = new Param("isPublic", "false");
        params[5] = new Param("isFeatured", "false");
        params[6] = new Param("snapshotId", snapshotId);
        params[7] = new Param("passwordEnabled", String.valueOf(isPasswordEnabled(server.getProviderMachineImageId())));
        doc = method.get(method.buildUrl(CREATE_TEMPLATE, params));

        NodeList matches = doc.getElementsByTagName("templateid"); // v2.1
        String templateId = null;
        
        if( matches.getLength() > 0 ) {
            templateId = matches.item(0).getFirstChild().getNodeValue();
        }
        if( templateId == null ) {
            matches = doc.getElementsByTagName("id"); // v2.2
            if( matches.getLength() > 0 ) {
                templateId = matches.item(0).getFirstChild().getNodeValue();
            }
        }
        if( templateId == null ) {
            throw new CloudException("Failed to provide a template ID.");
        }
        provider.waitForJob(doc, "Create Template");
        return getMachineImage(templateId);
    }
    
    @Override
    public String installImageFromUpload(MachineImageFormat format, InputStream imageStream) throws CloudException, InternalException {
        throw new OperationNotSupportedException("Installing from upload is not currently supported in Cloudstack.");
    }
    
    @Override
    public boolean isImageSharedWithPublic(String templateId) throws CloudException, InternalException {
        CloudstackMethod method = new CloudstackMethod(provider);
        String url = method.buildUrl(LIST_TEMPLATES, new Param[] { new Param("templateFilter", "self") });
        Document doc = method.get(url);        
        NodeList matches = doc.getElementsByTagName("template");
        
        for( int i=0; i<matches.getLength(); i++ ) {
            Node node = matches.item(i);
            
            MachineImage image = toImage(node, true);
            
            if( image != null && image.getProviderMachineImageId().equals(templateId) ) {
                return true;
            }
        }
        return false;
    }
    
    private boolean isPasswordEnabled(String templateId) throws InternalException, CloudException {
        CloudstackMethod method = new CloudstackMethod(provider);
        String url = method.buildUrl(LIST_TEMPLATES, new Param[] { new Param("templateFilter", "executable") });
        Document doc = method.get(url);        
        NodeList matches = doc.getElementsByTagName("template");
        
        for( int i=0; i<matches.getLength(); i++ ) {
            Node node = matches.item(i);
            
            Boolean val = isPasswordEnabled(templateId, node);
            
            if( val != null ) {
                return val.booleanValue();
            }
        }
        return false;
    }
    
    private Boolean isPasswordEnabled(String templateId, Node node) {
        NodeList attributes = node.getChildNodes();
        boolean enabled = false;
        String id = null;
        
        for( int i=0; i<attributes.getLength(); i++ ) {
            Node attribute = attributes.item(i);
            String name = attribute.getNodeName().toLowerCase();
            String value;
            
            if( attribute.getChildNodes().getLength() > 0 ) {
                value = attribute.getFirstChild().getNodeValue();
            }
            else {
                value = null;
            }
            if( name.equalsIgnoreCase("id") ) {
                id = value;
            }
            else if( name.equalsIgnoreCase("passwordenabled") ) {
                enabled = (value != null && value.equalsIgnoreCase("true"));
            }
            if( id != null && enabled ) {
                break;
            }
        } 
        if( id == null || !id.equals(templateId) ) {
            return null;
        }
        return enabled;
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        CloudstackMethod method = new CloudstackMethod(provider);
        
        try {
            method.get(method.buildUrl(Zones.LIST_ZONES, new Param[] { new Param("available", "true") }));
            return true;
        }
        catch( CloudstackException e ) {
            int code = e.getHttpCode();

            if( code == HttpServletResponse.SC_FORBIDDEN || code == 401 || code == 531 ) {
                return false;
            }
            throw e;
        }
    }
    
    @Override
    public Iterable<MachineImage> listMachineImages() throws InternalException, CloudException {
        CloudstackMethod method = new CloudstackMethod(provider);
        Document doc = method.get(method.buildUrl(LIST_TEMPLATES, new Param[] { new Param("templateFilter", "self"), new Param("zoneId", provider.getContext().getRegionId()) }));
        ArrayList<MachineImage> templates = new ArrayList<MachineImage>();
        NodeList matches = doc.getElementsByTagName("template");
        
        for( int i=0; i<matches.getLength(); i++ ) {
            Node node = matches.item(i);
            
            MachineImage image = toImage(node, false);
            
            if( image != null ) {
                templates.add(image);
            }
        }
        return templates;
    }
    

    @Override
    public Iterable<MachineImage> listMachineImagesOwnedBy(String accountId) throws CloudException, InternalException {
        CloudstackMethod method = new CloudstackMethod(provider);
        Document doc = method.get(method.buildUrl(LIST_TEMPLATES, new Param[] { new Param("templateFilter", "featured"), new Param("zoneId", provider.getContext().getRegionId()) }));
        ArrayList<MachineImage> templates = new ArrayList<MachineImage>();
        NodeList matches = doc.getElementsByTagName("template");
        
        for( int i=0; i<matches.getLength(); i++ ) {
            Node node = matches.item(i);
            
            MachineImage image = toImage(node, false);
            
            if( image != null ) {
                templates.add(image);
            }
        }
        return templates;
    }
    
    @Override
    public Iterable<String> listShares(String templateId) throws CloudException, InternalException {
        CloudstackMethod method = new CloudstackMethod(provider);
        Document doc = method.get(method.buildUrl(LIST_TEMPLATES, new Param[] { new Param("id", templateId) }));
        TreeSet<String> accounts = new TreeSet<String>();
        NodeList matches = doc.getElementsByTagName("account");
        
        for( int i=0; i<matches.getLength(); i++ ) {
            Node node = matches.item(i);
            
            accounts.add(node.getFirstChild().getNodeValue());
        }
        return accounts;
    }
    
    @Override
    public Iterable<MachineImageFormat> listSupportedFormats() throws CloudException, InternalException {
        return Collections.emptyList();
    }

    @Override
    public @Nonnull String[] mapServiceAction(@Nonnull ServiceAction action) {
        return new String[0];
    }

    @Override
    public String registerMachineImage(String atStorageLocation) throws CloudException, InternalException {
        int idx = atStorageLocation.lastIndexOf("/");
        String name;
        
        if( idx > -1 ) {
            name = atStorageLocation.substring(idx+1);
        }
        else {
            name = atStorageLocation;
        }
        Platform platform = Platform.guess(name);
        Architecture architecture = guess(name);
        Param[] params = new Param[8];
        
        params[0] = new Param("name", name);
        params[1] = new Param("displayText", name);
        params[2] = new Param("url", atStorageLocation);
        params[3] = new Param("format", "RAW");
        params[4] = new Param("osTypeId", toOs(platform, architecture));
        params[5] = new Param("zoneId", provider.getContext().getRegionId());
        params[6] = new Param("isPublic", "false");
        params[7] = new Param("isFeatured", "false");

        CloudstackMethod method = new CloudstackMethod(provider);        
        Document doc = method.get(method.buildUrl(REGISTER_TEMPLATE, params));
        NodeList matches = doc.getElementsByTagName("templateid");
        String templateId = null;
        
        if( matches.getLength() > 0 ) {
            templateId = matches.item(0).getFirstChild().getNodeValue();
        }
        provider.waitForJob(doc, "Create Template");
        return templateId;
    }
    
    @Override
    public void remove(@Nonnull String templateId) throws InternalException, CloudException {
        ProviderContext ctx = provider.getContext();
        
        if( ctx == null ) {
            throw new InternalException("No context was set for the request");
        }
        String accountNumber = ctx.getAccountNumber();
        MachineImage img = getMachineImage(templateId);
        
        if( img == null ) {
            throw new CloudException("No such machine image: " + templateId);
        }
        if( !accountNumber.equals(img.getProviderOwnerId()) ) {
            throw new CloudException(accountNumber + " cannot remove images belonging to " + img.getProviderOwnerId());
        }
        CloudstackMethod method = new CloudstackMethod(provider);
        Document doc = method.get(method.buildUrl(DELETE_TEMPLATE, new Param[] { new Param("id", templateId) }));

        provider.waitForJob(doc, "Delete Template");
    }
    
    @Override
    public Iterable<MachineImage> searchMachineImages(String keyword, Platform platform, Architecture architecture) throws InternalException, CloudException {
        Param[] params;
            
        if( keyword == null ) {
            params = new Param[] { new Param("templateFilter", "executable"),  new Param("zoneId", provider.getContext().getRegionId()) };            
        }
        else {
            params = new Param[] { new Param("templateFilter", "executable"),  new Param("zoneId", provider.getContext().getRegionId()), new Param("keyword", keyword) };                            
        }
        CloudstackMethod method = new CloudstackMethod(provider);
        Document doc = method.get(method.buildUrl(LIST_TEMPLATES, params));            
        ArrayList<MachineImage> templates = new ArrayList<MachineImage>();
        NodeList matches = doc.getElementsByTagName("template");
        
        for( int i=0; i<matches.getLength(); i++ ) {
            MachineImage img = toImage(matches.item(i), false);
            
            if( img != null ) {
                if( architecture != null && !architecture.equals(img.getArchitecture()) ) {
                    continue;
                }
                if( platform != null && !platform.equals(Platform.UNKNOWN) ) {
                    Platform mine = img.getPlatform();
                    
                    if( platform.isWindows() && !mine.isWindows() ) {
                        continue;
                    }
                    if( platform.isUnix() && !mine.isUnix() ) {
                        continue;
                    }
                    if( platform.isBsd() && !mine.isBsd() ) {
                        continue;
                    }
                    if( platform.isLinux() && !mine.isLinux() ) {
                        continue;
                    }
                    if( platform.equals(Platform.UNIX) ) {
                        if( !mine.isUnix() ) {
                            continue;
                        }
                    }
                    else if( !platform.equals(mine) ) {
                        continue;
                    }
                }
                if( keyword != null && !keyword.equals("") ) {
                    keyword = keyword.toLowerCase();
                    if( !img.getProviderMachineImageId().toLowerCase().contains(keyword) ) {
                        if( !img.getName().toLowerCase().contains(keyword) ) {
                            if( !img.getDescription().toLowerCase().contains(keyword) ) {
                                continue;
                            }
                        }
                    }
                }               
                templates.add(img);
            }
        }
        return templates;
    }

    @Override
    public void shareMachineImage(String templateId, String withAccountId, boolean allow) throws CloudException, InternalException {
        MachineImage img = getMachineImage(templateId);
        
        if( img == null ) {
            return;
        }
        if( !provider.getContext().getAccountNumber().equals(img.getProviderOwnerId()) ) {
            return;
        }
        Param[] params;
        
        if( withAccountId == null ) {
            params = new Param[] { new Param("id", templateId), new Param("isPublic", String.valueOf(allow)) };
        }
        else {
            String action = (allow ? "add" : "remove");
            
            params = new Param[] { new Param("id", templateId), new Param("accounts", withAccountId), new Param("op", action) };                
        }
        CloudstackMethod method = new CloudstackMethod(provider);
        Document doc = method.get(method.buildUrl(UPDATE_TEMPLATE_PERMISSIONS, params));

        provider.waitForJob(doc, "Share Template");
    }
    
    @Override
    public boolean supportsCustomImages() {
        return true;
    }

    @Override
    public boolean supportsImageSharing() {
        return true;
    }

    @Override
    public boolean supportsImageSharingWithPublic() {
        return true;
    }
    
    private MachineImage toImage(Node node, boolean onlyIfPublic) throws CloudException, InternalException {
        Architecture bestArchitectureGuess = Architecture.I64;
        HashMap<String,String> properties = new HashMap<String,String>();
        NodeList attributes = node.getChildNodes();
        MachineImage image = new MachineImage();
        boolean isPublic = false;
        
        image.setProviderOwnerId(provider.getContext().getAccountNumber());
        image.setType(MachineImageType.STORAGE);
        image.setCurrentState(MachineImageState.PENDING);
        image.setProviderRegionId(provider.getContext().getRegionId());
        image.setTags(properties);
        for( int i=0; i<attributes.getLength(); i++ ) {
            Node attribute = attributes.item(i);
            String name = attribute.getNodeName().toLowerCase();
            String value;
            
            if( attribute.getChildNodes().getLength() > 0 ) {
                value = attribute.getFirstChild().getNodeValue();
            }
            else {
                value = null;
            }
            if( name.equals("id") ) {
                image.setProviderMachineImageId(value);
            }
            else if( name.equals("zoneid") ) {
                if( !provider.getContext().getRegionId().equals(value) ) {
                    return null;
                }
            }
            else if( name.equalsIgnoreCase("account") ) {
                if( value != null && value.startsWith("Customer [") ) {
                    value = value.substring("Customer [".length());
                    value = value.substring(0, value.length()-1);
                }
                image.setProviderOwnerId(value);
            }
            else if( name.equals("name") ) {
                image.setName(value);
                if( value != null && value.contains("x64") ) {
                    bestArchitectureGuess = Architecture.I64;
                }
                else if( value != null && value.contains("x32") ) {
                    bestArchitectureGuess = Architecture.I32;
                }
            }
            else if( name.equals("displaytext") ) {
                image.setDescription(value);
                if( value != null && value.contains("x64") ) {
                    bestArchitectureGuess = Architecture.I64;
                }
                else if( value != null && value.contains("x32") ) {
                    bestArchitectureGuess = Architecture.I32;
                }
            }
            else if( name.equals("ispublic") ) {
                isPublic = value.equalsIgnoreCase("true");
            }
            else if( name.equals("isfeatured") ) {
                //image.setType("featured");
                //TODO: examine this
            }
            else if( name.equals("ostypename") ) {
                if( value != null && value.contains("64") ) {
                    bestArchitectureGuess = Architecture.I64;
                }
                else if( value != null && value.contains("32") ) {
                    bestArchitectureGuess = Architecture.I32;
                }
                if( value != null ) {
                    image.setPlatform(Platform.guess(value));
                }
            }
            else if( name.equals("ostypeid") && value != null ) {
                image.getTags().put("cloud.com.os.typeId", value);
            }
            else if( name.equals("bits") ) {
                if( value.equals("64") ) {
                    image.setArchitecture(Architecture.I64);
                }
                else {
                    image.setArchitecture(Architecture.I32);
                }
            }
            else if( name.equals("created") ) {
                // 2010-06-29T20:49:28+1000
                // TODO: implement when dasein cloud supports template creation timestamps
            }
            else if( name.equals("isready") ) {
                if( value.equalsIgnoreCase("true") ) {
                    image.setCurrentState(MachineImageState.ACTIVE);
                }
            }
            else if( name.equals("status") ) {
                if( value == null || !value.equalsIgnoreCase("Download Complete") ) {
                    System.out.println("Template status=" + value);
                }
            }
        }
        if( image.getPlatform() == null && image.getName() != null ) {
            image.setPlatform(Platform.guess(image.getName()));
        }
        else {
            image.setPlatform(Platform.UNKNOWN);
        }
        if( image.getArchitecture() == null ) {
            image.setArchitecture(bestArchitectureGuess);
        }
        if( !onlyIfPublic || isPublic ) {
            guessSoftware(image);
            return image;
        }
        return null;
    }
    
    private String toOs(Platform platform, Architecture architecture) throws InternalException, CloudException {
        CloudstackMethod method = new CloudstackMethod(provider);
        Document doc = method.get(method.buildUrl(LIST_OS_TYPES, new Param[0]));
        NodeList matches = doc.getElementsByTagName("ostype");
        
        for( int i=0; i<matches.getLength(); i++ ) {
            NodeList attrs = matches.item(i).getChildNodes();
            Architecture arch = Architecture.I64;
            Platform pf = null;
            String id = null;
            
            for( int j=0; j<attrs.getLength(); j++ ) {
                Node attr = attrs.item(j);

                if( attr.getNodeName().equals("id") ) {
                    id = attr.getFirstChild().getNodeValue();
                }
                else if( attr.getNodeName().equals("description") ) {
                    String desc = attr.getFirstChild().getNodeValue();
                    
                    pf = Platform.guess(desc);
                    arch = guess(desc);
                }
            }
            if( platform.equals(pf) && architecture.equals(arch) ) {
                return id;
            }
        }
        return null;
    }

    @Override
    public String transfer(CloudProvider fromCloud, String machineImageId) throws CloudException, InternalException {
        throw new OperationNotSupportedException("Cloudstack does not support image transfer.");
    }
    
    private String validateName(String name) throws InternalException, CloudException {
        if( name.length() < 32 ) {
            return name;
        }
        name = name.substring(0,32);
        boolean found = false;
        int i = 0;
        
        do {
            found = false;
            for( MachineImage vm : listMachineImages() ) {
                if( vm.getName().equals(name) ) {
                    found = true;
                    break;
                }
            }
            if( found ) {
                i++;
                if( i < 10 ) {
                    name = name.substring(0,31) + i;
                }
                else if( i < 100 ) {
                    name = name.substring(0, 30) + i;
                }
                else {
                    name = name.substring(0, 29) + i;
                }
            }
        } while( found );
        return name;
    }
}
