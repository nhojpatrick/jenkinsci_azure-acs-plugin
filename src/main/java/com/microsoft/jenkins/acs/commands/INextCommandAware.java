package com.microsoft.jenkins.acs.commands;

/**
 * Indicates that a command is aware of its next command to be executed based on its execution status.
 */
public interface INextCommandAware {
    Class<? extends ICommand<? extends IBaseCommandData>> getNextCommand();
}
