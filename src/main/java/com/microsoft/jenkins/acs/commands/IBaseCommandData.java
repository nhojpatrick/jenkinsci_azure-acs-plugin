/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.management.Azure;
import com.microsoft.jenkins.acs.JobContext;

public interface IBaseCommandData {
    void logError(String message);

    void logStatus(String status);

    void logError(Exception ex);

    void logError(String prefix, Exception ex);

    void setDeploymentState(DeploymentState deployState);

    JobContext jobContext();

    DeploymentState getDeploymentState();

    Azure getAzureClient();

    String getResourceGroupName();

    String getContainerServiceName();
}
