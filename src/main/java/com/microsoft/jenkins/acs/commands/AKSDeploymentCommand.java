/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.resources.GenericResource;
import com.microsoft.azure.management.resources.fluentcore.arm.ResourceUtils;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.jenkins.acs.orchestrators.DeploymentConfig;
import com.microsoft.jenkins.acs.util.AzureHelper;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.DeployHelper;
import hudson.FilePath;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.io.OutputStream;

public class AKSDeploymentCommand
        extends KubernetesDeploymentCommandBase<AKSDeploymentCommand.IAKSDeploymentCommandData> {
    @Override
    public void execute(IAKSDeploymentCommandData context) {
        final String azureCredentialsId = context.getAzureCredentialsId();

        AKSDeployWorker deployer = new AKSDeployWorker();
        deployer.setServicePrincipal(AzureCredentials.getServicePrincipal(azureCredentialsId));
        deployer.setResourceGroupName(context.getResourceGroupName());
        deployer.setContainerServiceName(context.getContainerServiceName());

        doExecute(context, deployer);
    }

    static class AKSDeployWorker extends KubernetesDeployWorker {
        private AzureCredentials.ServicePrincipal servicePrincipal;
        private String resourceGroupName;
        private String containerServiceName;

        @Override
        protected FilePath[] resolveConfigFiles() throws IOException, InterruptedException {
            DeploymentConfig deploymentConfig = getConfigFactory().buildForAKS(getWorkspace(), getEnvVars());
            return deploymentConfig.getConfigFiles();
        }

        @Override
        protected void prepareKubeconfig(FilePath kubeconfigFile) throws Exception {
            AzureCredentials.ServicePrincipal sp = getServicePrincipal();
            Azure azureClient = AzureHelper.buildClientFromServicePrincipal(sp);
            String id = ResourceUtils.constructResourceId(
                    sp.getSubscriptionId(),
                    getResourceGroupName(),
                    Constants.AKS_PROVIDER,
                    Constants.AKS_RESOURCE_TYPE,
                    getContainerServiceName(),
                    "");
            // Cannot use #get(resourceGroup, provider, type, name) because it uses #list() internally which won't
            // return the properties.
            GenericResource resource = azureClient.genericResources().getById(id);
            Object properties = resource.properties();
            try {
                String userConfig = DeployHelper.getProperty(
                        properties, "accessProfiles.clusterUser.kubeConfig", String.class);
                if (StringUtils.isBlank(userConfig)) {
                    throw new IllegalStateException("Null user kubeconfig returned from Azure");
                }
                byte[] kubeconfig = Base64.decodeBase64(userConfig);
                try (OutputStream out = kubeconfigFile.write()) {
                    out.write(kubeconfig);
                }
            } catch (IllegalArgumentException | ClassCastException e) {
                throw new IllegalStateException("Failed to get kubeconfig", e);
            }
        }

        public AzureCredentials.ServicePrincipal getServicePrincipal() {
            return servicePrincipal;
        }

        public void setServicePrincipal(AzureCredentials.ServicePrincipal servicePrincipal) {
            this.servicePrincipal = servicePrincipal;
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
    }

    public interface IAKSDeploymentCommandData
            extends KubernetesDeploymentCommandBase.IKubernetesDeploymentCommandData {
        String getAzureCredentialsId();

        String getResourceGroupName();
    }
}
