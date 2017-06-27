/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs;

import com.microsoft.jenkins.acs.commands.DeploymentState;
import com.microsoft.jenkins.acs.commands.IBaseCommandData;
import com.microsoft.jenkins.acs.commands.ICommand;
import com.microsoft.jenkins.acs.commands.TransitionInfo;
import com.microsoft.jenkins.acs.services.ICommandServiceData;

import java.util.Hashtable;

public abstract class AbstractBaseContext implements ICommandServiceData {
    private transient JobContext jobContext;
    private transient DeploymentState deployState = DeploymentState.Unknown;
    private transient Hashtable<Class, TransitionInfo> commands;
    private transient Class startCommandClass;

    protected void configure(
            JobContext jobContext,
            Hashtable<Class, TransitionInfo> commands,
            Class startCommandClass) {
        this.jobContext = jobContext;
        this.commands = commands;
        this.startCommandClass = startCommandClass;
    }

    @Override
    public Hashtable<Class, TransitionInfo> getCommands() {
        return commands;
    }

    @Override
    public Class getStartCommandClass() {
        return startCommandClass;
    }

    @Override
    public abstract IBaseCommandData getDataForCommand(ICommand command);

    public void setDeploymentState(DeploymentState deployState) {
        this.deployState = deployState;
    }

    public DeploymentState getDeploymentState() {
        return this.deployState;
    }

    public boolean getHasError() {
        return this.deployState.equals(DeploymentState.HasError);
    }

    public boolean getIsFinished() {
        return this.deployState.equals(DeploymentState.HasError) ||
                this.deployState.equals(DeploymentState.Done);
    }

    public final JobContext jobContext() {
        return jobContext;
    }

    public void logStatus(String status) {
        jobContext().getTaskListener().getLogger().println(status);
    }

    public void logError(Exception ex) {
        this.logError("Error: ", ex);
    }

    public void logError(String prefix, Exception ex) {
        jobContext().getTaskListener().error(prefix + ex.getMessage());
        ex.printStackTrace();
        this.deployState = DeploymentState.HasError;
    }

    public void logError(String message) {
        jobContext().getTaskListener().error(message);
        this.deployState = DeploymentState.HasError;
    }
}
