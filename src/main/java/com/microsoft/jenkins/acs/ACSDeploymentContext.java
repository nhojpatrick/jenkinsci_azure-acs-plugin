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
import com.microsoft.jenkins.acs.exceptions.AzureCloudException;
import com.microsoft.jenkins.acs.util.AzureHelper;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.DependencyMigration;
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
    private final String containerServiceName;
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
            final String containerServiceName,
            final String sshCredentialsId,
            final String kubernetesNamespace,
            final String configFilePaths,
            final boolean enableConfigSubstitution) {
        this.azureCredentialsId = azureCredentialsId;
        this.resourceGroupName = resourceGroupName;
        this.containerServiceName = containerServiceName;
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
    public void setMgmtFQDN(String mgmtFQDN) {
        this.mgmtFQDN = mgmtFQDN;
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
    public IBaseCommandData getDataForCommand(ICommand command) {
        return this;
    }

    @Override
    public String getLinuxAdminUsername() {
        return linuxAdminUsername;
    }

    @Override
    public void setLinuxRootUsername(String linuxAdminUsername) {
        this.linuxAdminUsername = linuxAdminUsername;
    }

    @Override
    public ContainerServiceOchestratorTypes getOrchestratorType() {
        return orchestratorType;
    }

    @Override
    public void setOrchestratorType(ContainerServiceOchestratorTypes orchestratorType) {
        this.orchestratorType = orchestratorType;
    }

    @Override
    public String getContainerServiceName() {
        return containerServiceName;
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
            @Nonnull final TaskListener listener) throws IOException, InterruptedException, AzureCloudException {
        this.azureClient = AzureHelper.buildClientFromCredentialsId(getAzureCredentialsId());

        Hashtable<Class, TransitionInfo> commands = new Hashtable<>();
        commands.put(GetContainserServiceInfoCommand.class, new TransitionInfo(new GetContainserServiceInfoCommand(), DeploymentChoiceCommand.class, null));

        // DeploymentChoiceCommand will point out the next step through INextCommandAware
        commands.put(DeploymentChoiceCommand.class, new TransitionInfo(new DeploymentChoiceCommand(), null, null));

        // ACS with Kubernetes will add a security rule for the service port automatically, so no need to manually create one to enable the port access
        commands.put(KubernetesDeploymentCommand.class, new TransitionInfo(new KubernetesDeploymentCommand(), null, null));

        commands.put(MarathonDeploymentCommand.class, new TransitionInfo(new MarathonDeploymentCommand(), EnablePortCommand.class, null));
        commands.put(EnablePortCommand.class, new TransitionInfo(new EnablePortCommand(), null, null));
        super.configure(new JobContext(run, workspace, launcher, listener), commands, GetContainserServiceInfoCommand.class);
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
            model.add("--- Select Azure Credentials ---", Constants.INVALID_OPTION);
            model.withAll(credentials);
            return model;
        }

        public FormValidation doVerifyConfiguration(@QueryParameter String azureCredentialsId) {
            if (StringUtils.isBlank(azureCredentialsId)) {
                return FormValidation.error("Error: no Azure credentials are selected");
            }
            try {
                AzureCredentials.ServicePrincipal servicePrincipal = AzureCredentials.getServicePrincipal(azureCredentialsId);
                if (StringUtils.isBlank(servicePrincipal.getClientId())) {
                    return FormValidation.error("Cannot get the service principal from the selected Azure credentials ID");
                }

                Azure azureClient = Azure.authenticate(DependencyMigration.buildAzureTokenCredentials(servicePrincipal)).withSubscription(servicePrincipal.getSubscriptionId());
                azureClient.storageAccounts().checkNameAvailability("CI_SYSTEM");
                return FormValidation.ok(Constants.OP_SUCCESS);
            } catch (Exception e) {
                return FormValidation.error("Error validating configuration: " + e.getMessage());
            }
        }

        public ListBoxModel doFillResourceGroupNameItems(@QueryParameter String azureCredentialsId) {
            ListBoxModel model = new ListBoxModel();

            if (StringUtils.isBlank(azureCredentialsId)
                    || Constants.INVALID_OPTION.equals(azureCredentialsId)) {
                model.add("--- select credentials first ---", Constants.INVALID_OPTION);
                return model;
            }

            try {
                AzureCredentials.ServicePrincipal servicePrincipal = AzureCredentials.getServicePrincipal(azureCredentialsId);
                if (StringUtils.isEmpty(servicePrincipal.getClientId())) {
                    model.add("--- select credentials first ---", Constants.INVALID_OPTION);
                    return model;
                }

                Azure azureClient = AzureHelper.buildClientFromServicePrincipal(servicePrincipal);
                for (ResourceGroup resourceGroup : azureClient.resourceGroups().list()) {
                    model.add(resourceGroup.name());
                }
            } catch (Exception ex) {
                model.add(String.format("*** Failed to load resource groups: %s ***", ex.getMessage()), Constants.INVALID_OPTION);
            }

            if (model.isEmpty()) {
                model.add("--- No resource groups found ---", Constants.INVALID_OPTION);
            }

            return model;
        }

        public ListBoxModel doFillContainerServiceNameItems(
                @QueryParameter final String azureCredentialsId,
                @QueryParameter final String resourceGroupName) {
            ListBoxModel model = new ListBoxModel();

            if (StringUtils.isBlank(azureCredentialsId)
                    || Constants.INVALID_OPTION.equals(azureCredentialsId)
                    || StringUtils.isBlank(resourceGroupName)
                    || Constants.INVALID_OPTION.equals(resourceGroupName)) {
                model.add("--- select credentials & resource group first ---", Constants.INVALID_OPTION);
                return model;
            }

            try {
                AzureCredentials.ServicePrincipal servicePrincipal =
                        AzureCredentials.getServicePrincipal(azureCredentialsId);
                if (StringUtils.isEmpty(servicePrincipal.getClientId())) {
                    model.add("--- select credentials & resource group first ---", Constants.INVALID_OPTION);
                    return model;
                }

                Azure azureClient = AzureHelper.buildClientFromServicePrincipal(servicePrincipal);

                PagedList<ContainerService> containerServices =
                        azureClient.containerServices().listByResourceGroup(resourceGroupName);
                for (ContainerService containerService : containerServices) {
                    ContainerServiceOchestratorTypes orchestratorType = containerService.orchestratorType();
                    if (orchestratorType == ContainerServiceOchestratorTypes.DCOS ||
                            orchestratorType == ContainerServiceOchestratorTypes.KUBERNETES) {
                        model.add(containerService.name());
                    }
                }
            } catch (Exception ex) {
                model.add(String.format("*** Failed to load resource groups: %s ***", ex.getMessage()), Constants.INVALID_OPTION);
            }

            if (model.isEmpty()) {
                model.add("--- No resource groups found ---", Constants.INVALID_OPTION);
            }

            return model;
        }

        public FormValidation doCheckConfigFilePaths(@QueryParameter String value) {
            if (value == null || value.length() == 0) {
                return FormValidation.error("Config file path(s) is required.");
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
