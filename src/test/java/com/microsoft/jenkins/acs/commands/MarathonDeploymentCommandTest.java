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
import com.microsoft.jenkins.acs.util.DockerConfigBuilder;
import com.microsoft.jenkins.acs.util.JSchClient;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.Run;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the {@link MarathonDeploymentCommand}.
 */
public class MarathonDeploymentCommandTest {
    private static final String FQDN = "fqdn.test";
    private static final String ROOT_USER = "azureuser";
    private static final String DCOS_CREDENTIALS_PATH = "/mnt/share/dcosshare";
    private static final String REMOTE_APP_CONFIG_NAME = "acsDep1234567890.json";
    private static final String MARATHON_APP_ID = "marathon-app-01";

    @Test
    public void testSuccessfulExecute() throws IOException, JSchException, InterruptedException {
        ContextBuilder b = new ContextBuilder();

        b.executeCommand();

        final String archivePath = DCOS_CREDENTIALS_PATH + "/docker.tar.gz";

        verify(b.agentClient, times(1)).execRemote("mkdir -p -- '" + DCOS_CREDENTIALS_PATH + "'");
        verify(b.agentClient, times(1)).copyTo(b.dockerConfigStream, archivePath);
        verify(b.jSchClient, times(1)).copyTo(b.marathonConfigStream, b.remoteDeploymentConfigName);
        verify(b.jSchClient, times(1)).execRemote(
                "curl -i -X DELETE http://localhost/marathon/v2/apps/" + b.marathonAppId);
        verify(b.jSchClient, times(1)).execRemote(
                "curl -i -H 'Content-Type: application/json' -d@'" + b.remoteDeploymentConfigName
                        + "' http://localhost/marathon/v2/apps?force=true");
        verify(b.jSchClient, times(1)).execRemote("rm -f -- '" + b.remoteDeploymentConfigName + "'");
    }

    @Test
    public void testNullDeploymentConfig() throws IOException, JSchException, InterruptedException {
        ContextBuilder b = new ContextBuilder().withDeploymentConfig(null);
        b.executeCommand();

        verify(b.context, times(1)).setDeploymentState(DeploymentState.HasError);
        verify(b.externalUtils, never()).buildJSchClient(
                any(String.class),
                any(Integer.TYPE),
                any(String.class),
                any(SSHUserPrivateKey.class),
                any(IBaseCommandData.class));
    }

    @Test
    public void testEmptyConfigFiles() throws IOException, JSchException, InterruptedException {
        ContextBuilder b = new ContextBuilder().withConfigFiles((FilePath[])null);
        b.executeCommand();

        verify(b.context, times(1)).setDeploymentState(DeploymentState.HasError);
        verify(b.externalUtils, never()).buildJSchClient(
                any(String.class),
                any(Integer.TYPE),
                any(String.class),
                any(SSHUserPrivateKey.class),
                any(IBaseCommandData.class));

        b = new ContextBuilder().withConfigFiles();
        b.executeCommand();
        verify(b.context, times(1)).setDeploymentState(DeploymentState.HasError);
        verify(b.externalUtils, never()).buildJSchClient(
                any(String.class),
                any(Integer.TYPE),
                any(String.class),
                any(SSHUserPrivateKey.class),
                any(IBaseCommandData.class));
    }

    @Test
    public void testNoRegistryCredentials() throws IOException, JSchException, InterruptedException {
        ContextBuilder b = new ContextBuilder().withoutRegistryCredentials();
        b.executeCommand();

        verify(b.jSchClient, never()).execRemote("mkdir -p -- '" + DCOS_CREDENTIALS_PATH + "'");
        verify(b.context, times(1)).setDeploymentState(DeploymentState.Success);
    }

    /**
     * Log warning but allow it to proceed.
     *
     * @see com.microsoft.jenkins.acs.util.DeployHelper#checkURIForMarathon(String)
     */
    @Test
    public void testInvalidDcosCredentialsPath() throws IOException, JSchException, InterruptedException {
        ContextBuilder b = new ContextBuilder().withDcosCredentialsPath("/invalid'path*");
        b.executeCommand();

        verify(b.context, times(1)).setDeploymentState(DeploymentState.Success);
    }

    private static class ContextBuilder {
        MarathonDeploymentCommand.IMarathonDeploymentCommandData context;

        DeploymentConfig deploymentConfig;
        FilePath configFile;
        FilePath[] configFiles;
        JobContext jobContext;
        EnvVars envVars;
        Run<?, ?> run;
        String dcosDockerCredentialsPath;
        boolean dockerCredentialsPathShared;
        SSHUserPrivateKey sshCredentials;
        MarathonDeploymentCommand.ExternalUtils externalUtils;
        JSchClient jSchClient;
        JSchClient agentClient;
        DockerConfigBuilder dockerConfigBuilder;
        FilePath credentialsPath;
        InputStream dockerConfigStream;
        String remoteDeploymentConfigName;
        ByteArrayInputStream marathonConfigStream;
        String marathonAppId;
        List<DockerRegistryEndpoint> registryEndpoints;

        ContextBuilder() throws IOException, JSchException, InterruptedException {
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

            sshCredentials = mock(SSHUserPrivateKey.class);
            doReturn(sshCredentials).when(context).getSshCredentials();
            doReturn(FQDN).when(context).getMgmtFQDN();
            doReturn(ROOT_USER).when(context).getLinuxAdminUsername();

            deploymentConfig = mock(DeploymentConfig.class);
            doReturn(deploymentConfig).when(context).getDeploymentConfig();
            configFile = mock(FilePath.class);
            doReturn(mock(InputStream.class)).when(configFile).read();
            configFiles = new FilePath[]{configFile};
            when(deploymentConfig.getConfigFiles()).thenReturn(configFiles);

            dcosDockerCredentialsPath = DCOS_CREDENTIALS_PATH;
            doReturn(dcosDockerCredentialsPath).when(context).getDcosDockerCredentialsPath();
            dockerCredentialsPathShared = true;
            doReturn(dockerCredentialsPathShared).when(context).isDcosDockerCredenditalsPathShared();

            externalUtils = mock(MarathonDeploymentCommand.ExternalUtils.class);
            jSchClient = mock(JSchClient.class);
            doReturn(jSchClient).when(externalUtils).buildJSchClient(
                    any(String.class),
                    any(Integer.TYPE),
                    any(String.class),
                    any(SSHUserPrivateKey.class),
                    any(IBaseCommandData.class));

            final String agent = "10.32.0.5";
            List<String> agents = new ArrayList<>(Collections.singletonList(agent));
            doReturn(agents).when(externalUtils).getAgentNodes(jSchClient);

            agentClient = mock(JSchClient.class);
            doReturn(agentClient).when(jSchClient).forwardSSH(agent, Constants.DEFAULT_SSH_PORT);

            registryEndpoints = mock(List.class);
            doReturn(false).when(registryEndpoints).isEmpty();
            doReturn(registryEndpoints).when(context).getContainerRegistryCredentials();

            dockerConfigBuilder = mock(DockerConfigBuilder.class);
            doReturn(dockerConfigBuilder).when(externalUtils).buildDockerConfigBuilder(registryEndpoints);
            credentialsPath = mock(FilePath.class);
            dockerConfigStream = mock(InputStream.class);
            doReturn(dockerConfigStream).when(credentialsPath).read();
            doReturn(credentialsPath).when(dockerConfigBuilder).buildArchiveForMarathon(jobContext);
            remoteDeploymentConfigName = REMOTE_APP_CONFIG_NAME;
            doReturn(remoteDeploymentConfigName).when(externalUtils).buildRemoteDeployConfigName();

            marathonConfigStream = mock(ByteArrayInputStream.class);
            doReturn(marathonConfigStream).when(jobContext).replaceMacro(any(InputStream.class), any(Boolean.TYPE));

            marathonAppId = MARATHON_APP_ID;
            doReturn(marathonAppId).when(externalUtils).getMarathonAppId(marathonConfigStream);

            dockerConfigBuilder = mock(DockerConfigBuilder.class);

            doReturn(true).when(context).isEnableConfigSubstitution();
        }

        ContextBuilder withDeploymentConfig(DeploymentConfig config) throws IOException, InterruptedException {
            this.deploymentConfig = config;
            doReturn(deploymentConfig).when(context).getDeploymentConfig();
            return this;
        }

        ContextBuilder withConfigFiles(FilePath... configFiles) {
            this.configFiles = configFiles;
            when(deploymentConfig.getConfigFiles()).thenReturn(configFiles);
            return this;
        }

        ContextBuilder withoutRegistryCredentials() {
            doReturn(true).when(registryEndpoints).isEmpty();
            return this;
        }

        ContextBuilder withDcosCredentialsPath(String path) {
            this.dcosDockerCredentialsPath = path;
            doReturn(path).when(context).getDcosDockerCredentialsPath();
            return this;
        }

        void executeCommand() {
            new MarathonDeploymentCommand(externalUtils).execute(context);
        }
    }
}
