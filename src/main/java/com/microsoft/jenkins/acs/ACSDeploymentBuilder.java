/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Builder;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;

public class ACSDeploymentBuilder extends Builder implements SimpleBuildStep {
    private ACSDeploymentContext context;

    @DataBoundConstructor
    public ACSDeploymentBuilder(ACSDeploymentContext context) {
        this.context = context;
    }

    public ACSDeploymentContext getContext() {
        return this.context;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Override
    public void perform(
            @Nonnull Run<?, ?> run,
            @Nonnull FilePath workspace,
            @Nonnull Launcher launcher,
            @Nonnull TaskListener listener) throws IOException, InterruptedException {
        listener.getLogger().println(Messages.ACSDeploymentBuilder_starting());
        this.context.configure(run, workspace, launcher, listener);
        this.context.executeCommands();

        if (context.getLastCommandState().isError()) {
            listener.getLogger().println(
                    Messages.ACSDeploymentBuilder_endWithErrorState(context.getCommandState()));
            run.setResult(Result.FAILURE);
        } else {
            listener.getLogger().println(Messages.ACSDeploymentBuilder_finished());
        }

    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return Messages.plugin_displayName();
        }
    }
}
