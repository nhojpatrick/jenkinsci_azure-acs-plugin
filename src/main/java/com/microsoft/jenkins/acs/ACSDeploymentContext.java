/**
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
import com.microsoft.azure.PagedList;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.ContainerService;
import com.microsoft.azure.management.compute.ContainerServiceOchestratorTypes;
import com.microsoft.azure.management.resources.ResourceGroup;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.jenkins.acs.commands.DeploymentChoiceCommand;
import com.microsoft.jenkins.acs.commands.DeploymentState;
import com.microsoft.jenkins.acs.commands.EnablePortCommand;
import com.microsoft.jenkins.acs.commands.GetContainserServiceInfoCommand;
import com.microsoft.jenkins.acs.commands.IBaseCommandData;
import com.microsoft.jenkins.acs.commands.ICommand;
import com.microsoft.jenkins.acs.commands.KubernetesDeploymentCommand;
import com.microsoft.jenkins.acs.commands.MarathonDeploymentCommand;
import com.microsoft.jenkins.acs.commands.TransitionInfo;
import com.microsoft.jenkins.acs.util.AzureHelper;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.JSchClient;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;

public class ACSDeploymentContext extends AbstractBaseContext
        implements GetContainserServiceInfoCommand.IGetContainserServiceInfoCommandData,
        EnablePortCommand.IEnablePortCommandData,
        MarathonDeploymentCommand.IMarathonDeploymentCommandData,
        KubernetesDeploymentCommand.IKubernetesDeploymentCommandData,
        DeploymentChoiceCommand.IDeploymentChoiceCommandData,
        Describable<ACSDeploymentContext> {

    private final String azureCredentialsId;
    private final String resourceGroupName;
    private final String containerService;
    private final String sshCredentialsId;
    private final String kubernetesNamespace;
    private final String configFilePaths;
    private final boolean enableConfigSubstitution;

    private transient Azure azureClient;
    private transient String mgmtFQDN;
    private transient String linuxAdminUsername;
    private transient ContainerServiceOchestratorTypes orchestratorType;
    private transient SSHUserPrivateKey sshCredentials;

    @DataBoundConstructor
    public ACSDeploymentContext(
            final String azureCredentialsId,
            final String resourceGroupName,
            final String containerService,
            final String sshCredentialsId,
            final String kubernetesNamespace,
            final String configFilePaths,
            final boolean enableConfigSubstitution) {
        this.azureCredentialsId = azureCredentialsId;
        this.resourceGroupName = resourceGroupName;
        this.containerService = containerService;
        this.sshCredentialsId = sshCredentialsId;
        this.kubernetesNamespace = kubernetesNamespace;
        this.configFilePaths = configFilePaths;
        this.enableConfigSubstitution = enableConfigSubstitution;
    }

    @Override
    public Descriptor<ACSDeploymentContext> getDescriptor() {
        return (DescriptorImpl) Jenkins.getInstance().getDescriptorOrDie(ACSDeploymentContext.class);
    }

    public String getAzureCredentialsId() {
        return azureCredentialsId;
    }

    @Override
    public String getConfigFilePaths() {
        return this.configFilePaths;
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

    @Override
    public ContainerServiceOchestratorTypes getOrchestratorType() {
        return orchestratorType;
    }

    @Override
    public void setOrchestratorType(final ContainerServiceOchestratorTypes type) {
        this.orchestratorType = type;
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
        return kubernetesNamespace;
    }

    @Override
    public boolean isEnableConfigSubstitution() {
        return enableConfigSubstitution;
    }

    public void configure(
            @Nonnull final Run<?, ?> run,
            @Nonnull final FilePath workspace,
            @Nonnull final Launcher launcher,
            @Nonnull final TaskListener listener) throws IOException {
        this.azureClient = AzureHelper.buildClientFromCredentialsId(getAzureCredentialsId());

        Hashtable<Class, TransitionInfo> commands = new Hashtable<>();
        commands.put(GetContainserServiceInfoCommand.class,
                new TransitionInfo(new GetContainserServiceInfoCommand(), DeploymentChoiceCommand.class, null));

        // DeploymentChoiceCommand will point out the next step through INextCommandAware
        commands.put(DeploymentChoiceCommand.class,
                new TransitionInfo(new DeploymentChoiceCommand(), null, null));

        // ACS with Kubernetes will add a security rule for the service port automatically,
        // so no need to manually create one to enable the port access
        commands.put(KubernetesDeploymentCommand.class,
                new TransitionInfo(new KubernetesDeploymentCommand(), null, null));

        commands.put(MarathonDeploymentCommand.class,
                new TransitionInfo(new MarathonDeploymentCommand(), EnablePortCommand.class, null));
        commands.put(EnablePortCommand.class,
                new TransitionInfo(new EnablePortCommand(), null, null));
        super.configure(
                new JobContext(run, workspace, launcher, listener),
                commands,
                GetContainserServiceInfoCommand.class);
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
            return null;
        }
        String[] parts = containerService.split("\\|");
        if (parts.length == 2) {
            return parts[1].trim();
        }
        return null;
    }

    public static String validate(
            final String azureCredentialsId,
            final String resourceGroup,
            final String containerService,
            final String sshCredentialsId,
            final String kubernetesNamespace) {
        AzureCredentials.ServicePrincipal servicePrincipal = AzureCredentials.getServicePrincipal(azureCredentialsId);

        if (StringUtils.isBlank(servicePrincipal.getSubscriptionId())) {
            return Messages.ACSDeploymentContext_missingCredentials();
        }
        if (StringUtils.isBlank(resourceGroup) || Constants.INVALID_OPTION.equals(resourceGroup)) {
            return Messages.ACSDeploymentContext_missingResourceGroup();
        }
        if (StringUtils.isBlank(containerService) || Constants.INVALID_OPTION.equals(containerService)) {
            return Messages.ACSDeploymentContext_missingContainerServiceName();
        }
        if (StringUtils.isBlank(sshCredentialsId) || getSshCredentials(sshCredentialsId) == null) {
            return Messages.ACSDeploymentContext_missingSSHCredentials();
        }

        ContainerServiceOchestratorTypes orchestratorType =
                ContainerServiceOchestratorTypes.fromString(getOrchestratorType(containerService));
        if (orchestratorType == null) {
            return Messages.ACSDeploymentContext_missingOrchestratorType();
        }

        if (!Constants.SUPPORTED_ORCHESTRATOR.contains(orchestratorType)) {
            return Messages.ACSDeploymentContext_orchestratorNotSupported(orchestratorType);
        }

        if (ContainerServiceOchestratorTypes.KUBERNETES == orchestratorType
                && StringUtils.isBlank(kubernetesNamespace)) {
            return Messages.ACSDeploymentContext_missingKubernetesNamespace();
        }
        return null;
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<ACSDeploymentContext> {
        public ListBoxModel doFillAzureCredentialsIdItems(@AncestorInPath final Item owner) {
            List<AzureCredentials> credentials;
            if (owner == null) {
                credentials = CredentialsProvider.lookupCredentials(
                        AzureCredentials.class,
                        Jenkins.getInstance(),
                        ACL.SYSTEM,
                        Collections.<DomainRequirement>emptyList()
                );
            } else {
                credentials = CredentialsProvider.lookupCredentials(
                        AzureCredentials.class,
                        owner,
                        ACL.SYSTEM,
                        Collections.<DomainRequirement>emptyList()
                );
            }
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
                    if (orchestratorType == ContainerServiceOchestratorTypes.DCOS
                            || orchestratorType == ContainerServiceOchestratorTypes.KUBERNETES) {
                        String name = String.format("%s | %s",
                                containerService.name(), containerService.orchestratorType());
                        String value = String.format("%s | %s",
                                containerService.name(), containerService.orchestratorType());
                        model.add(name, value);
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

        public String getDefaultKubernetesNamespace() {
            return "default";
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return null;
        }
    }
}
