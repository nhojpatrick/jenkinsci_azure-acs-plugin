/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.microsoft.azure.PagedList;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.ContainerService;
import com.microsoft.azure.management.compute.ContainerServiceOchestratorTypes;
import com.microsoft.azure.management.resources.ResourceGroup;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.jenkins.acs.commands.CheckBuildResultCommand;
import com.microsoft.jenkins.acs.commands.DeploymentChoiceCommand;
import com.microsoft.jenkins.acs.commands.DeploymentState;
import com.microsoft.jenkins.acs.commands.EnablePortCommand;
import com.microsoft.jenkins.acs.commands.GetContainerServiceInfoCommand;
import com.microsoft.jenkins.acs.commands.IBaseCommandData;
import com.microsoft.jenkins.acs.commands.ICommand;
import com.microsoft.jenkins.acs.commands.KubernetesDeploymentCommand;
import com.microsoft.jenkins.acs.commands.MarathonDeploymentCommand;
import com.microsoft.jenkins.acs.commands.RunOn;
import com.microsoft.jenkins.acs.commands.SwarmDeploymentCommand;
import com.microsoft.jenkins.acs.commands.TransitionInfo;
import com.microsoft.jenkins.acs.orchestrators.DeploymentConfig;
import com.microsoft.jenkins.acs.orchestrators.KubernetesDeploymentConfig;
import com.microsoft.jenkins.acs.orchestrators.MarathonDeploymentConfig;
import com.microsoft.jenkins.acs.orchestrators.SwarmDeploymentConfig;
import com.microsoft.jenkins.acs.util.AzureHelper;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.DeployHelper;
import com.microsoft.jenkins.acs.util.JSchClient;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;

public class ACSDeploymentContext extends AbstractBaseContext
        implements CheckBuildResultCommand.ICheckBuildResultCommandData,
        GetContainerServiceInfoCommand.IGetContainerServiceInfoCommandData,
        EnablePortCommand.IEnablePortCommandData,
        MarathonDeploymentCommand.IMarathonDeploymentCommandData,
        KubernetesDeploymentCommand.IKubernetesDeploymentCommandData,
        SwarmDeploymentCommand.ISwarmDeploymentCommandData,
        DeploymentChoiceCommand.IDeploymentChoiceCommandData {

    private final String azureCredentialsId;
    private final String resourceGroupName;
    private final String containerService;
    private final String sshCredentialsId;
    private final String configFilePaths;

    private String runOn;

    private boolean enableConfigSubstitution;
    private String kubernetesNamespace;
    private boolean swarmRemoveContainersFirst;

    private String secretName;
    private String dcosDockerCredentialsPath;
    private boolean dcosDockerCredenditalsPathShared;
    private List<DockerRegistryEndpoint> containerRegistryCredentials;

    private transient Azure azureClient;
    private transient String mgmtFQDN;
    private transient String linuxAdminUsername;
    private transient ContainerServiceOchestratorTypes orchestratorType;
    private transient SSHUserPrivateKey sshCredentials;

    private transient DeploymentConfig deploymentConfig;

    @DataBoundConstructor
    public ACSDeploymentContext(
            final String azureCredentialsId,
            final String resourceGroupName,
            final String containerService,
            final String sshCredentialsId,
            final String configFilePaths) {
        this.azureCredentialsId = azureCredentialsId;
        this.resourceGroupName = StringUtils.trimToEmpty(resourceGroupName);
        this.containerService = StringUtils.trimToEmpty(containerService);
        this.sshCredentialsId = sshCredentialsId;
        this.configFilePaths = StringUtils.trimToEmpty(configFilePaths);
    }

    @Override
    public StepExecution start(final StepContext context) throws Exception {
        return new ExecutionImpl(new ACSDeploymentRecorder(this), context);
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    private static final class ExecutionImpl extends SynchronousNonBlockingStepExecution<Void> {
        private static final long serialVersionUID = 1L;

        private final transient SimpleBuildStep delegate;

        ExecutionImpl(final SimpleBuildStep delegate, final StepContext context) {
            super(context);
            this.delegate = delegate;
        }

        @SuppressWarnings("ConstantConditions")
        @Override
        protected Void run() throws Exception {
            StepContext context = getContext();
            FilePath workspace = context.get(FilePath.class);
            workspace.mkdirs();
            delegate.perform(
                    context.get(Run.class),
                    workspace,
                    context.get(Launcher.class),
                    context.get(TaskListener.class));
            return null;
        }

        @Nonnull
        @Override
        public String getStatus() {
            String base = super.getStatus();
            if (delegate != null) {
                return delegate.getClass().getName() + ": " + base;
            } else {
                return base;
            }
        }
    }

    public String getRunOn() {
        if (StringUtils.isBlank(runOn)) {
            return getDescriptor().getDefaultRunOn();
        }
        return runOn;
    }

    @DataBoundSetter
    public void setRunOn(final String runOn) {
        this.runOn = runOn;
    }

    @Override
    public RunOn getRunOnOption() {
        return RunOn.fromString(getRunOn());
    }

    public String getAzureCredentialsId() {
        return azureCredentialsId;
    }

    public String getConfigFilePaths() {
        return this.configFilePaths;
    }

    @Override
    public DeploymentConfig getDeploymentConfig() {
        return deploymentConfig;
    }

    public String getSshCredentialsId() {
        return sshCredentialsId;
    }

    @Override
    public SSHUserPrivateKey getSshCredentials() {
        if (sshCredentials == null) {
            sshCredentials = getSshCredentials(getSshCredentialsId());
        }
        return sshCredentials;
    }

    @Override
    public void setMgmtFQDN(final String fqdn) {
        this.mgmtFQDN = fqdn;
    }

    @Override
    public String getResourceGroupName() {
        return this.resourceGroupName;
    }

    @Override
    public String getMgmtFQDN() {
        return this.mgmtFQDN;
    }

    @Override
    public IBaseCommandData getDataForCommand(final ICommand command) {
        return this;
    }

    @Override
    public String getLinuxAdminUsername() {
        return linuxAdminUsername;
    }

    @Override
    public void setLinuxRootUsername(final String username) {
        this.linuxAdminUsername = username;
    }

    /**
     * We use the orchestratorType bound at configuration time. At build time we should check if this is consistent
     * with the configuration and fail if not.
     *
     * @return the container service's orchestrator type configured for the build
     */
    @Override
    public ContainerServiceOchestratorTypes getOrchestratorType() {
        if (orchestratorType == null) {
            orchestratorType = ContainerServiceOchestratorTypes.fromString(getOrchestratorType(containerService));
        }

        return orchestratorType;
    }

    public String getContainerService() {
        return this.containerService;
    }


    /**
     * In order to pass the container service orchestrator type to the front-end, the {@link #containerService} field
     * will be in the following format. This will be stored into the configuration. At runtime, we need to extract
     * the container service name in order to pass it to the Azure service.
     * <p>
     * <code>
     * container_service_name|orchestrator_type
     * </code>
     *
     * @see #getContainerService()
     */
    @Override
    public String getContainerServiceName() {
        return getContainerServiceName(getContainerService());
    }

    @Override
    public Azure getAzureClient() {
        return this.azureClient;
    }

    @Override
    public String getKubernetesNamespace() {
        if (StringUtils.isBlank(kubernetesNamespace)) {
            return getDescriptor().getDefaultKubernetesNamespace();
        }
        return kubernetesNamespace;
    }

    @DataBoundSetter
    public void setKubernetesNamespace(final String kubernetesNamespace) {
        this.kubernetesNamespace = StringUtils.trimToEmpty(kubernetesNamespace);
    }

    @Override
    public boolean isSwarmRemoveContainersFirst() {
        return this.swarmRemoveContainersFirst;
    }

    @DataBoundSetter
    public void setSwarmRemoveContainersFirst(final boolean swarmRemoveContainersFirst) {
        this.swarmRemoveContainersFirst = swarmRemoveContainersFirst;
    }

    @Override
    public boolean isEnableConfigSubstitution() {
        return enableConfigSubstitution;
    }

    @DataBoundSetter
    public void setEnableConfigSubstitution(final boolean enableConfigSubstitution) {
        this.enableConfigSubstitution = enableConfigSubstitution;
    }

    @Override
    public String getSecretName() {
        return secretName;
    }

    @DataBoundSetter
    public void setSecretName(final String secretName) {
        this.secretName = StringUtils.trimToEmpty(secretName);
    }

    @Override
    public String getDcosDockerCredentialsPath() {
        return dcosDockerCredentialsPath;
    }

    @DataBoundSetter
    public void setDcosDockerCredentialsPath(final String dcosDockerCredentialsPath) {
        this.dcosDockerCredentialsPath = StringUtils.trimToEmpty(dcosDockerCredentialsPath);
    }

    @Override
    public boolean isDcosDockerCredenditalsPathShared() {
        return dcosDockerCredenditalsPathShared;
    }

    @DataBoundSetter
    public void setDcosDockerCredenditalsPathShared(final boolean dcosDockerCredenditalsPathShared) {
        this.dcosDockerCredenditalsPathShared = dcosDockerCredenditalsPathShared;
    }

    public List<DockerRegistryEndpoint> getContainerRegistryCredentials() {
        if (containerRegistryCredentials == null) {
            return new ArrayList<>(0);
        }
        return containerRegistryCredentials;
    }

    @DataBoundSetter
    public void setContainerRegistryCredentials(final List<DockerRegistryEndpoint> containerRegistryCredentials) {
        List<DockerRegistryEndpoint> endpoints = new ArrayList<>();
        for (DockerRegistryEndpoint endpoint : containerRegistryCredentials) {
            if (endpoint.getUrl() != null && endpoint.getCredentialsId() != null) {
                endpoints.add(endpoint);
            }
        }
        this.containerRegistryCredentials = endpoints;
    }

    public void configure(
            @Nonnull final Run<?, ?> run,
            @Nonnull final FilePath workspace,
            @Nonnull final Launcher launcher,
            @Nonnull final TaskListener listener) throws IOException, InterruptedException {

        this.azureClient = AzureHelper.buildClientFromCredentialsId(getAzureCredentialsId());

        Hashtable<Class, TransitionInfo> commands = new Hashtable<>();

        commands.put(CheckBuildResultCommand.class,
                new TransitionInfo(new CheckBuildResultCommand(), GetContainerServiceInfoCommand.class, null));

        commands.put(GetContainerServiceInfoCommand.class,
                new TransitionInfo(new GetContainerServiceInfoCommand(), DeploymentChoiceCommand.class, null));

        // DeploymentChoiceCommand will point out the next step through INextCommandAware
        commands.put(DeploymentChoiceCommand.class,
                new TransitionInfo(new DeploymentChoiceCommand(), null, null));

        // ACS with Kubernetes will add a security rule for the service port automatically,
        // so no need to manually create one to enable the port access
        commands.put(KubernetesDeploymentCommand.class,
                new TransitionInfo(new KubernetesDeploymentCommand(), null, null));

        commands.put(MarathonDeploymentCommand.class,
                new TransitionInfo(new MarathonDeploymentCommand(), EnablePortCommand.class, null));

        commands.put(SwarmDeploymentCommand.class,
                new TransitionInfo(new SwarmDeploymentCommand(), EnablePortCommand.class, null));

        commands.put(EnablePortCommand.class,
                new TransitionInfo(new EnablePortCommand(), null, null));

        final JobContext jobContext = new JobContext(run, workspace, launcher, listener);
        super.configure(
                jobContext,
                commands,
                CheckBuildResultCommand.class);

        // Build DeploymentConfig
        final EnvVars envVars = jobContext().envVars();
        final String expandedConfigFilePaths = envVars.expand(getConfigFilePaths());
        final FilePath[] configFiles = jobContext.workspacePath().list(expandedConfigFilePaths);
        if (configFiles.length == 0) {
            throw new IllegalArgumentException(Messages.ACSDeploymentContext_noConfigFilesFound(getConfigFilePaths()));
        }

        switch (getOrchestratorType()) {
            case DCOS:
                deploymentConfig = new MarathonDeploymentConfig(configFiles);
                break;
            case KUBERNETES:
                deploymentConfig = new KubernetesDeploymentConfig(configFiles);
                break;
            case SWARM:
                deploymentConfig = new SwarmDeploymentConfig(configFiles);
                break;
            default:
                throw new IllegalArgumentException(
                        Messages.ACSDeploymentContext_orchestratorNotSupported(getOrchestratorType()));
        }

        this.setDeploymentState(DeploymentState.Running);
    }

    private static SSHUserPrivateKey getSshCredentials(final String id) {
        SSHUserPrivateKey creds = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        BasicSSHUserPrivateKey.class,
                        Jenkins.getInstance(),
                        ACL.SYSTEM,
                        Collections.<DomainRequirement>emptyList()),
                CredentialsMatchers.withId(id));
        return creds;
    }

    public static String getContainerServiceName(final String containerService) {
        if (StringUtils.isBlank(containerService)) {
            throw new IllegalArgumentException(Messages.ACSDeploymentContext_blankContainerService());
        }
        String[] part = containerService.split("\\|");
        return part[0].trim();
    }

    public static String getOrchestratorType(final String containerService) {
        if (StringUtils.isBlank(containerService)) {
            throw new IllegalArgumentException(Messages.ACSDeploymentContext_blankOrchestratorType());
        }
        String[] parts = containerService.split("\\|");
        if (parts.length == 2) {
            String type = parts[1].trim();
            if (StringUtils.isNotEmpty(type)) {
                return type;
            }
        }
        throw new IllegalArgumentException(Messages.ACSDeploymentContext_blankOrchestratorType());
    }

    @VisibleForTesting
    interface CredentailsFinder {
        AzureCredentials.ServicePrincipal getServicePrincipal(String azureCredentialsId);

        SSHUserPrivateKey getSshCredentials(String sshCredentialsId);

        CredentailsFinder DEFAULT = new CredentailsFinder() {
            @Override
            public AzureCredentials.ServicePrincipal getServicePrincipal(final String credentialsId) {
                return AzureCredentials.getServicePrincipal(credentialsId);
            }

            @Override
            public SSHUserPrivateKey getSshCredentials(final String credentialsId) {
                return ACSDeploymentContext.getSshCredentials(credentialsId);
            }
        };
    }

    public static String validate(
            final String azureCredentialsId,
            final String resourceGroup,
            final String containerService,
            final String sshCredentialsId,
            final String kubernetesNamespace) {
        return validate(
                azureCredentialsId,
                resourceGroup,
                containerService,
                sshCredentialsId,
                kubernetesNamespace,
                CredentailsFinder.DEFAULT);
    }

    @VisibleForTesting
    static String validate(
            final String azureCredentialsId,
            final String resourceGroup,
            final String containerService,
            final String sshCredentialsId,
            final String kubernetesNamespace,
            final CredentailsFinder credentailsFinder) {
        AzureCredentials.ServicePrincipal servicePrincipal =
                credentailsFinder.getServicePrincipal(azureCredentialsId);

        if (StringUtils.isBlank(servicePrincipal.getSubscriptionId())) {
            return Messages.ACSDeploymentContext_missingCredentials();
        }
        if (StringUtils.isBlank(resourceGroup) || Constants.INVALID_OPTION.equals(resourceGroup)) {
            return Messages.ACSDeploymentContext_missingResourceGroup();
        }
        if (StringUtils.isBlank(containerService) || Constants.INVALID_OPTION.equals(containerService)) {
            return Messages.ACSDeploymentContext_missingContainerServiceName();
        }
        if (StringUtils.isBlank(sshCredentialsId) || credentailsFinder.getSshCredentials(sshCredentialsId) == null) {
            return Messages.ACSDeploymentContext_missingSSHCredentials();
        }

        try {
            final String orchestratorTypeName = getOrchestratorType(containerService);
            ContainerServiceOchestratorTypes orchestratorType =
                    ContainerServiceOchestratorTypes.fromString(orchestratorTypeName);

            if (!Constants.SUPPORTED_ORCHESTRATOR.contains(orchestratorType)) {
                return Messages.ACSDeploymentContext_orchestratorNotSupported(orchestratorTypeName);
            }

            if (ContainerServiceOchestratorTypes.KUBERNETES == orchestratorType
                    && StringUtils.isBlank(kubernetesNamespace)) {
                return Messages.ACSDeploymentContext_missingKubernetesNamespace();
            }
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        return null;
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {
        public ListBoxModel doFillAzureCredentialsIdItems(@AncestorInPath final Item owner) {
            List<AzureCredentials> credentials = CredentialsProvider.lookupCredentials(
                    AzureCredentials.class,
                    owner,
                    ACL.SYSTEM,
                    Collections.<DomainRequirement>emptyList()
            );
            StandardListBoxModel model = new StandardListBoxModel();
            model.add(Messages.ACSDeploymentContext_selectAzureCredentials(), Constants.INVALID_OPTION);
            model.withAll(credentials);
            return model;
        }

        public FormValidation doVerifyConfiguration(@QueryParameter final String azureCredentialsId,
                                                    @QueryParameter final String resourceGroupName,
                                                    @QueryParameter final String containerService,
                                                    @QueryParameter final String sshCredentialsId,
                                                    @QueryParameter final String kubernetesNamespace) {
            String validateResult = validate(
                    azureCredentialsId,
                    resourceGroupName,
                    containerService,
                    sshCredentialsId,
                    kubernetesNamespace);
            if (validateResult != null) {
                return FormValidation.error(validateResult);
            }

            try {
                Azure azureClient = AzureHelper.buildClientFromCredentialsId(azureCredentialsId);

                ResourceGroup group = azureClient.resourceGroups().getByName(resourceGroupName);
                if (group == null) {
                    return FormValidation.error(Messages.ACSDeploymentContext_resourceGroupNotFound());
                }

                ContainerService container = azureClient
                        .containerServices()
                        .getByResourceGroup(resourceGroupName, getContainerServiceName(containerService));
                if (container == null) {
                    return FormValidation.error(Messages.ACSDeploymentContext_containerServiceNotFound());
                }

                if (!container.orchestratorType().toString().equalsIgnoreCase(getOrchestratorType(containerService))) {
                    return FormValidation.error(Messages.ACSDeploymentContext_containerServiceTypeMissMatch());
                }

                try {
                    JSchClient jschClient = new JSchClient(
                            container.masterFqdn(),
                            Constants.sshPort(container.orchestratorType()),
                            container.linuxRootUsername(),
                            getSshCredentials(sshCredentialsId), null);

                    jschClient.execRemote("ls");
                } catch (Exception e) {
                    return FormValidation.error(Messages.ACSDeploymentContext_sshFailure(e.getMessage()));
                }

                return FormValidation.ok(Messages.ACSDeploymentContext_validationSuccess());
            } catch (Exception e) {
                return FormValidation.error(Messages.ACSDeploymentContext_validationError(e.getMessage()));
            }
        }

        public ListBoxModel doFillResourceGroupNameItems(@QueryParameter final String azureCredentialsId) {
            ListBoxModel model = new ListBoxModel();

            if (StringUtils.isBlank(azureCredentialsId)
                    || Constants.INVALID_OPTION.equals(azureCredentialsId)) {
                model.add(Messages.ACSDeploymentContext_selectAzureCredentialsFirst(), Constants.INVALID_OPTION);
                return model;
            }

            try {
                AzureCredentials.ServicePrincipal servicePrincipal =
                        AzureCredentials.getServicePrincipal(azureCredentialsId);
                if (StringUtils.isEmpty(servicePrincipal.getClientId())) {
                    model.add(Messages.ACSDeploymentContext_selectAzureCredentialsFirst(), Constants.INVALID_OPTION);
                    return model;
                }

                Azure azureClient = AzureHelper.buildClientFromServicePrincipal(servicePrincipal);
                for (ResourceGroup resourceGroup : azureClient.resourceGroups().list()) {
                    model.add(resourceGroup.name());
                }
            } catch (Exception ex) {
                model.add(
                        Messages.ACSDeploymentContext_failedToLoadResourceGroups(ex.getMessage()),
                        Constants.INVALID_OPTION);
            }

            if (model.isEmpty()) {
                model.add(Messages.ACSDeploymentContext_noResourceGroupFound(), Constants.INVALID_OPTION);
            }

            return model;
        }

        public ListBoxModel doFillContainerServiceItems(
                @QueryParameter final String azureCredentialsId,
                @QueryParameter final String resourceGroupName) {
            ListBoxModel model = new ListBoxModel();

            if (StringUtils.isBlank(azureCredentialsId)
                    || Constants.INVALID_OPTION.equals(azureCredentialsId)
                    || StringUtils.isBlank(resourceGroupName)
                    || Constants.INVALID_OPTION.equals(resourceGroupName)) {
                model.add(
                        Messages.ACSDeploymentContext_selectAzureCredentialsAndGroupFirst(),
                        Constants.INVALID_OPTION);
                return model;
            }

            try {
                AzureCredentials.ServicePrincipal servicePrincipal =
                        AzureCredentials.getServicePrincipal(azureCredentialsId);
                if (StringUtils.isEmpty(servicePrincipal.getClientId())) {
                    model.add(
                            Messages.ACSDeploymentContext_selectAzureCredentialsAndGroupFirst(),
                            Constants.INVALID_OPTION);
                    return model;
                }

                Azure azureClient = AzureHelper.buildClientFromServicePrincipal(servicePrincipal);

                PagedList<ContainerService> containerServices =
                        azureClient.containerServices().listByResourceGroup(resourceGroupName);
                for (ContainerService containerService : containerServices) {
                    ContainerServiceOchestratorTypes orchestratorType = containerService.orchestratorType();
                    if (Constants.SUPPORTED_ORCHESTRATOR.contains(orchestratorType)) {
                        String value = String.format("%s | %s",
                                containerService.name(), containerService.orchestratorType());
                        model.add(value);
                    }
                }
            } catch (Exception ex) {
                model.add(
                        Messages.ACSDeploymentContext_failedToLoadContainerServices(ex.getMessage()),
                        Constants.INVALID_OPTION);
            }

            if (model.isEmpty()) {
                model.add(Messages.ACSDeploymentContext_noContainerServiceFound(), Constants.INVALID_OPTION);
            }

            return model;
        }

        public FormValidation doCheckConfigFilePaths(@QueryParameter final String value) {
            if (value == null || value.length() == 0) {
                return FormValidation.error(Messages.ACSDeploymentContext_configFilePathsRequired());
            }

            return FormValidation.ok();
        }

        public ListBoxModel doFillSshCredentialsIdItems(@AncestorInPath final Item owner) {
            List<SSHUserPrivateKey> credentials;
            if (owner == null) {
                credentials = CredentialsProvider.lookupCredentials(
                        SSHUserPrivateKey.class,
                        Jenkins.getInstance(),
                        ACL.SYSTEM,
                        Collections.<DomainRequirement>emptyList());
            } else {
                credentials = CredentialsProvider.lookupCredentials(
                        SSHUserPrivateKey.class,
                        owner,
                        ACL.SYSTEM,
                        Collections.<DomainRequirement>emptyList());
            }
            ListBoxModel m = new StandardListBoxModel().withAll(credentials);
            return m;
        }

        public FormValidation doCheckSecretName(
                @QueryParameter final String containerService,
                @QueryParameter final String value) {
            String name = StringUtils.trimToEmpty(value);
            if (StringUtils.isEmpty(name)) {
                return FormValidation.ok();
            }

            ContainerServiceOchestratorTypes orchestratorType;
            try {
                orchestratorType = ContainerServiceOchestratorTypes.fromString(getOrchestratorType(containerService));
            } catch (IllegalArgumentException e) {
                // orchestrator type not determined, skip check
                return FormValidation.ok();
            }
            if (orchestratorType != ContainerServiceOchestratorTypes.KUBERNETES) {
                return FormValidation.ok();
            }

            String variableStripped = DeployHelper.removeVariables(name);
            if (variableStripped.length() > Constants.KUBERNETES_NAME_LENGTH_LIMIT) {
                return FormValidation.error(Messages.ACSDeploymentContext_secretNameTooLong());
            }

            // Replace the variables with a single character valid name "a", and then check if it matches the pattern.
            // This will check if the static part of the secret name is valid at the configuration time.
            String variableReplaced = DeployHelper.replaceVariables(name, "a");
            if (Constants.KUBERNETES_NAME_PATTERN.matcher(variableReplaced).matches()) {
                return FormValidation.ok();
            }

            return FormValidation.error(Messages.ACSDeploymentContext_secretNameNotMatch(
                    Constants.KUBERNETES_NAME_PATTERN.pattern()));
        }

        public FormValidation doCheckDcosDockerCredentialsPath(
                @QueryParameter final String containerService,
                @QueryParameter final String value) {
            String path = StringUtils.trimToEmpty(value);
            if (StringUtils.isEmpty(path)) {
                return FormValidation.ok();
            }

            ContainerServiceOchestratorTypes orchestratorType;
            try {
                orchestratorType = ContainerServiceOchestratorTypes.fromString(getOrchestratorType(containerService));
            } catch (IllegalArgumentException e) {
                // orchestrator type not determined, skip check
                return FormValidation.ok();
            }
            if (orchestratorType != ContainerServiceOchestratorTypes.DCOS) {
                return FormValidation.ok();
            }

            char first = path.charAt(0);
            if (first != '/' && first != '$') {
                return FormValidation.error(Messages.ACSDeploymentContext_onlyAbsolutePathAllowed());
            }

            String variableStripped = DeployHelper.removeVariables(path);
            if (StringUtils.isEmpty(variableStripped)) {
                // the path is constructed purely by variables, we cannot determine its value now.
                return FormValidation.ok();
            }
            if (!DeployHelper.checkURIForMarathon(variableStripped)) {
                // Return warnings instead error in case Marathon fixed this in future.
                return FormValidation.ok(Messages.ACSDeploymentContext_uriNotAccepted());
            }

            return FormValidation.ok();
        }

        public String getDefaultKubernetesNamespace() {
            return "default";
        }

        public String getDefaultRunOn() {
            return "Success";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return ImmutableSet.of(Run.class, FilePath.class, Launcher.class, TaskListener.class);
        }

        @Override
        public String getFunctionName() {
            return "acsDeploy";
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return Messages.plugin_displayName();
        }
    }
}
