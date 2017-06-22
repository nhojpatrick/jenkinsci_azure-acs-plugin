/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.jenkins.acs.commands.DeploymentState;
import com.microsoft.jenkins.acs.commands.EnablePortCommand;
import com.microsoft.jenkins.acs.commands.GetPublicFQDNCommand;
import com.microsoft.jenkins.acs.commands.IBaseCommandData;
import com.microsoft.jenkins.acs.commands.ICommand;
import com.microsoft.jenkins.acs.commands.MarathonDeploymentCommand;
import com.microsoft.jenkins.acs.commands.ResourceGroupCommand;
import com.microsoft.jenkins.acs.commands.TemplateDeployCommand;
import com.microsoft.jenkins.acs.commands.TemplateMonitorCommand;
import com.microsoft.jenkins.acs.commands.TransitionInfo;
import com.microsoft.jenkins.acs.commands.ValidateContainerCommand;
import com.microsoft.jenkins.acs.exceptions.AzureCloudException;
import com.microsoft.jenkins.acs.services.AzureManagementServiceDelegate;
import com.microsoft.jenkins.acs.services.IARMTemplateServiceData;
import com.microsoft.jenkins.acs.util.DependencyMigration;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Hashtable;

public class ACSDeploymentContext extends AbstractBaseContext
        implements ResourceGroupCommand.IResourceGroupCommandData,
        ValidateContainerCommand.IValidateContainerCommandData,
        GetPublicFQDNCommand.IGetPublicFQDNCommandData,
        EnablePortCommand.IEnablePortCommandData,
        MarathonDeploymentCommand.IMarathonDeploymentCommandData,
        TemplateDeployCommand.ITemplateDeployCommandData,
        TemplateMonitorCommand.ITemplateMonitorCommandData,
        IARMTemplateServiceData,
        Describable<ACSDeploymentContext> {

    private transient AzureCredentials.ServicePrincipal servicePrincipal;
    private Azure azureClient;
    private String deploymentName;
    private String mgmtFQDN;
    private String dnsNamePrefix;
    private String agentCount;
    private String agentVMSize;
    private String linuxAdminUsername;
    private String masterCount;
    private String sshRSAPublicKey;
    private String marathonConfigFile;
    private String sshKeyFilePassword;
    private String sshKeyFileLocation;
    private String location;
    private String orchestratorType;

    private transient File localMarathonConfigFile;

    private static final String EMBEDDED_TEMPLATE_FILENAME = "/templateValue.json";

    public ACSDeploymentContext() {
        this.location = "West US";
    }

    @DataBoundConstructor
    public ACSDeploymentContext(
            final String dnsNamePrefix,
            final String agentCount,
            final String agentVMSize,
            final String linuxAdminUsername,
            final String masterCount,
            final String sshRSAPublicKey,
            final String marathonConfigFile,
            final String sshKeyFilePassword,
            final String sshKeyFileLocation,
            final String location) {
        this.dnsNamePrefix = dnsNamePrefix;
        this.agentCount = agentCount;
        this.agentVMSize = agentVMSize;
        this.linuxAdminUsername = linuxAdminUsername;
        this.orchestratorType = "DCOS";
        this.masterCount = masterCount;
        this.sshRSAPublicKey = sshRSAPublicKey;
        this.marathonConfigFile = marathonConfigFile;
        this.sshKeyFilePassword = sshKeyFilePassword;
        this.sshKeyFileLocation = sshKeyFileLocation;
        this.location = location;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Descriptor<ACSDeploymentContext> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    public String getDnsNamePrefix() {
        return this.dnsNamePrefix;
    }

    public String getAgentCount() {
        return this.agentCount;
    }

    public String getAgentVMSize() {
        return this.agentVMSize;
    }

    public String getLinuxAdminUsername() {
        return this.linuxAdminUsername;
    }

    public String getOrchestratorType() {
        return this.orchestratorType;
    }

    public String getMasterCount() {
        return this.masterCount;
    }

    public String getSshRSAPublicKey() {
        return this.sshRSAPublicKey;
    }

    public String getMarathonConfigFile() {
        return this.marathonConfigFile;
    }

    public String getSshKeyFileLocation() {
        return this.sshKeyFileLocation;
    }

    public String getSshKeyFilePassword() {
        return this.sshKeyFilePassword;
    }

    public String getLocation() {
        return this.location;
    }

    public void setDeploymentName(String deploymentName) {
        this.deploymentName = deploymentName;
    }

    public void setMgmtFQDN(String mgmtFQDN) {
        this.mgmtFQDN = mgmtFQDN;
    }

    public String getResourceGroupName() {
        return this.dnsNamePrefix;
    }

    public String getDeploymentName() {
        return this.deploymentName;
    }

    public String getMgmtFQDN() {
        return this.mgmtFQDN;
    }

    public File getLocalMarathonConfigFile() {
        return localMarathonConfigFile;
    }

    @Override
    public IBaseCommandData getDataForCommand(ICommand command) {
        return this;
    }

    @Override
    public Azure getAzureClient() {
        return this.azureClient;
    }

    public void configure(TaskListener listener, FilePath workspacePath, AzureCredentials.ServicePrincipal servicePrincipal) throws IOException, InterruptedException, AzureCloudException {
        this.servicePrincipal = servicePrincipal;
        this.azureClient = Azure.authenticate(DependencyMigration.buildAzureTokenCredentials(servicePrincipal)).withSubscription(servicePrincipal.getSubscriptionId());

        this.localMarathonConfigFile = File.createTempFile("marathon-", ".json", new File(System.getProperty("java.io.tmpdir")));
        FilePath[] files = workspacePath.list(marathonConfigFile);
        if (files.length < 1) {
            throw new IllegalArgumentException("Marathon configuration file is not found at " + marathonConfigFile);
        } else if (files.length > 1) {
            throw new IllegalArgumentException("Multiple Marathon configuration files were found at " + marathonConfigFile);
        }
        byte[] bytes = IOUtils.toByteArray(files[0].toURI());
        try (OutputStream out = new FileOutputStream(localMarathonConfigFile)) {
            out.write(bytes);
        }

        Hashtable<Class, TransitionInfo> commands = new Hashtable<Class, TransitionInfo>();
        commands.put(ResourceGroupCommand.class, new TransitionInfo(new ResourceGroupCommand(), ValidateContainerCommand.class, null));
        commands.put(ValidateContainerCommand.class, new TransitionInfo(new ValidateContainerCommand(), GetPublicFQDNCommand.class, TemplateDeployCommand.class));
        commands.put(TemplateDeployCommand.class, new TransitionInfo(new TemplateDeployCommand(), TemplateMonitorCommand.class, null));
        commands.put(TemplateMonitorCommand.class, new TransitionInfo(new TemplateMonitorCommand(), GetPublicFQDNCommand.class, null));
        commands.put(GetPublicFQDNCommand.class, new TransitionInfo(new GetPublicFQDNCommand(), MarathonDeploymentCommand.class, null));
        commands.put(MarathonDeploymentCommand.class, new TransitionInfo(new MarathonDeploymentCommand(), EnablePortCommand.class, null));
        commands.put(EnablePortCommand.class, new TransitionInfo(new EnablePortCommand(), null, null));
        super.configure(listener, commands, ResourceGroupCommand.class);
        this.setDeploymentState(DeploymentState.Running);
    }

    @Override
    public String getEmbeddedTemplateName() {
        return EMBEDDED_TEMPLATE_FILENAME;
    }

    @Override
    public void configureTemplate(JsonNode tmp) throws IllegalAccessException, AzureCloudException {
        if (StringUtils.isBlank(this.getDnsNamePrefix())) {
            throw new AzureCloudException(
                    String.format("Invalid DNS name prefix '%s'", this.dnsNamePrefix));
        }

        AzureManagementServiceDelegate.validateAndAddFieldValue("string", this.dnsNamePrefix, "dnsNamePrefix",
                String.format("Invalid DNS name prefix '%s'", this.getDnsNamePrefix()),
                tmp);
        AzureManagementServiceDelegate.validateAndAddFieldValue("int", this.agentCount, "agentCount", null, tmp);
        AzureManagementServiceDelegate.validateAndAddFieldValue("string", this.agentVMSize, "agentVMSize", null, tmp);
        AzureManagementServiceDelegate.validateAndAddFieldValue("string", this.linuxAdminUsername, "linuxAdminUsername", null, tmp);
        AzureManagementServiceDelegate.validateAndAddFieldValue("string", this.orchestratorType, "orchestratorType", null, tmp);
        AzureManagementServiceDelegate.validateAndAddFieldValue("int", this.masterCount, "masterCount", null, tmp);
        AzureManagementServiceDelegate.validateAndAddFieldValue("string", this.sshRSAPublicKey, "sshRSAPublicKey", null, tmp);
    }

    @Override
    public IARMTemplateServiceData getArmTemplateServiceData() {
        return this;
    }

    @Extension
    public static final class DescriptorImpl extends ACSDeploymentContextDescriptor {
    }
}
