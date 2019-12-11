/**
 * IngestorCommandRunner.java
 * 
 * (C) 2019 Dr. Bassler & Co. Managementberatung GmbH
 */
package de.dlr.proseo.ui.cli;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.dlr.proseo.model.Orbit;
import de.dlr.proseo.model.rest.model.RestProduct;
import de.dlr.proseo.ui.backend.ServiceConfiguration;
import de.dlr.proseo.ui.backend.ServiceConnection;
import de.dlr.proseo.ui.backend.UserManager;
import de.dlr.proseo.ui.cli.parser.ParsedCommand;
import de.dlr.proseo.ui.cli.parser.ParsedOption;
import de.dlr.proseo.ui.cli.parser.ParsedParameter;

/**
 * Run commands for ingesting and managing prosEO products (create, read, update, delete etc.). All methods assume that before 
 * invocation a syntax check of the command has been performed, so no extra checks are performed.
 * 
 * @author Dr. Thomas Bassler
 *
 */
@Component
public class IngestorCommandRunner {

	/* Message ID constants */
	// Same as in OrderCommandRunner
	private static final int MSG_ID_INVALID_COMMAND_NAME = 2930;
	private static final int MSG_ID_SUBCOMMAND_MISSING = 2931;
	private static final int MSG_ID_USER_NOT_LOGGED_IN = 2932;
	private static final int MSG_ID_NOT_AUTHORIZED = 2933;
	private static final int MSG_ID_OPERATION_CANCELLED = 2934;
	private static final int MSG_ID_INVALID_TIME = 2945;
	
	// Specific to IngestorCommandRunner
	private static final int MSG_ID_NO_PRODUCTS_FOUND = 2950;
	private static final int MSG_ID_PRODUCT_CREATED = 2951;
	private static final int MSG_ID_INVALID_DATABASE_ID = 2952;
	private static final int MSG_ID_NO_IDENTIFIER_GIVEN = 2953;
	private static final int MSG_ID_PRODUCT_NOT_FOUND = 2954;
	private static final int MSG_ID_PRODUCT_UPDATED = 2955;
	private static final int MSG_ID_PRODUCT_DELETED = 2956;
	private static final int MSG_ID_INGESTION_FILE_MISSING = 2957;
	private static final int MSG_ID_PROCESSING_FACILITY_MISSING = 2958;
	private static final int MSG_ID_PRODUCTS_INGESTED = 2959;
	private static final int MSG_ID_PRODUCT_DATA_INVALID = 2960;
//	private static final int MSG_ID_NOT_IMPLEMENTED = 9000;

	/* Message string constants */
	private static final String MSG_INVALID_COMMAND_NAME = "(E%d) Invalid command name %s";
	private static final String MSG_SUBCOMMAND_MISSING = "(E%d) Subcommand missing for command %s";
	private static final String MSG_USER_NOT_LOGGED_IN = "(E%d) User not logged in";
	private static final String MSG_NOT_AUTHORIZED = "(E%d) User %s not authorized to manage products for mission %s";
	private static final String MSG_NO_PRODUCTS_FOUND = "(E%d) No products found for given search criteria";
	private static final String MSG_INVALID_TIME = "(E%d) Time format %s not parseable";
	private static final String MSG_INVALID_DATABASE_ID = "(E%d) Database ID %s not numeric";
	private static final String MSG_NO_IDENTIFIER_GIVEN = "(E%d) No product database ID given";
	private static final String MSG_PRODUCT_NOT_FOUND = "(E%d) Product with database ID %d not found";
	private static final String MSG_INGESTION_FILE_MISSING = "(E%d) No file for product ingestion given";
	private static final String MSG_PROCESSING_FACILITY_MISSING = "(E%d) No processing facility to ingest to given";
	private static final String MSG_PRODUCT_DATA_INVALID = "(E%d) Product data invalid (cause: %s)";
//	private static final String MSG_NOT_IMPLEMENTED = "(E%d) Command %s not implemented";

	private static final String MSG_OPERATION_CANCELLED = "(I%d) Operation cancelled";
	private static final String MSG_PRODUCT_CREATED = "(I%d) Product of class %s created (database ID %d, UUID %s)";
	private static final String MSG_PRODUCT_UPDATED = "(I%d) Product with database ID %d updated (new version %d)";
	private static final String MSG_PRODUCT_DELETED = "(I%d) Product with database ID %d deleted";
	private static final String MSG_PRODUCTS_INGESTED = "(I%d) %d products ingested to processing facility %s";

	/* Other string constants */
	public static final String CMD_PRODUCT = "product";
	private static final String CMD_SHOW = "show";
	private static final String CMD_CREATE = "create";
	private static final String CMD_UPDATE = "update";
	private static final String CMD_DELETE = "delete";
	public static final String CMD_INGEST = "ingest";

	private static final String MSG_CHECKING_FOR_MISSING_MANDATORY_ATTRIBUTES = "Checking for missing mandatory attributes ...";
	private static final String PROMPT_PRODUCT_CLASS = "Product class (empty field cancels): ";
	private static final String PROMPT_FILE_CLASS = "File class (empty field cancels): ";
	private static final String PROMPT_START_TIME = "Sensing start time (empty field cancels): ";
	private static final String PROMPT_STOP_TIME = "Sensing stop time (empty field cancels): ";
	private static final String PROMPT_GENERATION_TIME = "Product generation time (empty field cancels): ";
	
	private static final String URI_PATH_INGESTOR = "/ingest";
	private static final String URI_PATH_PRODUCTS = "/products";
	
	private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withZone(ZoneId.of("UTC"));

	/** The user manager used by all command runners */
	@Autowired
	private UserManager userManager;
	
	/** The configuration object for the prosEO backend services */
	@Autowired
	private ServiceConfiguration serviceConfig;
	
	/** The connector service to the prosEO backend services */
	@Autowired
	private ServiceConnection serviceConnection;
	
	/** A logger for this class */
	private static Logger logger = LoggerFactory.getLogger(IngestorCommandRunner.class);

	/**
	 * Create a new product; if the input is not from a file, the user will be prompted for mandatory attributes
	 * not given on the command line
	 * 
	 * @param createCommand the parsed "product create" command
	 */
	private void createProduct(ParsedCommand createCommand) {
		if (logger.isTraceEnabled()) logger.trace(">>> createProduct({})", (null == createCommand ? "null" : createCommand.getName()));
		
		/* Check command options */
		File productFile = null;
		String productFileFormat = null;
		for (ParsedOption option: createCommand.getOptions()) {
			switch(option.getName()) {
			case "file":
				productFile = new File(option.getValue());
				break;
			case "format":
				productFileFormat = option.getValue().toUpperCase();
				break;
			}
		}
		
		/* Read product file, if any */
		RestProduct restProduct = null;
		if (null == productFile) {
			restProduct = new RestProduct();
		} else {
			try {
				restProduct = CLIUtil.parseObjectFile(productFile, productFileFormat, RestProduct.class);
			} catch (IllegalArgumentException | IOException e) {
				System.err.println(e.getMessage());
				return;
			}
		}
		
		/* Check command parameters (overriding values from product file) */
		for (int i = 0; i < createCommand.getParameters().size(); ++i) {
			ParsedParameter param = createCommand.getParameters().get(i);
			if (0 == i) {
				// First parameter is product class name
				restProduct.setProductClass(param.getValue());
			} else {
				// Remaining parameters are "attribute=value" parameters
				try {
					CLIUtil.setAttribute(restProduct, param.getValue());
				} catch (Exception e) {
					System.err.println(e.getMessage());
					return;
				}
			}
		}
		
		/* Set missing attributes to default values where possible */
		if (null == restProduct.getMissionCode() || 0 == restProduct.getMissionCode().length()) {
			restProduct.setMissionCode(userManager.getMission());
		}
		
		/* Prompt user for missing mandatory attributes */
		System.out.println(MSG_CHECKING_FOR_MISSING_MANDATORY_ATTRIBUTES);
		if (null == restProduct.getProductClass() || 0 == restProduct.getProductClass().length()) {
			System.out.print(PROMPT_PRODUCT_CLASS);
			String response = System.console().readLine();
			if ("".equals(response)) {
				System.out.println(String.format(MSG_OPERATION_CANCELLED, MSG_ID_OPERATION_CANCELLED));
				return;
			}
			restProduct.setProductClass(response);
		}
		if (null == restProduct.getFileClass() || 0 == restProduct.getFileClass().length()) {
			System.out.print(PROMPT_FILE_CLASS);
			String response = System.console().readLine();
			if ("".equals(response)) {
				System.out.println(String.format(MSG_OPERATION_CANCELLED, MSG_ID_OPERATION_CANCELLED));
				return;
			}
			restProduct.setFileClass(response);
		}
		while (null == restProduct.getSensingStartTime() || 0 == restProduct.getSensingStartTime().length()) {
			System.out.print(PROMPT_START_TIME);
			String response = System.console().readLine();
			if ("".equals(response)) {
				System.out.println(String.format(MSG_OPERATION_CANCELLED, MSG_ID_OPERATION_CANCELLED));
				return;
			}
			try {
				restProduct.setSensingStartTime(Orbit.orbitTimeFormatter.format(Instant.parse(response + "Z"))); // no time zone in input expected
			} catch (DateTimeParseException e) {
				System.err.println(String.format(MSG_INVALID_TIME, MSG_ID_INVALID_TIME, response));
			}
		}
		while (null == restProduct.getSensingStopTime() || 0 == restProduct.getSensingStopTime().length()) {
			System.out.print(PROMPT_STOP_TIME);
			String response = System.console().readLine();
			if ("".equals(response)) {
				System.out.println(String.format(MSG_OPERATION_CANCELLED, MSG_ID_OPERATION_CANCELLED));
				return;
			}
			try {
				restProduct.setSensingStopTime(Orbit.orbitTimeFormatter.format(Instant.parse(response + "Z"))); // no time zone in input expected
			} catch (DateTimeParseException e) {
				System.err.println(String.format(MSG_INVALID_TIME, MSG_ID_INVALID_TIME, response));
			}
		}
		while (null == restProduct.getSensingStopTime() || 0 == restProduct.getSensingStopTime().length()) {
			System.out.print(PROMPT_START_TIME);
			String response = System.console().readLine();
			if ("".equals(response)) {
				System.out.println(String.format(MSG_OPERATION_CANCELLED, MSG_ID_OPERATION_CANCELLED));
				return;
			}
			try {
				restProduct.setSensingStopTime(Orbit.orbitTimeFormatter.format(Instant.parse(response + "Z"))); // no time zone in input expected
			} catch (DateTimeParseException e) {
				System.err.println(String.format(MSG_INVALID_TIME, MSG_ID_INVALID_TIME, response));
			}
		}
		while (null == restProduct.getGenerationTime() || 0 == restProduct.getGenerationTime().length()) {
			System.out.print(PROMPT_GENERATION_TIME);
			String response = System.console().readLine();
			if ("".equals(response)) {
				System.out.println(String.format(MSG_OPERATION_CANCELLED, MSG_ID_OPERATION_CANCELLED));
				return;
			}
			try {
				restProduct.setGenerationTime(Orbit.orbitTimeFormatter.format(Instant.parse(response + "Z"))); // no time zone in input expected
			} catch (DateTimeParseException e) {
				System.err.println(String.format(MSG_INVALID_TIME, MSG_ID_INVALID_TIME, response));
			}
		}
		
		/* Create product */
		try {
			restProduct = serviceConnection.postToService(serviceConfig.getIngestorUrl(), URI_PATH_PRODUCTS, 
					restProduct, RestProduct.class, userManager.getUser(), userManager.getPassword());
		} catch (HttpClientErrorException.BadRequest e) {
			// Already logged
			System.err.println(String.format(MSG_PRODUCT_DATA_INVALID, MSG_ID_PRODUCT_DATA_INVALID,  e.getMessage()));
			return;
		} catch (HttpClientErrorException.Unauthorized e) {
			// Already logged
			System.err.println(String.format(MSG_NOT_AUTHORIZED, MSG_ID_NOT_AUTHORIZED,  userManager.getUser(), userManager.getMission()));
			return;
		} catch (RuntimeException e) {
			// Already logged
			System.err.println(e.getMessage());
			return;
		}

		/* Report success, giving newly assigned product ID and UUID */
		String message = String.format(MSG_PRODUCT_CREATED, MSG_ID_PRODUCT_CREATED,
				restProduct.getProductClass(), restProduct.getId(), restProduct.getUuid());
		logger.info(message);
		System.out.println(message);
	}
	
	/**
	 * Show the product specified in the command parameters or options
	 * 
	 * @param showCommand the parsed "product show" command
	 */
	private void showProduct(ParsedCommand showCommand) {
		if (logger.isTraceEnabled()) logger.trace(">>> showProduct({})", (null == showCommand ? "null" : showCommand.getName()));
		
		/* Prepare request URI */
		String requestURI = URI_PATH_PRODUCTS + "?mission=" + userManager.getMission();
		
		for (ParsedOption option: showCommand.getOptions()) {
			if ("from".equals(option.getName())) {
				requestURI += "&startTimeFrom=" + formatter.format(Instant.parse(option.getValue()));
			} else if ("to".equals(option.getName())) {
				requestURI += "&startTimeTo=" + formatter.format(Instant.parse(option.getValue()));
			}
		}
		for (ParsedParameter parameter: showCommand.getParameters()) {
			requestURI += "&productClass=" + parameter.getValue();
		}
		
		/* Get the product information from the Ingestor */
		List<?> resultList = null;
		try {
			resultList = serviceConnection.getFromService(serviceConfig.getIngestorUrl(),
					requestURI, List.class, userManager.getUser(), userManager.getPassword());
		} catch (HttpClientErrorException.NotFound e) {
			String message = String.format(MSG_NO_PRODUCTS_FOUND, MSG_ID_NO_PRODUCTS_FOUND);
			logger.error(message);
			System.err.println(message);
			return;
		} catch (HttpClientErrorException.Unauthorized e) {
			// Already logged
			System.err.println(String.format(MSG_NOT_AUTHORIZED, MSG_ID_NOT_AUTHORIZED,  userManager.getUser(), userManager.getMission()));
			return;
		} catch (RuntimeException e) {
			// Already logged
			System.err.println(e.getMessage());
			return;
		}
		
		/* Display the product(s) found */
		ObjectMapper mapper = new ObjectMapper();
		for (Object result: resultList) {
			RestProduct restProduct = mapper.convertValue(result, RestProduct.class);
			
			// TODO Format output
			System.out.println(restProduct);
		}
	}
	
	/**
	 * Update a product from a product file or from "attribute=value" pairs (overriding any product file entries)
	 * 
	 * @param updateCommand the parsed "product update" command
	 */
	private void updateProduct(ParsedCommand updateCommand) {
		if (logger.isTraceEnabled()) logger.trace(">>> updateProduct({})", (null == updateCommand ? "null" : updateCommand.getName()));

		/* Check command options */
		File productFile = null;
		String productFileFormat = null;
		boolean isDeleteAttributes = false;
		for (ParsedOption option: updateCommand.getOptions()) {
			switch(option.getName()) {
			case "file":
				productFile = new File(option.getValue());
				break;
			case "format":
				productFileFormat = option.getValue().toUpperCase();
				break;
			case "delete-attributes":
				isDeleteAttributes = true;
				break;
			}
		}
		
		/* Read product file, if any, and update product attributes */
		RestProduct updatedProduct = null;
		if (null == productFile) {
			updatedProduct = new RestProduct();
		} else {
			try {
				updatedProduct = CLIUtil.parseObjectFile(productFile, productFileFormat, RestProduct.class);
			} catch (IllegalArgumentException | IOException e) {
				System.err.println(e.getMessage());
				return;
			}
		}
		
		/* Check command parameters (overriding values from product file) */
		for (int i = 0; i < updateCommand.getParameters().size(); ++i) {
			ParsedParameter param = updateCommand.getParameters().get(i);
			if (0 == i) {
				// First parameter is database ID
				try {
					updatedProduct.setId(Long.parseLong(param.getValue()));
				} catch (NumberFormatException e) {
					System.err.println(String.format(MSG_INVALID_DATABASE_ID, MSG_ID_INVALID_DATABASE_ID, param.getValue()));
					return;
				}
			} else {
				// Remaining parameters are "attribute=value" parameters
				try {
					CLIUtil.setAttribute(updatedProduct, param.getValue());
				} catch (Exception e) {
					System.err.println(e.getMessage());
					return;
				}
			}
		}
		
		/* Read original product from Ingestor service */
		RestProduct restProduct = null;
		if (null == updatedProduct.getId() || 0 == updatedProduct.getId().longValue()) {
			// No identifying value given
			System.err.println(String.format(MSG_NO_IDENTIFIER_GIVEN, MSG_ID_NO_IDENTIFIER_GIVEN));
			return;
		}
		try {
			restProduct = serviceConnection.getFromService(serviceConfig.getIngestorUrl(),
					URI_PATH_PRODUCTS + "/" + updatedProduct.getId(),
					RestProduct.class, userManager.getUser(), userManager.getPassword());
		} catch (HttpClientErrorException.NotFound e) {
			String message = String.format(MSG_PRODUCT_NOT_FOUND, MSG_ID_PRODUCT_NOT_FOUND, restProduct.getId());
			logger.error(message);
			System.err.println(message);
			return;
		} catch (HttpClientErrorException.BadRequest e) {
			// Already logged
			System.err.println(String.format(MSG_PRODUCT_DATA_INVALID, MSG_ID_PRODUCT_DATA_INVALID,  e.getMessage()));
			return;
		} catch (HttpClientErrorException.Unauthorized e) {
			// Already logged
			System.err.println(String.format(MSG_NOT_AUTHORIZED, MSG_ID_NOT_AUTHORIZED,  userManager.getUser(), userManager.getMission()));
			return;
		} catch (RuntimeException e) {
			// Already logged
			System.err.println(e.getMessage());
			return;
		}

		/* Compare attributes of database product with updated product */
		// No modification of ID, version, mission code, product class or UUID allowed
		if (isDeleteAttributes || null != updatedProduct.getFileClass()) {
			restProduct.setFileClass(updatedProduct.getFileClass());
		}
		if (isDeleteAttributes || null != updatedProduct.getMode()) {
			restProduct.setMode(updatedProduct.getMode());
		}
		if (isDeleteAttributes || null != updatedProduct.getSensingStartTime()) {
			restProduct.setSensingStartTime(updatedProduct.getSensingStartTime());
		}
		if (isDeleteAttributes || null != updatedProduct.getSensingStopTime()) {
			restProduct.setSensingStopTime(updatedProduct.getSensingStopTime());
		}
		if (isDeleteAttributes || null != updatedProduct.getGenerationTime()) {
			restProduct.setGenerationTime(updatedProduct.getGenerationTime());
		}
		if (isDeleteAttributes || !updatedProduct.getComponentProductIds().isEmpty()) {
			restProduct.setComponentProductIds(updatedProduct.getComponentProductIds());
		}
		if (isDeleteAttributes || null != updatedProduct.getEnclosingProductId()) {
			restProduct.setEnclosingProductId(updatedProduct.getEnclosingProductId());
		}
		if (isDeleteAttributes || null != updatedProduct.getOrbit()) {
			restProduct.setOrbit(updatedProduct.getOrbit());
		}
		if (isDeleteAttributes || !updatedProduct.getProductFile().isEmpty()) {
			restProduct.setProductFile(updatedProduct.getProductFile());
		}
		if (isDeleteAttributes || !updatedProduct.getParameters().isEmpty()) {
			restProduct.setParameters(updatedProduct.getParameters());
		}
		
		/* Update product using Ingestor service */
		try {
			restProduct = serviceConnection.patchToService(serviceConfig.getIngestorUrl(), URI_PATH_PRODUCTS + "/" + restProduct.getId(),
					restProduct, RestProduct.class, userManager.getUser(), userManager.getPassword());
		} catch (HttpClientErrorException.NotFound e) {
			String message = String.format(MSG_PRODUCT_NOT_FOUND, MSG_ID_PRODUCT_NOT_FOUND, restProduct.getId());
			logger.error(message);
			System.err.println(message);
			return;
		} catch (HttpClientErrorException.Unauthorized e) {
			// Already logged
			System.err.println(String.format(MSG_NOT_AUTHORIZED, MSG_ID_NOT_AUTHORIZED,  userManager.getUser(), userManager.getMission()));
			return;
		} catch (RuntimeException e) {
			// Already logged
			System.err.println(e.getMessage());
			return;
		}
		
		/* Report success, giving new product version */
		String message = String.format(MSG_PRODUCT_UPDATED, MSG_ID_PRODUCT_UPDATED, restProduct.getId(), restProduct.getVersion());
		logger.info(message);
		System.out.println(message);
	}
	
	/**
	 * Delete the given product
	 * 
	 * @param deleteCommand the parsed "product delete" command
	 */
	private void deleteProduct(ParsedCommand deleteCommand) {
		if (logger.isTraceEnabled()) logger.trace(">>> deleteProduct({})", (null == deleteCommand ? "null" : deleteCommand.getName()));

		/* Get product database ID from command parameters */
		if (deleteCommand.getParameters().isEmpty()) {
			// No identifying value given
			System.err.println(String.format(MSG_NO_IDENTIFIER_GIVEN, MSG_ID_NO_IDENTIFIER_GIVEN));
			return;
		}
		String productIdString = deleteCommand.getParameters().get(0).getValue();
		Long productId = null;
		try {
			productId = Long.parseLong(productIdString);
		} catch (NumberFormatException e) {
			System.err.println(String.format(MSG_INVALID_DATABASE_ID, MSG_ID_INVALID_DATABASE_ID, productIdString));
			return;
		}
		
		/* Delete product using Ingestor service */
		try {
			serviceConnection.deleteFromService(serviceConfig.getIngestorUrl(), URI_PATH_PRODUCTS + "/" + productIdString, 
					userManager.getUser(), userManager.getPassword());
		} catch (HttpClientErrorException.NotFound e) {
			String message = String.format(MSG_PRODUCT_NOT_FOUND, MSG_ID_PRODUCT_NOT_FOUND, productId);
			logger.error(message);
			System.err.println(message);
			return;
		} catch (HttpClientErrorException.Unauthorized e) {
			// Already logged
			System.err.println(String.format(MSG_NOT_AUTHORIZED, MSG_ID_NOT_AUTHORIZED,  userManager.getUser(), userManager.getMission()));
			return;
		} catch (Exception e) {
			// Already logged
			System.err.println(e.getMessage());
			return;
		}
		
		/* Report success */
		String message = String.format(MSG_PRODUCT_DELETED, MSG_ID_PRODUCT_DELETED, productId);
		logger.info(message);
		System.out.println(message);
	}
	
	/**
	 * Ingest a list of products
	 * 
	 * @param ingestCommand the parsed "ingest" command
	 */
	private void ingestProduct(ParsedCommand ingestCommand) {
		if (logger.isTraceEnabled()) logger.trace(">>> ingestProduct({})", (null == ingestCommand ? "null" : ingestCommand.getName()));

		/* Check command options */
		File productFile = null;
		String productFileFormat = null;
		for (ParsedOption option: ingestCommand.getOptions()) {
			switch(option.getName()) {
			case "file":
				productFile = new File(option.getValue());
				break;
			case "format":
				productFileFormat = option.getValue().toUpperCase();
				break;
			}
		}
		if (null == productFile) {
			System.err.println(String.format(MSG_INGESTION_FILE_MISSING, MSG_ID_INGESTION_FILE_MISSING));
			return;
		}
		
		/* Get processing facility from command parameters */
		if (ingestCommand.getParameters().isEmpty()) {
			System.err.println(String.format(MSG_PROCESSING_FACILITY_MISSING, MSG_ID_PROCESSING_FACILITY_MISSING));
			return;
		}
		String processingFacility = ingestCommand.getParameters().get(0).getValue();
		
		/* Read file of products to ingest */
		List<?> productsToIngest = null;
		try {
				productsToIngest = CLIUtil.parseObjectFile(productFile, productFileFormat, List.class);
			} catch (IllegalArgumentException | IOException e) {
				System.err.println(e.getMessage());
				return;
			}
		
		/* Ingest all given products to the given processing facility using the Ingestor service */
		List<?> ingestedProducts = null;
		try {
			ingestedProducts = serviceConnection.postToService(serviceConfig.getIngestorUrl(),
					URI_PATH_INGESTOR + "/" + processingFacility,
					productsToIngest, List.class, userManager.getUser(), userManager.getPassword());
		} catch (HttpClientErrorException.Unauthorized e) {
			// Already logged
			System.err.println(String.format(MSG_NOT_AUTHORIZED, MSG_ID_NOT_AUTHORIZED,  userManager.getUser(), userManager.getMission()));
			return;
		} catch (RuntimeException e) {
			// Already logged
			System.err.println(e.getMessage());
			return;
		}
		
		/* Report success */
		String message = String.format(MSG_PRODUCTS_INGESTED, MSG_ID_PRODUCTS_INGESTED, ingestedProducts.size(), processingFacility);
		logger.info(message);
		System.out.println(message);
	}
	
	/**
	 * Run the given command
	 * 
	 * @param command the command to execute
	 */
	void executeCommand(ParsedCommand command) {
		if (logger.isTraceEnabled()) logger.trace(">>> executeCommand({})", (null == command ? "null" : command.getName()));
		
		/* Check that user is logged in */
		if (null == userManager.getUser()) {
			System.err.println(String.format(MSG_USER_NOT_LOGGED_IN, MSG_ID_USER_NOT_LOGGED_IN, command.getName()));
		}
		
		/* Check argument */
		if (!CMD_PRODUCT.equals(command.getName()) && !CMD_INGEST.equals(command.getName())) {
			System.err.println(String.format(MSG_INVALID_COMMAND_NAME, MSG_ID_INVALID_COMMAND_NAME, command.getName()));
			return;
		}
		
		/* Make sure a subcommand is given */
		if (CMD_PRODUCT.equals(command.getName()) && (null == command.getSubcommand() || null == command.getSubcommand().getName())) {
			System.err.println(String.format(MSG_SUBCOMMAND_MISSING, MSG_ID_SUBCOMMAND_MISSING, command.getName()));
			return;
		}
		
		/* Execute the (sub)command */
		if (CMD_INGEST.equals(command.getName())) {
			if (command.isHelpRequested()) {
				command.getSyntaxCommand().printHelp(System.out);
			} else {
				ingestProduct(command);
			}
			return;
		}
		
		/* Check for subcommand help request */
		ParsedCommand subcommand = command.getSubcommand();
		if (subcommand.isHelpRequested()) {
			subcommand.getSyntaxCommand().printHelp(System.out);
			return;
		}
		
		/* Execute subcommand */
		switch(subcommand.getName()) {
		case CMD_CREATE:	createProduct(subcommand); break;
		case CMD_SHOW:		showProduct(subcommand); break;
		case CMD_UPDATE:	updateProduct(subcommand); break;
		case CMD_DELETE:	deleteProduct(subcommand); break;
		}
	}
}
