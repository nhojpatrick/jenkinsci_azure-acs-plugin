/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs.commands;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.JSchHelper;
import com.microsoft.jenkins.acs.util.KubernetesClientUtil;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.commons.lang.StringUtils;

import java.io.File;

/**
 * Command to deploy Kubernetes configurations to Azure Container Service.
 */
public class KubernetesDeploymentCommand implements ICommand<KubernetesDeploymentCommand.IKubernetesDeploymentCommandData> {
    @Override
    public void execute(IKubernetesDeploymentCommandData context) {
        final EnvVars envVars = context.getEnvVars();
        final Run<?, ?> run = context.getRun();
        final FilePath workspace = context.getWorkspace();
        final Launcher launcher = context.getLauncher();
        final TaskListener listener = context.getListener();

        SSHUserPrivateKey sshCredentials = context.getSshCredentials();
        String kubernetesNamespaceCfg = Util.replaceMacro(context.getKubernetesNamespace(), envVars).trim();
        String configFilePathsCfg = Util.replaceMacro(context.getConfigFilePaths(), envVars).trim();

        if (StringUtils.isBlank(configFilePathsCfg)) {
            context.logStatus(Messages.KubernetesDeploymentCommand_configFilesNotSpecified());
            run.setResult(Result.UNSTABLE);
            return;
        }

        File kubeconfigFile = null;
        try {
            kubeconfigFile = File.createTempFile(Constants.KUBECONFIG_PREFIX, "", Constants.TEMP_DIR);
            JSchHelper.scpFrom(
                    listener.getLogger(),
                    context.getMgmtFQDN(), JSchHelper.DEFAULT_SSH_PORT,
                    context.getLinuxAdminUsername(),
                    sshCredentials,
                    Constants.KUBECONFIG_FILE,
                    kubeconfigFile);

            Config config = kubeConfigFromFile(kubeconfigFile);

            KubernetesClient client = new DefaultKubernetesClient(config);
            KubernetesClientUtil.apply(
                    run, workspace, launcher, listener, client, kubernetesNamespaceCfg, configFilePathsCfg, context.isEnableConfigSubstitution());
        } catch (Exception e) {
            e.printStackTrace(listener.error(Messages.KubernetesDeploymentCommand_unexpectedError()));
            run.setResult(Result.FAILURE);
        } finally {
            if (kubeconfigFile != null) {
                listener.getLogger().println(
                        Messages.KubernetesDeploymentCommand_deleteConfigFile(kubeconfigFile.getAbsolutePath()));
                if (!kubeconfigFile.delete()) {
                    listener.getLogger().println(
                            Messages.KubernetesDeploymentCommand_failedToDeleteFile(kubeconfigFile.getAbsolutePath()));
                }
            }
        }
    }

    private static Config kubeConfigFromFile(final File file) {
        System.setProperty(Config.KUBERNETES_CLIENT_KEY_FILE_SYSTEM_PROPERTY, "true");
        System.setProperty(Config.KUBERNETES_KUBECONFIG_FILE, file.getAbsolutePath());
        return Config.autoConfigure();
    }

    public interface IKubernetesDeploymentCommandData extends IBaseCommandData {
        Run<?, ?> getRun();

        FilePath getWorkspace();

        Launcher getLauncher();

        TaskListener getListener();

        String getMgmtFQDN();

        String getLinuxAdminUsername();

        SSHUserPrivateKey getSshCredentials();

        String getKubernetesNamespace();

        String getConfigFilePaths();

        boolean isEnableConfigSubstitution();
    }
}
