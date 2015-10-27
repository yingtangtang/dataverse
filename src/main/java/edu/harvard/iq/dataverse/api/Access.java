/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.harvard.iq.dataverse.api;

import edu.harvard.iq.dataverse.DataFile;
import edu.harvard.iq.dataverse.FileMetadata;
import edu.harvard.iq.dataverse.DataFileServiceBean;
import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.DatasetVersionServiceBean;
import edu.harvard.iq.dataverse.DatasetServiceBean;
import edu.harvard.iq.dataverse.Dataverse;
import edu.harvard.iq.dataverse.DataverseServiceBean;
import edu.harvard.iq.dataverse.DataverseSession;
import edu.harvard.iq.dataverse.DataverseTheme;
import edu.harvard.iq.dataverse.PermissionServiceBean;
import edu.harvard.iq.dataverse.authorization.Permission;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.authorization.users.GuestUser;
import edu.harvard.iq.dataverse.dataaccess.DataFileIO;
import edu.harvard.iq.dataverse.dataaccess.FileAccessIO;
import edu.harvard.iq.dataverse.dataaccess.OptionalAccessService;
import edu.harvard.iq.dataverse.dataaccess.ImageThumbConverter;
import edu.harvard.iq.dataverse.datavariable.DataVariable;
import edu.harvard.iq.dataverse.datavariable.VariableServiceBean;
import edu.harvard.iq.dataverse.export.DDIExportServiceBean;
import edu.harvard.iq.dataverse.settings.SettingsServiceBean;
import edu.harvard.iq.dataverse.worldmapauth.WorldMapTokenServiceBean;

import java.util.List;
import java.util.logging.Logger;
import javax.ejb.EJB;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Properties;
import javax.inject.Inject;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;

import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.UriInfo;


import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.QueryParam;
import javax.ws.rs.ServiceUnavailableException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

/*
    Custom API exceptions [NOT YET IMPLEMENTED]
import edu.harvard.iq.dataverse.api.exceptions.NotFoundException;
import edu.harvard.iq.dataverse.api.exceptions.ServiceUnavailableException;
import edu.harvard.iq.dataverse.api.exceptions.PermissionDeniedException;
import edu.harvard.iq.dataverse.api.exceptions.AuthorizationRequiredException;
*/

/**
 *
 * @author Leonid Andreev
 * 
 * The data (file) access API is based on the DVN access API v.1.0 (that came 
 * with the v.3.* of the DVN app) and extended for DVN 4.0 to include some
 * extra fancy functionality, such as subsetting individual columns in tabular
 * data files and more.
 */

@Path("access")
public class Access extends AbstractApiBean {
    private static final Logger logger = Logger.getLogger(Access.class.getCanonicalName());
        
    @EJB
    DataFileServiceBean dataFileService;
    @EJB 
    DatasetServiceBean datasetService; 
    @EJB
    DatasetVersionServiceBean versionService;
    @EJB
    DataverseServiceBean dataverseService; 
    @EJB
    VariableServiceBean variableService;
    @EJB
    SettingsServiceBean settingsService; 
    @EJB
    DDIExportServiceBean ddiExportService;
    @EJB
    PermissionServiceBean permissionService;
    @Inject
    DataverseSession session;
    @EJB
    WorldMapTokenServiceBean worldMapTokenServiceBean;

    //@EJB
    
    // TODO: 
    // versions? -- L.A. 4.0 beta 10
    @Path("datafile/bundle/{fileId}")
    @GET
    @Produces({"application/zip"})
    public BundleDownloadInstance datafileBundle(@PathParam("fileId") Long fileId, @QueryParam("key") String apiToken, @Context UriInfo uriInfo, @Context HttpHeaders headers, @Context HttpServletResponse response) /*throws NotFoundException, ServiceUnavailableException, PermissionDeniedException, AuthorizationRequiredException*/ {
 
        DataFile df = dataFileService.find(fileId);
        
        if (df == null) {
            logger.warning("Access: datafile service could not locate a DataFile object for id "+fileId+"!");
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        
        // This will throw a WebApplicationException, with the correct 
        // exit code, if access isn't authorized: 
        checkAuthorization(df, apiToken);
        
        DownloadInfo dInfo = new DownloadInfo(df);
        BundleDownloadInstance downloadInstance = new BundleDownloadInstance(dInfo);
        
        FileMetadata fileMetadata = df.getFileMetadata();
        DatasetVersion datasetVersion = df.getOwner().getLatestVersion();
        
        downloadInstance.setFileCitationEndNote(datasetService.createCitationXML(datasetVersion, fileMetadata));
        downloadInstance.setFileCitationRIS(datasetService.createCitationRIS(datasetVersion, fileMetadata));
        
        ByteArrayOutputStream outStream = null;
        outStream = new ByteArrayOutputStream();

        try {
            ddiExportService.exportDataFile(
                    fileId,
                    outStream,
                    null,
                    null);

            downloadInstance.setFileDDIXML(outStream.toString());

        } catch (Exception ex) {
            // if we can't generate the DDI, it's ok; 
            // we'll just generate the bundle without it. 
        }
        
        return downloadInstance; 
    }
    
    @Path("datafile/{fileId}")
    @GET
    @Produces({ "application/xml" })
    public DownloadInstance datafile(@PathParam("fileId") Long fileId, @QueryParam("key") String apiToken, @Context UriInfo uriInfo, @Context HttpHeaders headers, @Context HttpServletResponse response) /*throws NotFoundException, ServiceUnavailableException, PermissionDeniedException, AuthorizationRequiredException*/ {                

        DataFile df = dataFileService.find(fileId);
        
        if (df == null) {
            logger.warning("Access: datafile service could not locate a DataFile object for id "+fileId+"!");
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        
        // This will throw a WebApplicationException, with the correct 
        // exit code, if access isn't authorized: 
        checkAuthorization(df, apiToken);
        
        DownloadInfo dInfo = new DownloadInfo(df);

        if (dataFileService.thumbnailSupported(df)) {
            dInfo.addServiceAvailable(new OptionalAccessService("thumbnail", "image/png", "imageThumb=true", "Image Thumbnail (64x64)"));
        }

        if (df.isTabularData()) {
            String originalMimeType = df.getDataTable().getOriginalFileFormat();
            dInfo.addServiceAvailable(new OptionalAccessService("original", originalMimeType, "format=original","Saved original (" + originalMimeType + ")"));
            
            dInfo.addServiceAvailable(new OptionalAccessService("R", "application/x-rlang-transport", "format=RData", "Data in R format"));
            dInfo.addServiceAvailable(new OptionalAccessService("preprocessed", "application/json", "format=prep", "Preprocessed data in JSON"));
            dInfo.addServiceAvailable(new OptionalAccessService("subset", "text/tab-separated-values", "variables=&lt;LIST&gt;", "Column-wise Subsetting"));
        }
        DownloadInstance downloadInstance = new DownloadInstance(dInfo);
        
        for (String key : uriInfo.getQueryParameters().keySet()) {
            String value = uriInfo.getQueryParameters().getFirst(key);
            
            if (downloadInstance.isDownloadServiceSupported(key, value)) {
                // this automatically sets the conversion parameters in 
                // the download instance to key and value;
                // TODO: I should probably set these explicitly instead. 
                
                if (downloadInstance.getConversionParam().equals("subset")) {
                    String subsetParam = downloadInstance.getConversionParamValue();
                    String variableIdParams[] = subsetParam.split(",");
                    if (variableIdParams != null && variableIdParams.length > 0) {
                        logger.fine(variableIdParams.length + " tokens;");
                        for (int i = 0; i < variableIdParams.length; i++) {
                            logger.fine("token: " + variableIdParams[i]);
                            String token = variableIdParams[i].replaceFirst("^v", "");
                            Long variableId = null;
                            try {
                                variableId = new Long(token);
                            } catch (NumberFormatException nfe) {
                                variableId = null;
                            }
                            if (variableId != null) {
                                logger.fine("attempting to look up variable id " + variableId);
                                if (variableService != null) {
                                    DataVariable variable = variableService.find(variableId);
                                    if (variable != null) {
                                        if (downloadInstance.getExtraArguments() == null) {
                                            downloadInstance.setExtraArguments(new ArrayList<Object>());
                                        }
                                        logger.fine("putting variable id "+variable.getId()+" on the parameters list of the download instance.");
                                        downloadInstance.getExtraArguments().add(variable);
                                        
                                        //if (!variable.getDataTable().getDataFile().getId().equals(sf.getId())) {
                                        //variableList.add(variable);
                                        //}
                                    }
                                } else {
                                    logger.fine("variable service is null.");
                                }
                            }
                        }
                    }
                }

                break;
            } else {
                // Service unknown/not supported/bad arguments, etc.:
                // TODO: throw new ServiceUnavailableException(); 
            }
            
        }
        /* 
         * Provide content type header:
         * (this will be done by the InstanceWriter class - ?)
         */
         
        /* Provide "Access-Control-Allow-Origin" header:
         * (may not be needed here... - that header was added specifically
         * to get the data exploration app to be able to access the metadata
         * API; may have been something specific to Vito's installation too
         * -- L.A.)
         */
        response.setHeader("Access-Control-Allow-Origin", "*");
                
        /* 
         * Provide some browser-friendly headers: (?)
         */
        //return retValue; 
        return downloadInstance;
    }
    
    
    /* 
     * Variants of the Access API calls for retrieving datafile-level 
     * Metadata.
    */
    
    
    // Metadata format defaults to DDI:
    @Path("datafile/{fileId}/metadata")
    @GET
    @Produces({"text/xml"})
    public String tabularDatafileMetadata(@PathParam("fileId") Long fileId, @QueryParam("exclude") String exclude, @QueryParam("include") String include, @Context HttpHeaders header, @Context HttpServletResponse response) throws NotFoundException, ServiceUnavailableException /*, PermissionDeniedException, AuthorizationRequiredException*/ { 
        return tabularDatafileMetadataDDI(fileId, exclude, include, header, response);
    }
    
    /* 
     * This has been moved here, under /api/access, from the /api/meta hierarchy
     * which we are going to retire.
     */
    @Path("datafile/{fileId}/metadata/ddi")
    @GET
    @Produces({"text/xml"})
    public String tabularDatafileMetadataDDI(@PathParam("fileId") Long fileId, @QueryParam("exclude") String exclude, @QueryParam("include") String include, @Context HttpHeaders header, @Context HttpServletResponse response) throws NotFoundException, ServiceUnavailableException /*, PermissionDeniedException, AuthorizationRequiredException*/ {
        String retValue = "";

        DataFile dataFile = null; 
        
        //httpHeaders.add("Content-disposition", "attachment; filename=\"dataverse_files.zip\"");
        //httpHeaders.add("Content-Type", "application/zip; name=\"dataverse_files.zip\"");
        response.setHeader("Content-disposition", "attachment; filename=\"dataverse_files.zip\"");
        
        dataFile = dataFileService.find(fileId);
        if (dataFile == null) {
            throw new NotFoundException();
        }
        
        String fileName = dataFile.getFileMetadata().getLabel().replaceAll("\\.tab$", "-ddi.xml");
        response.setHeader("Content-disposition", "attachment; filename=\""+fileName+"\"");
        response.setHeader("Content-Type", "application/xml; name=\""+fileName+"\"");
        
        ByteArrayOutputStream outStream = null;
        outStream = new ByteArrayOutputStream();

        try {
            ddiExportService.exportDataFile(
                    fileId,
                    outStream,
                    exclude,
                    include);

            retValue = outStream.toString();

        } catch (Exception e) {
            // For whatever reason we've failed to generate a partial 
            // metadata record requested. 
            // We return Service Unavailable.
            throw new ServiceUnavailableException();
        }

        response.setHeader("Access-Control-Allow-Origin", "*");

        return retValue;
    }
    
    @Path("variable/{varId}/metadata/ddi")
    @GET
    @Produces({ "application/xml" })

    public String dataVariableMetadataDDI(@PathParam("varId") Long varId, @QueryParam("exclude") String exclude, @QueryParam("include") String include, @Context HttpHeaders header, @Context HttpServletResponse response) /*throws NotFoundException, ServiceUnavailableException, PermissionDeniedException, AuthorizationRequiredException*/ {
        String retValue = "";
        
        ByteArrayOutputStream outStream = null;
        try {
            outStream = new ByteArrayOutputStream();

            ddiExportService.exportDataVariable(
                    varId,
                    outStream,
                    exclude,
                    include);
        } catch (Exception e) {
            // For whatever reason we've failed to generate a partial 
            // metadata record requested. We simply return an empty string.
            return retValue;
        }

        retValue = outStream.toString();
        
        response.setHeader("Access-Control-Allow-Origin", "*");
        
        return retValue; 
    }
    
    /*
     * "Preprocessed data" metadata format:
     * (this was previously provided as a "format conversion" option of the 
     * file download form of the access API call)
     */
    
    @Path("datafile/{fileId}/metadata/preprocessed")
    @GET
    @Produces({"text/xml"})
    
    public DownloadInstance tabularDatafileMetadataPreprocessed(@PathParam("fileId") Long fileId, @QueryParam("key") String apiToken, @Context UriInfo uriInfo, @Context HttpHeaders headers, @Context HttpServletResponse response) throws ServiceUnavailableException {
    
        DataFile df = dataFileService.find(fileId);
        
        if (df == null) {
            logger.warning("Access: datafile service could not locate a DataFile object for id "+fileId+"!");
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        
        // This will throw a WebApplicationException, with the correct 
        // exit code, if access isn't authorized: 
        checkAuthorization(df, apiToken);
        DownloadInfo dInfo = new DownloadInfo(df);

        if (df.isTabularData()) {
            dInfo.addServiceAvailable(new OptionalAccessService("preprocessed", "application/json", "format=prep", "Preprocessed data in JSON"));
        } else {
            throw new ServiceUnavailableException("Preprocessed Content Metadata requested on a non-tabular data file.");
        }
        DownloadInstance downloadInstance = new DownloadInstance(dInfo);
        if (downloadInstance.isDownloadServiceSupported("format", "prep")) {
            logger.fine("Preprocessed data for tabular file "+fileId);
        }
        
        response.setHeader("Access-Control-Allow-Origin", "*");
        
        return downloadInstance;
    }
    
    /* 
     * API method for downloading zipped bundles of multiple files:
    */
    
    
    @Path("datafiles/{fileIds}")
    @GET
    @Produces({"application/zip"})
    public ZippedDownloadInstance datafiles(@PathParam("fileIds") String fileIds, @QueryParam("key") String apiToken, @Context UriInfo uriInfo, @Context HttpHeaders headers, @Context HttpServletResponse response) throws WebApplicationException /*throws NotFoundException, ServiceUnavailableException, PermissionDeniedException, AuthorizationRequiredException*/ {
        ByteArrayOutputStream outStream = null;
        // create a Download Instance without, without a primary Download Info object:
        ZippedDownloadInstance downloadInstance = new ZippedDownloadInstance();

        if (fileIds == null || fileIds.equals("")) {
            throw new WebApplicationException(Response.Status.BAD_REQUEST);
        }

        String fileIdParams[] = fileIds.split(",");
        if (fileIdParams != null && fileIdParams.length > 0) {
            logger.fine(fileIdParams.length + " tokens;");
            for (int i = 0; i < fileIdParams.length; i++) {
                logger.fine("token: " + fileIdParams[i]);
                Long fileId = null;
                try {
                    fileId = new Long(fileIdParams[i]);
                } catch (NumberFormatException nfe) {
                    fileId = null;
                }
                logger.fine("attempting to look up file id " + fileId);
                DataFile file = dataFileService.find(fileId);
                if (file != null) {
                    if (isAccessAuthorized(file, apiToken)) { 
                        logger.fine("adding datafile (id=" + file.getId() + ") to the download list of the ZippedDownloadInstance.");
                        downloadInstance.addDataFile(file);
                    } else {
                        downloadInstance.setManifest(downloadInstance.getManifest() + 
                                file.getFileMetadata().getLabel() + " IS RESTRICTED AND CANNOT BE DOWNLOADED\r\n");
                    }

                } else {
                    // Or should we just drop it and make a note in the Manifest?    
                    throw new WebApplicationException(Response.Status.NOT_FOUND);
                }
            }
        } else {
            throw new WebApplicationException(Response.Status.BAD_REQUEST);
        }

        if (downloadInstance.getDataFiles().size() < 1) {
            // This means the file ids supplied were valid, but none were 
            // accessible for this user:
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        

        return downloadInstance;
    }
    
    @Path("tempPreview/{fileSystemId}")
    @GET
    @Produces({"image/png"})
    public InputStream tempPreview(@PathParam("fileSystemId") String fileSystemId, @Context UriInfo uriInfo, @Context HttpHeaders headers, @Context HttpServletResponse response) /*throws NotFoundException, ServiceUnavailableException, PermissionDeniedException, AuthorizationRequiredException*/ {
        
        String filesRootDirectory = System.getProperty("dataverse.files.directory");
        if (filesRootDirectory == null || filesRootDirectory.equals("")) {
            filesRootDirectory = "/tmp/files";
        }

        String fileSystemName = filesRootDirectory + "/temp/" + fileSystemId;
        
        String mimeTypeParam = uriInfo.getQueryParameters().getFirst("mimetype");
        String imageThumbFileName = null;
                
        if ("application/pdf".equals(mimeTypeParam)) {
            imageThumbFileName = ImageThumbConverter.generatePDFThumb(fileSystemName);
        } else {
            imageThumbFileName = ImageThumbConverter.generateImageThumb(fileSystemName);
        }
        
        // TODO: 
        // double-check that this temporary preview thumbnail gets deleted 
        // once the file is saved "for real". 
        // (or maybe we shouldn't delete it - but instead move it into the 
        // permanent location... so that it doesn't have to be generated again?)
        // -- L.A. Aug. 21 2014
        // Update: 
        // the temporary thumbnail file does get cleaned up now; 
        // but yeay, maybe we should be saving it permanently instead, as 
        // the above suggested...
        // -- L.A. Feb. 28 2015
        
        
        if (imageThumbFileName == null) {
            return null; 
        }
        /* 
         removing the old, non-vector default icon: 
            imageThumbFileName = getWebappImageResource(DEFAULT_FILE_ICON);
        }
        */

        InputStream in;

        try {
            in = new FileInputStream(imageThumbFileName);
        } catch (Exception ex) {

            return null;
        }
        return in;

    }
    
    
    
    @Path("fileCardImage/{fileId}")
    @GET
    @Produces({ "image/png" })
    public InputStream fileCardImage(@PathParam("fileId") Long fileId, @Context UriInfo uriInfo, @Context HttpHeaders headers, @Context HttpServletResponse response) /*throws NotFoundException, ServiceUnavailableException, PermissionDeniedException, AuthorizationRequiredException*/ {        
        
        
        
        DataFile df = dataFileService.find(fileId);
        
        if (df == null) {
            logger.warning("Preview: datafile service could not locate a DataFile object for id "+fileId+"!");
            return null; 
        }
        
        DataFileIO thumbnailDataAccess = null; 
        
        try {
            DataFileIO dataAccess = df.getAccessObject();
            if (dataAccess != null && dataAccess.isLocalFile()) {
                dataAccess.open();

                if ("application/pdf".equalsIgnoreCase(df.getContentType())
                        || df.isImage()
                        || "application/zipped-shapefile".equalsIgnoreCase(df.getContentType())) {

                    thumbnailDataAccess = ImageThumbConverter.getImageThumb((FileAccessIO) dataAccess, 48);
                }
            }
        } catch (IOException ioEx) {
            return null;
        }
        
        if (thumbnailDataAccess != null && thumbnailDataAccess.getInputStream() != null) {
            return thumbnailDataAccess.getInputStream();
        }

        return null; 
    }
    
    @Path("dsCardImage/{versionId}")
    @GET
    @Produces({ "image/png" })
    public InputStream dsCardImage(@PathParam("versionId") Long versionId, @Context UriInfo uriInfo, @Context HttpHeaders headers, @Context HttpServletResponse response) /*throws NotFoundException, ServiceUnavailableException, PermissionDeniedException, AuthorizationRequiredException*/ {        
        
        
        DatasetVersion datasetVersion = versionService.find(versionId);
        
        if (datasetVersion == null) {
            logger.warning("Preview: Version service could not locate a DatasetVersion object for id "+versionId+"!");
            return null; 
        }
        
        //String imageThumbFileName = null; 
        DataFileIO thumbnailDataAccess = null;
        
        // First, check if this dataset has a designated thumbnail image: 
        
        if (datasetVersion.getDataset() != null) {
            
            DataFile logoDataFile = datasetVersion.getDataset().getThumbnailFile();
            if (logoDataFile != null) {
        
                try {
                    DataFileIO dataAccess = logoDataFile.getAccessObject();
                    if (dataAccess != null && dataAccess.isLocalFile()) {
                        dataAccess.open();

                        thumbnailDataAccess = ImageThumbConverter.getImageThumb((FileAccessIO) dataAccess, 48);
                    }
                } catch (IOException ioEx) {
                    thumbnailDataAccess = null; 
                }
            }
                
               
        
            // If not, we'll try to use one of the files in this dataset version:
            if (thumbnailDataAccess == null) {

                if (!datasetVersion.getDataset().isHarvested()) {
                    thumbnailDataAccess = getThumbnailForDatasetVersion(datasetVersion); 
                }
            }
            
            if (thumbnailDataAccess != null && thumbnailDataAccess.getInputStream() != null) {
                return thumbnailDataAccess.getInputStream();
            }        
        }

        return null; 
    }
    
    @Path("dvCardImage/{dataverseId}")
    @GET
    @Produces({ "image/png" })
    public InputStream dvCardImage(@PathParam("dataverseId") Long dataverseId, @Context UriInfo uriInfo, @Context HttpHeaders headers, @Context HttpServletResponse response) /*throws NotFoundException, ServiceUnavailableException, PermissionDeniedException, AuthorizationRequiredException*/ {        
        
        
        Dataverse dataverse = dataverseService.find(dataverseId);
        
        if (dataverse == null) {
            logger.warning("Preview: Version service could not locate a DatasetVersion object for id "+dataverseId+"!");
            return null; 
        }
        
        String imageThumbFileName = null; 
        
        // First, check if the dataverse has a defined logo: 
        
        if (dataverse.getDataverseTheme()!=null && dataverse.getDataverseTheme().getLogo() != null && !dataverse.getDataverseTheme().getLogo().equals("")) {
            File dataverseLogoFile = getLogo(dataverse);
            if (dataverseLogoFile != null) {
                String logoThumbNailPath = null;
                InputStream in = null;

                try {
                    if (dataverseLogoFile.exists()) {
                        logoThumbNailPath =  ImageThumbConverter.generateImageThumb(dataverseLogoFile.getAbsolutePath(), 48);
                        if (logoThumbNailPath != null) {
                            in = new FileInputStream(logoThumbNailPath);
                        }
                    }
                } catch (Exception ex) {
                    in = null; 
                }
                if (in != null) {
                    return in;
                }    
            }
        }
        
        // If there's no uploaded logo for this dataverse, go through its 
        // [released] datasets and see if any of them have card images:
        
        // TODO: figure out if we want to be doing this! 
        // (efficiency considerations...) -- L.A. 4.0 
        // And we definitely don't want to be doing this for harvested 
        // dataverses:
        
        DataFileIO thumbnailDataAccess = null; 
        
        if (!dataverse.isHarvested()) {
            for (Dataset dataset : datasetService.findPublishedByOwnerId(dataverseId)) {
                if (dataset != null) {
                    DatasetVersion releasedVersion = dataset.getReleasedVersion();
                    thumbnailDataAccess = getThumbnailForDatasetVersion(releasedVersion); 
                    if (thumbnailDataAccess != null) {
                        break;
                    }
                }
            }
        }
        
        if (thumbnailDataAccess != null && thumbnailDataAccess.getInputStream() != null) {
            return thumbnailDataAccess.getInputStream();
        }

        return null;
    }
    
    // helper methods:
    
    private DataFileIO getThumbnailForDatasetVersion(DatasetVersion datasetVersion) {
        DataFileIO thumbnailDataAccess = null;
        if (datasetVersion != null) {
            List<FileMetadata> fileMetadatas = datasetVersion.getFileMetadatas();

            for (FileMetadata fileMetadata : fileMetadatas) {
                DataFile dataFile = fileMetadata.getDataFile();

                if (dataFile != null) {

                    try {
                        DataFileIO dataAccess = dataFile.getAccessObject();
                        if (dataAccess != null && dataAccess.isLocalFile()) {
                            dataAccess.open();

                            thumbnailDataAccess = ImageThumbConverter.getImageThumb((FileAccessIO) dataAccess, 48);
                        }
                    } catch (IOException ioEx) {
                        thumbnailDataAccess = null;
                    }
                }
                if (thumbnailDataAccess != null) {
                    break;
                }
            }
        }
        return thumbnailDataAccess;
    }
    
    // TODO: 
    // put this method into the dataverseservice; use it there
    // -- L.A. 4.0 beta14
    
    private File getLogo(Dataverse dataverse) {
        if (dataverse.getId() == null) {
            return null; 
        }
        
        DataverseTheme theme = dataverse.getDataverseTheme(); 
        if (theme != null && theme.getLogo() != null && !theme.getLogo().equals("")) {
            Properties p = System.getProperties();
            String domainRoot = p.getProperty("com.sun.aas.instanceRoot");
  
            if (domainRoot != null && !"".equals(domainRoot)) {
                return new File (domainRoot + File.separator + 
                    "docroot" + File.separator + 
                    "logos" + File.separator + 
                    dataverse.getLogoOwnerId() + File.separator + 
                    theme.getLogo());
            }
        }
            
        return null;         
    }
    
    /* 
        removing: 
    private String getWebappImageResource(String imageName) {
        String imageFilePath = null;
        String persistenceFilePath = null;
        java.net.URL persistenceFileUrl = Thread.currentThread().getContextClassLoader().getResource("META-INF/persistence.xml");
        
        if (persistenceFileUrl != null) {
            persistenceFilePath = persistenceFileUrl.getDataFile();
            if (persistenceFilePath != null) {
                persistenceFilePath = persistenceFilePath.replaceFirst("/[^/]*$", "/");
                imageFilePath = persistenceFilePath + "../../../resources/images/" + imageName;
                return imageFilePath; 
            }
            logger.warning("Null file path representation of the location of persistence.xml in the webapp root directory!"); 
        } else {
            logger.warning("Could not find the location of persistence.xml in the webapp root directory!");
        }

        return null;
    }
    */
    
    
    // checkAuthorization is a convenience method; it calls the boolean method
    // isAccessAuthorized(), the actual workhorse, tand throws a 403 exception if not.
    
    private void checkAuthorization(DataFile df, String apiToken) throws WebApplicationException {

        if (!isAccessAuthorized(df, apiToken)) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }        
    }
    
    
    
    private boolean isAccessAuthorized(DataFile df, String apiToken) {
    // First, check if the file belongs to a released Dataset version: 
        
        boolean published = false; 
        
        // TODO: 
        // this very likely creates a ton of queries; put some thought into 
        // optimizing this stuff? -- 4.2.1
        // 
        // update: it appears that we can finally trust the dvObject.isReleased()
        // method; so all this monstrous crawling through the filemetadatas, 
        // below, may not be necessary anymore! - need to verify... L.A. 10.21.2015
        
        if (df.getOwner().getReleasedVersion() != null) {
            //logger.fine("file belongs to a dataset with a released version.");
            if (df.getOwner().getReleasedVersion().getFileMetadatas() != null) {
                //logger.fine("going through the list of filemetadatas that belong to the released version.");
                for (FileMetadata fm : df.getOwner().getReleasedVersion().getFileMetadatas()) {
                    if (df.equals(fm.getDataFile())) {
                        //logger.fine("found a match!");
                        published = true; 
                    }
                }
            }
        }
        
        // TODO: (IMPORTANT!)
        // Business logic like this should NOT be maintained in individual 
        // application fragments. 
        // At the moment it is duplicated here, and inside the Dataset page.
        // There are also stubs for file-level permission lookups and caching
        // inside Gustavo's view-scoped PermissionsWrapper. 
        // All this logic needs to be moved to the PermissionServiceBean where it will be 
        // centrally maintained; with the PermissionsWrapper providing 
        // efficient cached lookups to the pages (that often need to make 
        // repeated lookups on the same files). Care will need to be taken 
        // to preserve the slight differences in logic utilized by the page and 
        // this Access call (the page checks the restriction flag on the
        // filemetadata, not the datafile - as it needs to reflect the permission 
        // status of the file in the version history).  
        // I will open a 4.[34] ticket. 
        //
        // -- L.A. 4.2.1
        
        
        // We don't need to check permissions on files that are 
        // from released Dataset versions and not restricted: 
        
        if (!df.isRestricted()) {
            // And if they are not published, they can still be downloaded, if the user
            // has the permission to view unpublished versions:
            if (published || permissionService.on(df.getOwner()).has(Permission.ViewUnpublishedDataset)) {
                return true;
            }
            return false;
        }
        
        AuthenticatedUser user = null;
       
        /** 
         * Authentication/authorization:
         * 
         * note that the fragment below - that retrieves the session object
         * and tries to find the user associated with the session - is really
         * for logging/debugging purposes only; for practical purposes, it 
         * would be enough to just call "permissionService.on(df).has(Permission.DownloadFile)"
         * and the method does just that, tries to authorize for the user in 
         * the current session (or guest user, if no session user is available):
         */
        
        if (session != null) {
            if (session.getUser() != null) {
                if (session.getUser().isAuthenticated()) {
                    user = (AuthenticatedUser) session.getUser();
                } else {
                    logger.fine("User associated with the session is not an authenticated user. (Guest access will be assumed).");
                    if (session.getUser() instanceof GuestUser) {
                        logger.fine("User associated with the session is indeed a guest user.");
                    }
                }
            } else {
                logger.fine("No user associated with the session.");
            }
        } else {
            logger.fine("Session is null.");
        } 
                
        if (permissionService.on(df).has(Permission.DownloadFile)) {
            // Note: PermissionServiceBean.on(Datafile df) will obtain the 
            // User from the Session object, just like in the code fragment 
            // above. That's why it's not passed along as an argument.
            if (published) {
                if (user != null) {
                    logger.fine("Session-based auth: user "+user.getName()+" has access rights on the requested datafile.");
                } else {
                    logger.fine("Session-based auth: guest user is granted access to the datafile.");
                }
                return true; 
            } else {
                // if the file is NOT published, we will let them download the 
                // file ONLY if they also have the permission to view 
                // unpublished verions:
                if (permissionService.on(df.getOwner()).has(Permission.ViewUnpublishedDataset)) {
                    if (user != null) {
                        logger.fine("Session-based auth: user " + user.getName() + " has access rights on the (unpublished) datafile.");
                    } else {
                        logger.fine("Session-based auth: guest user is granted access to the (unpublished) datafile.");
                    }
                    return true; 
                }
            }
        } 
        
        // if that failed, we'll still check if the download can be authorized based
        // on any tokens they have:
        
        // TODO: maybe we should check for tokens FIRST? - otherwise we are always
        // performing a guest authorization lookup. -- 4.2.1
        
        if ((apiToken != null)&&(apiToken.length()==64)){
            /* 
                WorldMap token check
                - WorldMap tokens are 64 chars in length
            
                - Use the worldMapTokenServiceBean to verify token 
                    and check permissions against the requested DataFile
            */
            if (!(this.worldMapTokenServiceBean.isWorldMapTokenAuthorizedForDataFileDownload(apiToken, df))){
                return false;
            }
            
            // Yes! User may access file
            //
            logger.fine("WorldMap token-based auth: Token is valid for the requested datafile");
            return true;
            
        } else if ((apiToken != null)&&(apiToken.length()!=64)) {
            // Will try to obtain the user information from the API token, 
            // if supplied: 
        
            user = findUserByApiToken(apiToken);
            
            if (user == null) {
                logger.warning("API token-based auth: Unable to find a user with the API token provided.");
                return false;
            } 
            
            if (permissionService.userOn(user, df).has(Permission.DownloadFile)) { 
                if (published) {
                    logger.fine("API token-based auth: User "+user.getName()+" has rights to access the datafile.");
                    return true; 
                } else {
                    // if the file is NOT published, we will let them download the 
                    // file ONLY if they also have the permission to view 
                    // unpublished verions:
                    if (permissionService.userOn(user, df.getOwner()).has(Permission.ViewUnpublishedDataset)) {
                        logger.fine("API token-based auth: User "+user.getName()+" has rights to access the (unpublished) datafile.");
                        return true;
                    } else {
                        logger.fine("API token-based auth: User "+user.getName()+" is not authorized to access the (unpublished) datafile.");
                    }
                }
            } else {
                logger.fine("API token-based auth: User "+user.getName()+" is not authorized to access the datafile.");
            }
            
            return false;
        } 
        
        if (user != null) {
            logger.fine("Session-based auth: user " + user.getName() + " has NO access rights on the requested datafile.");
        } else {
            logger.fine("Unauthenticated access: No guest access to the datafile.");
        }
        
        return false; 
    }
            // preserving comments added by Phil, before passing the ticket #2648 to me: -- L.A., 4.2.1
            // - should be removed after the code is reviewed and we've determined that it's now 
            // doing the right thing. 

            /**
             * We want the DownloadFile permission to only apply to *published*
             * files. This is a change/desire that is being introduced in 4.2.1.
             * If the file has not been published yet, assigning the
             * DownloadFile permission should have no effect. See
             * https://github.com/IQSS/dataverse/issues/2648 for details. This
             * is also why we don't show it in MyData and defer notifications
             * until the dataset is published. See
             * https://github.com/IQSS/dataverse/issues/2645 for more discussion
             * about the change.
             */
            /*
             if (!df.isReleased()) {
                    logger.fine("API token-based auth: User " + user.getName() + " is not authorized to access the datafile. The DownloadFile permission has been granted but the file/dataset has not 
been published.");
            /**
             * Throwing "forbidden" is commented out because if we throw
             * forbidden here it fixed the bug at
             * https://github.com/IQSS/dataverse/issues/2648 (see note above)
             * but introduces a regression in which the user who uploaded the
             * file in the first place can no longer download the file. See
             * scripts/issues/2648/reproduce for some tests to exercise this
             * code. Can we safely comment out the throwing of "forbidden" below
             * if we only throw "forbidden" if the user is not the owner of the
             * file? Should we check some other permission that will serve as a
             * proxy to mean that the user is the owner of the file?
             *
             * It has been suggested that all of this logic might exist in the
             * canDownloadFile method in DatasetPage or perhaps the
             * hasDownloadFilePermission method in PermissionsWrapper. Probably
             * all of business rules should be consolidated into one place so
             * there is only one code path to troubleshoot.
             */
//                    throw new WebApplicationException(Response.Status.FORBIDDEN);
            
            
}