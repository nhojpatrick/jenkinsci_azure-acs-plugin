/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.microsoft.jenkins.acs.ACSDeploymentContext;
import com.microsoft.jenkins.acs.JobContext;
import hudson.model.Result;
import hudson.model.Run;
import org.junit.Test;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link CheckBuildResultCommand}.
 */
public class CheckBuildResultCommandTest {
    @Test
    public void testOnSuccessGotSuccess() {
        verifyState(RunOn.Success, Result.SUCCESS, DeploymentState.Success);
    }

    @Test
    public void testOnSuccessGotUnstable() {
        verifyState(RunOn.Success, Result.UNSTABLE, DeploymentState.Done);
    }

    @Test
    public void testOnSuccessGotFailure() {
        verifyState(RunOn.Success, Result.FAILURE, DeploymentState.Done);
    }

    @Test
    public void testOnSuccessOrUnstableGotSuccess() {
        verifyState(RunOn.SuccessOrUnstable, Result.SUCCESS, DeploymentState.Success);
    }

    @Test
    public void testOnSuccessOrUnstableGotUnstable() {
        verifyState(RunOn.SuccessOrUnstable, Result.UNSTABLE, DeploymentState.Success);
    }

    @Test
    public void testOnSuccessOrUnstableGotFailure() {
        verifyState(RunOn.SuccessOrUnstable, Result.FAILURE, DeploymentState.Done);
    }

    private void verifyState(
            final RunOn runOn,
            final Result result,
            final DeploymentState expectedState) {
        CheckBuildResultCommand.ICheckBuildResultCommandData context = prepareContext(runOn, result);
        new CheckBuildResultCommand().execute(context);

        verify(context, times(1)).setDeploymentState(expectedState);
    }

    private CheckBuildResultCommand.ICheckBuildResultCommandData prepareContext(
            final RunOn runOn,
            final Result result) {
        CheckBuildResultCommand.ICheckBuildResultCommandData context = mock(ACSDeploymentContext.class);

        doReturn(runOn).when(context).getRunOnOption();

        JobContext jobContext = mock(JobContext.class);
        Run<?, ?> run = mock(Run.class);
        doReturn(jobContext).when(context).jobContext();
        doReturn(run).when(jobContext).getRun();
        doReturn(result).when(run).getResult();

        return context;
    }
}
