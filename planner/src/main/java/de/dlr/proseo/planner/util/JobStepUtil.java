/**
 * JobStepUtil.java
 * 
 * © 2019 Prophos Informatik GmbH
 */
package de.dlr.proseo.planner.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.ws.rs.ProcessingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import de.dlr.proseo.model.Job.JobState;
import de.dlr.proseo.model.Job;
import de.dlr.proseo.model.JobStep;
import de.dlr.proseo.model.Mission;
import de.dlr.proseo.model.ProcessingFacility;
import de.dlr.proseo.model.ProcessingOrder;
import de.dlr.proseo.model.Product;
import de.dlr.proseo.model.ProductClass;
import de.dlr.proseo.model.ProductQuery;
import de.dlr.proseo.model.JobStep.JobStepState;
import de.dlr.proseo.model.dao.ProductRepository;
import de.dlr.proseo.model.service.ProductQueryService;
import de.dlr.proseo.model.service.RepositoryService;
import de.dlr.proseo.planner.Messages;
import de.dlr.proseo.planner.ProductionPlanner;
import de.dlr.proseo.planner.kubernetes.KubeConfig;
import de.dlr.proseo.planner.kubernetes.KubeJob;

/**
 * Handle job steps 
 * 
 * @author Ernst Melchinger
 *
 */
/**
 * @author melchinger
 *
 */
@Component
@Transactional
public class JobStepUtil {
	
	/**
	 * Logger of this class
	 */
	private static Logger logger = LoggerFactory.getLogger(JobStepUtil.class);

	/** JPA entity manager */
	@PersistenceContext
	private EntityManager em;
	
	@Autowired
	private ProductQueryService productQueryService;
	@Autowired
	private ProductionPlanner productionPlanner;
	
	/** REST template builder */
	@Autowired
	RestTemplateBuilder rtb;
	
	/**
	 * Search for not satisfied product queries referencing product class on processing facility and check which are now satisfied. 
	 * Change state of job step to READY if all queries are now satisfied. 
	 * 
	 * @param processingFacility
	 * @param pc Product class
	 */
	@Transactional
	public void searchForJobStepsToRun(ProcessingFacility processingFacility, ProductClass pc) {
		// Search for all job steps on processingFacility with states INITIAL, WAITING_INPUT
		List<de.dlr.proseo.model.JobStep.JobStepState> jobStepStates = new ArrayList<>();
		jobStepStates.add(de.dlr.proseo.model.JobStep.JobStepState.INITIAL);
		jobStepStates.add(de.dlr.proseo.model.JobStep.JobStepState.WAITING_INPUT);
		List<JobStep> jobSteps = null;
		if (processingFacility == null) {
			jobSteps = new ArrayList<>();
			if (pc != null) {
				List<ProductQuery> productQueries = RepositoryService.getProductQueryRepository()
						.findUnsatisfiedByProductClass(pc.getId());
				for (ProductQuery pq : productQueries) {
					jobSteps.add(pq.getJobStep());
				}
			} else {
				for (ProcessingFacility pf : RepositoryService.getFacilityRepository().findAll()) {
					jobSteps.addAll(RepositoryService.getJobStepRepository().findAllByProcessingFacilityAndJobStepStateIn(pf.getId(), jobStepStates));
				}
			}
		} else {
			if (pc != null) {
				jobSteps = new ArrayList<>();
				List<ProductQuery> productQueries = RepositoryService.getProductQueryRepository()
						.findUnsatisfiedByProductClass(pc.getId());
				for (ProductQuery pq : productQueries) {
					if (pq.getJobStep().getJob().getProcessingFacility().getId() == processingFacility.getId()) {
						jobSteps.add(pq.getJobStep());
					}
				}
			} else {
				jobSteps = RepositoryService.getJobStepRepository().findAllByProcessingFacilityAndJobStepStateIn(processingFacility.getId(), jobStepStates);
			}
		}
		for (JobStep js : jobSteps) {
			checkJobStepQueries(js, false);
		}
	}

	/**
	 * Suspend job step, kill it if force is true otherwise wait until finish.
	 *  
	 * @param js Job step
	 * @param force Force 
	 * @return Result message
	 */
	@Transactional
	public Messages suspend(JobStep js, Boolean force) {
		Messages answer = Messages.FALSE;
		// check current state for possibility to be suspended
		// INITIAL, WAITING_INPUT, READY, RUNNING, COMPLETED, FAILED
		if (js != null) {
			switch (js.getJobStepState()) {
			case INITIAL:
				answer = Messages.JOBSTEP_SUSPENDED;
				break;
			case READY:
			case WAITING_INPUT:
				js.setJobStepState(de.dlr.proseo.model.JobStep.JobStepState.INITIAL);
				js.incrementVersion();
				RepositoryService.getJobStepRepository().save(js);
				answer = Messages.JOBSTEP_SUSPENDED;
				break;
			case RUNNING:
				Boolean deleted = false;
				if (force != null && force) {
					KubeConfig kc = productionPlanner.getKubeConfig(js.getJob().getProcessingFacility().getName());
					if (kc != null) {
						KubeJob kj = kc.getKubeJob(ProductionPlanner.jobNamePrefix + js.getId());
						if (kj != null) {
							deleted = kc.deleteJob(kj.getJobName());
						}
					}
				}
				if (deleted) {
					js.setJobStepState(de.dlr.proseo.model.JobStep.JobStepState.INITIAL);
					js.incrementVersion();
					answer = Messages.JOBSTEP_SUSPENDED;
					RepositoryService.getJobStepRepository().save(js);
				} else {
					answer = Messages.JOBSTEP_ALREADY_RUNNING;
				}
				break;
			case COMPLETED:
				answer = Messages.JOBSTEP_COMPLETED;
				break;
			case FAILED:
				answer = Messages.JOBSTEP_FAILED;
				break;
			default:
				break;
			}
			answer.log(logger, String.valueOf(js.getId()));
		}
		return answer;
	}

	/**
	 * Cancel job step
	 * 
	 * @param js Job step
	 * @return Result message
	 */
	@Transactional
	public Messages cancel(JobStep js) {
		Messages answer = Messages.FALSE;
		// check current state for possibility to be suspended
		if (js != null) {
			switch (js.getJobStepState()) {
			case INITIAL:
			case READY:
			case WAITING_INPUT:
				js.setJobStepState(de.dlr.proseo.model.JobStep.JobStepState.FAILED);
				js.incrementVersion();
				RepositoryService.getJobStepRepository().save(js);
				answer = Messages.JOBSTEP_CANCELED;
				break;
			case RUNNING:
				answer = Messages.JOBSTEP_ALREADY_RUNNING;
				break;
			case COMPLETED:
				answer = Messages.JOBSTEP_ALREADY_COMPLETED;
				break;
			case FAILED:
				answer = Messages.JOBSTEP_ALREADY_FAILED;
				break;
			default:
				break;
			}
			answer.log(logger, String.valueOf(js.getId()));
		}
		return answer;
	}

	/**
	 * Retry job step
	 * 
	 * @param js Job step
	 * @return Result message
	 */
	@Transactional
	public Messages retry(JobStep js) {
		Messages answer = Messages.FALSE;
		// check current state for possibility to be suspended
		if (js != null) {
			switch (js.getJobStepState()) {
			case INITIAL:
			case READY:
			case RUNNING:
			case COMPLETED:
				answer = Messages.JOBSTEP_COULD_NOT_RETRY;
				break;
			case WAITING_INPUT:
			case FAILED:
				Product jsp = js.getOutputProduct();
				if (jsp != null) {
					// collect output products
					List<Product> jspList = new ArrayList<Product>();
					collectProducts(jsp, jspList);
					if (checkProducts(jspList)) {
						// Product was created, due to some communication problems the wrapper process finished with errors. 
						// 	Dicard this problem and set job step to completed
						js.setJobStepState(de.dlr.proseo.model.JobStep.JobStepState.COMPLETED);
						js.incrementVersion();
						RepositoryService.getJobStepRepository().save(js);
						answer = Messages.JOBSTEP_RETRIED_COMPLETED;
						break;
					}		
				}
				js.setJobStepState(de.dlr.proseo.model.JobStep.JobStepState.INITIAL);
				js.incrementVersion();
				RepositoryService.getJobStepRepository().save(js);
				answer = Messages.JOBSTEP_RETRIED;
				break;
			default:
				break;
			}
			answer.log(logger, String.valueOf(js.getId()));
		}
		return answer;
	}

	/**
	 * Check job step whether job step has been finished
	 * 
	 * @param js Job step
	 * @return true if finished
	 */
	@Transactional
	public Boolean checkFinish(JobStep js) {
		Boolean answer = false;
		// check current state for possibility to be suspended
		if (js != null) {
			switch (js.getJobStepState()) {
			case INITIAL:
			case READY:
			case WAITING_INPUT:
				break;
			case RUNNING:
			case COMPLETED:
				em.merge(js);
				UtilService.getJobUtil().checkFinish(js.getJob());
				answer = true;
				break;
			case FAILED:
				em.merge(js);
				UtilService.getJobUtil().setHasFailedJobSteps(js.getJob(), true);
				UtilService.getJobUtil().checkFinish(js.getJob());
				answer = true;
				break;
			default:
				break;
			}
		}
		return answer;
	}

	/**
	 * Delete job step
	 * 
	 * @param js Job step
	 * @return true  if deleted
	 */
	@Transactional
	public Boolean delete(JobStep js) {
		Boolean answer = false;
		// check current state for possibility to be suspended
		if (js != null) {
			switch (js.getJobStepState()) {
			case INITIAL:
			case READY:
			case WAITING_INPUT:
				if (js.getOutputProduct() != null) {
					deleteProduct(js.getOutputProduct());	
					js.setOutputProduct(null);
					deleteJOF(js);
					js.setJobOrderFilename(null);
				};
				// fall through intended
			case COMPLETED:
			case FAILED:
				if (js.getOutputProduct() != null) {
					js.getOutputProduct().setJobStep(null);
				}
				for (ProductQuery pq : js.getInputProductQueries()) {
					for (Product p : pq.getSatisfyingProducts()) {						
						p.getSatisfiedProductQueries().remove(pq);
					}
					pq.getSatisfyingProducts().clear();
					RepositoryService.getProductQueryRepository().delete(pq);
				}
				js.getInputProductQueries().clear();
				Messages.JOBSTEP_DELETED.log(logger, String.valueOf(js.getId()));
				answer = true;
				break;
			case RUNNING:
				Messages.JOBSTEP_ALREADY_RUNNING.log(logger, String.valueOf(js.getId()));
				break;
			default:
				break;
			}
		}
		return answer;
	}


	/**
	 * Delete also not finished job step
	 * 
	 * @param js Job step
	 * @return true if deleted
	 */
	@Transactional
	public Boolean deleteForced(JobStep js) {
		Boolean answer = false;
		// check current state for possibility to be suspended
		if (js != null) {
			switch (js.getJobStepState()) {
			case INITIAL:
			case READY:
			case WAITING_INPUT:
				if (js.getOutputProduct() != null) {
					deleteProduct(js.getOutputProduct());	
					js.setOutputProduct(null);
					deleteJOF(js);
					js.setJobOrderFilename(null);
				};
			case RUNNING:
			case COMPLETED:
			case FAILED:
				if (js.getOutputProduct() != null) {
					js.getOutputProduct().setJobStep(null);
				}
				for (ProductQuery pq : js.getInputProductQueries()) {
					for (Product p : pq.getSatisfyingProducts()) {
						p.getSatisfiedProductQueries().clear();
					}
					pq.getSatisfyingProducts().clear();
					RepositoryService.getProductQueryRepository().delete(pq);
				}
				js.getInputProductQueries().clear();
				// RepositoryService.getJobStepRepository().delete(js);
				Messages.JOBSTEP_DELETED.log(logger, String.valueOf(js.getId()));
				answer = true;
				break;
			default:
				break;
			}
		}
		return answer;
	}


	/**
	 * Resume job step
	 * 
	 * @param js Job step
	 * @return Result message
	 */
	@Transactional
	public Messages resume(JobStep js, Boolean force) {
		Messages answer = Messages.FALSE;
		// check current state for possibility to be suspended
		if (js != null) {
			switch (js.getJobStepState()) {
			case INITIAL:
			case WAITING_INPUT:
				checkJobStepQueries(js, force);
				if (js.getJobStepState() == JobStepState.WAITING_INPUT) {
					answer = Messages.JOBSTEP_WAITING;
				} else {
					answer = Messages.JOBSTEP_READY;
				}				
				break;
			case READY:
				answer = Messages.JOBSTEP_READY;
				break;
			case RUNNING:
				answer = Messages.JOBSTEP_ALREADY_RUNNING;
				break;
			case COMPLETED:
				answer = Messages.JOBSTEP_ALREADY_COMPLETED;
				break;
			case FAILED:
				answer = Messages.JOBSTEP_ALREADY_FAILED;
				break;
			default:
				break;
			}
			answer.log(logger, String.valueOf(js.getId()));
		}
		return answer;
	}


	/**
	 * Start job step on Kubernetes cluster
	 * 
	 * @param js Job step
	 * @return Result message
	 */
	@Transactional
	public Boolean startJobStep(JobStep js) {
		Boolean answer = false;
		// check current state for possibility to be suspended
		if (js != null) {
			switch (js.getJobStepState()) {
			case INITIAL:
			case WAITING_INPUT:
				break;
			case READY:
				UtilService.getJobUtil().startJob(js.getJob());
				js.setJobStepState(JobStepState.RUNNING);
				js.incrementVersion();
				RepositoryService.getJobStepRepository().save(js);
				Messages.JOBSTEP_STARTED.log(logger, String.valueOf(js.getId()));
				answer = true;
				break;
			case RUNNING:
				answer = true;
				break;
			case COMPLETED:
			case FAILED:
			default:
				break;
			}
		}
		return answer;
	}

	/**
	 * Check the queries of a job step which job is released or started.
	 * Check all if force is true
	 *  
	 * @param js Job step
	 * @param force 
	 */
	@Transactional
	public void checkJobStepQueries(JobStep js, Boolean force) {
		Boolean hasUnsatisfiedInputQueries = false;
		if (js.getJobStepState() == JobStepState.INITIAL || js.getJobStepState() == JobStepState.WAITING_INPUT) {
			if (   js.getJob() != null 
					&& (force || js.getJob().getJobState() == JobState.RELEASED || js.getJob().getJobState() == JobState.STARTED)) {
				logger.trace("Looking for product queries of job step: " + js.getId());
				for (ProductQuery pq : js.getInputProductQueries()) {
					if (!pq.isSatisfied()) {
						if (productQueryService.executeQuery(pq, false, false)) {
							js.getOutputProduct().getSatisfiedProductQueries().add(pq);
							RepositoryService.getProductQueryRepository().save(pq);
							RepositoryService.getProductRepository().save(js.getOutputProduct());
						} else {
							hasUnsatisfiedInputQueries = true;
						}
					}
				}
				if (hasUnsatisfiedInputQueries) {
					if (js.getJobStepState() != de.dlr.proseo.model.JobStep.JobStepState.WAITING_INPUT) {
						js.setJobStepState(de.dlr.proseo.model.JobStep.JobStepState.WAITING_INPUT);
						js.incrementVersion();
						RepositoryService.getJobStepRepository().save(js);
						em.merge(js);
					}				
				} else {
					js.setJobStepState(de.dlr.proseo.model.JobStep.JobStepState.READY);
					js.incrementVersion();
					RepositoryService.getJobStepRepository().save(js);
					em.merge(js);
				}
			}
		}
	}

	/**
	 * Delete product tree. Used during delete of job step.
	 * @param p
	 */
	@Transactional
	private void deleteProduct(Product p) {
		if (p!= null) {
			if (p.getEnclosingProduct() != null) {
				p.getEnclosingProduct().getComponentProducts().remove(p);
			}
			List<Product> cps = new ArrayList<Product>();
			cps.addAll(p.getComponentProducts());
			for (Product cp : cps) {
				deleteProduct(cp);
			}
			RepositoryService.getProductRepository().delete(p);
		}
	}

	/**
	 * Check all unsatisfied queries of all job steps on all facilities whether they can be started.
	 */
	@Transactional
    public void checkForJobStepsToRun() {
    	if (productionPlanner != null) {
    		Collection<KubeConfig> kcs = productionPlanner.getKubeConfigs();
    		if (kcs != null) {
    			for (KubeConfig kc : kcs) {
    				kc.sync();
    				checkForJobStepsToRun(kc, null, false);
    			}
    		}
    	}
    }

	/**
	 * If onlyRun is false, check unsatisfied queries of product class on processing facility (defined in Kube config).
	 * Start ready job steps on facility.
	 * @param kc KubeConfig
	 * @param pc ProductClass
	 * @param onlyRun
	 */
	@Transactional
    public void checkForJobStepsToRun(KubeConfig kc, ProductClass pc, Boolean onlyRun) {
		if (productionPlanner != null) {
			if (kc != null) {
				List<JobStepState> states = new ArrayList<JobStepState>();
				states.add(JobStepState.READY);
				Optional<ProcessingFacility> pfo = RepositoryService.getFacilityRepository().findById(kc.getLongId());
				if (pfo.isPresent()) {
					if (!onlyRun) {
						this.searchForJobStepsToRun(pfo.get(), pc);
					}
					List<JobStep> jobSteps = RepositoryService.getJobStepRepository().findAllByProcessingFacilityAndJobStepStateIn(kc.getLongId(), states);
					for (JobStep js : jobSteps) {
						if (js.getJob().getJobState() == JobState.RELEASED || js.getJob().getJobState() == JobState.STARTED) {
							if (kc.couldJobRun()) {
								kc.createJob(String.valueOf(js.getId()), null, null);
							}
						}
					}
				}
			} else {
				checkForJobStepsToRun();
			}
		}
    }

	/**
	 * Check unsatisfied queries of job step on processing facility (defined in Kube config).
	 * Start ready job steps on facility.
	 * @param kc KubeConfig
	 * @param js JobStep
	 */
	@Transactional
    public void checkJobStepToRun(KubeConfig kc, JobStep js) {
		if (productionPlanner != null) {
			if (kc != null && js != null) {
				Optional<ProcessingFacility> pfo = RepositoryService.getFacilityRepository().findById(kc.getLongId());
				if (pfo.isPresent()) {
					checkJobStepQueries(js, false);
					if (js.getJob().getJobState() == JobState.RELEASED || js.getJob().getJobState() == JobState.STARTED) {
						if (kc.couldJobRun()) {
							kc.createJob(String.valueOf(js.getId()), null, null);
						}
					}
				}
			}
		}
	}

	/**
	 * Check unsatisfied queries of job steps in job on processing facility (defined in Kube config).
	 * Start ready job steps on facility.
	 * @param kc KubeConfig
	 * @param job Job
	 */
	@Transactional
    public void checkJobToRun(KubeConfig kc, Job job) {
		if (productionPlanner != null) {
			if (kc != null && job != null) {
				Optional<ProcessingFacility> pfo = RepositoryService.getFacilityRepository().findById(kc.getLongId());
				if (pfo.isPresent()) {
					for (JobStep js : job.getJobSteps()) {
						checkJobStepQueries(js, false);
						if (js.getJobStepState() == JobStepState.READY) {	
							if (js.getJob().getJobState() == JobState.RELEASED || js.getJob().getJobState() == JobState.STARTED) {
								if (kc.couldJobRun()) {
									kc.createJob(String.valueOf(js.getId()), null, null);
								}
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Check unsatisfied queries of job steps in processing order on processing facility (defined in Kube config).
	 * Start ready job steps on facility.
	 * @param kc KubeConfig
	 * @param order ProcessingOrder
	 */
	@Transactional
    public void checkOrderToRun(KubeConfig kc, ProcessingOrder order) {
		if (productionPlanner != null) {
			if (kc != null && order != null) {
				Optional<ProcessingFacility> pfo = RepositoryService.getFacilityRepository().findById(kc.getLongId());
				if (pfo.isPresent()) {
					List<Job> jobList = new ArrayList<Job>();
					jobList.addAll(order.getJobs());
					for (Job job : jobList) {
						List<JobStep> jobStepList = new ArrayList<JobStep>();
						jobStepList.addAll(job.getJobSteps());
						for (JobStep js : jobStepList) {
							checkJobStepQueries(js, false);
							if (js.getJobStepState() == JobStepState.READY) {	
								if (js.getJob().getJobState() == JobState.RELEASED || js.getJob().getJobState() == JobState.STARTED) {
									if (kc.couldJobRun()) {
										kc.createJob(String.valueOf(js.getId()), null, null);
									}
								}
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Collect products of a ptoduct tree into list
	 * 
	 * @param p Root product
	 * @param list Product list
	 */
	@Transactional
    private void collectProducts(Product p, List<Product> list) {
		if (p != null) {
			list.add(p);
			for (Product cp : p.getComponentProducts()) {
				collectProducts(p, list);
			}
		}
	}
	
	/**
	 * Check whether the products of list exists and are already generated
	 * @param list Product list
	 * @return true if all products are generated
	 */
	@Transactional
    private Boolean checkProducts(List<Product> list) {
		Boolean answer = true;
		for (Product p : list) {
			List<Product> resultList = RepositoryService.getProductRepository()
				.findByProductClassAndConfiguredProcessorAndSensingStartTimeAndSensingStopTime(
					p.getProductClass().getId(),
					p.getConfiguredProcessor().getId(),
					p.getSensingStartTime(),
					p.getSensingStopTime());
			if (resultList.isEmpty()) {
				answer = false;
				break;
			} else {
				for (Product rp : resultList) {
					if (rp.getGenerationTime() == null) {
						answer = false;
					}
				}
			}
		}
		return answer;
	}
	
	private Boolean deleteJOF(JobStep js) {
		if (js != null && js.getJobOrderFilename() != null) {
			ProcessingFacility facility = js.getJob().getProcessingFacility();
			String storageManagerUrl = facility.getStorageManagerUrl()
					+ String.format("/products?pathInfo=%s", js.getJobOrderFilename()); 

			RestTemplate restTemplate = rtb
					.basicAuthentication(facility.getStorageManagerUser(), facility.getStorageManagerPassword())
					.build();
			try {
				restTemplate.delete(storageManagerUrl);
				Messages.JOF_DELETED.log(logger, js.getJobOrderFilename());
				return true;
			} catch (RestClientException e) {
				Messages.JOF_DELETING_ERROR.log(logger, js.getJobOrderFilename(), facility.getName(), e.getMessage());
				return false;
			} 
		} else {
			return false;
		}
	}
}
