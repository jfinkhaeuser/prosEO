/**
 * ProductQueryServiceTest.java
 * 
 * (C) 2019 Dr. Bassler & Co. Managementberatung GmbH
 */
package de.dlr.proseo.model.service;

import static org.junit.Assert.*;

import java.text.ParseException;
import java.time.Instant;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.annotation.Transactional;

import de.dlr.proseo.model.Job;
import de.dlr.proseo.model.JobStep;
import de.dlr.proseo.model.Mission;
import de.dlr.proseo.model.Orbit;
import de.dlr.proseo.model.Parameter;
import de.dlr.proseo.model.Product;
import de.dlr.proseo.model.ProductClass;
import de.dlr.proseo.model.ProductQuery;
import de.dlr.proseo.model.SimpleSelectionRule;
import de.dlr.proseo.model.util.SelectionRule;
import de.dlr.proseo.model.Parameter.ParameterType;

/**
 * Test class for ProductQueryService
 * 
 * @author Dr. Thomas Bassler
 */
@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(classes = RepositoryApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@DirtiesContext
@Transactional
@AutoConfigureTestEntityManager
public class ProductQueryServiceTest {

	/* Various static test data */
	private static final String TEST_CODE = "S5P";
	private static final String TEST_TARGET_PRODUCT_TYPE = "FRESCO";
	private static final String TEST_TARGET_MISSION_TYPE = "L2__FRESCO_";
	private static final String TEST_SOURCE_PRODUCT_TYPE = "L1B";
	private static final String TEST_SOURCE_MISSION_TYPE = "L1B________";
	private static final String TEST_MODE = "OFFL";
	private static final String TEST_SELECTION_RULE = "FOR L1B/revision:01 SELECT ValIntersect(0, 0)";
	private static final String TEST_SELECTION_RULE_MINCOVER = "FOR L1B SELECT ValIntersect(0, 0) MINCOVER(70)";
	private static final Instant TEST_START_TIME_EARLY = Instant.parse("2019-08-29T23:00:00Z");
	private static final Instant TEST_STOP_TIME_EARLY = Instant.parse("2019-08-30T01:00:00Z");
	private static final Instant TEST_START_TIME_LATE = Instant.parse("2019-08-30T01:00:00Z");
	private static final Instant TEST_STOP_TIME_LATE = Instant.parse("2019-08-30T03:00:00Z");

	/* Test products */
	private static String[][] testProductData = {
		// id, version, mission code, product class, mode, sensing start, sensing stop, generation, revision (parameter)
		{ "0", "1", TEST_CODE, TEST_SOURCE_PRODUCT_TYPE, TEST_MODE, "2019-08-29T22:49:21.074395", "2019-08-30T00:19:33.946628", "2019-10-05T10:12:39.000000", "01" },
		{ "0", "1", TEST_CODE, TEST_SOURCE_PRODUCT_TYPE, TEST_MODE, "2019-08-30T00:19:33.946628", "2019-08-30T01:49:46.482753", "2019-10-05T10:13:22.000000", "01" },
		{ "0", "1", "TDM", "DEM", null, "2019-08-30T00:19:33.946628", "2019-08-30T01:49:46.482753", "2019-10-05T10:13:22.000000", "02" }
	};

	@Autowired
	ProductQueryService queryService;
	
	/** A logger for this class */
	private static Logger logger = LoggerFactory.getLogger(ProductQueryServiceTest.class);
	
	/**
	 * @throws java.lang.Exception
	 */
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}

	/**
	 * @throws java.lang.Exception
	 */
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}

	/**
	 * @throws java.lang.Exception
	 */
	@Before
	public void setUp() throws Exception {
	}

	/**
	 * @throws java.lang.Exception
	 */
	@After
	public void tearDown() throws Exception {
	}

	/**
	 * Create a product from a data array
	 * 
	 * @param testData an array of Strings representing the product to create
	 * @return a Product with its attributes set to the input data
	 */
	private Product createProduct(String[] testData) {
		Product testProduct = new Product();
		
		testProduct.setProductClass(
				RepositoryService.getProductClassRepository().findByMissionCodeAndProductType(testData[2], testData[3]));

		logger.info("... creating product with product type {}", (null == testProduct.getProductClass() ? null : testProduct.getProductClass().getProductType()));
		testProduct.setMode(testData[4]);
		testProduct.setSensingStartTime(Instant.from(Orbit.orbitTimeFormatter.parse(testData[5])));
		testProduct.setSensingStopTime(Instant.from(Orbit.orbitTimeFormatter.parse(testData[6])));
		testProduct.setGenerationTime(Instant.from(Orbit.orbitTimeFormatter.parse(testData[7])));
		testProduct.getParameters().put(
				"revision", new Parameter().init(ParameterType.INTEGER, Integer.parseInt(testData[8])));
		testProduct = RepositoryService.getProductRepository().save(testProduct);
		
		logger.info("Created test product {}", testProduct.getId());
		return testProduct;
	}
	
	/**
	 * Test method for {@link de.dlr.proseo.model.service.ProductQueryService#executeQuery(de.dlr.proseo.model.ProductQuery, boolean)}.
	 */
	@Test
	public final void testExecuteQuery() {
		
		// Create test data: mission, product class, product, selection rules (with and without MINCOVER), order, job, job step
		Mission mission = RepositoryService.getMissionRepository().findByCode(TEST_CODE);
		if (null == mission) {
			mission = new Mission();
			mission.setCode(TEST_CODE);
			mission.getProcessingModes().add(TEST_MODE);
			mission = RepositoryService.getMissionRepository().save(mission);
		}
		logger.info("Using mission " + mission.getCode() + " with id " + mission.getId());
		
		ProductClass targetProdClass = RepositoryService.getProductClassRepository().findByMissionCodeAndProductType(TEST_CODE, TEST_TARGET_PRODUCT_TYPE);
		if (null == targetProdClass) {
			targetProdClass = new ProductClass();
			targetProdClass.setMission(mission);
			targetProdClass.setProductType(TEST_TARGET_PRODUCT_TYPE);
			targetProdClass.setMissionType(TEST_TARGET_MISSION_TYPE);
			targetProdClass = RepositoryService.getProductClassRepository().save(targetProdClass);
			mission.getProductClasses().add(targetProdClass);
			//mission = RepositoryService.getMissionRepository().save(mission);
		}
		logger.info("Using target product class " + targetProdClass.getProductType() + " with id " + targetProdClass.getId());
		
		ProductClass sourceProdClass = RepositoryService.getProductClassRepository().findByMissionCodeAndProductType(TEST_CODE, TEST_SOURCE_PRODUCT_TYPE);
		if (null == sourceProdClass) {
			sourceProdClass = new ProductClass();
			sourceProdClass.setMission(mission);
			sourceProdClass.setProductType(TEST_SOURCE_PRODUCT_TYPE);
			sourceProdClass.setMissionType(TEST_SOURCE_MISSION_TYPE);
			sourceProdClass = RepositoryService.getProductClassRepository().save(sourceProdClass);
			mission.getProductClasses().add(sourceProdClass);
			//mission = RepositoryService.getMissionRepository().save(mission);
		}
		logger.info("Using source product class " + sourceProdClass.getProductType() + " with id " + sourceProdClass.getId());
		
		Product product0 = createProduct(testProductData[0]);
		Product product1 = createProduct(testProductData[1]);
		
		Job jobEarly = new Job();
		jobEarly.setStartTime(TEST_START_TIME_EARLY);
		jobEarly.setStopTime(TEST_STOP_TIME_EARLY);
		JobStep jobStepEarly = new JobStep();
		jobStepEarly.setJob(jobEarly);
		jobStepEarly.setProcessingMode(TEST_MODE);
		
		Job jobLate = new Job();
		jobLate.setStartTime(TEST_START_TIME_LATE);
		jobLate.setStopTime(TEST_STOP_TIME_LATE);
		JobStep jobStepLate = new JobStep();
		jobStepLate.setJob(jobLate);
		jobStepLate.setProcessingMode(TEST_MODE);
		
		// Test first product query without MINCOVER --> satisfied
		SelectionRule selectionRule = null;
		try {
			selectionRule = SelectionRule.parseSelectionRule(targetProdClass, TEST_SELECTION_RULE);
		} catch (IllegalArgumentException | ParseException e) {
			e.printStackTrace();
			fail("Unexpected exception when parsing selection rule " + TEST_SELECTION_RULE + " (cause: " + e.getMessage() + ")");
		}
		assertTrue("List of selection rules is empty", !selectionRule.getSimpleRules().isEmpty());

		SimpleSelectionRule simpleSelectionRule = selectionRule.getSimpleRules().iterator().next();
		ProductQuery query = ProductQuery.fromSimpleSelectionRule(simpleSelectionRule, jobStepLate);
		logger.trace("Starting test for product query 1 based on " + simpleSelectionRule);
		assertTrue("Product query 1 fails unexpectedly for JPQL", queryService.executeJpqlQuery(query));
		assertTrue("Product query 1 fails unexpectedly for SQL", queryService.executeSqlQuery(query));
		
		// Test second product query with MINCOVER --> satisfied for early interval, not satisfied for late interval
		try {
			selectionRule = SelectionRule.parseSelectionRule(targetProdClass, TEST_SELECTION_RULE_MINCOVER);
		} catch (IllegalArgumentException | ParseException e) {
			e.printStackTrace();
			fail("Unexpected exception when parsing selection rule " + TEST_SELECTION_RULE + " (cause: " + e.getMessage() + ")");
		}
		assertTrue("List of selection rules is empty", !selectionRule.getSimpleRules().isEmpty());

		simpleSelectionRule = selectionRule.getSimpleRules().iterator().next();
		query = ProductQuery.fromSimpleSelectionRule(simpleSelectionRule, jobStepEarly);
		logger.trace("Starting test for product query 2 and early interval based on " + simpleSelectionRule);
		assertTrue("Product query 2 fails unexpectedly for early interval and JPQL", queryService.executeJpqlQuery(query));
		assertTrue("Product query 2 fails unexpectedly for early interval and SQL", queryService.executeSqlQuery(query));

		query = ProductQuery.fromSimpleSelectionRule(simpleSelectionRule, jobStepLate);
		logger.trace("Starting test for product query 2 and late interval based on " + simpleSelectionRule);
		assertTrue("Product query 2 succeeds unexpectedly for early interval and JPQL", !queryService.executeJpqlQuery(query));
		assertTrue("Product query 2 succeeds unexpectedly for early interval and SQL", !queryService.executeSqlQuery(query));
		
		logger.info("OK: Test for executeQuery completed");
	}

}