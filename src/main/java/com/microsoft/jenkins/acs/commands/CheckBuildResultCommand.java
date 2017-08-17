/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.azurecommons.JobContext;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import com.microsoft.jenkins.azurecommons.command.IBaseCommandData;
import com.microsoft.jenkins.azurecommons.command.ICommand;
import hudson.model.Result;

/**
 * Checks the current build result and determines if we need to start the ACS deployment.
 */
public class CheckBuildResultCommand implements ICommand<CheckBuildResultCommand.ICheckBuildResultCommandData> {
    @Override
    public void execute(ICheckBuildResultCommandData context) {
        final JobContext jobContext = context.getJobContext();
        final RunOn runOn = context.getRunOnOption();
        final Result result = jobContext.getRun().getResult();

        if (runOn.shouldRunOn(result)) {
            context.logStatus(Messages.CheckBuildResultCommand_continue(result));
            context.setCommandState(CommandState.Success);
        } else {
            context.logStatus(Messages.CheckBuildResultCommand_abort(result));
            context.setCommandState(CommandState.Done);
        }
    }

    public interface ICheckBuildResultCommandData extends IBaseCommandData {
        RunOn getRunOnOption();
    }
}
