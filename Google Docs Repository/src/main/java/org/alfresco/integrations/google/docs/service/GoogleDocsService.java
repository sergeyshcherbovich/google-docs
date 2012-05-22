
package org.alfresco.integrations.google.docs.service;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.alfresco.integrations.AbstractIntegration;
import org.alfresco.integrations.google.docs.GoogleDocsConstants;
import org.alfresco.integrations.google.docs.GoogleDocsModel;
import org.alfresco.integrations.google.docs.exceptions.GoogleDocsAuthenticationException;
import org.alfresco.integrations.google.docs.exceptions.GoogleDocsServiceException;
import org.alfresco.integrations.google.docs.exceptions.GoogleDocsTypeException;
import org.alfresco.integrations.google.docs.exceptions.MustDowngradeFormatException;
import org.alfresco.integrations.google.docs.exceptions.MustUpgradeFormatException;
import org.alfresco.integrations.google.docs.utils.FileNameUtil;
import org.alfresco.model.ContentModel;
import org.alfresco.query.CannedQueryPageDetails;
import org.alfresco.query.PagingRequest;
import org.alfresco.query.PagingResults;
import org.alfresco.service.cmr.lock.LockService;
import org.alfresco.service.cmr.lock.LockStatus;
import org.alfresco.service.cmr.lock.LockType;
import org.alfresco.service.cmr.lock.NodeLockedException;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.oauth2.OAuth2StoreService;
import org.alfresco.service.cmr.remotecredentials.OAuth2CredentialsInfo;
import org.alfresco.service.cmr.remoteticket.NoSuchSystemException;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.namespace.QName;
import org.alfresco.util.Pair;
import org.alfresco.util.TempFileProvider;
import org.apache.commons.httpclient.HttpStatus;
import org.springframework.core.io.Resource;
import org.springframework.social.ApiException;
import org.springframework.social.connect.Connection;
import org.springframework.social.google.docs.api.GoogleDocs;
import org.springframework.social.google.docs.connect.GoogleDocsConnectionFactory;
import org.springframework.social.oauth2.AccessGrant;
import org.springframework.social.oauth2.GrantType;
import org.springframework.social.oauth2.OAuth2Parameters;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.google.gdata.client.docs.DocsService;
import com.google.gdata.client.media.ResumableGDataFileUploader;
import com.google.gdata.data.MediaContent;
import com.google.gdata.data.PlainTextConstruct;
import com.google.gdata.data.docs.DocumentEntry;
import com.google.gdata.data.docs.DocumentListEntry;
import com.google.gdata.data.docs.PresentationEntry;
import com.google.gdata.data.docs.SpreadsheetEntry;
import com.google.gdata.data.media.MediaFileSource;
import com.google.gdata.data.media.MediaSource;
import com.google.gdata.util.ServiceException;

public class GoogleDocsService extends AbstractIntegration implements GoogleDocsConstants
{
    //Services
    private OAuth2StoreService oauth2StoreService;
    private GoogleDocsConnectionFactory connectionFactory;
    private FileFolderService fileFolderService;
    private NodeService nodeService;
    private LockService lockservice;

    //Property Mappings
    private Map<String, String> importFormats = new HashMap<String, String>();
    private Map<String, Map<String, String>> exportFormats = new HashMap<String, Map<String, String>>();
    private Map<String, String> upgradeMappings = new HashMap<String, String>();
    private Map<String, String> downgradeMappings = new HashMap<String, String>();
    
    //New Content
    private Resource newDocument;
    private Resource newSpreadsheet;
    private Resource newPresentation;

    public void setImportFormats(Map<String, String> importFormats)
    {
        this.importFormats = importFormats;
    }

    public void setExportFormats(Map<String, Map<String, String>> exportFormats)
    {
        this.exportFormats = exportFormats;
    }

    public void setUpgradeMappings(Map<String, String> upgradeMappings)
    {
        this.upgradeMappings = upgradeMappings;
    }

    public void setDowngradeMappings(Map<String, String> downgradeMappings)
    {
        this.downgradeMappings = downgradeMappings;
    }

    public void setOauth2StoreService(OAuth2StoreService oauth2StoreService)
    {
        this.oauth2StoreService = oauth2StoreService;
    }

    public void setConnectionFactory(GoogleDocsConnectionFactory connectionFactory)
    {
        this.connectionFactory = connectionFactory;
    }

    public void setFileFolderService(FileFolderService fileFolderService)
    {
        this.fileFolderService = fileFolderService;
    }
    
    public void setNodeService(NodeService nodeService)
    {
        this.nodeService = nodeService;
    }
    
    public void setLockService(LockService lockService)
    {
        this.lockservice = lockService;
    }

    public ArrayList<String> getImportFormatsList()
    {
        return new ArrayList<String>(importFormats.keySet());
    }
    
    public void setNewDocument(Resource newDocument)
    {
        this.newDocument = newDocument;
    }
    
    public void setNewSpreadsheet(Resource newSpreadsheet)
    {
        this.newSpreadsheet = newSpreadsheet;
    }
    
    public void setNewPresentation(Resource newPresentation)
    {
        this.newPresentation = newPresentation;
    }

    public boolean isImportable(String mimetype)
    {
        return importFormats.containsKey(mimetype);
    }

    public String getImportType(String mimetype)
    {
        return importFormats.get(mimetype);
    }

    public boolean isExportable(String mimetype) throws MustUpgradeFormatException,
                MustDowngradeFormatException
    {
        if (isUpgrade(mimetype))
        {
            throw new MustUpgradeFormatException();
        }
        else if (isDownGrade(mimetype)) { throw new MustDowngradeFormatException(); }

        String type = getImportType(mimetype);
        Set<String> exportMimetypes = getExportableMimeTypes(type);

        return exportMimetypes.contains(mimetype);
    }

    public Set<String> getExportableMimeTypes(String type)
    {
        Set<String> mimetypes = new HashSet<String>();

        if (exportFormats.containsKey(type))
        {
            mimetypes = exportFormats.get(type).keySet();
        }

        return mimetypes;
    }

    public boolean isUpgrade(String mimetype)
    {
        return upgradeMappings.containsKey(mimetype);
    }

    public boolean isDownGrade(String mimetype)
    {
        return downgradeMappings.containsKey(mimetype);
    }
    
    public String getContentType(NodeRef nodeRef){
        String contentType = null;
        
        String mimetype = fileFolderService.getFileInfo(nodeRef).getContentData().getMimetype();
        
        contentType = importFormats.get(mimetype);
        
        return contentType;
    }
    
    public String getExportFormat(String contentType, String mimeType)
    {
        String exportFormat = null;
        
        mimeType = validateMimeType(mimeType);
        
        if (exportFormats.containsKey(contentType))
        {
            exportFormat = exportFormats.get(contentType).get(mimeType);
        }
        
        return exportFormat;
    }
    
    public String validateMimeType(String mimeType){
        
        if (isDownGrade(mimeType))
        {
            mimeType = downgradeMappings.get(mimeType);
        } 
        else if (isUpgrade(mimeType))
        {
            mimeType = upgradeMappings.get(mimeType);
        }
        
        return mimeType;
    }

    private Connection<GoogleDocs> getConnection() throws GoogleDocsAuthenticationException
    {
        Connection<GoogleDocs> connection = null;

        OAuth2CredentialsInfo credentialInfo = oauth2StoreService
                    .getOAuth2Credentials(REMOTE_SYSTEM);

        if (credentialInfo != null)
        {

            AccessGrant accessGrant = new AccessGrant(credentialInfo.getOAuthAccessToken());

            try
            {
                connection = connectionFactory.createConnection(accessGrant);
            }
            catch (ApiException ae)
            {
                if (ae.getCause() instanceof ServiceException){
                    ServiceException se = (ServiceException) ae.getCause();
                    if (se.getHttpErrorCodeOverride() == HttpStatus.SC_UNAUTHORIZED){
                        accessGrant = refreshAccessToken();
                        connection = connectionFactory.createConnection(accessGrant);
                    }
                } 
            }
        }

        return connection;
    }

    public boolean isAuthenticated()
    {
        boolean authenticated = false;

        OAuth2CredentialsInfo credentialInfo = oauth2StoreService
                    .getOAuth2Credentials(REMOTE_SYSTEM);

        if (credentialInfo != null)
        {
            authenticated = true;
        }

        return authenticated;
    }

    public String getAuthenticateUrl(String state)
    {
        String authenticateUrl = null;

        if (state != null)
        {

            /*
             * When we change to spring social 1.0.2 OAuth2Parameters will need
             * to be updated OAuth2Parameters parameters = new
             * OAuth2Parameters(); parameters.setRedirectUri(REDIRECT_URI);
             * parameters.setScope(SCOPE); parameters.setState(state);
             */
            
            //TODO Add offline
            
            MultiValueMap<String, String> additionalParameters = new LinkedMultiValueMap<String, String>(1);
            additionalParameters.add("access_type", "offline");
            
            OAuth2Parameters parameters = new OAuth2Parameters(REDIRECT_URI, SCOPE, state, additionalParameters);
            parameters.getAdditionalParameters();
            authenticateUrl = connectionFactory.getOAuthOperations().buildAuthenticateUrl(
                        GrantType.AUTHORIZATION_CODE, parameters);

        }

        return authenticateUrl;
    }

    public boolean completeAuthentication(String access_token)
    {
        boolean authenticationComplete = false;

        AccessGrant accessGrant = connectionFactory.getOAuthOperations().exchangeForAccess(
                    access_token, REDIRECT_URI, null);

        Date expiresIn = null;

        if (accessGrant.getExpireTime() != null)
        {
            if (accessGrant.getExpireTime() > 0L)
            {
                expiresIn = new Date(new Date().getTime() + accessGrant.getExpireTime());
            }
        }

        try
        {
            oauth2StoreService.storeOAuth2Credentials(REMOTE_SYSTEM, accessGrant.getAccessToken(),
                        accessGrant.getRefreshToken(), expiresIn, new Date());

            authenticationComplete = true;
        }
        catch (NoSuchSystemException nsse)
        {
            throw new GoogleDocsAuthenticationException(nsse.getMessage());
        }

        return authenticationComplete;
    }
    
    private AccessGrant refreshAccessToken(){
        OAuth2CredentialsInfo credentialInfo = oauth2StoreService
                    .getOAuth2Credentials(REMOTE_SYSTEM);
        
        if (credentialInfo.getOAuthRefreshToken() != null){
            
            AccessGrant accessGrant = null;
            try
            {
                
                accessGrant = connectionFactory.getOAuthOperations().refreshAccess(credentialInfo.getOAuthRefreshToken(), null, null);
            }
            catch (ApiException ae)
            {
                if (ae.getCause() instanceof ServiceException){
                    ServiceException se = (ServiceException) ae.getCause();
                    if (se.getHttpErrorCodeOverride() == HttpStatus.SC_UNAUTHORIZED){
                        throw new GoogleDocsAuthenticationException("Token Refresh Failed.");
                    }
                }
            }
            
            if (accessGrant != null)
            {
                Date expiresIn = null;
                
                if (accessGrant.getExpireTime() != null)
                {
                    if (accessGrant.getExpireTime() > 0L)
                    {
                        expiresIn = new Date(new Date().getTime() + accessGrant.getExpireTime());
                    }
                }
    
                try
                {
                    oauth2StoreService.storeOAuth2Credentials(REMOTE_SYSTEM, accessGrant.getAccessToken(),
                                credentialInfo.getOAuthRefreshToken(), expiresIn, new Date());
                }
                catch (NoSuchSystemException nsse)
                {
                    throw new GoogleDocsAuthenticationException(nsse.getMessage());
                } 
            }
            else
            {
                throw new GoogleDocsAuthenticationException("No Access Grant Returned.");
            } 
            
            return accessGrant;
            
        } else {
            throw new GoogleDocsAuthenticationException("No Refresh Token Provided");
        }
    }

    protected DocsService getDocsService(Connection<GoogleDocs> connection)
    {
        DocsService docsService = null;
        
        try {
            docsService = connection.getApi().setAuthentication(new DocsService(APPLICATION_NAME));
        } catch (ApiException error){
            System.out.println(error.getCause());
            System.out.println(error.getCause().getCause());
            System.out.println(error.getCause().getCause().getCause());
        }

        return docsService;

    }

    protected DocumentListEntry createContent(String type, String name)
                throws GoogleDocsServiceException, GoogleDocsTypeException
    {
        DocsService docsService = getDocsService(getConnection());

        DocumentListEntry entry = null;
        if (type.equals(DOCUMENT_TYPE))
        {
            if (name == null)
            {
                name = NEW_DOCUMENT_NAME;
            }
            entry = new DocumentEntry();
        }
        else if (type.equals(SPREADSHEET_TYPE))
        {
            if (name == null)
            {
                name = NEW_SPREADSHEET_NAME;
            }
            entry = new SpreadsheetEntry();
        }
        else if (type.equals(PRESENTATION_TYPE))
        {
            if (name == null)
            {
                name = NEW_PRESENTATION_NAME;
            }
            entry = new PresentationEntry();
        }
        

        if (entry != null)
        {
            entry.setTitle(new PlainTextConstruct(name));
            // TODO In production this should be set to true;
            entry.setHidden(false);

            try
            {
                return docsService.insert(new URL(URL_CREATE_NEW_MEDIA), entry);
            }
            catch (MalformedURLException error)
            {
                throw new GoogleDocsServiceException(error.getMessage());
            }
            catch (IOException error)
            {
                throw new GoogleDocsServiceException(error.getMessage());
            }
            catch (ServiceException error)
            {
                throw new GoogleDocsServiceException(error.getMessage());
            }
        }
        else
        {
            throw new GoogleDocsTypeException("Type Unknown: " + type
                        + ". Must be document, spreadsheet, presentation or folder.");
        }
    }

    public DocumentListEntry createDocument(NodeRef nodeRef)
    {  
        DocumentListEntry documentListEntry = createContent(DOCUMENT_TYPE, fileFolderService.getFileInfo(nodeRef).getName());
        
        try {
            ContentWriter writer = fileFolderService.getWriter(nodeRef);
            writer.setMimetype("application/msword");
            writer.putContent(newDocument.getInputStream());
        }
        catch(IOException io)
        {
            throw new GoogleDocsServiceException(io.getMessage());
        }
        
        return documentListEntry;
    }

    public DocumentListEntry createSpreadSheet(NodeRef nodeRef)
    {
        DocumentListEntry documentListEntry = createContent(SPREADSHEET_TYPE, fileFolderService.getFileInfo(nodeRef).getName());
        
        try {
            ContentWriter writer = fileFolderService.getWriter(nodeRef);
            writer.setMimetype("application/vnd.ms-excel");
            writer.putContent(newSpreadsheet.getInputStream());
        }
        catch(IOException io)
        {
            throw new GoogleDocsServiceException(io.getMessage());
        }
        
        return documentListEntry;
    }

    public DocumentListEntry createPresentation(NodeRef nodeRef)
    {
        DocumentListEntry documentListEntry = createContent(PRESENTATION_TYPE, fileFolderService.getFileInfo(nodeRef).getName());
        
        try {
            ContentWriter writer = fileFolderService.getWriter(nodeRef);
            writer.setMimetype("application/vnd.ms-powerpoint");
            writer.putContent(newPresentation.getInputStream());
        }
        catch(IOException io)
        {
            throw new GoogleDocsServiceException(io.getMessage());
        }
        
        return documentListEntry;
    }

    public void getDocument(NodeRef nodeRef, String resourceID)
    {

        DocsService docsService = getDocsService(getConnection());

        try
        {
            MediaContent mc = new MediaContent();
            
            String mimeType = null;
            String exportFormat = null;
            
            mimeType = validateMimeType(fileFolderService.getFileInfo(nodeRef).getContentData().getMimetype());
                exportFormat = getExportFormat(getContentType(nodeRef), mimeType);

            mc.setUri(URL_DOCUMENT_DOWNLOAD + "?docID="
                        + resourceID.substring(resourceID.lastIndexOf(':') + 1)
                        + "&exportFormat=" + exportFormat);

            MediaSource ms = docsService.getMedia(mc);

            ContentWriter writer = fileFolderService.getWriter(nodeRef);
            writer.setMimetype(mimeType);
            writer.putContent(ms.getInputStream());
            
            if (nodeService.hasAspect(nodeRef, ContentModel.ASPECT_TEMPORARY))
            {
                nodeService.removeAspect(nodeRef, ContentModel.ASPECT_TEMPORARY);
            }
            
            DocumentListEntry documentListEntry = docsService.getEntry(new URL(URL_CREATE_NEW_MEDIA + "/" + resourceID.substring(resourceID.lastIndexOf(':') + 1)), DocumentListEntry.class);
            
            renameNode(nodeRef, documentListEntry.getTitle().getPlainText());
            
            deleteContent(nodeRef, documentListEntry);

        }
        catch (IOException error)
        {
            throw new GoogleDocsServiceException(error.getMessage());
        }
        catch (ServiceException error)
        {
            throw new GoogleDocsServiceException(error.getMessage());
        }
    }
    
    public void getDocument(NodeRef nodeRef)
    {
        //TODO Wrap with try for null
        String resourceID = nodeService.getProperty(nodeRef, GoogleDocsModel.PROP_RESOURCE_ID).toString();
            
        getDocument(nodeRef, resourceID); 
    }

    public void getSpreadSheet(NodeRef nodeRef, String resourceID)
    {

        DocsService docsService = getDocsService(getConnection());

        try
        {
            MediaContent mc = new MediaContent();
            
            String mimeType = null;
            String exportFormat = null;
            
            mimeType = validateMimeType(fileFolderService.getFileInfo(nodeRef).getContentData().getMimetype());
            exportFormat = getExportFormat(getContentType(nodeRef), mimeType);

            mc.setUri(URL_SPREADSHEET_DOWNLOAD + "?key="
                        + resourceID.substring(resourceID.lastIndexOf(':') + 1)
                        + "&exportFormat=" + exportFormat);

            MediaSource ms = docsService.getMedia(mc);

            ContentWriter writer = fileFolderService.getWriter(nodeRef);
            writer.setMimetype(mimeType);
            writer.putContent(ms.getInputStream());
            
            if (nodeService.hasAspect(nodeRef, ContentModel.ASPECT_TEMPORARY))
            {
                nodeService.removeAspect(nodeRef, ContentModel.ASPECT_TEMPORARY);
            }
            
            DocumentListEntry documentListEntry = docsService.getEntry(new URL(URL_CREATE_NEW_MEDIA + "/" + resourceID.substring(resourceID.lastIndexOf(':') + 1)), DocumentListEntry.class);
            
            renameNode(nodeRef, documentListEntry.getTitle().getPlainText());
            
            deleteContent(nodeRef, documentListEntry);

        }
        catch (IOException error)
        {
            throw new GoogleDocsServiceException(error.getMessage());
        }
        catch (ServiceException error)
        {
            throw new GoogleDocsServiceException(error.getMessage());
        } 
    }
    
    public void getSpreadSheet(NodeRef nodeRef)
    {
        //TODO Wrap with try for null
        String resourceID = nodeService.getProperty(nodeRef, GoogleDocsModel.PROP_RESOURCE_ID).toString();
        
        getSpreadSheet(nodeRef, resourceID);
    }

    public void getPresentation(NodeRef nodeRef, String resourceID)
    {

        DocsService docsService = getDocsService(getConnection());

        try
        {
            MediaContent mc = new MediaContent();
            
            String mimeType = null;
            String exportFormat = null;
            
            mimeType = validateMimeType(fileFolderService.getFileInfo(nodeRef).getContentData().getMimetype());
            exportFormat = getExportFormat(getContentType(nodeRef), mimeType);
            
            mc.setUri(URL_PRESENTATION_DOWNLOAD + "?docID="
                        + resourceID.substring(resourceID.lastIndexOf(':') + 1)
                        + "&exportFormat=" + exportFormat);

            MediaSource ms = docsService.getMedia(mc);

            ContentWriter writer = fileFolderService.getWriter(nodeRef);
            writer.setMimetype(mimeType);
            writer.putContent(ms.getInputStream());
            
            if (nodeService.hasAspect(nodeRef, ContentModel.ASPECT_TEMPORARY))
            {
                nodeService.removeAspect(nodeRef, ContentModel.ASPECT_TEMPORARY);
            }
            
            DocumentListEntry documentListEntry = docsService.getEntry(new URL(URL_CREATE_NEW_MEDIA + "/" + resourceID.substring(resourceID.lastIndexOf(':') + 1)), DocumentListEntry.class);
            
            renameNode(nodeRef, documentListEntry.getTitle().getPlainText());
            
            deleteContent(nodeRef, documentListEntry);

        }
        catch (IOException error)
        {
            throw new GoogleDocsServiceException(error.getMessage());
        }
        catch (ServiceException error)
        {
            throw new GoogleDocsServiceException(error.getMessage());
        } 
    }
    
    public void getPresentation(NodeRef nodeRef)
    {
        //TODO Wrap with try for null
        String resourceID = nodeService.getProperty(nodeRef, GoogleDocsModel.PROP_RESOURCE_ID).toString();
        
        getPresentation(nodeRef, resourceID);
    }
    
    public DocumentListEntry uploadFile(NodeRef nodeRef)
    {
        DocsService docsService = getDocsService(getConnection());
        
        DocumentListEntry uploaded = null;
        
        //It makes me want to cry that the don't support inputStreams.
        File file = null;
        
        try
        {
            //Get the read
            ContentReader reader = fileFolderService.getReader(nodeRef);
            file = File.createTempFile(nodeRef.getId(), ".tmp", TempFileProvider.getTempDir());
            reader.getContent(file);
            
            //Get the mimetype
            FileInfo fileInfo = fileFolderService.getFileInfo(nodeRef);
            String mimetype = fileInfo.getContentData().getMimetype();
            
            //Create MediFileSource
            MediaFileSource mediaFile = new MediaFileSource(file, mimetype); 
            
            DocumentListEntry entry = new DocumentListEntry();
            entry.setTitle(new PlainTextConstruct(fileInfo.getName()));
            //In prodcution this should always be true
            entry.setHidden(false);

            //Will this be Sync?
            ResumableGDataFileUploader uploader = new ResumableGDataFileUploader.Builder(docsService, new URL(URL_CREATE_MEDIA), mediaFile, entry).chunkSize(10485760L).build();
            uploader.start();
            
            while (!uploader.isDone()){
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    throw new GoogleDocsServiceException(ie.getMessage());
                }
            }
            uploaded = uploader.getResponse(DocumentListEntry.class);

        }
        catch (IOException io)
        {
            throw new GoogleDocsServiceException(io.getMessage());
        }
        catch (ServiceException se)
        {
            throw new GoogleDocsServiceException(se.getHttpErrorCodeOverride() + ": " + se.getMessage());
        }
        finally
        {
            if (file != null)
            {
                file.delete();
            }
        }
        
        return uploaded;
    }
    
    /**
     * 
     * @param nodeRef
     * @param documentListEntry Must be the most current DocumentListEntry
     * @return
     */
    public boolean deleteContent(NodeRef nodeRef, DocumentListEntry documentListEntry)
    {
        boolean deleted = false;
        
        DocsService docsService = getDocsService(getConnection());

        try
        {
            docsService.delete(new URL(URL_CREATE_NEW_MEDIA + "/" + documentListEntry.getResourceId().substring(documentListEntry.getResourceId().lastIndexOf(':') + 1)+ "?delete=true"), documentListEntry.getEtag());
            
            unDecorateNode(nodeRef);
        }
        catch (MalformedURLException error)
        {
            throw new GoogleDocsServiceException(error.getMessage());
        }
        catch (IOException error)
        {
            throw new GoogleDocsServiceException(error.getMessage());
        }
        catch (ServiceException error)
        {
           throw new GoogleDocsServiceException(error.getHttpErrorCodeOverride() + ": " + error.getMessage());
        }
        
        return deleted;
    }
    
    public void decorateNode(NodeRef nodeRef, DocumentListEntry documentListEntry, boolean newcontent)
    {
        if (newcontent){
            // Mark temporary until first save
            nodeService.addAspect(nodeRef, ContentModel.ASPECT_TEMPORARY, null);
        }

        // Get the googleMetadata to reference the Node
        // TODO Do we need to add eTag for discard/revision
        Map<QName, Serializable> aspectProperties = new HashMap<QName, Serializable>();
        aspectProperties.put(GoogleDocsModel.PROP_RESOURCE_ID, documentListEntry.getResourceId());
        aspectProperties.put(GoogleDocsModel.PROP_EDITORURL, documentListEntry.getDocumentLink().getHref());
        nodeService.addAspect(nodeRef, GoogleDocsModel.ASPECT_GOOGLEDOCS, aspectProperties);
    }
    
    public void unDecorateNode(NodeRef nodeRef)
    {
        if (nodeService.hasAspect(nodeRef, GoogleDocsModel.ASPECT_GOOGLEDOCS))
        {
            nodeService.removeAspect(nodeRef, GoogleDocsModel.ASPECT_GOOGLEDOCS);
        }
    }
    
    public void lockNode(NodeRef nodeRef)
    {
        lockservice.lock(nodeRef, LockType.READ_ONLY_LOCK);
        nodeService.setProperty(nodeRef, GoogleDocsModel.PROP_LOCKED, true);
    }
    
    public void unlockNode(NodeRef nodeRef){
        lockservice.unlock(nodeRef);
        nodeService.setProperty(nodeRef, GoogleDocsModel.PROP_LOCKED, false);
    }
    
    /**
     * Is the node locked by Googledocs?
     * 
     * If the document is marked locked in the model, but not locked in the repository, the locked
     * property is set to false
     * 
     * @param nodeRef
     * @return
     */
    public boolean isLockedByGoogleDocs(NodeRef nodeRef){
        
        boolean locked = false;
        
        if ((Boolean)nodeService.getProperty(nodeRef, GoogleDocsModel.PROP_LOCKED)) {
            LockStatus lockStatus = lockservice.getLockStatus(nodeRef);
            if(lockStatus.equals(LockStatus.NO_LOCK)){
                //fix broken lock
                nodeService.setProperty(nodeRef, GoogleDocsModel.PROP_LOCKED, false);
            } else {
                locked = true;
            }
        }
        
        return locked;
    }
    
    /**
     * 
     * @param nodeRef
     * @return Will return false is the document is not locked
     */
    public boolean isGoolgeDocsLockOwner(NodeRef nodeRef)
    {
        boolean isOwner = false;
        
        if (isLockedByGoogleDocs(nodeRef)){
            try {
                lockservice.checkForLock(nodeRef);
                isOwner = true;
            } catch (NodeLockedException nle)
            {
                //Locked by another user
            }
        }
        
        return isOwner;
    }
    
    private void renameNode(NodeRef nodeRef, String name)
    {
        nodeService.setProperty(nodeRef, ContentModel.PROP_NAME, name);
    }
    
    private String filenameHandler(String contentType, NodeRef nodeRef, String filename)
    {
        List<Pair<QName, Boolean>> sortProps = new ArrayList<Pair<QName, Boolean>>(1);
        sortProps.add(new Pair<QName, Boolean>(ContentModel.PROP_NAME, false));
        
        // TODO what kind of Patterns can we use?
        PagingResults<FileInfo> results = fileFolderService.list(nodeService.getPrimaryParent(nodeRef).getParentRef(), true, false,
                    filename + "*", null, sortProps, new PagingRequest(
                                CannedQueryPageDetails.DEFAULT_PAGE_SIZE));

        List<FileInfo> page = results.getPage();
        FileInfo fileInfo = null;
        if (page.size() > 0)
        {
            fileInfo = page.get(0);
        }

        if (fileInfo != null)
        {
            filename = FileNameUtil.IncrementFileName(contentType, fileInfo.getName(), false);
        } 

        return filename;

    }
    

    @Override
    public void removeApp(boolean removeContent)
    {
        // TODO Auto-generated method stub

    }

}
