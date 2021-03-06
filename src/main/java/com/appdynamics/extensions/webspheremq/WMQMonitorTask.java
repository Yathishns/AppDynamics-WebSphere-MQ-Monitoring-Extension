package com.appdynamics.extensions.webspheremq;

import com.appdynamics.extensions.conf.MonitorConfiguration;
import com.appdynamics.extensions.webspheremq.common.Constants;
import com.appdynamics.extensions.webspheremq.config.MqMetric;
import com.appdynamics.extensions.webspheremq.config.QueueManager;
import com.appdynamics.extensions.webspheremq.config.WMQMetricOverride;
import com.appdynamics.extensions.webspheremq.metricscollector.*;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import com.ibm.mq.MQException;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.pcf.PCFMessageAgent;
import com.singularity.ee.agent.systemagent.api.exception.TaskExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Hashtable;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates all metrics collection for all artifacts related to a queue manager.
 * 
 * @author rajeevsingh,kunalgup
 * @version 2.0
 *
 */
public class WMQMonitorTask implements Runnable {

	public static final Logger logger = LoggerFactory.getLogger(WMQMonitorTask.class);
	private QueueManager queueManager;
	private String metricPrefix;
	private MonitorConfiguration monitorConfig;
	private MqMetric[] mqMetrics;

	public WMQMonitorTask(QueueManager queueManager, String metricPrefix, MqMetric[] mqMetrics, MonitorConfiguration monitorConfig) {
		this.queueManager = queueManager;
		this.metricPrefix = metricPrefix;
		this.monitorConfig = monitorConfig;
		this.mqMetrics = mqMetrics;
	}

	public void run() {
		long startTime = System.currentTimeMillis();
		try {
			logger.debug("WebSphereMQ monitor thread for queueManager " + queueManager.getName() + " started.");
			extractAndReportMetrics();
		} catch (Exception e) {
			logger.error("Error in run of " + Thread.currentThread().getName(), e);
		} finally {
			long endTime = System.currentTimeMillis() - startTime;
			logger.debug("WebSphereMQ monitor thread for queueManager " + queueManager.getName() + " ended. Time taken = " + endTime);
		}
	}

	private void extractAndReportMetrics() throws MQException, TaskExecutionException {
		Map<String, Map<String, WMQMetricOverride>> metricsMap = getMetricsToReport();
		WMQContext auth = new WMQContext(queueManager);
		Hashtable env = auth.getMQEnvironment();
		extractAndReportInternal(env, metricsMap);
	}

	/**
	 * Returns master data structure,This map will contain only those metrics which are to be reported to controller.<br>
	 * It contains metric type as key and a map of metric and WMQMetricOverride as value,<br>
	 * entryset of internal map implicitly represents metrics to be reported.
	 */
	private Map<String, Map<String, WMQMetricOverride>> getMetricsToReport() {
		Map<String, Map<String, WMQMetricOverride>> metricsMap = Maps.newHashMap();
		for (MqMetric mqMetric : mqMetrics) {
			String metricType = mqMetric.getMetricsType();
			List includeMetrics = (List) mqMetric.getMetrics().get("include");
			Map<String, WMQMetricOverride> metricToReport = Maps.newHashMap();
			if (includeMetrics != null) {
				metricToReport = gatherMetricNamesByApplyingIncludeFilter(includeMetrics);
			}
			metricsMap.put(metricType, metricToReport);
		}
		return metricsMap;
	}

	private Map<String, WMQMetricOverride> gatherMetricNamesByApplyingIncludeFilter(List includeMetrics) {
		Map<String, WMQMetricOverride> overrideMap = Maps.newHashMap();
		for (Object inc : includeMetrics) {
			Map metric = (Map) inc;
			// Get the First Entry which is the metric
			Map.Entry firstEntry = (Map.Entry) metric.entrySet().iterator().next();
			String metricName = firstEntry.getKey().toString();
			WMQMetricOverride override = new WMQMetricOverride();
			override.setAlias(firstEntry.getValue().toString());
			override.setMultiplier(metric.get("multiplier") != null ? Double.parseDouble(metric.get("multiplier").toString()) : Constants.DEFAULT_MULTIPLIER);
			String metricType = metric.get("metricType") != null ? metric.get("metricType").toString() : Constants.DEFAULT_METRIC_TYPE;
			String[] metricTypes = metricType.split(" ");
			override.setAggregator(metricTypes[0]);
			override.setTimeRollup(metricTypes[1]);
			override.setClusterRollup(metricTypes[2]);
			override.setMetricKey(metricName);
			override.setIbmCommand((String) metric.get("ibmCommand"));
			override.setIbmConstant((String) metric.get("ibmConstant"));
			if (override.getConstantValue() == -1) {
				// Only add the metric which is valid, if constant value
				// resolutes to -1 then it is invalid.
				logger.warn("{} is not a valid valid metric, this metric will not be processed", override.getIbmConstant());
			} else {
				overrideMap.put(metricName, override);
			}
			logger.debug("Override Definition: " + override.toString());
		}
		return overrideMap;
	}

	private void extractAndReportInternal(Hashtable env, Map<String, Map<String, WMQMetricOverride>> metricsMap) throws TaskExecutionException {

		MQQueueManager ibmQueueManager = null;
		PCFMessageAgent agent = null;
		try {
			if (env != null) {
				ibmQueueManager = new MQQueueManager(queueManager.getName(), env);
			} else {
				ibmQueueManager = new MQQueueManager(queueManager.getName());
			}
			logger.debug("Connection initiated for queue manager {} in thread {}", queueManager.getName(), Thread.currentThread().getName());

			agent = new PCFMessageAgent(ibmQueueManager);
			if(!Strings.isNullOrEmpty(queueManager.getReplyQueuePrefix())){
				agent.setReplyQueuePrefix(queueManager.getReplyQueuePrefix());
			}
			if(!Strings.isNullOrEmpty(queueManager.getModelQueueName())){
				agent.setModelQueueName(queueManager.getModelQueueName());
			}
			logger.debug("Intialized PCFMessageAgent for queue manager {} in thread {}", agent.getQManagerName(), Thread.currentThread().getName());

			Map<String, WMQMetricOverride> qMgrMetricsToReport = metricsMap.get(Constants.METRIC_TYPE_QUEUE_MANAGER);
			if (qMgrMetricsToReport != null) {
				MetricsCollector qMgrMetricsCollector = new QueueManagerMetricsCollector(qMgrMetricsToReport, this.monitorConfig, agent, queueManager, this.metricPrefix);
				qMgrMetricsCollector.process();
			} else {
				logger.warn("No queue manager metrics to report");
			}

			Map<String, WMQMetricOverride> channelMetricsToReport = metricsMap.get(Constants.METRIC_TYPE_CHANNEL);
			if (channelMetricsToReport != null) {
				MetricsCollector channelMetricsCollector = new ChannelMetricsCollector(channelMetricsToReport, this.monitorConfig, agent, queueManager, this.metricPrefix);
				channelMetricsCollector.process();
			} else {
				logger.warn("No channel metrics to report");
			}

			Map<String, WMQMetricOverride> queueMetricsToReport = metricsMap.get(Constants.METRIC_TYPE_QUEUE);
			if (queueMetricsToReport != null) {
				MetricsCollector queueMetricsCollector = new QueueMetricsCollector(queueMetricsToReport, this.monitorConfig, agent, queueManager, this.metricPrefix);
				queueMetricsCollector.process();
			} else {
				logger.warn("No queue metrics to report");
			}

			Map<String, WMQMetricOverride> listenerMetricsToReport = metricsMap.get(Constants.METRIC_TYPE_LISTENER);
			if (queueMetricsToReport != null) {
				MetricsCollector listenerMetricsCollector = new ListenerMetricsCollector(listenerMetricsToReport, this.monitorConfig, agent, queueManager, this.metricPrefix);
				listenerMetricsCollector.process();
			} else {
				logger.warn("No listener metrics to report");
			}
		} catch (MQException mqe) {
			logger.error(mqe.getMessage(),mqe);
			throw new TaskExecutionException(mqe.getMessage());
		} catch(Exception e){
			logger.error("Some unknown exception occured",e);
			throw new TaskExecutionException(e);
		}
		finally {
			cleanUp(ibmQueueManager, agent);
		}
	}



	/**
	 * Destroy the agent and disconnect from queue manager
	 * 
	 */
	private void cleanUp(MQQueueManager ibmQueueManager, PCFMessageAgent agent) {
		// Disconnect the agent.

		if (agent != null) {
			try {
				String qMgrName = agent.getQManagerName();
				agent.disconnect();
				logger.debug("PCFMessageAgent disconnected for queue manager {} in thread {}", qMgrName, Thread.currentThread().getName());
			} catch (Exception e) {
				logger.error("Error occoured  while disconnting PCFMessageAgent for queue manager {} in thread {}", queueManager.getName(), Thread.currentThread().getName(), e);
			}
		}

		// Disconnect queue manager
		
		if (ibmQueueManager != null) {
			try {
				ibmQueueManager.disconnect();
				//logger.debug("Connection diconnected for queue manager {} in thread {}", ibmQueueManager.getName(), Thread.currentThread().getName());
			} catch (Exception e) {
				logger.error("Error occoured while disconnting queue manager {} in thread {}", queueManager.getName(), Thread.currentThread().getName(), e);
			}
		}
	}

}
