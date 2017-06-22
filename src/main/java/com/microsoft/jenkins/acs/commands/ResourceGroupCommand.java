/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.management.Azure;

public class ResourceGroupCommand implements ICommand<ResourceGroupCommand.IResourceGroupCommandData> {
    public void execute(ResourceGroupCommand.IResourceGroupCommandData context) {
        final String resourceGroupName = context.getResourceGroupName();
        final String location = context.getLocation();
        final Azure azureClient = context.getAzureClient();
        context.logStatus(String.format("Creating resource group '%s' if it does not exist", resourceGroupName));
        azureClient.resourceGroups().define(resourceGroupName).withRegion(location).create();

        context.setDeploymentState(DeploymentState.Success);
    }

    public interface IResourceGroupCommandData extends IBaseCommandData {
        String getLocation();
    }
}
