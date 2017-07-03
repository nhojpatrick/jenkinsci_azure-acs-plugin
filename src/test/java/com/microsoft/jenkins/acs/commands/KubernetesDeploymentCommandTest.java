/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.jcraft.jsch.JSchException;
import com.microsoft.jenkins.acs.ACSDeploymentContext;
import com.microsoft.jenkins.acs.JobContext;
import com.microsoft.jenkins.acs.orchestrators.DeploymentConfig;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.JSchClient;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.Run;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link KubernetesDeploymentCommand}.
 */
@SuppressWarnings("unchecked")
public class KubernetesDeploymentCommandTest {
    private static final String FQDN = "fqdn.test";
    private static final String ROOT_USER = "azureuser";
    private static final String SECRET_NAME = "secret";
    private static final String NAMESPACE = "default";
    private static final String RUN_NAME = "Test-Run";

    @Test
    public void testSuccess() throws IOException, JSchException, InterruptedException {
        ContextBuilder b = new ContextBuilder();

        b.executeCommand();

        verify(b.externalUtils, times(1)).buildJSchClient(
                FQDN,
                Constants.KUBERNETES_SSH_PORT,
                ROOT_USER,
                b.sshCredentials,
                b.context);
        verify(b.jSchClient, times(1)).copyFrom(any(String.class), any(File.class));

        verify(b.externalUtils, times(1)).prepareKubernetesSecrets(
                b.jobContext,
                b.kubernetesClient,
                NAMESPACE,
                SECRET_NAME,
                b.registryEndpoints);

        verify(b.externalUtils, times(1)).applyKubernetesConfig(
                b.jobContext,
                b.kubernetesClient,
                NAMESPACE,
                b.configFiles,
                true);

        verify(b.context, times(1)).setDeploymentState(DeploymentState.Success);
    }

    @Test
    public void testNoRegistryCredentials() throws IOException {
        ContextBuilder b = new ContextBuilder().withoutRegistryCredentials();
        b.executeCommand();

        verify(b.externalUtils, never()).prepareKubernetesSecrets(
                any(JobContext.class),
                any(KubernetesClient.class),
                any(String.class),
                any(String.class),
                any(List.class));
        verify(b.context, times(1)).setDeploymentState(DeploymentState.Success);
    }

    @Test
    public void testKubernetesNamespaceSubstitution() throws IOException, InterruptedException {
        final String ns = "namespace-in-variable";
        ContextBuilder b = new ContextBuilder()
                .withNamespace("test-$NS")
                .addEnvVar("NS", ns);
        b.executeCommand();

        verify(b.externalUtils, times(1)).prepareKubernetesSecrets(
                b.jobContext,
                b.kubernetesClient,
                "test-" + ns,
                SECRET_NAME,
                b.registryEndpoints);

        verify(b.externalUtils, times(1)).applyKubernetesConfig(
                b.jobContext,
                b.kubernetesClient,
                "test-" + ns,
                b.configFiles,
                true);

        verify(b.context, times(1)).setDeploymentState(DeploymentState.Success);

        b = new ContextBuilder()
                .withNamespace("test-${NO_NS}")
                .addEnvVar("NS", ns);
        b.executeCommand();
        verify(b.externalUtils, times(1)).prepareKubernetesSecrets(
                b.jobContext,
                b.kubernetesClient,
                "test-${NO_NS}",
                SECRET_NAME,
                b.registryEndpoints);
    }

    @Test
    public void testSecretName() throws IOException {
        ContextBuilder b = new ContextBuilder()
                .withSecretName("secret-$BUILD_NUMBER")
                .addEnvVar("BUILD_NUMBER", "2");
        b.executeCommand();

        verify(b.externalUtils, times(1)).prepareKubernetesSecrets(
                b.jobContext,
                b.kubernetesClient,
                NAMESPACE,
                "secret-2",
                b.registryEndpoints);

        verify(b.context, times(1)).setDeploymentState(DeploymentState.Success);

        b = new ContextBuilder().withSecretName("");
        b.executeCommand();

        verify(b.externalUtils, times(1)).prepareKubernetesSecrets(
                b.jobContext,
                b.kubernetesClient,
                NAMESPACE,
                "acs-plugin-test-run", // generated from the display name of Run<?, ?>
                b.registryEndpoints);
        verify(b.context, times(1)).setDeploymentState(DeploymentState.Success);

        // secret name length limit 253
        String secret = new String(new char[300]).replaceAll("\0{10}", "0123456789");
        final ContextBuilder b1 = new ContextBuilder().withSecretName(secret);
        b1.executeCommand();

        verify(b1.externalUtils, never()).prepareKubernetesSecrets(
                any(JobContext.class),
                any(KubernetesClient.class),
                any(String.class),
                any(String.class),
                any(List.class));
        verify(b1.context, times(1)).setDeploymentState(DeploymentState.HasError);
    }

    private static class ContextBuilder {
        KubernetesDeploymentCommand.IKubernetesDeploymentCommandData context;

        JobContext jobContext;
        EnvVars envVars;
        Run<?, ?> run;
        SSHUserPrivateKey sshCredentials;
        DeploymentConfig deploymentConfig;
        FilePath configFile;
        FilePath[] configFiles;
        KubernetesDeploymentCommand.ExternalUtils externalUtils;
        JSchClient jSchClient;
        File kubeconfigFile;
        KubernetesClient kubernetesClient;
        List<DockerRegistryEndpoint> registryEndpoints;

        ContextBuilder() throws IOException {
            this.context = mock(ACSDeploymentContext.class);

            final Answer<Void> answer = new Answer<Void>() {
                @Override
                public Void answer(InvocationOnMock invocation) throws Throwable {
                    context.setDeploymentState(DeploymentState.HasError);
                    return null;
                }
            };

            doAnswer(answer).when(context).logError(any(String.class));
            doAnswer(answer).when(context).logError(any(Exception.class));
            doAnswer(answer).when(context).logError(any(String.class), any(Exception.class));

            jobContext = mock(JobContext.class);
            envVars = new EnvVars();
            doReturn(jobContext).when(context).jobContext();
            doReturn(envVars).when(jobContext).envVars();
            run = mock(Run.class);
            //noinspection ResultOfMethodCallIgnored
            doReturn(run).when(jobContext).getRun();
            doReturn(RUN_NAME).when(run).getDisplayName();

            sshCredentials = mock(SSHUserPrivateKey.class);
            doReturn(sshCredentials).when(context).getSshCredentials();
            doReturn(FQDN).when(context).getMgmtFQDN();
            doReturn(ROOT_USER).when(context).getLinuxAdminUsername();

            doReturn(NAMESPACE).when(context).getKubernetesNamespace();
            deploymentConfig = mock(DeploymentConfig.class);
            doReturn(deploymentConfig).when(context).getDeploymentConfig();
            configFile = mock(FilePath.class);
            configFiles = new FilePath[]{configFile};
            doReturn(configFiles).when(deploymentConfig).getConfigFiles();

            externalUtils = mock(KubernetesDeploymentCommand.ExternalUtils.class);
            jSchClient = mock(JSchClient.class);
            doReturn(jSchClient).when(externalUtils).buildJSchClient(
                    any(String.class),
                    any(Integer.TYPE),
                    any(String.class),
                    any(SSHUserPrivateKey.class),
                    any(IBaseCommandData.class));

            kubeconfigFile = mock(File.class);

            doReturn(kubeconfigFile).when(externalUtils).createTempConfigFile();

            kubernetesClient = mock(KubernetesClient.class);
            doReturn(kubernetesClient).when(externalUtils).buildKubernetesClient(any(File.class));

            registryEndpoints = mock(List.class);
            doReturn(false).when(registryEndpoints).isEmpty();
            doReturn(registryEndpoints).when(context).getContainerRegistryCredentials();

            doReturn(SECRET_NAME).when(context).getSecretName();

            doReturn(true).when(context).isEnableConfigSubstitution();
        }

        ContextBuilder addEnvVar(String name, String value) {
            this.envVars.put(name, value);
            return this;
        }

        ContextBuilder withNamespace(String ns) {
            doReturn(ns).when(context).getKubernetesNamespace();
            return this;
        }

        ContextBuilder withoutRegistryCredentials() {
            doReturn(true).when(registryEndpoints).isEmpty();
            return this;
        }

        ContextBuilder withSecretName(String secretName) {
            doReturn(secretName).when(context).getSecretName();
            return this;
        }

        ContextBuilder disableConfigSubstitution() {
            doReturn(false).when(context).isEnableConfigSubstitution();
            return this;
        }

        void executeCommand() {
            new KubernetesDeploymentCommand(externalUtils).execute(context);
        }
    }
}
