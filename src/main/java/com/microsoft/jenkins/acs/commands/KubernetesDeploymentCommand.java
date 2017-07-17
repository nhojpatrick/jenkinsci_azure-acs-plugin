/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.google.common.annotations.VisibleForTesting;
import com.jcraft.jsch.JSchException;
import com.microsoft.jenkins.acs.JobContext;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.orchestrators.DeploymentConfig;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.DeployHelper;
import com.microsoft.jenkins.acs.util.JSchClient;
import com.microsoft.jenkins.acs.util.KubernetesClientUtil;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Util;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

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
    KubernetesDeploymentCommand(final ExternalUtils externalUtils) {
        this.externalUtils = externalUtils;
    }

    @Override
    public void execute(final IKubernetesDeploymentCommandData context) {
        final JobContext jobContext = context.jobContext();
        final EnvVars envVars = jobContext.envVars();

        SSHUserPrivateKey sshCredentials = context.getSshCredentials();
        String kubernetesNamespaceCfg = Util.replaceMacro(context.getKubernetesNamespace(), envVars).trim();
        DeploymentConfig deploymentConfig = context.getDeploymentConfig();
        FilePath[] configFiles = deploymentConfig.getConfigFiles();

        File kubeconfigFile = null;
        JSchClient jschClient = null;
        try {
            jschClient = externalUtils.buildJSchClient(
                    context.getMgmtFQDN(),
                    Constants.KUBERNETES_SSH_PORT,
                    context.getLinuxAdminUsername(),
                    sshCredentials,
                    context);
            kubeconfigFile = externalUtils.createTempConfigFile();

            jschClient.copyFrom(Constants.KUBECONFIG_FILE, kubeconfigFile);

            KubernetesClient kubernetesClient = externalUtils.buildKubernetesClient(kubeconfigFile);

            final List<DockerRegistryEndpoint> registryCredentials = context.getContainerRegistryCredentials();
            if (!registryCredentials.isEmpty()) {
                final String secretName = prepareSecretName(context.getSecretName(), jobContext, envVars);

                externalUtils.prepareKubernetesSecrets(
                        jobContext,
                        kubernetesClient,
                        kubernetesNamespaceCfg,
                        secretName,
                        registryCredentials);

                context.logStatus(Messages.KubernetesDeploymentCommand_injectSecretName(
                        Constants.KUBERNETES_SECRET_NAME_PROP, secretName));
                DeployHelper.injectEnvironmentVariable(
                        jobContext.getRun(), Constants.KUBERNETES_SECRET_NAME_PROP, secretName);
            }

            externalUtils.applyKubernetesConfig(
                    jobContext,
                    kubernetesClient,
                    kubernetesNamespaceCfg,
                    configFiles,
                    context.isEnableConfigSubstitution());

            context.setDeploymentState(DeploymentState.Success);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            context.logError(e);
        } catch (IOException | JSchException | IllegalArgumentException e) {
            context.logError(e);
        } finally {
            if (kubeconfigFile != null) {
                context.logStatus(
                        Messages.KubernetesDeploymentCommand_deleteConfigFile(kubeconfigFile.getAbsolutePath()));
                if (!kubeconfigFile.delete()) {
                    context.logStatus(
                            Messages.KubernetesDeploymentCommand_failedToDeleteFile(kubeconfigFile.getAbsolutePath()));
                }
            }
            if (jschClient != null) {
                jschClient.close();
            }
        }
    }

    private static String prepareSecretName(final String nameCfg, final JobContext jobContext, final EnvVars envVars) {
        String name = StringUtils.trimToEmpty(envVars.expand(nameCfg));
        if (name.length() > Constants.KUBERNETES_NAME_LENGTH_LIMIT) {
            throw new IllegalArgumentException(Messages.KubernetesDeploymentCommand_secretNameTooLong(name));
        }

        if (!name.isEmpty()) {
            if (!Constants.KUBERNETES_NAME_PATTERN.matcher(name).matches()) {
                throw new IllegalArgumentException(Messages.KubernetesDeploymentCommand_illegalSecretName(name));
            }

            return name;
        }
        String runName = jobContext.getRun().getDisplayName();
        if (StringUtils.isBlank(runName)) {
            runName = UUID.randomUUID().toString();
        }
        name = Constants.KUBERNETES_SECRET_NAME_PREFIX
                + runName.replaceAll("[^0-9a-zA-Z]", "-").toLowerCase();
        if (name.length() > Constants.KUBERNETES_NAME_LENGTH_LIMIT) {
            name = name.substring(0, Constants.KUBERNETES_NAME_LENGTH_LIMIT);
        }
        return name;
    }

    private static Config kubeConfigFromFile(final File file) {
        System.setProperty(Config.KUBERNETES_CLIENT_KEY_FILE_SYSTEM_PROPERTY, "true");
        System.setProperty(Config.KUBERNETES_KUBECONFIG_FILE, file.getAbsolutePath());
        return Config.autoConfigure();
    }

    @VisibleForTesting
    interface ExternalUtils {
        JSchClient buildJSchClient(String host,
                                   int port,
                                   @Nullable String username,
                                   SSHUserPrivateKey credentials,
                                   @Nullable IBaseCommandData context);

        File createTempConfigFile() throws IOException;

        KubernetesClient buildKubernetesClient(File configFile);

        void prepareKubernetesSecrets(
                JobContext jobContext,
                KubernetesClient kubernetesClient,
                String kubernetesNamespace,
                String secretName,
                List<DockerRegistryEndpoint> credentials) throws IOException;

        void applyKubernetesConfig(
                JobContext jobContext,
                KubernetesClient client,
                String namespace,
                FilePath[] configFiles,
                boolean enableConfigSubstitution) throws IOException, InterruptedException;

        ExternalUtils DEFAULT = new ExternalUtils() {
            @Override
            public JSchClient buildJSchClient(
                    final String host,
                    final int port,
                    @Nullable final String username,
                    final SSHUserPrivateKey credentials,
                    @Nullable final IBaseCommandData context) {
                return new JSchClient(host, port, username, credentials, context);
            }

            @Override
            public File createTempConfigFile() throws IOException {
                return File.createTempFile(Constants.KUBECONFIG_PREFIX, "", Constants.TEMP_DIR);
            }

            @Override
            public KubernetesClient buildKubernetesClient(final File configFile) {
                return new DefaultKubernetesClient(kubeConfigFromFile(configFile));
            }

            @Override
            public void prepareKubernetesSecrets(
                    final JobContext jobContext,
                    final KubernetesClient kubernetesClient,
                    final String kubernetesNamespace,
                    final String secretName,
                    final List<DockerRegistryEndpoint> credentials) throws IOException {
                KubernetesClientUtil.prepareSecrets(
                        jobContext,
                        kubernetesClient,
                        kubernetesNamespace,
                        secretName,
                        credentials);
            }

            @Override
            public void applyKubernetesConfig(
                    final JobContext jobContext,
                    final KubernetesClient client,
                    final String namespace,
                    final FilePath[] configFiles,
                    final boolean enableConfigSubstitution) throws IOException, InterruptedException {
                KubernetesClientUtil.apply(
                        jobContext,
                        client,
                        namespace,
                        configFiles,
                        enableConfigSubstitution);
            }
        };
    }

    public interface IKubernetesDeploymentCommandData extends IBaseCommandData {
        String getMgmtFQDN();

        String getLinuxAdminUsername();

        SSHUserPrivateKey getSshCredentials();

        String getKubernetesNamespace();

        DeploymentConfig getDeploymentConfig();

        boolean isEnableConfigSubstitution();

        String getSecretName();

        List<DockerRegistryEndpoint> getContainerRegistryCredentials();
    }
}
