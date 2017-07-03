/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.jcraft.jsch.JSchException;
import com.microsoft.jenkins.acs.JobContext;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.JSchClient;
import com.microsoft.jenkins.acs.util.KubernetesClientUtil;
import hudson.EnvVars;
import hudson.Util;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.commons.lang.StringUtils;

import java.io.File;
import java.io.IOException;

/**
 * Command to deploy Kubernetes configurations to Azure Container Service.
 */
public class KubernetesDeploymentCommand
        implements ICommand<KubernetesDeploymentCommand.IKubernetesDeploymentCommandData> {
    @Override
    public void execute(final IKubernetesDeploymentCommandData context) {
        final JobContext jobContext = context.jobContext();
        final EnvVars envVars = jobContext.envVars();

        SSHUserPrivateKey sshCredentials = context.getSshCredentials();
        String kubernetesNamespaceCfg = Util.replaceMacro(context.getKubernetesNamespace(), envVars).trim();
        String configFilePathsCfg = Util.replaceMacro(context.getConfigFilePaths(), envVars).trim();

        if (StringUtils.isBlank(configFilePathsCfg)) {
            context.logStatus(Messages.KubernetesDeploymentCommand_configFilesNotSpecified());
            context.setDeploymentState(DeploymentState.HasError);
            return;
        }

        File kubeconfigFile = null;
        JSchClient jschClient = null;
        try {
            jschClient = new JSchClient(
                    context.getMgmtFQDN(),
                    Constants.KUBERNETES_SSH_PORT,
                    context.getLinuxAdminUsername(),
                    sshCredentials,
                    context);
            kubeconfigFile = File.createTempFile(Constants.KUBECONFIG_PREFIX, "", Constants.TEMP_DIR);

            jschClient.copyFrom(Constants.KUBECONFIG_FILE, kubeconfigFile);

            Config config = kubeConfigFromFile(kubeconfigFile);

            KubernetesClient kubernetesClient = new DefaultKubernetesClient(config);
            KubernetesClientUtil.apply(
                    jobContext,
                    kubernetesClient,
                    kubernetesNamespaceCfg,
                    configFilePathsCfg,
                    context.isEnableConfigSubstitution());

            context.setDeploymentState(DeploymentState.Success);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            context.logError(e);
        } catch (IOException | JSchException e) {
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

    private static Config kubeConfigFromFile(final File file) {
        System.setProperty(Config.KUBERNETES_CLIENT_KEY_FILE_SYSTEM_PROPERTY, "true");
        System.setProperty(Config.KUBERNETES_KUBECONFIG_FILE, file.getAbsolutePath());
        return Config.autoConfigure();
    }

    public interface IKubernetesDeploymentCommandData extends IBaseCommandData {
        String getMgmtFQDN();

        String getLinuxAdminUsername();

        SSHUserPrivateKey getSshCredentials();

        String getKubernetesNamespace();

        String getConfigFilePaths();

        boolean isEnableConfigSubstitution();
    }
}
