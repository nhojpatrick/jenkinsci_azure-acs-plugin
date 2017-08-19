/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.microsoft.jenkins.acs.ACSDeploymentContext;
import com.microsoft.jenkins.acs.orchestrators.DeploymentConfig;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.azurecommons.JobContext;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import com.microsoft.jenkins.azurecommons.remote.SSHClient;
import com.microsoft.jenkins.kubernetes.KubernetesClientWrapper;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.Job;
import hudson.model.Run;
import hudson.util.VariableResolver;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.OutputStream;
import java.io.PrintStream;
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
    public void testSuccess() throws Exception {
        ContextBuilder b = new ContextBuilder();

        b.executeCommand();

        verify(b.externalUtils, times(1)).buildSSHClient(
                FQDN,
                Constants.KUBERNETES_SSH_PORT,
                b.sshCredentials);
        verify(b.sshClient, times(1)).copyFrom(any(String.class), any(OutputStream.class));

        verify(b.kubernetesClientWrapper, times(1)).createOrReplaceSecrets(
                b.job,
                NAMESPACE,
                SECRET_NAME,
                b.registryEndpoints);

        verify(b.kubernetesClientWrapper, times(1)).apply(b.configFiles);

        verify(b.context, times(1)).setCommandState(CommandState.Success);
    }

    @Test
    public void testNoRegistryCredentials() throws Exception {
        ContextBuilder b = new ContextBuilder().withoutRegistryCredentials();
        b.executeCommand();

        verify(b.kubernetesClientWrapper, never()).createOrReplaceSecrets(
                any(Job.class),
                any(String.class),
                any(String.class),
                any(List.class));
        verify(b.context, times(1)).setCommandState(CommandState.Success);
    }

    @Test
    public void testKubernetesNamespaceSubstitution() throws Exception {
        final String ns = "namespace-in-variable";
        ContextBuilder b = new ContextBuilder()
                .withNamespace("test-$NS")
                .addEnvVar("NS", ns);
        b.executeCommand();

        verify(b.kubernetesClientWrapper, times(1)).createOrReplaceSecrets(
                b.job,
                "test-" + ns,
                SECRET_NAME,
                b.registryEndpoints);

        verify(b.kubernetesClientWrapper, times(1)).apply(b.configFiles);

        verify(b.context, times(1)).setCommandState(CommandState.Success);

        b = new ContextBuilder()
                .withNamespace("test-${NO_NS}")
                .addEnvVar("NS", ns);
        b.executeCommand();
        verify(b.kubernetesClientWrapper, times(1)).createOrReplaceSecrets(
                b.job,
                "test-${NO_NS}",
                SECRET_NAME,
                b.registryEndpoints);
    }

    @Test
    public void testSecretName() throws Exception {
        ContextBuilder b = new ContextBuilder()
                .withSecretName("secret-$BUILD_NUMBER")
                .addEnvVar("BUILD_NUMBER", "2");
        b.executeCommand();

        verify(b.kubernetesClientWrapper, times(1)).createOrReplaceSecrets(
                b.job,
                NAMESPACE,
                "secret-2",
                b.registryEndpoints);

        verify(b.context, times(1)).setCommandState(CommandState.Success);

        b = new ContextBuilder().withSecretName("");
        b.executeCommand();

        verify(b.kubernetesClientWrapper, times(1)).createOrReplaceSecrets(
                b.job,
                NAMESPACE,
                "acs-plugin-test-run", // generated from the display name of Run<?, ?>
                b.registryEndpoints);
        verify(b.context, times(1)).setCommandState(CommandState.Success);

        // secret name length limit 253
        String secret = new String(new char[300]).replaceAll("\0{10}", "0123456789");
        final ContextBuilder b1 = new ContextBuilder().withSecretName(secret);
        b1.executeCommand();

        verify(b1.kubernetesClientWrapper, never()).createOrReplaceSecrets(
                any(Job.class),
                any(String.class),
                any(String.class),
                any(List.class));
        verify(b1.context, times(1)).setCommandState(CommandState.HasError);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static class ContextBuilder {
        KubernetesDeploymentCommand.IKubernetesDeploymentCommandData context;

        JobContext jobContext;
        EnvVars envVars;
        Run<?, ?> run;
        Job job;
        SSHUserPrivateKey sshCredentials;
        DeploymentConfig deploymentConfig;
        FilePath configFile;
        FilePath[] configFiles;
        KubernetesDeploymentCommand.ExternalUtils externalUtils;
        SSHClient sshClient;
        FilePath kubeconfigFile;
        KubernetesClientWrapper kubernetesClientWrapper;
        List<DockerRegistryEndpoint> registryEndpoints;

        ContextBuilder() throws Exception {
            this.context = mock(ACSDeploymentContext.class);

            final Answer<Void> answer = new Answer<Void>() {
                @Override
                public Void answer(InvocationOnMock invocation) throws Throwable {
                    context.setCommandState(CommandState.HasError);
                    return null;
                }
            };

            doAnswer(answer).when(context).logError(any(String.class));
            doAnswer(answer).when(context).logError(any(Exception.class));
            doAnswer(answer).when(context).logError(any(String.class), any(Exception.class));

            jobContext = mock(JobContext.class);
            doReturn(mock(PrintStream.class)).when(jobContext).logger();
            envVars = new EnvVars();
            doReturn(jobContext).when(context).getJobContext();
            doReturn(envVars).when(jobContext).envVars();
            doReturn(envVars).when(context).getEnvVars();
            run = mock(Run.class);
            //noinspection ResultOfMethodCallIgnored
            doReturn(run).when(jobContext).getRun();
            doReturn(RUN_NAME).when(run).getDisplayName();
            job = mock(Job.class);
            doReturn(job).when(run).getParent();

            sshCredentials = mock(SSHUserPrivateKey.class);
            doReturn(sshCredentials).when(context).getSshCredentials();
            doReturn(FQDN).when(context).getMgmtFQDN();

            doReturn(NAMESPACE).when(context).getKubernetesNamespace();
            deploymentConfig = mock(DeploymentConfig.class);
            doReturn(deploymentConfig).when(context).getDeploymentConfig();
            configFile = mock(FilePath.class);
            configFiles = new FilePath[]{configFile};
            doReturn(configFiles).when(deploymentConfig).getConfigFiles();

            externalUtils = mock(KubernetesDeploymentCommand.ExternalUtils.class);
            sshClient = mock(SSHClient.class);
            doReturn(sshClient).when(externalUtils).buildSSHClient(
                    any(String.class),
                    any(Integer.TYPE),
                    any(SSHUserPrivateKey.class));
            doReturn(sshClient).when(sshClient).withLogger(any(PrintStream.class));
            doReturn(sshClient).when(sshClient).connect();

            kubeconfigFile = mock(FilePath.class);
            FilePath workspace = mock(FilePath.class);
            doReturn(workspace).when(jobContext).getWorkspace();
            doReturn(kubeconfigFile).when(workspace).createTempFile(any(String.class), any(String.class));
            doReturn("kubeconfig").when(kubeconfigFile).getRemote();
            doReturn(mock(OutputStream.class)).when(kubeconfigFile).write();

            kubernetesClientWrapper = mock(KubernetesClientWrapper.class);
            doReturn(kubernetesClientWrapper).when(externalUtils).buildKubernetesClientWrapper(any(String.class));
            doReturn(kubernetesClientWrapper).when(kubernetesClientWrapper).withLogger(any(PrintStream.class));
            doReturn(kubernetesClientWrapper).when(kubernetesClientWrapper).withVariableResolver(any(VariableResolver.class));

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
