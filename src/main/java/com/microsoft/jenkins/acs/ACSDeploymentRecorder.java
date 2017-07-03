/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs;

import com.microsoft.jenkins.acs.services.CommandService;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;

public class ACSDeploymentRecorder extends Recorder implements SimpleBuildStep {
    private ACSDeploymentContext context;

    @DataBoundConstructor
    public ACSDeploymentRecorder(
            final ACSDeploymentContext context) {
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
        listener.getLogger().println(Messages.ACSDeploymentRecorder_starting());
        this.context.configure(
                run,
                workspace,
                launcher,
                listener);

        CommandService.executeCommands(context);

        if (context.getHasError()) {
            listener.getLogger().println(
                    Messages.ACSDeploymentRecorder_endWithErrorState(context.getDeploymentState()));
            run.setResult(Result.FAILURE);
        } else {
            listener.getLogger().println(Messages.ACSDeploymentRecorder_finished());
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

        public boolean isApplicable(final Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return Messages.ACSDeploymentRecorder_displayName();
        }
    }
}
