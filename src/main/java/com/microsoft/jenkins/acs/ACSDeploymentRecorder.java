/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.jenkins.acs.services.AzureManagementServiceDelegate;
import com.microsoft.jenkins.acs.util.Constants;
import hudson.FilePath;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;

import com.microsoft.jenkins.acs.exceptions.AzureCloudException;
import com.microsoft.jenkins.acs.services.CommandService;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;

public class ACSDeploymentRecorder extends Recorder implements SimpleBuildStep {
    private ACSDeploymentContext context;
    private final String azureCredentialsId;

    private transient AzureCredentials.ServicePrincipal servicePrincipal;

    @DataBoundConstructor
    public ACSDeploymentRecorder(
            final ACSDeploymentContext context,
            final String azureCredentialsId) {
        this.context = context;
        this.azureCredentialsId = azureCredentialsId;
    }

    public ACSDeploymentContext getContext() {
        return this.context;
    }

    public String getAzureCredentialsId() {
        return azureCredentialsId;
    }

    public AzureCredentials.ServicePrincipal getServicePrincipal() {
        if (servicePrincipal == null) {
            servicePrincipal = AzureCredentials.getServicePrincipal(getAzureCredentialsId());
        }
        return servicePrincipal;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        // TODO Auto-generated method stub
        return BuildStepMonitor.NONE;
    }

    @Override
    public boolean needsToRunAfterFinalized() {
        /*
         * This should be run before the build is finalized.
         */
        return false;
    }

    @Override
    public void perform(
            @Nonnull final Run<?, ?> run,
            @Nonnull final FilePath workspace,
            @Nonnull final Launcher launcher,
            @Nonnull final TaskListener listener) throws IOException, InterruptedException {
        listener.getLogger().println("Starting Azure Container Service Deployment");

        try {
            this.context.configure(listener, new FilePath(launcher.getChannel(), workspace.getRemote()), getServicePrincipal());
        } catch (AzureCloudException ex) {
            listener.error("Error configuring deployment context: " + ex.getMessage());
            ex.printStackTrace();
        }

        CommandService.executeCommands(context);

        if (context.getHasError()) {
            listener.getLogger().println("ERROR: Azure Container Service deployment ended with " + context.getDeploymentState());
            run.setResult(Result.FAILURE);
        } else {
            listener.getLogger().println("Done Azure Container Service Deployment");
        }
    }

    /**
     * Descriptor for ACSDeployRecorderDescriptor. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     * <p>
     * <p>
     * See <tt>src/main/resources/hudson/plugins/hello_world/HelloWorldBuilder/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Azure Container Service Configuration";
        }

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
            ListBoxModel model = new StandardListBoxModel().withAll(credentials);
            return model;
        }

        public FormValidation doVerifyConfiguration(@QueryParameter String azureCredentialsId) {
            if (StringUtils.isBlank(azureCredentialsId)) {
                return FormValidation.error("Error: no Azure credentials are selected");
            }

            String response = AzureManagementServiceDelegate.verifyCredentials(azureCredentialsId);

            if (Constants.OP_SUCCESS.equalsIgnoreCase(response)) {
                return FormValidation.ok("Success");
            } else {
                return FormValidation.error(response);
            }
        }
    }
}
