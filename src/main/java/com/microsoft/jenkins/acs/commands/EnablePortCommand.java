/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.management.Azure;
import com.microsoft.jenkins.acs.exceptions.AzureCloudException;
import com.microsoft.jenkins.acs.util.JsonHelper;
import com.microsoft.jenkins.acs.util.NetworkResourceProviderHelper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

public class EnablePortCommand implements ICommand<EnablePortCommand.IEnablePortCommandData> {
    public void execute(IEnablePortCommandData context) {
        File marathonConfigFile = context.getLocalMarathonConfigFile();
        Azure azureClient = context.getAzureClient();
        String resourceGroupName = context.getResourceGroupName();
        String dnsNamePrefix = context.getDnsNamePrefix();
        try {
            ArrayList<Integer> hostPorts =
                    JsonHelper.getHostPorts(marathonConfigFile.getAbsolutePath());
            context.logStatus("Enabling ports");
            for (Integer hPort : hostPorts) {
                boolean retVal = NetworkResourceProviderHelper.createSecurityGroup(context, azureClient, resourceGroupName, dnsNamePrefix, hPort);
                if (retVal) {
                    retVal = NetworkResourceProviderHelper.createLoadBalancerRule(context, azureClient, resourceGroupName, dnsNamePrefix, hPort);
                    if (!retVal) {
                        throw new AzureCloudException("Error enabling port:" + hPort + ".  Unknown status of other ports.");
                    }
                } else {
                    throw new AzureCloudException("Error enabling port:" + hPort + ".  Unknown status of other ports.");
                }
            }

            context.setDeploymentState(DeploymentState.Success);
        } catch (InterruptedException | IOException | AzureCloudException e) {
            context.logError(e);
        }
    }

    public interface IEnablePortCommandData extends IBaseCommandData {
        String getDnsNamePrefix();

        File getLocalMarathonConfigFile();
    }
}
