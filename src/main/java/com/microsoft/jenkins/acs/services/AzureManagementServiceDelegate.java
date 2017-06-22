/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.azure.PagedList;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.appservice.ProvisioningState;
import com.microsoft.azure.management.resources.DeploymentMode;
import com.microsoft.azure.management.resources.DeploymentOperation;
import com.microsoft.azure.management.resources.Deployments;
import com.microsoft.azure.management.resources.TargetResource;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.jenkins.acs.commands.IBaseCommandData;
import com.microsoft.jenkins.acs.exceptions.AzureCloudException;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.DependencyMigration;
import org.apache.commons.lang.StringUtils;

import java.io.InputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AzureManagementServiceDelegate {

    private static final Logger LOGGER = Logger.getLogger(AzureManagementServiceDelegate.class.getName());

    public static String verifyCredentials(final String azureCredentialsId) {
        Callable<String> task = new Callable<String>() {
            @Override
            public String call() throws Exception {
                AzureCredentials.ServicePrincipal servicePrincipal = AzureCredentials.getServicePrincipal(azureCredentialsId);
                if (StringUtils.isBlank(servicePrincipal.getClientId())) {
                    return "Failure: Cannot get the service principal from Azure credentials ID " + azureCredentialsId;
                }

                Azure azureClient = Azure.authenticate(DependencyMigration.buildAzureTokenCredentials(servicePrincipal)).withSubscription(servicePrincipal.getSubscriptionId());
                azureClient.storageAccounts().checkNameAvailability("CI_SYSTEM");
                return Constants.OP_SUCCESS;
            }
        };

        ExecutorService service = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = service.submit(task);
            service.shutdown();
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.log(Level.SEVERE, "Error validating configuration", e);
            return "Failure: Exception occured while validating subscription configuration " + e;
        } finally {
            service.shutdown();
        }
    }

    public static String deploy(final IARMTemplateServiceData azureServiceData)
            throws AzureCloudException {
        try {
            final Azure azureClient = azureServiceData.getAzureClient();

            final long ts = System.currentTimeMillis();
            final InputStream embeddedTemplate;

            // check if a custom image id has been provided otherwise work with publisher and offer
            LOGGER.log(Level.INFO, "Use embedded deployment template {0}", azureServiceData.getEmbeddedTemplateName());
            embeddedTemplate = AzureManagementServiceDelegate.class.getResourceAsStream(azureServiceData.getEmbeddedTemplateName());

            final ObjectMapper mapper = new ObjectMapper();
            final JsonNode tmp = mapper.readTree(embeddedTemplate);

            azureServiceData.configureTemplate(tmp);

            final String deploymentName = String.valueOf(ts);
            azureClient.deployments()
                    .define(deploymentName)
                    .withExistingResourceGroup(azureServiceData.getResourceGroupName())
                    .withTemplate(tmp.toString())
                    .withParameters("{}")
                    .withMode(DeploymentMode.INCREMENTAL)
                    .create();
            return deploymentName;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "AzureManagementServiceDelegate: deployment: Unable to deploy", e);
            throw new AzureCloudException(e);
        }
    }

    public static void validateAndAddFieldValue(String type,
                                                String fieldValue,
                                                String fieldName,
                                                String errorMessage,
                                                JsonNode tmp)
            throws AzureCloudException, IllegalAccessException {
        if (StringUtils.isNotBlank(fieldValue)) {
            // Add count variable for loop....
            final ObjectMapper mapper = new ObjectMapper();
            final ObjectNode parameter = mapper.createObjectNode();
            parameter.put("type", type);
            if ("int".equals(type)) {
                parameter.put("defaultValue", Integer.parseInt(fieldValue));
            } else {
                parameter.put("defaultValue", fieldValue);
            }
            ObjectNode.class.cast(tmp.get("parameters")).replace(fieldName, parameter);
        } else if (StringUtils.isBlank(errorMessage)) {
            throw new AzureCloudException(errorMessage);
        }
    }

    public static boolean monitor(
            Deployments deployments,
            String resourceGroupName,
            String deploymentName,
            IBaseCommandData baseCommandData) {
        int completed = 0;
        do {
            PagedList<DeploymentOperation> ops;
            try {
                ops = deployments.getByResourceGroup(resourceGroupName, deploymentName).deploymentOperations().list();
            } catch (RuntimeException e) {
                LOGGER.log(Level.INFO, "Failed getting deployment operations" + e.getMessage());
                baseCommandData.logError("Failed getting deployment operations" + e.getMessage());
                return false;
            }

            completed = ops.size();
            for (DeploymentOperation op : ops) {
                TargetResource targetResource = op.targetResource();
                if (targetResource == null) {
                    // a deployment operation with null target resource may be returned, skip it
                    --completed;
                    continue;
                }
                final String resource = targetResource.resourceName();
                final String type = targetResource.resourceType();
                final ProvisioningState state = ProvisioningState.fromString(op.provisioningState());

                switch (state) {
                    case CANCELED:
                    case FAILED:
                    case DELETING:
                        LOGGER.log(Level.INFO, "Failed({0}): {1}:{2}", new Object[]{state, type, resource});
                        baseCommandData.logError(String.format("Failed(%s): %s:%s", state, type, resource));
                        return false;
                    case SUCCEEDED:
                        baseCommandData.logStatus(
                                String.format("Succeeded(%s): %s:%s", state, type, resource));
                        completed--;
                    default:
                        LOGGER.log(Level.INFO, "To Be Completed({0}): {1}:{2}", new Object[]{state, type, resource});
                        baseCommandData.logStatus(
                                String.format("To Be Completed(%s): %s:%s", state, type, resource));
                }
            }
        } while (completed != 0);

        return true;
    }
}
