/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.annotations.VisibleForTesting;
import com.jcraft.jsch.JSchException;
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
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Util;
import hudson.util.VariableResolver;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Command to deploy Kubernetes configurations to Azure Container Service.
 */
public class KubernetesDeploymentCommand
        implements ICommand<KubernetesDeploymentCommand.IKubernetesDeploymentCommandData> {

    private final ExternalUtils externalUtils;

    public KubernetesDeploymentCommand() {
        this(ExternalUtils.DEFAULT);
    }

    @VisibleForTesting
    KubernetesDeploymentCommand(ExternalUtils externalUtils) {
        this.externalUtils = externalUtils;
    }

    @Override
    public void execute(final IKubernetesDeploymentCommandData context) {
        final JobContext jobContext = context.getJobContext();
        final EnvVars envVars = context.getEnvVars();

        SSHUserPrivateKey sshCredentials = context.getSshCredentials();
        String kubernetesNamespaceCfg = Util.replaceMacro(context.getSecretNamespace(), envVars).trim();
        DeploymentConfig deploymentConfig = context.getDeploymentConfig();
        FilePath[] configFiles = deploymentConfig.getConfigFiles();

        FilePath kubeconfigFile = null;
        try {
            SSHClient sshClient = externalUtils
                    .buildSSHClient(context.getMgmtFQDN(), Constants.KUBERNETES_SSH_PORT, sshCredentials)
                    .withLogger(jobContext.logger());
            kubeconfigFile = jobContext.getWorkspace().createTempFile(Constants.KUBECONFIG_PREFIX, "");
            try (SSHClient connected = sshClient.connect();
                 OutputStream out = kubeconfigFile.write()) {
                connected.copyFrom(Constants.KUBECONFIG_FILE, out);
            }

            KubernetesClientWrapper clientWrapper =
                    externalUtils.buildKubernetesClientWrapper(kubeconfigFile.getRemote())
                            .withLogger(jobContext.logger());

            final List<DockerRegistryEndpoint> registryCredentials = context.getContainerRegistryCredentials();
            if (!registryCredentials.isEmpty()) {
                final String secretName = KubernetesClientWrapper.prepareSecretName(
                        context.getSecretName(), jobContext.getRun().getDisplayName(), envVars);

                clientWrapper.createOrReplaceSecrets(
                        jobContext.getRun().getParent(),
                        kubernetesNamespaceCfg,
                        secretName,
                        registryCredentials);

                context.logStatus(Messages.KubernetesDeploymentCommand_injectSecretName(
                        Constants.KUBERNETES_SECRET_NAME_PROP, secretName));
                EnvironmentInjector.inject(
                        jobContext.getRun(), envVars, Constants.KUBERNETES_SECRET_NAME_PROP, secretName);
            }

            if (context.isEnableConfigSubstitution()) {
                clientWrapper.withVariableResolver(new VariableResolver.ByMap<>(envVars));
            }

            clientWrapper.apply(configFiles);

            context.setCommandState(CommandState.Success);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            context.logError(e);
        } catch (Exception e) {
            context.logError(e);
        } finally {
            if (kubeconfigFile != null) {
                context.logStatus(
                        Messages.KubernetesDeploymentCommand_deleteConfigFile(kubeconfigFile.getRemote()));
                try {
                    if (!kubeconfigFile.delete()) {
                        context.logStatus(
                                Messages.KubernetesDeploymentCommand_failedToDeleteFile(kubeconfigFile.getRemote()));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    context.logStatus(
                            Messages.KubernetesDeploymentCommand_failedToDeleteFile(e.getMessage()));
                } catch (Exception e) {
                    context.logStatus(
                            Messages.KubernetesDeploymentCommand_failedToDeleteFile(e.getMessage()));
                }
            }
        }
    }

    @VisibleForTesting
    interface ExternalUtils {
        SSHClient buildSSHClient(String host,
                                 int port,
                                 SSHUserPrivateKey credentials) throws JSchException;

        KubernetesClientWrapper buildKubernetesClientWrapper(String kubeconfigFilePath);

        ExternalUtils DEFAULT = new ExternalUtils() {
            @Override
            public SSHClient buildSSHClient(String host, int port, SSHUserPrivateKey credentials) throws JSchException {
                return new SSHClient(host, port, credentials);
            }

            @Override
            public KubernetesClientWrapper buildKubernetesClientWrapper(String kubeconfigFilePath) {
                return new KubernetesClientWrapper(kubeconfigFilePath);
            }
        };
    }

    public interface IKubernetesDeploymentCommandData extends IBaseCommandData {
        String getMgmtFQDN();

        SSHUserPrivateKey getSshCredentials();

        String getSecretNamespace();

        DeploymentConfig getDeploymentConfig();

        boolean isEnableConfigSubstitution();

        String getSecretName();

        List<DockerRegistryEndpoint> getContainerRegistryCredentials();
    }
}
