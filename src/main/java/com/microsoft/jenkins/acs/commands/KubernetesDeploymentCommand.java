/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.google.common.annotations.VisibleForTesting;
import com.microsoft.azure.management.compute.ContainerServiceOchestratorTypes;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.orchestrators.DeploymentConfig;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.azurecommons.EnvironmentInjector;
import com.microsoft.jenkins.azurecommons.JobContext;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import com.microsoft.jenkins.azurecommons.command.IBaseCommandData;
import com.microsoft.jenkins.azurecommons.command.ICommand;
import com.microsoft.jenkins.azurecommons.remote.SSHClient;
import com.microsoft.jenkins.kubernetes.KubernetesClientWrapper;
import com.microsoft.jenkins.kubernetes.credentials.ResolvedDockerRegistryEndpoint;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Util;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.util.VariableResolver;
import io.fabric8.kubernetes.client.KubernetesClient;
import jenkins.security.MasterToSlaveCallable;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command to deploy Kubernetes configurations to Azure Container Service.
 */
public class KubernetesDeploymentCommand
        implements ICommand<KubernetesDeploymentCommand.IKubernetesDeploymentCommandData>, Serializable {
    private static final long serialVersionUID = 1L;

    @Override
    public void execute(IKubernetesDeploymentCommandData context) {
        JobContext jobContext = context.getJobContext();
        final FilePath workspace = jobContext.getWorkspace();
        final TaskListener taskListener = jobContext.getTaskListener();
        final EnvVars envVars = context.getEnvVars();
        final String managementFqdn = context.getMgmtFQDN();
        final SSHUserPrivateKey sshCredentials = context.getSshCredentials();
        final String secretNameCfg = context.getSecretName();
        final String defaultSecretName = jobContext.getRun().getDisplayName();
        final String kubernetesNamespaceCfg = Util.replaceMacro(context.getSecretNamespace(), envVars).trim();
        final boolean enableSubstitution = context.isEnableConfigSubstitution();
        final DeploymentConfig.Factory configFactory = new DeploymentConfig.Factory(context.getConfigFilePaths());
        final ContainerServiceOchestratorTypes orchestratorType = context.getOrchestratorType();

        try {
            final List<ResolvedDockerRegistryEndpoint> registryCredentials =
                    context.resolvedDockerRegistryEndpoints(jobContext.getRun().getParent());

            TaskResult taskResult = workspace.act(new MasterToSlaveCallable<TaskResult, Exception>() {
                @Override
                public TaskResult call() throws Exception {
                    TaskResult result = new TaskResult();
                    PrintStream logger = taskListener.getLogger();

                    DeploymentConfig deploymentConfig = configFactory.build(orchestratorType, workspace, envVars);
                    FilePath[] configFiles = deploymentConfig.getConfigFiles();

                    SSHClient sshClient = new SSHClient(managementFqdn, Constants.KUBERNETES_SSH_PORT, sshCredentials)
                            .withLogger(logger);
                    FilePath kubeconfigFile = workspace.createTempFile(Constants.KUBECONFIG_PREFIX, "");
                    try {
                        try (SSHClient connected = sshClient.connect();
                             OutputStream out = kubeconfigFile.write()) {
                            connected.copyFrom(Constants.KUBECONFIG_FILE, out);
                        }

                        KubernetesClientWrapper clientWrapper =
                                new KubernetesClientWrapper(kubeconfigFile.getRemote())
                                        .withLogger(logger);
                        result.masterHost = getMasterHost(clientWrapper);

                        if (!registryCredentials.isEmpty()) {
                            final String secretName = KubernetesClientWrapper.prepareSecretName(
                                    secretNameCfg, defaultSecretName, envVars);

                            clientWrapper.createOrReplaceSecrets(
                                    kubernetesNamespaceCfg, secretName, registryCredentials);

                            logger.println(Messages.KubernetesDeploymentCommand_injectSecretName(
                                    Constants.KUBERNETES_SECRET_NAME_PROP, secretName));
                            envVars.put(Constants.KUBERNETES_SECRET_NAME_PROP, secretName);
                            result.extraEnvVars.put(Constants.KUBERNETES_SECRET_NAME_PROP, secretName);
                        }

                        if (enableSubstitution) {
                            clientWrapper.withVariableResolver(new VariableResolver.ByMap<>(envVars));
                        }

                        clientWrapper.apply(configFiles);
                        result.commandState = CommandState.Success;

                        return result;
                    } finally {
                        logger.println(
                                Messages.KubernetesDeploymentCommand_deleteConfigFile(kubeconfigFile.getRemote()));
                        try {
                            if (!kubeconfigFile.delete()) {
                                logger.println(Messages.KubernetesDeploymentCommand_failedToDeleteFile(
                                        kubeconfigFile.getRemote()));
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            logger.println(
                                    Messages.KubernetesDeploymentCommand_failedToDeleteFile(e.getMessage()));
                        } catch (Exception e) {
                            logger.println(
                                    Messages.KubernetesDeploymentCommand_failedToDeleteFile(e.getMessage()));
                        }
                    }
                }
            });

            for (Map.Entry<String, String> entry : taskResult.extraEnvVars.entrySet()) {
                EnvironmentInjector.inject(jobContext.getRun(), envVars, entry.getKey(), entry.getValue());
            }

            context.setCommandState(taskResult.commandState);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            context.logError(e);
        }
    }

    @VisibleForTesting
    String getMasterHost(KubernetesClientWrapper wrapper) {
        final String unknown = "Unknown";
        if (wrapper == null) {
            return unknown;
        }
        KubernetesClient client = wrapper.getClient();
        if (client == null) {
            return unknown;
        }
        URL masterURL = client.getMasterUrl();
        if (masterURL == null) {
            return unknown;
        }
        return masterURL.getHost();
    }

    private static class TaskResult implements Serializable {
        private static final long serialVersionUID = 1L;

        private CommandState commandState = CommandState.Unknown;
        private Map<String, String> extraEnvVars = new HashMap<>();
        private String masterHost;
    }

    public interface IKubernetesDeploymentCommandData extends IBaseCommandData {
        String getMgmtFQDN();

        SSHUserPrivateKey getSshCredentials();

        String getSecretNamespace();

        String getConfigFilePaths();

        ContainerServiceOchestratorTypes getOrchestratorType();

        boolean isEnableConfigSubstitution();

        String getSecretName();

        List<ResolvedDockerRegistryEndpoint> resolvedDockerRegistryEndpoints(Item context) throws IOException;
    }
}
