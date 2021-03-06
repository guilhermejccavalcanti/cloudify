/*******************************************************************************
 * Copyright (c) 2011 GigaSpaces Technologies Ltd. All rights reserved
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *******************************************************************************/
package org.cloudifysource.esc.driver.provisioning;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.jini.core.discovery.LookupLocator;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.cloudifysource.domain.ServiceNetwork;
import org.cloudifysource.domain.cloud.Cloud;
import org.cloudifysource.domain.cloud.FileTransferModes;
import org.cloudifysource.domain.cloud.compute.ComputeTemplate;
import org.cloudifysource.domain.cloud.RemoteExecutionModes;
import org.cloudifysource.domain.cloud.ScriptLanguages;
import org.cloudifysource.dsl.internal.CloudifyConstants;
import org.cloudifysource.dsl.internal.DSLException;
import org.cloudifysource.dsl.internal.DSLReader;
import org.cloudifysource.dsl.internal.DSLUtils;
import org.cloudifysource.dsl.internal.packaging.ZipUtils;
import org.cloudifysource.dsl.internal.ServiceReader;
import org.cloudifysource.dsl.utils.IPUtils;
import org.cloudifysource.dsl.utils.ServiceUtils;
import org.cloudifysource.esc.driver.provisioning.context.DefaultProvisioningDriverClassContext;
import org.cloudifysource.esc.driver.provisioning.context.ProvisioningDriverClassContext;
import org.cloudifysource.esc.driver.provisioning.events.MachineStartRequestedCloudifyEvent;
import org.cloudifysource.esc.driver.provisioning.events.MachineStartedCloudifyEvent;
import org.cloudifysource.esc.driver.provisioning.network.BaseNetworkDriver;
import org.cloudifysource.esc.driver.provisioning.network.NetworkDriverConfiguration;
import org.cloudifysource.esc.driver.provisioning.network.RemoteNetworkProvisioningDriverAdapter;
import org.cloudifysource.esc.driver.provisioning.storage.BaseStorageDriver;
import org.cloudifysource.esc.driver.provisioning.storage.RemoteStorageProvisioningDriverAdapter;
import org.cloudifysource.esc.driver.provisioning.storage.StorageProvisioningDriver;
import org.cloudifysource.esc.driver.provisioning.storage.StorageProvisioningException;
import org.cloudifysource.esc.installer.AgentlessInstaller;
import org.cloudifysource.esc.installer.EnvironmentFileBuilder;
import org.cloudifysource.esc.installer.InstallationDetails;
import org.cloudifysource.esc.installer.InstallerException;
import org.cloudifysource.esc.util.CalcUtils;
import org.cloudifysource.esc.util.InstallationDetailsBuilder;
import org.cloudifysource.esc.util.ProvisioningDriverClassBuilder;
import org.cloudifysource.esc.util.Utils;
import org.cloudifysource.utilitydomain.data.reader.ComputeTemplatesReader;
import org.codehaus.jackson.map.ObjectMapper;
import org.openspaces.admin.Admin;
import org.openspaces.admin.AdminFactory;
import org.openspaces.admin.bean.BeanConfigurationException;
import org.openspaces.admin.gsa.GSAReservationId;
import org.openspaces.admin.gsa.GridServiceAgent;
import org.openspaces.admin.gsa.GridServiceAgents;
import org.openspaces.admin.gsa.events.ElasticGridServiceAgentProvisioningProgressChangedEventListener;
import org.openspaces.admin.gsc.GridServiceContainer;
import org.openspaces.admin.internal.gsa.InternalGridServiceAgent;
import org.openspaces.admin.machine.events.ElasticMachineProvisioningProgressChangedEventListener;
import org.openspaces.admin.pu.ProcessingUnit;
import org.openspaces.admin.pu.ProcessingUnitInstance;
import org.openspaces.admin.pu.elastic.ElasticMachineProvisioningConfig;
import org.openspaces.admin.zone.config.ExactZonesConfig;
import org.openspaces.admin.zone.config.ExactZonesConfigurer;
import org.openspaces.admin.zone.config.ZonesConfig;
import org.openspaces.core.bean.Bean;
import org.openspaces.grid.gsm.capacity.CapacityRequirements;
import org.openspaces.grid.gsm.capacity.CpuCapacityRequirement;
import org.openspaces.grid.gsm.capacity.MemoryCapacityRequirement;
import org.openspaces.grid.gsm.machines.FailedGridServiceAgent;
import org.openspaces.grid.gsm.machines.StartedGridServiceAgent;
import org.openspaces.grid.gsm.machines.isolation.ElasticProcessingUnitMachineIsolation;
import org.openspaces.grid.gsm.machines.plugins.ElasticMachineProvisioning;
import org.openspaces.grid.gsm.machines.plugins.events.GridServiceAgentStartRequestedEvent;
import org.openspaces.grid.gsm.machines.plugins.events.GridServiceAgentStartedEvent;
import org.openspaces.grid.gsm.machines.plugins.events.GridServiceAgentStopRequestedEvent;
import org.openspaces.grid.gsm.machines.plugins.events.GridServiceAgentStoppedEvent;
import org.openspaces.grid.gsm.machines.plugins.events.MachineStopRequestedEvent;
import org.openspaces.grid.gsm.machines.plugins.events.MachineStoppedEvent;
import org.openspaces.grid.gsm.machines.plugins.exceptions.ElasticGridServiceAgentProvisioningException;
import org.openspaces.grid.gsm.machines.plugins.exceptions.ElasticMachineProvisioningException;
import com.gigaspaces.document.SpaceDocument;

/****************************
 * An ESM machine provisioning implementation used by the Cloudify cloud driver. All calls to start/stop a machine are
 * delegated to the CloudifyProvisioning implementation. If the started machine does not have an agent running, this
 * class will install gigaspaces and start the agent using the Agent-less Installer process.
 * 
 * IMPORTANT NOTE: If you change the name of this class, you must also change the name in the esc-config project, in:
 * org.cloudifysource.esc.driver.provisioning.CloudifyMachineProvisioningConfig.getBeanClassName()
 * 
 * @author barakme
 * @since 2.0
 * 
 */
public class ElasticMachineProvisioningCloudifyAdapter implements ElasticMachineProvisioning, Bean {

    private static final String REMOTE_ADMIN_SHARE_CHAR = "$";

    private static final String BACK_SLASH = "\\";

    private static final String FORWARD_SLASH = "/";

    private static final int MILLISECONDS_IN_SECOND = 1000;

    private static final int DEFAULT_SHUTDOWN_TIMEOUT_AFTER_PROVISION_FAILURE = 5;

    // 5 minutes after 2 consecutive failed requests.
    private static final int DEFAULT_START_MACHINE_FAILURE_THROTTLING_TIMEFRAME_SEC = 300;

    // the default number of consequtive failures that can be occur in the timeframe window.
    private static final int DEFAULT_START_MACHINE_ALLOWED_FAILED_REQUESTS_IN_TIMEFRAME = 2;

    /**********
	 * .
	 */
    public static final String CLOUD_ZONE_PREFIX = "__cloud.zone.";

    // TODO: Store this object inside ElasticMachineProvisioningContext instead
    // of a static variable
    private static final Map<String, ProvisioningDriverClassContext> PROVISIONING_DRIVER_CONTEXT_PER_DRIVER_CLASSNAME = new HashMap<String, ProvisioningDriverClassContext>();

    private static final int DISCOVERY_INTERVAL = 5000;

    private static Admin globalAdminInstance = null;

    private static final Object GLOBAL_ADMIN_MUTEX = new Object();

    private static final long DEFAULT_AGENT_DISCOVERY_INTERVAL = 1000L;

    private StorageProvisioningDriver storageProvisioning;

    private BaseComputeDriver cloudifyProvisioning;

    private BaseNetworkDriver networkProvisioning;

    private Admin originalESMAdmin;

    private Cloud cloud;

    private Map<String, String> properties;

    private String cloudTemplateName;

    private String storageTemplateName;

    private String lookupLocatorsString;

    private CloudifyMachineProvisioningConfig config;

    private java.util.logging.Logger logger;

    private String serviceName;

    private ElasticMachineProvisioningProgressChangedEventListener machineEventListener;

    private ElasticGridServiceAgentProvisioningProgressChangedEventListener agentEventListener;

    private File cloudDslFile;

    // used to limit the rate of start-machine requests in the event of failure.
    // this is done to prevent management machine from overloading. CLOUDIFY-2201
    private RequestRateLimiter exceptionThrottler;

    private Admin getGlobalAdminInstance(final Admin esmAdminInstance) throws InterruptedException, ElasticMachineProvisioningException {
        synchronized (GLOBAL_ADMIN_MUTEX) {
            if (globalAdminInstance == null) {
                final AdminFactory factory = new AdminFactory();
                factory.useDaemonThreads(true);
                for (final String group : esmAdminInstance.getGroups()) {
                    factory.addGroup(group);
                }
                for (final LookupLocator locator : esmAdminInstance.getLocators()) {
                    factory.addLocator(IPUtils.getSafeIpAddress(locator.getHost()) + ":" + locator.getPort());
                }
                globalAdminInstance = factory.createAdmin();
                waitForAgentsToBeDiscovered(esmAdminInstance, globalAdminInstance);
            }
            return globalAdminInstance;
        }
    }

    private void waitForAgentsToBeDiscovered(final Admin esmAdminInstance, final Admin globalAdminInstance) throws InterruptedException, ElasticMachineProvisioningException {
        final long endTime = System.currentTimeMillis() + cloud.getConfiguration().getAdminLoadingTimeInSeconds() * MILLISECONDS_IN_SECOND;
        boolean esmAdminOk = false;
        Map<String, GridServiceContainer> undiscoveredAgentsContianersPerProcessingUnitInstanceName;
        while (System.currentTimeMillis() < endTime) {
            if (!esmAdminOk) {
                undiscoveredAgentsContianersPerProcessingUnitInstanceName = getUndiscoveredAgentsContianersPerProcessingUnitInstanceName(esmAdminInstance);
                if (undiscoveredAgentsContianersPerProcessingUnitInstanceName.isEmpty()) {
                    esmAdminOk = true;
                } else {
                    logger.info("Detected containers who\'s agent was not discovered yet : " + logContainers(undiscoveredAgentsContianersPerProcessingUnitInstanceName));
                    logger.info("Sleeping for 5 seconds");
                    Thread.sleep(DISCOVERY_INTERVAL);
                }
            } else {
                final Set<String> esmAdminAgentUids = esmAdminInstance.getGridServiceAgents().getUids().keySet();
                final Set<String> globalAdminAgentUids = globalAdminInstance.getGridServiceAgents().getUids().keySet();
                final Set<String> agentsNotDiscoveredInGlobalAdminInstance = new HashSet<String>();
                for (final String agentUid : esmAdminAgentUids) {
                    if (!globalAdminAgentUids.contains(agentUid)) {
                        agentsNotDiscoveredInGlobalAdminInstance.add(agentUid);
                    }
                }
                if (agentsNotDiscoveredInGlobalAdminInstance.isEmpty()) {
                    logger.fine("All agents discovered by esm admin are discovered by cloud driver admin. " + "Machine provisioning will continue.");
                    return;
                } else {
                    logger.info("Detected agents that are discovered in the esm admin but not in the " + "cloud driver admin : " + agentsNotDiscoveredInGlobalAdminInstance + " . Waiting 5 seconds for agent discovery");
                    Thread.sleep(DISCOVERY_INTERVAL);
                }
            }
        }
        throw new ElasticMachineProvisioningException("Cannot start a new machine when the admin " + "has not been synced properly");
    }

    private Map<String, GridServiceContainer> getUndiscoveredAgentsContianersPerProcessingUnitInstanceName(final Admin admin) {
        final Map<String, GridServiceContainer> undiscoveredAgentsContianersPerProcessingUnitInstanceName = new HashMap<String, GridServiceContainer>();
        for (final ProcessingUnit pu : admin.getProcessingUnits()) {
            for (final ProcessingUnitInstance instance : pu.getInstances()) {
                final GridServiceContainer container = instance.getGridServiceContainer();
                if (container == null || (container.getAgentId() != -1 && container.getGridServiceAgent() == null)) {
                    undiscoveredAgentsContianersPerProcessingUnitInstanceName.put(instance.getProcessingUnitInstanceName(), container);
                }
            }
        }
        return undiscoveredAgentsContianersPerProcessingUnitInstanceName;
    }

    private String logContainers(final Map<String, GridServiceContainer> undiscoveredAgentsContainersPerPUIName) {
        final StringBuilder builder = new StringBuilder();
        for (final Map.Entry<String, GridServiceContainer> entry : undiscoveredAgentsContainersPerPUIName.entrySet()) {
            final GridServiceContainer container = entry.getValue();
            final String processingUnitInstanceName = entry.getKey();
            if (container == null) {
                builder.append("GridServiceContainer is null");
            } else {
                builder.append("GridServiceContainer[uid=" + container.getUid() + "] agentId=[" + container.getAgentId() + "]");
            }
            builder.append(" processingUnitInstanceName=[" + processingUnitInstanceName + "]");
            builder.append(",");
        }
        return builder.toString();
    }

    @Override
    public boolean isStartMachineSupported() {
        return true;
    }

    @Override
    public GridServiceAgent[] getDiscoveredMachines(final long duration, final TimeUnit unit) throws InterruptedException, TimeoutException {
        final List<GridServiceAgent> result = new ArrayList<GridServiceAgent>();
        final GridServiceAgent[] agents = this.originalESMAdmin.getGridServiceAgents().getAgents();
        for (final GridServiceAgent agent : agents) {
            final String template = agent.getVirtualMachine().getDetails().getEnvironmentVariables().get(CloudifyConstants.GIGASPACES_CLOUD_TEMPLATE_NAME);
            if (template != null) {
                if (template.equals(this.cloudTemplateName)) {
                    result.add(agent);
                }
            } else {
                logger.fine("in getDiscoveredMachines() --> agent on host " + agent.getMachine().getHostAddress() + " does not have a template name attached to its env variables");
            }
        }
        return result.toArray(new GridServiceAgent[result.size()]);
    }

    private InstallationDetails createInstallationDetails(final Cloud cloud, final MachineDetails md, final GSAReservationId reservationId) throws FileNotFoundException {
        final ComputeTemplate template = this.cloud.getCloudCompute().getTemplates().get(this.cloudTemplateName);
        final ExactZonesConfigurer configurer = new ExactZonesConfigurer().addZones(config.getGridServiceAgentZones().getZones());
        if (!StringUtils.isBlank(md.getLocationId())) {
            configurer.addZone(CLOUD_ZONE_PREFIX + md.getLocationId());
        }
        final ExactZonesConfig zones = configurer.create();
        final InstallationDetails details = Utils.createInstallationDetails(md, cloud, template, zones, lookupLocatorsString, this.originalESMAdmin, false, null, reservationId, cloudTemplateName, "", "", config.getAuthGroups(), false, false);
        logger.info("Created new Installation Details: " + details);
        return details;
    }

    @Override
    public StartedGridServiceAgent startMachine(final ExactZonesConfig zones, final GSAReservationId reservationId, final FailedGridServiceAgent failedAgent, final long duration, final TimeUnit unit) throws ElasticMachineProvisioningException, ElasticGridServiceAgentProvisioningException, InterruptedException, TimeoutException {
        logger.info("Cloudify Adapter is starting a new machine with zones " + zones.getZones() + " and reservation id " + reservationId);
        final long end = System.currentTimeMillis() + unit.toMillis(duration);
        logger.info("Calling provisioning implementation for new machine");
        MachineDetails machineDetails;
        final ZonesConfig defaultZones = config.getGridServiceAgentZones();
        logger.fine("default zones = " + defaultZones.getZones());
        if (!defaultZones.isSatisfiedBy(zones)) {
            final String errorMessage = "The specified zones " + zones + " do not satisfy the configuration zones " + defaultZones;
            logger.warning(errorMessage);
            blockStartMachineOnException();
            throw new IllegalArgumentException(errorMessage);
        }
        final ComputeTemplate template = cloud.getCloudCompute().getTemplates().get(this.cloudTemplateName);
        final String locationId = findLocationIdInZones(zones, template);
        fireMachineStartEvent(locationId);
        try {
            final MachineDetails previousMachineDetails = getPreviousMachineDetailsFromFailedGSA(failedAgent);
            machineDetails = provisionMachine(locationId, reservationId, duration, unit, previousMachineDetails);
            if (!machineDetails.isAgentRunning()) {
                validateMachineIp(machineDetails);
            }
            if (machineDetails.getInstallerConfiguration() == null) {
                machineDetails.setInstallerConfigutation(template.getInstaller());
            }
        } catch (final Exception e) {
            logger.log(Level.WARNING, "Failed to provision machine: " + e.getMessage(), e);
            blockStartMachineOnException();
            throw new ElasticMachineProvisioningException("Failed to provision machine: " + e.getMessage(), e);
        }
        logger.info("Machine was provisioned by implementation. Machine is: " + machineDetails);
        String machineIp;
        if (cloud.getConfiguration().isConnectToPrivateIp()) {
            machineIp = machineDetails.getPrivateAddress();
        } else {
            machineIp = machineDetails.getPublicAddress();
        }
        if (machineIp == null) {
            final String errorMessage = "The IP of the new machine is null! Machine Details are: " + machineDetails + ".";
            logger.warning(errorMessage);
            blockStartMachineOnException();
            throw new IllegalStateException(errorMessage);
        }
        fireMachineStartedEvent(machineDetails, machineIp);
        fireGSAStartRequestedEvent(machineIp);
        final String volumeId = null;
        try {
            checkForProvisioningTimeout(end, machineDetails);
            if (machineDetails.isAgentRunning()) {
                logger.info("Machine provisioning provided a machine and indicated that an agent is already running");
            } else {
                logger.info("Cloudify Adapter is installing Cloudify agent with reservation id " + reservationId + " on " + machineIp);
                installAndStartAgent(machineDetails, reservationId, end);
                checkForProvisioningTimeout(end, machineDetails);
            }
            logger.info("Cloudify adapter is waiting for GSA on host: " + machineIp + " with reservation id: " + reservationId + " to become available");
            final GridServiceAgent gsa = waitForGsa(machineIp, end, reservationId);
            if (gsa == null) {
                throw new TimeoutException("New machine was provisioned and Cloudify was installed, " + "but a GSA was not discovered on the new machine: " + machineDetails);
            }
            agentEventListener.elasticGridServiceAgentProvisioningProgressChanged(new GridServiceAgentStartedEvent(machineIp, gsa.getUid()));
            if (gsa.getVirtualMachine().getDetails().getEnvironmentVariables().get(CloudifyConstants.GIGASPACES_CLOUD_TEMPLATE_NAME) == null) {
                throw new ElasticGridServiceAgentProvisioningException("an agent was started. but the property " + CloudifyConstants.GIGASPACES_CLOUD_TEMPLATE_NAME + " was missing from its environment variables.");
            }
            final Object context = new MachineDetailsDocumentConverter().toDocument(machineDetails);
            initExceptionThrottler();
            return new StartedGridServiceAgent(gsa, context);
        } catch (final ElasticMachineProvisioningException e) {
            logger.info("ElasticMachineProvisioningException occurred, " + e.getMessage());
            logger.info(ExceptionUtils.getFullStackTrace(e));
            handleExceptionAfterMachineCreated(machineIp, volumeId, machineDetails, end, reservationId);
            throw e;
        } catch (final ElasticGridServiceAgentProvisioningException e) {
            logger.info("ElasticGridServiceAgentProvisioningException occurred, " + e.getMessage());
            logger.info(ExceptionUtils.getFullStackTrace(e));
            handleExceptionAfterMachineCreated(machineIp, volumeId, machineDetails, end, reservationId);
            throw e;
        } catch (final TimeoutException e) {
            logger.info("TimeoutException occurred, " + e.getMessage());
            logger.info(ExceptionUtils.getFullStackTrace(e));
            handleExceptionAfterMachineCreated(machineIp, volumeId, machineDetails, end, reservationId);
            throw e;
        } catch (final InterruptedException e) {
            logger.info("InterruptedException occurred, " + e.getMessage());
            logger.info(ExceptionUtils.getFullStackTrace(e));
            handleExceptionAfterMachineCreated(machineIp, volumeId, machineDetails, end, reservationId);
            throw e;
        } catch (final Throwable e) {
            logger.info("Unexpected exception occurred, " + e.getMessage());
            logger.info(ExceptionUtils.getFullStackTrace(e));
            handleExceptionAfterMachineCreated(machineIp, volumeId, machineDetails, end, reservationId);
            throw new IllegalStateException("Unexpected exception during machine provisioning", e);
        }
    }

    private void initExceptionThrottler() {
        logger.fine("initilizing start-machine exception throttler.");
        int numRequests = getIntValue(CloudifyConstants.CUSTOM_PROPERTY_START_MACHINE_THROTTLING_NUM_REQUESTS);
        if (numRequests <= 0) {
            logger.fine("Throttling number of failed retries property not set. Using default value of " + DEFAULT_START_MACHINE_ALLOWED_FAILED_REQUESTS_IN_TIMEFRAME);
            numRequests = DEFAULT_START_MACHINE_ALLOWED_FAILED_REQUESTS_IN_TIMEFRAME;
        }
        int timeFrame = getIntValue(CloudifyConstants.CUSTOM_PROPERTY_START_MACHINE_THROTTLING_TIME_FRAME_SEC);
        if (timeFrame <= 0) {
            logger.fine("Throttling failed retries timeframe property not set. Using default value of " + DEFAULT_START_MACHINE_FAILURE_THROTTLING_TIMEFRAME_SEC);
            timeFrame = DEFAULT_START_MACHINE_FAILURE_THROTTLING_TIMEFRAME_SEC;
        }
        exceptionThrottler = new RequestRateLimiter(numRequests, timeFrame, TimeUnit.SECONDS);
    }

    // return a safe int value from custom map.
    private int getIntValue(final String customProperty) {
        Object number = cloud.getCustom().get(customProperty);
        if (number == null || !(number instanceof Integer)) {
            return 0;
        }
        return (Integer) number;
    }

    // throttling done to prevent esm from overloading 
    // management machine with start-machine requests in-case of failure.
    private void blockStartMachineOnException() {
        final Boolean throttlerEnabled = (Boolean) cloud.getCustom().get(CloudifyConstants.CUSTOM_PROPERTY_START_MACHINE_THROTTLING_ENABLED);
        if (throttlerEnabled != null && !throttlerEnabled) {
            return;
        }
        final long invocationTimeoutMin = TimeUnit.SECONDS.toMinutes(exceptionThrottler.getDuration());
        final boolean blockRequired = exceptionThrottler.tryBlock();
        if (blockRequired) {
            logger.warning("Maximum number of failed requests to start a machine " + "has been reached for service \'" + serviceName + "\'. Next attempt will be blocked for the remaining cool-down period of " + invocationTimeoutMin + " minutes.");
        }
        exceptionThrottler.block();
        if (blockRequired) {
            logger.info("Cool-down period has expired. Start-machine requests are now permitted.");
        } else {
            logger.fine("Start-machine failure has been registered. " + exceptionThrottler.getRemainingRetries() + " failures are allowed for service \'" + serviceName + "\' for the period of " + invocationTimeoutMin + " minutes.");
        }
    }

    private MachineDetails getPreviousMachineDetailsFromFailedGSA(final FailedGridServiceAgent failedAgent) {
        if (failedAgent == null) {
            return null;
        }
        final Object context = failedAgent.getAgentContext();
        if (context == null) {
            return null;
        }
        if (!(context instanceof SpaceDocument)) {
            throw new IllegalStateException("Expected to get a space document in the failed agent context, but got a: " + context.getClass().getName());
        }
        final SpaceDocument mdDocument = (SpaceDocument) context;
        final MachineDetails md = new MachineDetailsDocumentConverter().toMachineDetails(mdDocument);
        return md;
    }

    private String findLocationIdInZones(final ExactZonesConfig zones, final ComputeTemplate template) {
        String locationId = null;
        logger.fine("searching for cloud specific zone");
        for (final String zone : zones.getZones()) {
            logger.fine("current zone = " + zone);
            if (zone.startsWith(CLOUD_ZONE_PREFIX)) {
                logger.fine("found a zone with " + CLOUD_ZONE_PREFIX + " prefix : " + zone);
                if (locationId == null) {
                    locationId = zone.substring(CLOUD_ZONE_PREFIX.length());
                    logger.fine("passing locationId to machine provisioning as " + locationId);
                } else {
                    throw new IllegalArgumentException("The specified zones " + zones + " should include only one zone with the " + CLOUD_ZONE_PREFIX + " prefix:" + locationId);
                }
            }
        }
        if (locationId == null) {
            return template.getLocationId();
        }
        return locationId;
    }

    private void fireGSAStartRequestedEvent(final String machineIp) {
        final GridServiceAgentStartRequestedEvent agentStartEvent = new GridServiceAgentStartRequestedEvent();
        agentStartEvent.setHostAddress(machineIp);
        agentEventListener.elasticGridServiceAgentProvisioningProgressChanged(agentStartEvent);
    }

    private void fireMachineStartedEvent(final MachineDetails machineDetails, final String machineIp) {
        final MachineStartedCloudifyEvent machineStartedEvent = new MachineStartedCloudifyEvent();
        machineStartedEvent.setMachineDetails(machineDetails);
        machineStartedEvent.setHostAddress(machineIp);
        machineEventListener.elasticMachineProvisioningProgressChanged(machineStartedEvent);
    }

    private void fireMachineStartEvent(final String locationId) {
        final MachineStartRequestedCloudifyEvent machineStartEvent = new MachineStartRequestedCloudifyEvent();
        machineStartEvent.setTemplateName(cloudTemplateName);
        machineStartEvent.setLocationId(locationId);
        machineEventListener.elasticMachineProvisioningProgressChanged(machineStartEvent);
    }

    private void validateMachineIp(final MachineDetails machineDetails) throws CloudProvisioningException {
        logger.fine("Validating " + machineDetails + " after provisioning ");
        String machineIp;
        logger.fine("Listing existing agents");
        final GridServiceAgents gridServiceAgents = originalESMAdmin.getGridServiceAgents();
        for (final GridServiceAgent agent : gridServiceAgents) {
            if (cloud.getConfiguration().isConnectToPrivateIp()) {
                machineIp = machineDetails.getPrivateAddress();
            } else {
                machineIp = machineDetails.getPublicAddress();
            }
            logger.fine("Found agent " + agent.getUid() + " on host " + agent.getMachine().getHostAddress());
            if (agent.getMachine().getHostAddress().equals(machineIp)) {
                throw new CloudProvisioningException("An existing agent with ip " + machineIp + " was discovered. this machine is invalid.");
            }
        }
    }

    private boolean isStorageTemplateUsed() {
        return !StringUtils.isEmpty(this.storageTemplateName) && !this.storageTemplateName.equals("null");
    }

    private void handleExceptionAfterMachineCreated(final String machineIp, final String volumeId, final MachineDetails machineDetails, final long end, final GSAReservationId reservationId) {
        try {
            final boolean machineIpExists = machineIp != null && !machineIp.trim().isEmpty();
            if (machineIpExists) {
                try {
                    final GridServiceAgent agent = getGSAByIpOrHost(machineIp, reservationId);
                    if (agent != null) {
                        logger.info("handleExceptionAfterMachineCreated is shutting down agent: " + agent + " on host: " + machineIp);
                        agent.shutdown();
                        logger.fine("Agent on host: " + machineIp + " successfully shut down");
                    }
                } catch (final Exception e) {
                    logger.log(Level.WARNING, "Failed to shutdown agent on host: " + machineIp + ". Continuing with shutdown of " + "machine.", e);
                }
            }
            logger.info("Stopping machine " + machineIp + ", DEFAULT_SHUTDOWN_TIMEOUT_AFTER_PROVISION_FAILURE");
            final boolean stoppedMachine = this.cloudifyProvisioning.stopMachine(machineDetails.getPrivateAddress(), DEFAULT_SHUTDOWN_TIMEOUT_AFTER_PROVISION_FAILURE, TimeUnit.MINUTES);
            if (!stoppedMachine) {
                throw new ElasticMachineProvisioningException("Attempt to stop machine " + machineIp + " has failed. Cloud driver claims machine was already stopped, " + "however it was just recently started.");
            }
        } catch (final Exception e) {
            logger.log(Level.WARNING, "Machine Provisioning failed. " + "An error was encountered while trying to shutdown the new machine ( " + machineDetails.toString() + "). Error was: " + e.getMessage(), e);
        } finally {
            blockStartMachineOnException();
        }
    }

    private void checkForProvisioningTimeout(final long end, final MachineDetails machineDetails) throws TimeoutException, ElasticMachineProvisioningException, InterruptedException {
        if (System.currentTimeMillis() > end) {
            logger.warning("Provisioning of new machine exceeded the required timeout. Shutting down the new machine (" + machineDetails.toString() + ")");
            throw new TimeoutException("New machine provisioning exceeded the required timeout");
        }
    }

    private void installAndStartAgent(final MachineDetails machineDetails, final GSAReservationId reservationId, final long end) throws TimeoutException, InterruptedException, ElasticMachineProvisioningException, ElasticGridServiceAgentProvisioningException {
        final AgentlessInstaller installer = new AgentlessInstaller();
        InstallationDetails installationDetails;
        try {
            installationDetails = createInstallationDetails(cloud, machineDetails, reservationId);
        } catch (final FileNotFoundException e) {
            throw new ElasticGridServiceAgentProvisioningException("Failed to create installation details for agent: " + e.getMessage(), e);
        }
        logger.info("Starting agentless installation process on started machine with installation details: " + installationDetails);
        Logger.getLogger(AgentlessInstaller.SSH_LOGGER_NAME).setLevel(Level.parse(cloud.getProvider().getSshLoggingLevel()));
        try {
            installer.installOnMachineWithIP(installationDetails, remainingTimeTill(end), TimeUnit.MILLISECONDS);
        } catch (final InstallerException e) {
            throw new ElasticGridServiceAgentProvisioningException("Failed to install Cloudify Agent on newly provisioned machine: " + e.getMessage(), e);
        }
    }

    private long remainingTimeTill(final long end) throws TimeoutException {
        final long remaining = end - System.currentTimeMillis();
        if (remaining <= 0) {
            throw new TimeoutException("Passed target end time " + new Date(end));
        }
        return remaining;
    }

    private MachineDetails provisionMachine(final String locationId, final GSAReservationId reservationId, final long duration, final TimeUnit unit, final MachineDetails previousMachineDetails) throws TimeoutException, ElasticMachineProvisioningException {
        final ProvisioningContextImpl ctx = setUpProvisioningContext(locationId, reservationId, previousMachineDetails);
        MachineDetails machineDetails;
        try {
            machineDetails = cloudifyProvisioning.startMachine(ctx, duration, unit);
        } catch (final CloudProvisioningException e) {
            throw new ElasticMachineProvisioningException("Failed to start machine: " + e.getMessage(), e);
        } finally {
            ProvisioningContextAccess.setCurrentProvisioingContext(null);
        }
        if (machineDetails == null) {
            throw new IllegalStateException("Provisioning provider: " + cloudifyProvisioning.getClass().getName() + " returned null when calling startMachine");
        }
        logger.info("New machine was provisioned. Machine details: " + machineDetails);
        return machineDetails;
    }

    private ProvisioningContextImpl setUpProvisioningContext(final String locationId, final GSAReservationId reservationId, final MachineDetails previousMachineDetails) {
        final ProvisioningContextImpl ctx = new ProvisioningContextImpl();
        ctx.setLocationId(locationId);
        ctx.setCloudFile(cloudDslFile);
        ctx.setPreviousMachineDetails(previousMachineDetails);
        final InstallationDetailsBuilder builder = ctx.getInstallationDetailsBuilder();
        builder.setReservationId(reservationId);
        builder.setAdmin(globalAdminInstance);
        builder.setAuthGroups(this.config.getAuthGroups());
        builder.setCloud(this.cloud);
        builder.setCloudFile(this.cloudDslFile);
        builder.setKeystorePassword("");
        builder.setLookupLocators(this.lookupLocatorsString);
        builder.setManagement(false);
        builder.setRebootstrapping(false);
        builder.setReservationId(reservationId);
        builder.setSecurityProfile("");
        builder.setTemplate(this.cloud.getCloudCompute().getTemplates().get(this.cloudTemplateName));
        builder.setTemplateName(this.cloudTemplateName);
        builder.setZones(config.getGridServiceAgentZones().getZones());
        return ctx;
    }

    private GridServiceAgent waitForGsa(final String machineIp, final long end, final GSAReservationId reservationId) throws InterruptedException, TimeoutException {
        while (CalcUtils.millisUntil(end) > 0) {
            final GridServiceAgent gsa = getGSAByIpOrHost(machineIp, reservationId);
            if (gsa != null) {
                return gsa;
            }
            Thread.sleep(MILLISECONDS_IN_SECOND);
        }
        return null;
    }

    private GridServiceAgent getGSAByIpOrHost(final String machineIp, final GSAReservationId reservationId) {
        final GridServiceAgent[] allAgents = originalESMAdmin.getGridServiceAgents().getAgents();
        for (final GridServiceAgent gridServiceAgent : allAgents) {
            if (IPUtils.isSameIpAddress(gridServiceAgent.getMachine().getHostAddress(), machineIp) || gridServiceAgent.getMachine().getHostName().equals(machineIp)) {
                final boolean reservationMatches = checkReservationId(machineIp, reservationId, gridServiceAgent);
                if (reservationMatches) {
                    return gridServiceAgent;
                }
            }
        }
        return null;
    }

    private boolean checkReservationId(final String machineIp, final GSAReservationId reservationId, final GridServiceAgent gridServiceAgent) {
        final GSAReservationId discoveredReservationId = ((InternalGridServiceAgent) gridServiceAgent).getReservationId();
        logger.info("Discovered agent with reservation id " + discoveredReservationId);
        if (reservationId != null && !reservationId.equals(discoveredReservationId)) {
            logger.warning("Cloudify Adapter discovered the wrong agent for host: " + machineIp + ". " + "expected reservation id is " + reservationId + ". but actual was " + discoveredReservationId);
            return false;
        }
        return true;
    }

    @Override
    public CapacityRequirements getCapacityOfSingleMachine() {
        final ComputeTemplate template = cloud.getCloudCompute().getTemplates().get(this.cloudTemplateName);
        final CapacityRequirements capacityRequirements = new CapacityRequirements(new MemoryCapacityRequirement((long) template.getMachineMemoryMB()), new CpuCapacityRequirement(template.getNumberOfCores()));
        logger.info("Capacity requirements for a single machine are: " + capacityRequirements);
        return capacityRequirements;
    }

    @Override
    public void stopMachine(final StartedGridServiceAgent startedAgent, final long duration, final TimeUnit unit) throws ElasticMachineProvisioningException, ElasticGridServiceAgentProvisioningException, InterruptedException, TimeoutException {
        final long endTime = System.currentTimeMillis() + unit.toMillis(duration);
        final GridServiceAgent agent = startedAgent.getAgent();
        final String machineIp = agent.getMachine().getHostAddress();
        Exception failedToShutdownAgentException = null;
        final GridServiceAgentStopRequestedEvent agentStopEvent = new GridServiceAgentStopRequestedEvent();
        agentStopEvent.setHostAddress(machineIp);
        agentStopEvent.setAgentUid(agent.getUid());
        agentEventListener.elasticGridServiceAgentProvisioningProgressChanged(agentStopEvent);
        logger.fine("Shutting down agent: " + agent + " on host: " + machineIp);
        try {
            agent.shutdown();
            logger.fine("Agent on host: " + machineIp + " successfully shut down");
            final GridServiceAgentStoppedEvent agentStoppedEvent = new GridServiceAgentStoppedEvent();
            agentStoppedEvent.setHostAddress(machineIp);
            agentStoppedEvent.setAgentUid(agent.getUid());
            agentEventListener.elasticGridServiceAgentProvisioningProgressChanged(agentStoppedEvent);
        } catch (final Exception e) {
            failedToShutdownAgentException = e;
            logger.log(Level.FINE, "Failed to shutdown agent on host: " + machineIp + ". Continuing with shutdown of machine.", e);
        }
        try {
            final MachineStopRequestedEvent machineStopEvent = new MachineStopRequestedEvent();
            machineStopEvent.setHostAddress(machineIp);
            machineEventListener.elasticMachineProvisioningProgressChanged(machineStopEvent);
            logger.fine("Cloudify Adapter is shutting down machine with ip: " + machineIp);
            final boolean machineStopped = this.cloudifyProvisioning.stopMachine(machineIp, duration, unit);
            logger.fine("Shutdown result of machine: " + machineIp + " was: " + machineStopped);
            if (machineStopped) {
                final MachineStoppedEvent machineStoppedEvent = new MachineStoppedEvent();
                machineStoppedEvent.setHostAddress(machineIp);
                machineEventListener.elasticMachineProvisioningProgressChanged(machineStoppedEvent);
                while (agent.isDiscovered()) {
                    Thread.sleep(DEFAULT_AGENT_DISCOVERY_INTERVAL);
                    if (System.currentTimeMillis() > endTime && agent.isDiscovered()) {
                        if (failedToShutdownAgentException != null) {
                            throw new ElasticGridServiceAgentProvisioningException("Machine is stopped but agent [" + agent.getUid() + "] is still discovered." + "Failed to shutdown agent:" + failedToShutdownAgentException.getMessage(), failedToShutdownAgentException);
                        }
                        throw new ElasticGridServiceAgentProvisioningException("Machine is stopped but agent[" + agent.getUid() + "] is still discovered.");
                    }
                }
            } else {
                if (failedToShutdownAgentException == null) {
                    throw new ElasticMachineProvisioningException("Attempt to shutdown machine with IP: " + machineIp + " for agent with UID: " + agent.getUid() + " has failed. Cloud driver claims machine was " + "already stopped, however we just recently stopped the agent process on that machine.");
                }
            }
        } catch (final CloudProvisioningException e) {
            throw new ElasticMachineProvisioningException("Attempt to shutdown machine with IP: " + machineIp + " for agent with UID: " + agent.getUid() + " has failed with error: " + e.getMessage(), e);
        }
    }

    @Override
    public ElasticMachineProvisioningConfig getConfig() {
        return this.config;
    }

    // //////////////////////////////////
    // OpenSpaces Bean Implementation //
    // //////////////////////////////////
    @Override
    public void setAdmin(final Admin admin) {
        this.originalESMAdmin = admin;
    }

    @Override
    public void setProperties(final Map<String, String> properties) {
        this.properties = properties;
        this.config = new CloudifyMachineProvisioningConfig(properties);
    }

    @Override
    public Map<String, String> getProperties() {
        return this.properties;
    }

    private void initCloudObject(final String cloudConfigDirectory, final String overridesScript) throws DSLException {
        final DSLReader reader = new DSLReader();
        reader.setDslFileNameSuffix(DSLUtils.CLOUD_DSL_FILE_NAME_SUFFIX);
        reader.setWorkDir(new File(cloudConfigDirectory));
        reader.setCreateServiceContext(false);
        reader.setOverridesScript(overridesScript);
        this.cloud = reader.readDslEntity(Cloud.class);
        this.cloudDslFile = reader.getDslFile();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        logger = java.util.logging.Logger.getLogger(ElasticMachineProvisioningCloudifyAdapter.class.getName());
        String cloudConfigDirectoryPath = findCloudConfigDirectoryPath();
        try {
            final String cloudOverridesPerService = config.getCloudOverridesPerService();
            initCloudObject(cloudConfigDirectoryPath, cloudOverridesPerService);
            this.cloudTemplateName = properties.get(CloudifyConstants.ELASTIC_PROPERTIES_CLOUD_TEMPLATE_NAME);
            if (this.cloudTemplateName == null) {
                throw new BeanConfigurationException("Cloud template was not set!");
            }
            addTemplatesToCloud(new File(cloudConfigDirectoryPath));
            final ComputeTemplate computeTemplate = this.cloud.getCloudCompute().getTemplates().get(this.cloudTemplateName);
            if (computeTemplate == null) {
                throw new BeanConfigurationException("The provided cloud template name: " + this.cloudTemplateName + " was not found in the cloud configuration");
            }
            logger.info("Remote Directory is: " + computeTemplate.getRemoteDirectory());
            if (computeTemplate.getFileTransfer() == FileTransferModes.CIFS) {
                logger.info("Windows machine - modifying local directory location");
                final String remoteDirName = computeTemplate.getRemoteDirectory();
                final String windowsLocalDirPath = getWindowsLocalDirPath(remoteDirName, computeTemplate.getLocalDirectory());
                logger.info("Modified local dir name is: " + windowsLocalDirPath);
                computeTemplate.setLocalDirectory(windowsLocalDirPath);
            } else {
                computeTemplate.setLocalDirectory(computeTemplate.getRemoteDirectory());
            }
            try {
                final ProvisioningDriverClassBuilder builder = new ProvisioningDriverClassBuilder();
                final Object computeProvisioningInstance = builder.build(cloudConfigDirectoryPath, this.cloud.getConfiguration().getClassName());
                this.cloudifyProvisioning = ComputeDriverProvisioningAdapter.create(computeProvisioningInstance);
                final ProvisioningDriverClassContext provisioningDriverContext = lazyCreateProvisioningDriverClassContext(cloudifyProvisioning);
                this.cloudifyProvisioning.setProvisioningDriverClassContext(provisioningDriverContext);
                handleServiceCloudConfiguration();
                final String storageClassName = this.cloud.getConfiguration().getStorageClassName();
                if (StringUtils.isNotBlank(storageClassName)) {
                    logger.info("creating storage provisioning driver.");
                    this.storageProvisioning = (StorageProvisioningDriver) builder.build(cloudConfigDirectoryPath, storageClassName);
                    this.storageTemplateName = config.getStorageTemplateName();
                    logger.info("storage provisioning driver created successfully.");
                }
                final String networkDriverClassName = this.cloud.getConfiguration().getNetworkDriverClassName();
                if (!StringUtils.isEmpty(networkDriverClassName)) {
                    logger.info("creating network provisioning driver of type " + networkDriverClassName);
                    this.networkProvisioning = (BaseNetworkDriver) builder.build(cloudConfigDirectoryPath, networkDriverClassName);
                    logger.info("network provisioning driver was created succesfully.");
                }
                initExceptionThrottler();
            } catch (final ClassNotFoundException e) {
                throw new BeanConfigurationException("Failed to load provisioning class for cloud: " + this.cloud.getName() + ". Class not found: " + this.cloud.getConfiguration().getClassName(), e);
            } catch (final Exception e) {
                throw new BeanConfigurationException("Failed to load provisioning class for cloud: " + this.cloud.getName(), e);
            }
            this.lookupLocatorsString = createLocatorsString();
            logger.info("Locators string used for new instances will be: " + this.lookupLocatorsString);
        } catch (final DSLException e) {
            logger.severe("Could not parse the provided cloud configuration from : " + cloudConfigDirectoryPath + ": " + e.getMessage());
            throw new BeanConfigurationException("Could not parse the provided cloud configuration: " + cloudConfigDirectoryPath + ": " + e.getMessage(), e);
        }
    }

    /********
	 * Synchronized method verified that the setConfig() methods of cloud driver instances is called exactly once, the
	 * first time they are needed.
	 * 
	 * @throws StorageProvisioningException
	 * 
	 * @throws InterruptedException .
	 * @throws ElasticMachineProvisioningException .
	 * @throws CloudProvisioningException .
	 */
    private void configureDrivers() throws InterruptedException, ElasticMachineProvisioningException, CloudProvisioningException, StorageProvisioningException {
        final ComputeDriverConfiguration configuration = new ComputeDriverConfiguration();
        configuration.setAdmin(getGlobalAdminInstance(originalESMAdmin));
        configuration.setCloud(cloud);
        configuration.setCloudTemplate(cloudTemplateName);
        configuration.setManagement(false);
        configuration.setServiceName(serviceName);
        final ServiceNetwork network = createNetworkObject();
        configuration.setNetwork(network);
        this.cloudifyProvisioning.setConfig(configuration);
        if (this.storageProvisioning != null) {
            if (this.storageProvisioning instanceof BaseStorageDriver) {
                ((BaseStorageDriver) this.storageProvisioning).setComputeContext(cloudifyProvisioning.getComputeContext());
            }
            this.storageProvisioning.setConfig(cloud, this.cloudTemplateName);
        }
        if (this.networkProvisioning != null) {
            final NetworkDriverConfiguration networkConfig = new NetworkDriverConfiguration();
            networkConfig.setCloud(cloud);
            this.networkProvisioning.setConfig(networkConfig);
        }
    }

    private ServiceNetwork createNetworkObject() throws ElasticMachineProvisioningException {
        final String networkAsString = this.config.getNetworkAsString();
        logger.info("Network string is: " + networkAsString);
        if (StringUtils.isBlank(networkAsString)) {
            return null;
        }
        final ObjectMapper mapper = new ObjectMapper();
        try {
            final ServiceNetwork network = mapper.readValue(networkAsString, ServiceNetwork.class);
            return network;
        } catch (final IOException e) {
            throw new ElasticMachineProvisioningException("Failed to deserialize json string into service network description: " + e.getMessage(), e);
        }
    }

    private String findCloudConfigDirectoryPath() {
        String cloudConfigDirectoryPath = properties.get(CloudifyConstants.ELASTIC_PROPERTIES_CLOUD_CONFIGURATION_DIRECTORY);
        if (cloudConfigDirectoryPath == null) {
            logger.severe("[findCloudConfigDirectoryPath] - Missing cloud configuration property. Properties are: " + this.properties);
            throw new IllegalArgumentException("Cloud configuration directory was not set!");
        }
        if (ServiceUtils.isWindows()) {
            cloudConfigDirectoryPath = EnvironmentFileBuilder.normalizeCygwinPath(cloudConfigDirectoryPath);
            cloudConfigDirectoryPath = EnvironmentFileBuilder.normalizeLocalAbsolutePath(cloudConfigDirectoryPath);
        } else {
            cloudConfigDirectoryPath = EnvironmentFileBuilder.normalizeLinuxPath(cloudConfigDirectoryPath);
        }
        return cloudConfigDirectoryPath;
    }

    private void addTemplatesToCloud(final File cloudConfigDirectory) {
        File additionalTemplatesParentFolder = cloudConfigDirectory;
        String persistentStoragePath = cloud.getConfiguration().getPersistentStoragePath();
        if (persistentStoragePath != null) {
            logger.fine("[addTemplatesToCloud] - using the persistent storage folder [" + persistentStoragePath + "] as the parent of the additional templates folder.");
            additionalTemplatesParentFolder = new File(persistentStoragePath);
        }
        logger.info("[addTemplatesToCloud] - adding templates from directory " + "[" + additionalTemplatesParentFolder.getAbsolutePath() + "]");
        final File additionalTemplatesFolder = new File(additionalTemplatesParentFolder, CloudifyConstants.ADDITIONAL_TEMPLATES_FOLDER_NAME);
        if (!additionalTemplatesFolder.exists()) {
            logger.info("[addTemplatesToCloud] - no additional templates to add from directory " + additionalTemplatesParentFolder.getAbsolutePath());
            return;
        }
        final File[] listFiles = additionalTemplatesFolder.listFiles();
        logger.info("[addTemplatesToCloud] - found files: " + Arrays.toString(listFiles));
        final ComputeTemplatesReader reader = new ComputeTemplatesReader();
        final List<ComputeTemplate> addedTemplates = reader.addAdditionalTemplates(cloud, listFiles);
        logger.info("[addTemplatesToCloud] - Added " + addedTemplates.size() + " templates to the cloud: " + addedTemplates);
    }

    private String getWindowsLocalDirPath(final String remoteDirectoryPath, final String localDirName) {
        final String homeDirectoryName = getWindowsRemoteDirPath(remoteDirectoryPath);
        final File localDirectory = new File(homeDirectoryName, localDirName);
        return localDirectory.getAbsolutePath();
    }

    private String getWindowsRemoteDirPath(final String remoteDirectoryPath) {
        String homeDirectoryName = remoteDirectoryPath;
        homeDirectoryName = homeDirectoryName.replace(REMOTE_ADMIN_SHARE_CHAR, "");
        if (homeDirectoryName.startsWith(FORWARD_SLASH)) {
            homeDirectoryName = homeDirectoryName.substring(1);
        }
        if (homeDirectoryName.charAt(1) == FORWARD_SLASH.charAt(0)) {
            homeDirectoryName = homeDirectoryName.substring(0, 1) + ":" + homeDirectoryName.substring(1);
        }
        homeDirectoryName = homeDirectoryName.replace(FORWARD_SLASH, BACK_SLASH);
        return homeDirectoryName;
    }

    private void handleServiceCloudConfiguration() throws IOException {
        final byte[] serviceCloudConfigurationContents = this.config.getServiceCloudConfiguration();
        if (serviceCloudConfigurationContents != null) {
            logger.info("Found service cloud configuration - saving to file");
            final File tempZipFile = File.createTempFile("__CLOUD_DRIVER_SERVICE_CONFIGURATION_FILE", ".zip");
            FileUtils.writeByteArrayToFile(tempZipFile, serviceCloudConfigurationContents);
            logger.info("Wrote file: " + tempZipFile);
            final File tempServiceConfigurationDirectory = File.createTempFile("__CLOUD_DRIVER_SERVICE_CONFIGURATION_DIRECTORY", ".tmp");
            logger.info("Unzipping file to: " + tempServiceConfigurationDirectory);
            FileUtils.forceDelete(tempServiceConfigurationDirectory);
            tempServiceConfigurationDirectory.mkdirs();
            ZipUtils.unzip(tempZipFile, tempServiceConfigurationDirectory);
            final File[] childFiles = tempServiceConfigurationDirectory.listFiles();
            logger.info("Unzipped configuration contained top-level entries: " + Arrays.toString(childFiles));
            if (childFiles.length != 1) {
                throw new BeanConfigurationException("Received a service cloud configuration file, " + "but root of zip file had more then one entry!");
            }
            final File serviceCloudConfigurationFile = childFiles[0];
            logger.info("Setting service cloud configuration in cloud driver to: " + serviceCloudConfigurationFile);
            this.cloudifyProvisioning.setCustomDataFile(serviceCloudConfigurationFile);
        }
    }

    private static ProvisioningDriverClassContext lazyCreateProvisioningDriverClassContext(final BaseComputeDriver cloudifyProvisioning) {
        final String cloudDriverUniqueId = cloudifyProvisioning.getClass().getName();
        synchronized (PROVISIONING_DRIVER_CONTEXT_PER_DRIVER_CLASSNAME) {
            if (!PROVISIONING_DRIVER_CONTEXT_PER_DRIVER_CLASSNAME.containsKey(cloudDriverUniqueId)) {
                PROVISIONING_DRIVER_CONTEXT_PER_DRIVER_CLASSNAME.put(cloudDriverUniqueId, new DefaultProvisioningDriverClassContext());
            }
        }
        final ProvisioningDriverClassContext provisioningDriverContext = PROVISIONING_DRIVER_CONTEXT_PER_DRIVER_CLASSNAME.get(cloudDriverUniqueId);
        return provisioningDriverContext;
    }

    private String createLocatorsString() {
        final LookupLocator[] locators = this.originalESMAdmin.getLocators();
        final StringBuilder sb = new StringBuilder();
        for (final LookupLocator lookupLocator : locators) {
            sb.append(IPUtils.getSafeIpAddress(lookupLocator.getHost())).append(':').append(lookupLocator.getPort()).append(',');
        }
        if (!sb.toString().isEmpty()) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    @Override
    public void destroy() throws Exception {
        this.cloudifyProvisioning.close();
        if (isStorageTemplateUsed()) {
            this.storageProvisioning.close();
        }
    }

    /**
	 * @param isolation
	 *            - describes the relation between different service instances on the same machine Assuming each service
	 *            has a dedicated machine {@link org.openspaces.grid.gsm.machines.isolation.DedicatedMachineIsolation ;}
	 *            , the machine isolation name is the service name. This would change when instances from different
	 *            services would be installed on the same machine using
	 *            {@link org.openspaces.grid.gsm.machines.isolation.SharedMachineIsolation} .
	 */
    @Override
    public void setElasticProcessingUnitMachineIsolation(final ElasticProcessingUnitMachineIsolation isolation) {
        this.serviceName = isolation.getName();
    }

    @Override
    public void setElasticMachineProvisioningProgressChangedEventListener(final ElasticMachineProvisioningProgressChangedEventListener machineEventListener) {
        this.machineEventListener = machineEventListener;
    }

    @Override
    public void setElasticGridServiceAgentProvisioningProgressEventListener(final ElasticGridServiceAgentProvisioningProgressChangedEventListener agentEventListener) {
        this.agentEventListener = agentEventListener;
    }

    /**
	 * testing purposes And should not be used concurrently with any other method. ======= Clears the list of machines
	 * provisioned by any provisioning driver. This method should be used for testing purposes And should not be used
	 * concurrently with any other method.
	 */
    public static void clearContext() {
        synchronized (PROVISIONING_DRIVER_CONTEXT_PER_DRIVER_CLASSNAME) {
            PROVISIONING_DRIVER_CONTEXT_PER_DRIVER_CLASSNAME.clear();
        }
    }

    @Override
    public void cleanupMachineResources(final long duration, final TimeUnit timeUnit) throws ElasticMachineProvisioningException, InterruptedException, TimeoutException {
        try {
            cloudifyProvisioning.onServiceUninstalled(duration, timeUnit);
        } catch (final Exception e) {
            throw new ElasticMachineProvisioningException("Failed to cleanup cloud", e);
        }
    }

    @Override
    public Object getExternalApi(final String apiName) throws InterruptedException, ElasticMachineProvisioningException {
        Object externalApi = null;
        if (apiName.equals(CloudifyConstants.STORAGE_REMOTE_API_KEY)) {
            externalApi = new RemoteStorageProvisioningDriverAdapter(storageProvisioning, cloud.getCloudStorage().getTemplates().get(storageTemplateName));
        } else {
            if (apiName.equals(CloudifyConstants.NETWORK_REMOTE_API_KEY)) {
                externalApi = new RemoteNetworkProvisioningDriverAdapter(this.networkProvisioning);
            }
        }
        return externalApi;
    }

    @Override
    public void blockingAfterPropertiesSet() throws ElasticMachineProvisioningException, InterruptedException {
        try {
            configureDrivers();
        } catch (final CloudProvisioningException e) {
            throw new ElasticMachineProvisioningException("Failed to configure cloud driver for first use: " + e.getMessage(), e);
        } catch (final StorageProvisioningException e) {
            throw new ElasticMachineProvisioningException("Failed to configure storage driver for first use: " + e.getMessage(), e);
        }
    }

    private boolean isComputeTemplateWindows(final ComputeTemplate computeTemplate) {
        return RemoteExecutionModes.WINRM.equals(computeTemplate.getRemoteExecution()) || ScriptLanguages.WINDOWS_BATCH.equals(computeTemplate.getScriptLanguage());
    }
}
