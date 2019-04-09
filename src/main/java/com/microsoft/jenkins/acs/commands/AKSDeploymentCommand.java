/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.management.Azure;
import com.microsoft.jenkins.acs.orchestrators.DeploymentConfig;
import com.microsoft.jenkins.acs.util.AzureHelper;
import com.microsoft.jenkins.azurecommons.core.credentials.TokenCredentialData;
import hudson.FilePath;
import hudson.model.Item;
import org.apache.commons.lang3.ArrayUtils;

import java.io.IOException;
import java.io.OutputStream;

public class AKSDeploymentCommand
        extends KubernetesDeploymentCommandBase<AKSDeploymentCommand.IAKSDeploymentCommandData> {
    @Override
    public void execute(IAKSDeploymentCommandData context) {
        final Item owner = context.getJobContext().getOwner();
        final TokenCredentialData token = AzureHelper.getToken(owner, context.getAzureCredentialsId());

        AKSDeployWorker deployer = new AKSDeployWorker();
        deployer.setToken(token);
        deployer.setResourceGroupName(context.getResourceGroupName());
        deployer.setContainerServiceName(context.getContainerServiceName());

        doExecute(context, deployer);
    }

    static class AKSDeployWorker extends KubernetesDeployWorker {
        private TokenCredentialData token;
        private String resourceGroupName;
        private String containerServiceName;

        @Override
        protected FilePath[] resolveConfigFiles() throws IOException, InterruptedException {
            DeploymentConfig deploymentConfig = getConfigFactory().buildForAKS(getWorkspace(), getEnvVars());
            return deploymentConfig.getConfigFiles();
        }

        @Override
        protected void prepareKubeconfig(FilePath kubeconfigFile) throws Exception {
            Azure azureClient = AzureHelper.buildClient(token);
            byte[] adminKubeConfigContent = azureClient.kubernetesClusters()
                    .getAdminKubeConfigContent(getResourceGroupName(), getContainerServiceName());

            if (ArrayUtils.isEmpty(adminKubeConfigContent)) {
                throw new IllegalStateException("Null user kubeconfig returned from Azure");
            }
            try (OutputStream out = kubeconfigFile.write()) {
                out.write(adminKubeConfigContent);
            }
        }

        public String getResourceGroupName() {
            return resourceGroupName;
        }

        public void setResourceGroupName(String resourceGroupName) {
            this.resourceGroupName = resourceGroupName;
        }

        public String getContainerServiceName() {
            return containerServiceName;
        }

        public void setContainerServiceName(String containerServiceName) {
            this.containerServiceName = containerServiceName;
        }

        public TokenCredentialData getToken() {
            return token;
        }

        public void setToken(TokenCredentialData token) {
            this.token = token;
        }
    }

    public interface IAKSDeploymentCommandData
            extends KubernetesDeploymentCommandBase.IKubernetesDeploymentCommandData {
        String getAzureCredentialsId();

        String getResourceGroupName();
    }
}
