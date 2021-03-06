/**
 * BaseWrapper.java
 * 
 *  (C) 2019 Hubert Asamer, DLR
 *  (C) 2019 Dr. Bassler & Co. Managementberatung GmbH
 */
package de.dlr.proseo.basewrap;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.dlr.proseo.basewrap.rest.HttpResponseInfo;
import de.dlr.proseo.basewrap.rest.RestOps;
import de.dlr.proseo.interfaces.rest.model.RestProductFile;
import de.dlr.proseo.model.joborder.InputOutput;
import de.dlr.proseo.model.joborder.IpfFileName;
import de.dlr.proseo.model.joborder.JobOrder;
import de.dlr.proseo.model.joborder.Proc;
import de.dlr.proseo.model.util.OrbitTimeFormatter;

/**
 * prosEO Base Processor Wrapper - for processors conforming to ESA's
 * "Generic IPF Interface Specification" (MMFI-GSEG-EOPG-TN-07-0003, V.1.8)
 * 
 * @author Hubert Asamer
 * @author Dr. Thomas Bassler
 *
 */
public class BaseWrapper {

	/** Exit code for successful completion */
	private static final int EXIT_CODE_OK = 0;
	/** Exit code for failure */
	private static final int EXIT_CODE_FAILURE = 255;
	/** Exit code explanation for successful completion */
	private static final String EXIT_TEXT_OK = "OK";
	/** Exit code explanation for failure */
	private static final String EXIT_TEXT_FAILURE = "FAILURE";
	/** CallBack-Message for failure */
	private static final String CALLBACK_STATUS_FAILURE = "FAILURE";
	/** CallBack-Message for success */
	private static final String CALLBACK_STATUS_SUCCESS = "SUCCESS";

	/** Current directory of this program is used as work-dir */
	private static final Path WORKING_DIR = Paths.get(System.getProperty("user.dir"));
	/** Current timestamp used for output-file prefixes*/
	private static final long WRAPPER_TIMESTAMP = System.currentTimeMillis()/1000;
	/** Auto-created path/filename of JobOrderFile within container */
	private static final String CONTAINER_JOF_PATH = WORKING_DIR.toString()+File.separator+String.valueOf(WRAPPER_TIMESTAMP)+".xml";
	/** Directory prefix of produced output data */
	private static final String CONTAINER_OUTPUTS_PATH_PREFIX = String.valueOf(WRAPPER_TIMESTAMP);
	/** Constant for file name type "Physical" in JOF */
	private static final String FILENAME_TYPE_PHYSICAL = "Physical";
	/** Constant for file name type "Directory" in JOF */
	protected static final String FILENAME_TYPE_DIRECTORY = "Directory";
	/** Constant for file name type "Archive" in JOF (non-standard extension!) */
	protected static final String FILENAME_TYPE_ARCHIVE = "Archive";

	/* Message strings */
	private static final String MSG_CHECKING_ENVIRONMENT = "Checking {} environment variables:";
	private static final String MSG_DIFFERENT_FILE_PATHS_ASSIGNED = "Different file paths assigned by Storage Manager for files of same product ID {}";
	private static final String MSG_DIFFERENT_STORAGE_TYPES_ASSIGNED = "Different storage types assigned by Storage Manager for files of same product ID {}";
	private static final String MSG_DIRECTORY_NOT_EMPTY = "Output directory {} is not empty";
	private static final String MSG_ENVIRONMENT_CHECK_PASSED = "Check of environment variables passed";
	private static final String MSG_ERROR_CALLING_PLANNER = "Error calling Production Planner (HTTP status code: {})";
	private static final String MSG_ERROR_CONVERTING_INGESTOR_PRODUCT = "Error converting ingestor product with ID {} to JSON (cause: {})";
	private static final String MSG_ERROR_PUSHING_OUTPUT_FILE = "Error pushing output file {}, HTTP status code {}";
	private static final String MSG_ERROR_REGISTERING_PRODUCT = "Error registering product with ID {} with Ingestor (HTTP status code: {})";
	private static final String MSG_ERROR_RETRIEVING_INPUT_FILE = "Error retrieving input file {}, HTTP status code {}";
	private static final String MSG_ERROR_RETRIEVING_JOB_ORDER = "Error retrieving Job Order File, HTTP status code {}";
	private static final String MSG_ERROR_RUNNING_PROCESSOR = "Error running processor (cause: {})";
	private static final String MSG_FETCHED_INPUT_FILES = "Fetched {} input files and prepared directories for {} outputs -- Ready for processing using Container-JOF {}";
	private static final String MSG_INVALID_VALUE_OF_ENVVAR = "Invalid value of EnvVar: {}";
	private static final String MSG_LEAVING_BASE_WRAPPER = "Leaving base-wrapper with exit code {} ({})";
	private static final String MSG_MALFORMED_RESPONSE_FROM_STORAGE_MANAGER = "Malformed response {} from Storage Manager when pushing {}";
	private static final String MSG_NOT_A_DIRECTORY = "Output path {} is not a directory";
	private static final String MSG_PLANNER_RESPONSE = "Production Planner response for callback is {} ({})";
	private static final String MSG_PREFIX_TIMESTAMP_FOR_NAMING = "Prefix timestamp used for JobOrderFile naming and results is {}";
	private static final String MSG_PROCESSING_FINISHED = "Processing finished with return code {}";
	private static final String MSG_PRODUCT_ID_NOT_PARSEABLE = "Product ID {} not parseable as long integer";
	private static final String MSG_PRODUCTS_REGISTERED = "{} products registered with Ingestor";
	private static final String MSG_PRODUCTS_UPLOADED = "{} products with {} files uploaded to Storage Manager";
	private static final String MSG_REGISTERING_PRODUCTS = "Registering {} products with prosEO-Ingestor {}";
	private static final String MSG_STARTING_BASE_WRAPPER = "\n\n{\"prosEO\" : \"A Processing System for Earth Observation Data\"}\nStarting base-wrapper with JobOrder file {}";
	private static final String MSG_STARTING_PROCESSOR = "Starting Processing using command {} and local JobOrderFile: {}";
	private static final String MSG_UNABLE_TO_CREATE_DIRECTORY = "Unable to create directory path {}";
	private static final String MSG_UPLOADING_RESULTS = "Uploading results to Storage Manager";
	private static final String MSG_CANNOT_CALCULATE_CHECKSUM = "Cannot calculate MD5 checksum for product {}";
	private static final String MSG_MORE_THAN_ONE_ZIP_ARCHIVE = "More than one ZIP archive given for product {}";
	private static final String MSG_SKIPPING_INPUT_ENTRY = "Skipping input entry of type {} with filename type {}";

	/** Logger for this class */
	private static Logger logger = LoggerFactory.getLogger(BaseWrapper.class);

	/** Enumeration with valid environment variable names.
	 *  At runtime-start the BaseWrapper checks the presence and values of each variable.
	 *  <ul>
	 *  <li>{@link #JOBORDER_FILE} URI of valid JobOrder-File</li>
	 *  <li>{@link #STORAGE_ENDPOINT} public API endpoint URL of prosEO Storage Manager</li>
	 *  <li>{@link #STORAGE_USER} username for connection to Storage Manager</li>
	 *  <li>{@link #STORAGE_PASSWORD} password for connection to Storage Manager</li>
	 *  <li>{@link #INGESTOR_ENDPOINT} public API endpoint URL of prosEO-Ingestor</li>
	 *  <li>{@link #STATE_CALLBACK_ENDPOINT} public API endpoint URL of prosEO-Planner for submitting the final state of the wrapper-run.</li>
	 *  <li>{@link #PROCESSOR_SHELL_COMMAND} the processor shell command to be invoked by the wrapper</li>
	 *  <li>{@link #PROCESSING_FACILITY_NAME} name of the processing-facility this wrapper runs in.</li>
	 *  <li>{@link #PROSEO_USER} username for connection to Planner and Ingestor</li>
	 *  <li>{@link #PROSEO_PW} password for connection to Planner and Ingestor</li>
	 *  <li>{@link #LOCAL_FS_MOUNT} the mount point within the container, where the shared storage is mounted</li>
	 *  </ul>
	 *  
	 */
	enum ENV_VARS {
		JOBORDER_FILE
		, STORAGE_ENDPOINT
		, STORAGE_USER
		, STORAGE_PASSWORD
		, INGESTOR_ENDPOINT
		, STATE_CALLBACK_ENDPOINT
		, PROCESSOR_SHELL_COMMAND
		, PROCESSING_FACILITY_NAME
		, PROSEO_USER
		, PROSEO_PW
		, LOCAL_FS_MOUNT
	}

	// Environment Variables from Container (set via run-invocation or directly from docker-image)
	
	// Variables to be provided by Production Planner during invocation
	/** Path to Job Order File, format according to file system type */
	private String ENV_JOBORDER_FILE = System.getenv(ENV_VARS.JOBORDER_FILE.toString());
	
	/** HTTP endpoint for local Storage Manager */
	private String ENV_STORAGE_ENDPOINT = System.getenv(ENV_VARS.STORAGE_ENDPOINT.toString());
	
	/** User name for local Storage Manager */
	private String ENV_STORAGE_USER = System.getenv(ENV_VARS.STORAGE_USER.toString());
	/** Password for local Storage Manager */
	private String ENV_STORAGE_PASSWORD = System.getenv(ENV_VARS.STORAGE_PASSWORD.toString());
	/** Mount point of shared local file system */
	private String ENV_LOCAL_FS_MOUNT = System.getenv(ENV_VARS.LOCAL_FS_MOUNT.toString());
	
	/** User name for prosEO Control Instance */
	private String ENV_PROSEO_USER = System.getenv(ENV_VARS.PROSEO_USER.toString());
	/** Password for prosEO Control Instance */
	private String ENV_PROSEO_PW = System.getenv(ENV_VARS.PROSEO_PW.toString());

	/**
	 * Callback address for prosEO Production Planner, format is:
	 * <planner-URL>/processingfacilities/<procFacilityName>/finish/<podName>
	 */
	private String ENV_STATE_CALLBACK_ENDPOINT = System.getenv(ENV_VARS.STATE_CALLBACK_ENDPOINT.toString());
	/** Name of the Processing Facility this wrapper is running in */
	private String ENV_PROCESSING_FACILITY_NAME = System.getenv(ENV_VARS.PROCESSING_FACILITY_NAME.toString());
	
	/** HTTP endpoint for Ingestor callback */
	private String ENV_INGESTOR_ENDPOINT = System.getenv(ENV_VARS.INGESTOR_ENDPOINT.toString());

	// Variables to be provided by the processor or wrapper image
	/** Shell command to run the processor (with path to Job Order File as sole parameter) */
	private String ENV_PROCESSOR_SHELL_COMMAND = System.getenv(ENV_VARS.PROCESSOR_SHELL_COMMAND.toString());

	/**
	 * Remove protocol information, leading and trailing slashes from given file name
	 * 
	 * @param fileName String
	 * @return normalized file name string or an empty string, if fileName was null or blank
	 */
	private String normalizeFileName(final String fileName) {
		if (logger.isTraceEnabled()) logger.trace(">>> normalizeFileName({})", fileName);

		if (null == fileName || fileName.isBlank()) {
			return "";
		}

		String workFileName = fileName;

		// Step 1: Remove protocol
		String[] fileNameParts = workFileName.split(":", 2);
		if (2 == fileNameParts.length) {
			workFileName = fileNameParts[1];
		}

		// Step 2: Remove any leading and trailing slashes
		workFileName.replaceAll("^/+", "").replaceAll("/+$", "");

		return workFileName;
	}

	/**
	 * Check presence and values of all required Environment Variables
	 * @return true/false
	 */
	private Boolean checkEnvironment() {
		if (logger.isTraceEnabled()) logger.trace(">>> checkEnv()");

		logger.info(MSG_CHECKING_ENVIRONMENT, ENV_VARS.values().length);
		for (ENV_VARS e: ENV_VARS.values()) {
			logger.info("... {} = {}", e, System.getenv(e.toString()));
		}

		boolean envOK = true;
		if (ENV_JOBORDER_FILE == null || ENV_JOBORDER_FILE.isEmpty()) {
			logger.error(MSG_INVALID_VALUE_OF_ENVVAR, ENV_VARS.JOBORDER_FILE);
			envOK = false;
		}
		if (ENV_STORAGE_ENDPOINT == null || ENV_STORAGE_ENDPOINT.isEmpty()) {
			logger.error(MSG_INVALID_VALUE_OF_ENVVAR, ENV_VARS.STORAGE_ENDPOINT);
			envOK = false;
		}
		if (ENV_STORAGE_USER == null || ENV_STORAGE_USER.isEmpty()) {
			logger.error(MSG_INVALID_VALUE_OF_ENVVAR, ENV_VARS.STORAGE_USER);
			envOK = false;
		}
		if (ENV_STORAGE_PASSWORD == null || ENV_STORAGE_PASSWORD.isEmpty()) {
			logger.error(MSG_INVALID_VALUE_OF_ENVVAR, ENV_VARS.STORAGE_PASSWORD);
			envOK = false;
		}
		if (ENV_STATE_CALLBACK_ENDPOINT == null || ENV_STATE_CALLBACK_ENDPOINT.isEmpty()) {
			logger.error(MSG_INVALID_VALUE_OF_ENVVAR, ENV_VARS.STATE_CALLBACK_ENDPOINT);
			envOK = false;
		}
		if (ENV_PROCESSOR_SHELL_COMMAND == null || ENV_PROCESSOR_SHELL_COMMAND.isEmpty()) {
			logger.error(MSG_INVALID_VALUE_OF_ENVVAR, ENV_VARS.PROCESSOR_SHELL_COMMAND);
			envOK = false;
		}
		if (ENV_PROCESSING_FACILITY_NAME == null || ENV_PROCESSING_FACILITY_NAME.isEmpty()) {
			logger.error(MSG_INVALID_VALUE_OF_ENVVAR, ENV_VARS.PROCESSING_FACILITY_NAME);
			envOK = false;
		}
		if(ENV_INGESTOR_ENDPOINT==null || ENV_INGESTOR_ENDPOINT.isEmpty()) {
			logger.error(MSG_INVALID_VALUE_OF_ENVVAR, ENV_VARS.INGESTOR_ENDPOINT);
			envOK = false;
		}
		if(ENV_PROSEO_USER==null || ENV_PROSEO_USER.isEmpty()) {
			logger.error(MSG_INVALID_VALUE_OF_ENVVAR, ENV_VARS.PROSEO_USER);
			envOK = false;
		}
		if(ENV_PROSEO_PW==null || ENV_PROSEO_PW.isEmpty()) {
			logger.error(MSG_INVALID_VALUE_OF_ENVVAR, ENV_VARS.PROSEO_PW);
			envOK = false;
		}
		if(ENV_LOCAL_FS_MOUNT==null || ENV_LOCAL_FS_MOUNT.isEmpty()) {
			logger.error(MSG_INVALID_VALUE_OF_ENVVAR, ENV_VARS.LOCAL_FS_MOUNT);
			envOK = false;
		}

		if (envOK) {
			logger.info(MSG_ENVIRONMENT_CHECK_PASSED);
			logger.info(MSG_PREFIX_TIMESTAMP_FOR_NAMING, WRAPPER_TIMESTAMP);
		}
		return envOK;
	}
	/**
	 * Fetch Job Order file from Storage Manager
	 * 
	 * @return the Job Order file as String
	 */
	private String provideInitialJOF() {
		if (logger.isTraceEnabled()) logger.trace(">>> provideInitialJOF()");
		// Call Storage Manager to retrieve Job Order File as Base64-encoded string
		try {
			Map<String,String> params = new HashMap<>();
			params.put("pathInfo", ENV_JOBORDER_FILE);
			HttpResponseInfo responseInfo = RestOps.restApiCall(ENV_STORAGE_USER, ENV_STORAGE_PASSWORD, ENV_STORAGE_ENDPOINT,
					"/joborders", null, params, RestOps.HttpMethod.GET);

			if (200 == responseInfo.gethttpCode()) {
				return new String(Base64.getDecoder().decode(responseInfo.gethttpResponse()));
			} else {
				logger.error(MSG_ERROR_RETRIEVING_JOB_ORDER, responseInfo.gethttpCode());
				return null;
			}
		} catch (Exception e) {
			logger.error(MSG_ERROR_RETRIEVING_JOB_ORDER, "Error in RestOps.restApiCall");
			return null;
		}
	}

	/**
	 * Parse the given JobOrder XML file
	 * 
	 * @param jobOrderFile the XML file to parse
	 * @return JobOrder Object
	 */
	private JobOrder parseJobOrderFile(String jobOrderFile) {
		if (logger.isTraceEnabled()) logger.trace(">>> parseJobOrderFile(JOF)");

		JobOrder jobOrderDoc = null;
		jobOrderDoc = new JobOrder();
		jobOrderDoc.read(jobOrderFile);

		//jobOrderDoc = docBuilder.parse(jobOrderFile);
		return jobOrderDoc;
	}

	/**
	 * Hook for mission-specific modifications to the job order document before fetching input data
	 * Intended for override by mission-specific wrapper classes, NO-OP in BaseWrapper.
	 * 
	 * @param jobOrderDoc the job order document to modify
	 */
	protected void preFetchInputHook(JobOrder jobOrderDoc) {
		// No operation
	}

	/**
	 * Fetch remote input-data to container-workdir and return valid JobOrder object for container-runtime-context. (=remapped file-pathes)
	 * 
	 * @param jo the JobOrder file to parse
	 * @return JobOrder object valid for container-context
	 */
	private JobOrder fetchInputData(JobOrder jo) {
		if (logger.isTraceEnabled()) logger.trace(">>> fetchInputData(JOF)");

		int numberOfInputs = 0, numberOfOutputs = 0;

		// Loop all procs -> mainly only one is present
		for(Proc item : jo.getListOfProcs()) {
			// Loop all Input
			for (InputOutput io: item.getListOfInputs()) {
				if (!FILENAME_TYPE_PHYSICAL.equals(io.getFileNameType())) {
					// Only download "Physical" files
					logger.info(MSG_SKIPPING_INPUT_ENTRY, io.getFileType(), io.getFileNameType());
					continue;
				}
				// Loop List_of_File_Names
				for (IpfFileName fn: io.getFileNames()) {
					// Fill original filename with current val of `File_Name` --> for later use...
					fn.setOriginalFileName(fn.getFileName());
					
					// Test local availability of input file
					File f = new File(fn.getFileName());
					if (f.exists()) {
						// nothing to do
						continue;
					} 

					// Request input file from Storage Manager
					Map<String,String> params = new HashMap<>();
					params.put("pathInfo", fn.getFileName() + (io.getFileNameType().equalsIgnoreCase("Directory")==true?"/":""));
					HttpResponseInfo responseInfo = RestOps.restApiCall(ENV_STORAGE_USER, ENV_STORAGE_PASSWORD, ENV_STORAGE_ENDPOINT,
							"/productfiles", null, params, RestOps.HttpMethod.GET);

					if (200 != responseInfo.gethttpCode()) {
						logger.error(MSG_ERROR_RETRIEVING_INPUT_FILE, fn.getFileName(), responseInfo.gethttpCode());
						return null;
					}

					fn.setFileName(responseInfo.gethttpResponse());
					++numberOfInputs;
				}
			}

			// Loop all Output and prepare directories
			for (InputOutput io: item.getListOfOutputs()) {

				// Loop List_of_File_Names
				for (IpfFileName fn: io.getFileNames()) {

					// Fill original filename with current val of `File_Name` --> for later use --> push results step
					fn.setOriginalFileName(fn.getFileName());
					
					// Set output file_name to local work-dir path
					fn.setFileName(ENV_LOCAL_FS_MOUNT + File.separator + CONTAINER_OUTPUTS_PATH_PREFIX
							+ File.separator + normalizeFileName(fn.getFileName()));

					// Handle directories and regular files differently
					Path filePath = Paths.get(fn.getFileName());
					if (FILENAME_TYPE_DIRECTORY.equals(io.getFileNameType())) {
						if (Files.exists(filePath)) {
							if (!Files.isDirectory(filePath)) {
								logger.error(MSG_NOT_A_DIRECTORY, fn.getFileName());
								return null;
							}
							if (0 != filePath.toFile().list().length) {
								logger.error(MSG_DIRECTORY_NOT_EMPTY, fn.getFileName());
								return null;
							}
						}
						try {
							Files.createDirectories(filePath);
						} catch (IOException | SecurityException e) {
							logger.error(MSG_UNABLE_TO_CREATE_DIRECTORY, filePath);
							return null;
						}
					} else {
						try {
							Files.deleteIfExists(filePath);
							Files.createDirectories(filePath.getParent());
						} catch (IOException | SecurityException e) {
							logger.error(MSG_UNABLE_TO_CREATE_DIRECTORY, filePath.getParent());
							return null;
						}
					}

					++numberOfOutputs;
				}
			}
		}

		logger.info(MSG_FETCHED_INPUT_FILES, numberOfInputs, numberOfOutputs, CONTAINER_JOF_PATH);
		return jo;
	}

	/**
	 * Creates valid container-context JobOrderFile under given path
	 * 
	 * @param jo JobOrder remapped JobOrder object
	 * @param path file path of newly created JOF
	 * @return True/False
	 */
	private Boolean provideContainerJOF(JobOrder jo, String path) {	
		if (logger.isTraceEnabled()) logger.trace(">>> provideContainerJOF(JOF, {})", path);

		return jo.writeXML(path, false);
	}

	/**
	 * Executes the processor
	 * 
	 * @param jofPath path of re-mapped JobOrder file valid in container context
	 * @return True/False
	 */
	private Boolean runProcessor(String jofPath) {
		if (logger.isTraceEnabled()) logger.trace(">>> runProcessor({}, {})", jofPath);

		logger.info(MSG_STARTING_PROCESSOR, ENV_PROCESSOR_SHELL_COMMAND, jofPath);

		ProcessBuilder processBuilder = new ProcessBuilder();
		processBuilder.redirectErrorStream(true); 

		processBuilder.command((ENV_PROCESSOR_SHELL_COMMAND + " " + jofPath).split(" "));
		int exitCode = EXIT_CODE_FAILURE; // Failure
		try {
			Process process = processBuilder.start();
			BufferedReader reader =
					new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line;
			while ((line = reader.readLine()) != null) {
				logger.info("... " + line);
			}
			exitCode = process.waitFor();
			logger.info(MSG_PROCESSING_FINISHED, exitCode);
		} catch (IOException | InterruptedException e) {
			logger.error(MSG_ERROR_RUNNING_PROCESSOR, e.getMessage());
		}
		return exitCode == EXIT_CODE_OK;
	}

	/**
	 * Hook for mission-specific modifications to the final job order document after execution of the processor (before push of
	 * results). Intended for
	 * <ol>
	 *   <li>Adding additional output files to the output list as desired (e. g. log files, job order file)</li>
	 *   <li>Packaging multiple files into a single ZIP file for delivery via the PRIP if desired (add an output file
	 *       with File_Name_Type "Archive", using Java constant FILENAME_TYPE_ARCHIVE)</li>
	 * </ol>
	 * Note: The first (non-archive) output file is taken as the (main) product file, subsequent files are interpreted as
	 * auxiliary files.
	 * 
	 * Intended for override by mission-specific wrapper classes, NO-OP in BaseWrapper.
	 * 
	 * @param joWork the job order document to modify
	 */
	protected void postProcessingHook(JobOrder joWork) {
		// No operation
	}

	/**
	 * Pushes processing results to prosEO storage
	 * 
	 * @param jo jobOrder  JobOrder-Object (valid in container context)
	 * @return ArrayList all infos of pushed products
	 */
	private ArrayList<RestProductFile> pushResults(JobOrder jo) {
		if (logger.isTraceEnabled()) logger.trace(">>> pushResults(JOF)");

		logger.info(MSG_UPLOADING_RESULTS);

		int numOutputs = 0;

		ArrayList<RestProductFile> pushedOutputs = new ArrayList<RestProductFile>();

		for(Proc item : jo.getListOfProcs()) {
			// Loop all Outputs
			for (InputOutput io: item.getListOfOutputs()) {
				// Ignore directories (cannot be pushed)
				if (FILENAME_TYPE_DIRECTORY.equals(io.getFileNameType())) {
					continue;
				}
				
				// Prepare product file for Ingestor REST API
				RestProductFile productFile = new RestProductFile();
				try {
					productFile.setProductId(Long.parseLong((io.getProductID())));
				} catch (NumberFormatException e) {
					logger.error(MSG_PRODUCT_ID_NOT_PARSEABLE, io.getProductID());
					return null;
				}
				productFile.setProcessingFacilityName(ENV_PROCESSING_FACILITY_NAME);
				pushedOutputs.add(productFile);

				// Loop all output files
				for (IpfFileName fn: io.getFileNames()) {
					// Push output file to Storage Manager
					Map<String,String> params = new HashMap<>();
					params.put("pathInfo", fn.getFileName() + (io.getFileNameType().equalsIgnoreCase("Directory")==true?"/":""));
					params.put("productId", io.getProductID());
					HttpResponseInfo responseInfo = RestOps.restApiCall(ENV_STORAGE_USER, ENV_STORAGE_PASSWORD, ENV_STORAGE_ENDPOINT,
							"/productfiles", null, params, RestOps.HttpMethod.PUT);

					if (201 != responseInfo.gethttpCode()) {
						logger.error(MSG_ERROR_PUSHING_OUTPUT_FILE, fn.getFileName(), responseInfo.gethttpCode());
						return null;
					}

					String[] fileTypeAndName = responseInfo.gethttpResponse().split("[|]", 2);  // pipe sign is a symbol of regex, escaped with brackets
					if (2 != fileTypeAndName.length) {
						logger.error(MSG_MALFORMED_RESPONSE_FROM_STORAGE_MANAGER, responseInfo.gethttpResponse(), fn.getFileName());
						return null;
					}
					
					// Make sure all product files have the same storage tpye
					if (null == productFile.getStorageType()) {
						productFile.setStorageType(fileTypeAndName[0]);
					} else if (!productFile.getStorageType().equals(fileTypeAndName[0])) {
						logger.error(MSG_DIFFERENT_STORAGE_TYPES_ASSIGNED, productFile.getProductId());
						return null;
					}
					
					// Separate the file path into a directory path and a file name
					String filePath = fileTypeAndName[1]; // This is not a file path in the local (POSIX) file system; its separator is always "/"
					int lastSeparatorIndex = filePath.lastIndexOf('/');
					String parentPath = filePath.substring(0, lastSeparatorIndex);
					String fileName = filePath.substring(lastSeparatorIndex + 1);
					
					// Make sure all product files are stored in the same path
					if (null == productFile.getFilePath()) {
						productFile.setFilePath(parentPath);
					} else if (!productFile.getFilePath().equals(parentPath)) {
						logger.error(MSG_DIFFERENT_FILE_PATHS_ASSIGNED, productFile.getProductId());
						return null;
					}
					
					// Create metadata for this file
					if (FILENAME_TYPE_ARCHIVE.equals(io.getFileNameType())) {
						// Extension to JOF specification, only to be used in "postProcessingHook()" to identify ZIP archives,
						// must only be used once
						if (null != productFile.getZipFileName()) {
							logger.error(MSG_MORE_THAN_ONE_ZIP_ARCHIVE, productFile.getProductId());
							return null;
						}
						productFile.setZipFileName(fileName);
						File primaryProductFile = new File(fn.getFileName()); // The full path to the file in the local file system
						productFile.setZipFileSize(primaryProductFile.length());
						try {
							productFile.setZipChecksum(MD5Util.md5Digest(primaryProductFile));
							productFile.setZipChecksumTime(OrbitTimeFormatter.format(Instant.now()));
						} catch (IOException e) {
							logger.error(MSG_CANNOT_CALCULATE_CHECKSUM, productFile.getProductId());
							return null;
						}
					} else if (null == productFile.getProductFileName()) {
						// The first (non-archive) file is taken as the main product file
						productFile.setProductFileName(fileName);
						File primaryProductFile = new File(fn.getFileName()); // The full path to the file in the local file system
						productFile.setFileSize(primaryProductFile.length());
						try {
							productFile.setChecksum(MD5Util.md5Digest(primaryProductFile));
							productFile.setChecksumTime(OrbitTimeFormatter.format(Instant.now()));
						} catch (IOException e) {
							logger.error(MSG_CANNOT_CALCULATE_CHECKSUM, productFile.getProductId());
							return null;
						}
					} else {
						// Subsequent (non-archive) files are auxiliary files
						productFile.getAuxFileNames().add(fileName);
					}

					++numOutputs;
				}

			}
		}

		logger.info(MSG_PRODUCTS_UPLOADED, pushedOutputs.size(), numOutputs);
		if (logger.isDebugEnabled()) {
			logger.debug("Upload summary: listing {} outputs of type 'RestProductFile'", pushedOutputs.size());
			for (RestProductFile p : pushedOutputs) {
				logger.debug("PRODUCT_ID = {}, FS_TYPE = {}, PATH = {}, PRODUCT FILE = {}",
						p.getProductId(), p.getStorageType(), p.getFilePath(), p.getProductFileName());
			} 
		}
		return pushedOutputs;
	}

	/**
	 * Register pushed Products using prosEO-Ingestor REST API
	 * 
	 * @param pushedProducts ArrayList
	 * @return HTTP response code of Ingestor-API
	 */
	private boolean ingestPushedOutputs(ArrayList<RestProductFile> pushedProducts) {
		if (logger.isTraceEnabled()) logger.trace(">>> ingestPushedOutputs(RestProductFile[{}])", pushedProducts.size());

		logger.info(MSG_REGISTERING_PRODUCTS, pushedProducts.size(), ENV_INGESTOR_ENDPOINT);

		// loop pushed products and send HTTP-POST to prosEO-Ingestor in order to register the products
		for (RestProductFile productFile : pushedProducts) {
			String ingestorRestUrl =   "/ingest/" + ENV_PROCESSING_FACILITY_NAME + "/" + productFile.getProductId();

			ObjectMapper obj = new ObjectMapper(); 
			String jsonRequest = null;
			try {
				jsonRequest = obj.writeValueAsString(productFile);
			} catch (JsonProcessingException e) {
				logger.error(MSG_ERROR_CONVERTING_INGESTOR_PRODUCT, productFile.getProductId(), e.getMessage());
				return false;
			} 
			HttpResponseInfo responseInfo = RestOps.restApiCall(ENV_PROSEO_USER, ENV_PROSEO_PW, ENV_INGESTOR_ENDPOINT,
					ingestorRestUrl, jsonRequest, null, RestOps.HttpMethod.POST);

			if (null == responseInfo || 201 != responseInfo.gethttpCode()) {
				logger.error(MSG_ERROR_REGISTERING_PRODUCT,
						productFile.getProductId(), (null == responseInfo ? 500 : responseInfo.gethttpCode()));
				return false;
			}
		}

		logger.info(MSG_PRODUCTS_REGISTERED, pushedProducts.size());
		return true;
	}


	/**
	 * Triggers a callback to ENV_STATE_CALLBACK_ENDPOINT (prosEO Production Planner)
	 * 
	 * @param msg the callback message
	 */
	private void callBack(String msg) {
		if (logger.isTraceEnabled()) logger.trace(">>> callBack({})", msg);

		try {
			Map<String,String> params = new HashMap<>();
			params.put("status", msg);
			// queryParam status is set by wrapper
			HttpResponseInfo responseInfo = RestOps.restApiCall(ENV_PROSEO_USER, ENV_PROSEO_PW, ENV_STATE_CALLBACK_ENDPOINT,
					"", null, params, RestOps.HttpMethod.POST);

			if (null == responseInfo || 200 != responseInfo.gethttpCode()) {
				logger.error(MSG_ERROR_CALLING_PLANNER, (null == responseInfo ? 500 : responseInfo.gethttpCode()));
			}

			logger.info(MSG_PLANNER_RESPONSE, responseInfo.gethttpResponse(), responseInfo.gethttpCode());
		} catch (Exception e) {
			logger.error(MSG_ERROR_RETRIEVING_JOB_ORDER, "Error in RestOps.restApiCall");
			return;
		}
		
	}

	/**
	 * Perform processing: check parameters, parse JobOrder file, fetch input files, process, push output files
	 * 
	 * @return the program exit code (OK or FAILURE)
	 */
	final public int run() {
		if (logger.isTraceEnabled()) logger.trace(">>> run()");

		/* ProcessorWrapperFlow */
		/* ==================== */

		/* STEP [1] Check environment variables (acting as invocation parameters) */

		logger.info(MSG_STARTING_BASE_WRAPPER,  ENV_JOBORDER_FILE);
		Boolean check = checkEnvironment();
		if (!check) {
			logger.info(MSG_LEAVING_BASE_WRAPPER, EXIT_CODE_FAILURE, EXIT_TEXT_FAILURE);
			return EXIT_CODE_FAILURE;
		}

		/* STEP [2] Fetch the JobOrder file from the Storage Manager */

		String jobOrderFile = provideInitialJOF();
		if (null == jobOrderFile) {
			callBack(CALLBACK_STATUS_FAILURE);
			logger.info(MSG_LEAVING_BASE_WRAPPER, EXIT_CODE_FAILURE, EXIT_TEXT_FAILURE);
			return EXIT_CODE_FAILURE;
		}

		/* STEP [3] Create a Job Order document from the JobOrder file */

		JobOrder jobOrderDoc = parseJobOrderFile(jobOrderFile);
		if (null == jobOrderDoc) {
			callBack(CALLBACK_STATUS_FAILURE);
			logger.info(MSG_LEAVING_BASE_WRAPPER, EXIT_CODE_FAILURE, EXIT_TEXT_FAILURE);
			return EXIT_CODE_FAILURE;
		}

		/* STEP [4 - Optional] Modify Job Order document for processor operation */

		/* Hook for additional mission-specific pre-fetch operations on the job order document */
		preFetchInputHook(jobOrderDoc);

		/* STEP [5] Fetch input files and remap file names in Job Order */

		JobOrder joWork = null;
		joWork = fetchInputData(jobOrderDoc);
		if (null == joWork) {
			callBack(CALLBACK_STATUS_FAILURE);
			logger.info(MSG_LEAVING_BASE_WRAPPER, EXIT_CODE_FAILURE, EXIT_TEXT_FAILURE);
			return EXIT_CODE_FAILURE;
		}

		/* STEP [6] Create Job Order File in file system for container context */

		Boolean containerJOF = provideContainerJOF(joWork, CONTAINER_JOF_PATH);
		if (!containerJOF) {
			callBack(CALLBACK_STATUS_FAILURE);
			logger.info(MSG_LEAVING_BASE_WRAPPER, EXIT_CODE_FAILURE, EXIT_TEXT_FAILURE);
			return EXIT_CODE_FAILURE;
		}

		/* STEP [7] Execute Processor */

		Boolean procRun = runProcessor(CONTAINER_JOF_PATH);
		if (!procRun) {
			callBack(CALLBACK_STATUS_FAILURE);
			logger.info(MSG_LEAVING_BASE_WRAPPER, EXIT_CODE_FAILURE, EXIT_TEXT_FAILURE);
			return EXIT_CODE_FAILURE;
		}

		/* STEP [8] Perform processor-specific updates to the Job Order document */

		/* Hook for additional post-processing operations on the job order document */
		postProcessingHook(joWork);

		/* STEP [9] Push Processing Results to prosEO Storage, if any */

		ArrayList<RestProductFile> pushedProducts = pushResults(joWork);
		if (null == pushedProducts) {
			callBack(CALLBACK_STATUS_FAILURE);
			logger.info(MSG_LEAVING_BASE_WRAPPER, EXIT_CODE_FAILURE, EXIT_TEXT_FAILURE);
			return EXIT_CODE_FAILURE;
		}

		/* STEP [10] Register pushed products using with prosEO Ingestor */

		boolean ingestOK = ingestPushedOutputs(pushedProducts);
		if (!ingestOK) {
			callBack(CALLBACK_STATUS_FAILURE);
			logger.info(MSG_LEAVING_BASE_WRAPPER, EXIT_CODE_FAILURE, EXIT_TEXT_FAILURE);
			return EXIT_CODE_FAILURE;
		}

		/* STEP [11] Report success to Production Planner */

		callBack(CALLBACK_STATUS_SUCCESS);

		logger.info(MSG_LEAVING_BASE_WRAPPER, EXIT_CODE_OK, EXIT_TEXT_OK);
		return EXIT_CODE_OK;
	}

	/**
	 * Main routine: Run BaseWrapper
	 * 
	 * @param args first string argument is class name of actual Wrapper class (must be a subclass of BaseWrapper or BaseWrapper
	 *        itself; default is to use BaseWrapper)
	 */
	public static void main(String[] args) {
		Class<?> clazz = null;;
		try {
			clazz = ( 0 == args.length ? BaseWrapper.class : ClassLoader.getSystemClassLoader().loadClass(args[0]) );
		} catch (ClassNotFoundException e) {
			logger.error("Requested wrapper class {} not found", args[0]);
			System.exit(EXIT_CODE_FAILURE);
		}
		if (!BaseWrapper.class.isAssignableFrom(clazz)) {
			logger.error("Requested wrapper class {} is not a subclass of BaseWrapper", clazz.getName());
			System.exit(EXIT_CODE_FAILURE);
		}
		try {
			System.exit(((BaseWrapper) clazz.getDeclaredConstructor().newInstance()).run());
		} catch (Exception e) {
			logger.error("Requested wrapper class {} cannot be launched (cause: {})", clazz.getName(), e.getMessage());
			System.exit(EXIT_CODE_FAILURE);
		}
	}

}
