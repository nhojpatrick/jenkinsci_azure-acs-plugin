/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.services;

import com.microsoft.jenkins.acs.commands.DeploymentState;
import com.microsoft.jenkins.acs.commands.IBaseCommandData;
import com.microsoft.jenkins.acs.commands.ICommand;
import com.microsoft.jenkins.acs.commands.INextCommandAware;
import com.microsoft.jenkins.acs.commands.TransitionInfo;

import java.util.Hashtable;

public final class CommandService {
    public static boolean executeCommands(final ICommandServiceData commandServiceData) {
        Class startCommand = commandServiceData.getStartCommandClass();
        Hashtable<Class, TransitionInfo> commands = commandServiceData.getCommands();
        if (!commands.isEmpty() && startCommand != null) {
            //successfully started
            TransitionInfo current = commands.get(startCommand);
            while (current != null) {
                ICommand<IBaseCommandData> command = current.getCommand();
                IBaseCommandData commandData = commandServiceData.getDataForCommand(command);
                command.execute(commandData);
                TransitionInfo previous = current;
                current = null;

                if (command instanceof INextCommandAware) {
                    Class next;
                    if (commandData.getDeploymentState() == DeploymentState.Success) {
                        next = ((INextCommandAware) command).getSuccess();
                    } else {
                        next = ((INextCommandAware) command).getFail();
                    }
                    if (next != null) {
                        current = commands.get(next);
                    }
                } else if (commandData.getDeploymentState() == DeploymentState.Success
                        && previous.getSuccess() != null) {
                    current = commands.get(previous.getSuccess());
                } else if (commandData.getDeploymentState() == DeploymentState.UnSuccessful
                        && previous.getFail() != null) {
                    current = commands.get(previous.getFail());
                } else if (commandData.getDeploymentState() == DeploymentState.HasError) {
                    return false;
                }
            }

            return true;
        }

        return false;
    }

    private CommandService() {
        // hide constructor
    }
}
