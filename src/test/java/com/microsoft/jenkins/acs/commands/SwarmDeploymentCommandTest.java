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
import hudson.FilePath;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import org.apache.commons.codec.binary.Base64;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryToken;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests for the {@link SwarmDeploymentCommand}.
 */
public class SwarmDeploymentCommandTest {
    private static final String FQDN = "fqdn.test";
    private static final String ROOT_USER = "azureuser";
    private static final String DOCKER_USER = "dockeruser";
    private static final String DOCKER_PASSWORD = "dockerpass";
    private static final String DEFAULT_SERVER = "https://index.docker.io/v1/";
    private static final String REMOTE_APP_CONFIG_NAME = "acsDep1234567890.yml";

    @Test
    public void testSuccessfulExecute() throws IOException, JSchException, InterruptedException {
        ContextBuilder b = new ContextBuilder();
        b.executeCommand();

        verify(b.jSchClient, times(1)).execRemote(String.format("docker login -u '%s' -p '%s' '%s'",
                DOCKER_USER, DOCKER_PASSWORD, DEFAULT_SERVER), false);
        verify(b.jSchClient, times(1)).copyTo(b.configFileStream, REMOTE_APP_CONFIG_NAME);
        verify(b.jSchClient, times(1)).execRemote("DOCKER_HOST=:2375 docker-compose -f '" + REMOTE_APP_CONFIG_NAME + "' down");
        verify(b.jSchClient, times(1)).execRemote("DOCKER_HOST=:2375 docker-compose -f '" + REMOTE_APP_CONFIG_NAME + "' up -d");
        verify(b.jSchClient, times(1)).execRemote("rm -f -- '" + REMOTE_APP_CONFIG_NAME + "'");
        verify(b.context, times(1)).setDeploymentState(DeploymentState.Success);
    }

    @Test
    public void testWithNullDeploymentConfig() throws IOException, JSchException, InterruptedException {
        ContextBuilder b = new ContextBuilder().withoutDeploymentConfig();
        b.executeCommand();

        verify(b.jSchClient, never()).execRemote(any(String.class), any(Boolean.TYPE));
        verify(b.context, times(1)).setDeploymentState(DeploymentState.HasError);
    }

    @Test
    public void testWithoutContainerRegistryCredentials() throws IOException, JSchException, InterruptedException {
        ContextBuilder b = new ContextBuilder().withoutContainerRegistryCredentials();
        b.executeCommand();

        verify(b.jSchClient, never()).execRemote(any(String.class), any(Boolean.TYPE));
        verify(b.context, times(1)).setDeploymentState(DeploymentState.Success);
    }

    @Test
    public void testNullDockerRegistryToken() throws IOException, JSchException, InterruptedException {
        ContextBuilder b = new ContextBuilder().withNullDockerRegistryToken();
        b.executeCommand();

        verify(b.jSchClient, never()).execRemote(any(String.class), any(Boolean.TYPE));
        verify(b.context, times(1)).setDeploymentState(DeploymentState.Success);
    }

    @Test
    public void testEmptyDockerRegistryAuthToken() throws IOException, JSchException, InterruptedException {
        ContextBuilder b = new ContextBuilder().withToken("");
        b.executeCommand();

        verify(b.jSchClient, never()).execRemote(any(String.class), any(Boolean.TYPE));
        verify(b.context, times(1)).setDeploymentState(DeploymentState.HasError);
    }

    @Test
    public void testIllegalDockerRegistryAuthToken() throws IOException, JSchException, InterruptedException {
        ContextBuilder b = new ContextBuilder().withToken(base64Encode("only_name"));
        b.executeCommand();

        verify(b.jSchClient, never()).execRemote(any(String.class), any(Boolean.TYPE));
        verify(b.context, times(1)).setDeploymentState(DeploymentState.HasError);
    }

    @Test
    public void testWithoutConfigFiles() throws  IOException, JSchException, InterruptedException {
        ContextBuilder b = new ContextBuilder().withoutConfigFiles();
        b.executeCommand();

        verify(b.jSchClient, never()).copyTo(any(InputStream.class), any(String.class));
        verify(b.context, times(1)).setDeploymentState(DeploymentState.Success);
    }

    private static class ContextBuilder {
        SwarmDeploymentCommand.ISwarmDeploymentCommandData context;

        SSHUserPrivateKey sshCredentials;
        JobContext jobContext;

        DeploymentConfig deploymentConfig;

        SwarmDeploymentCommand.ExternalUtils externalUtils;
        JSchClient jSchClient;

        DockerRegistryEndpoint registryEndpoint;
        DockerRegistryToken registryToken;
        URL effectiveUrl;
        List<DockerRegistryEndpoint> registryEndpoints;

        FilePath configFile;
        FilePath[] configFiles;
        String remoteDeployFileName;
        ByteArrayInputStream configFileStream;

        boolean swarmRemoveContainersFirst;

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

            doReturn(FQDN).when(context).getMgmtFQDN();
            sshCredentials = mock(SSHUserPrivateKey.class);
            doReturn(sshCredentials).when(context).getSshCredentials();
            doReturn(ROOT_USER).when(context).getLinuxAdminUsername();
            doReturn(true).when(context).isEnableConfigSubstitution();

            jobContext = mock(JobContext.class);
            doReturn(jobContext).when(context).jobContext();
            Run<?, ?> run = mock(Run.class);
            Job job = mock(Job.class);
            doReturn(job).when(run).getParent();
            doReturn(run).when(jobContext).getRun();

            deploymentConfig = mock(DeploymentConfig.class);
            doReturn(deploymentConfig).when(context).getDeploymentConfig();

            externalUtils = mock(SwarmDeploymentCommand.ExternalUtils.class);
            jSchClient = mock(JSchClient.class);
            doReturn(jSchClient).when(externalUtils).buildJSchClient(
                    any(String.class),
                    any(Integer.TYPE),
                    any(String.class),
                    any(SSHUserPrivateKey.class),
                    any(IBaseCommandData.class));
            doReturn(REMOTE_APP_CONFIG_NAME).when(externalUtils).buildRemoteDeployConfigName();

            registryEndpoint = mock(DockerRegistryEndpoint.class);
            registryToken = mock(DockerRegistryToken.class);
            doReturn(toToken(DOCKER_USER, DOCKER_PASSWORD)).when(registryToken).getToken();
            effectiveUrl = mock(URL.class);
            doReturn(DEFAULT_SERVER).when(effectiveUrl).toString();
            doReturn(effectiveUrl).when(registryEndpoint).getEffectiveUrl();
            doReturn(registryToken).when(registryEndpoint).getToken(any(Item.class));

            registryEndpoints = new ArrayList<>(Collections.singletonList(registryEndpoint));
            doReturn(registryEndpoints).when(context).getContainerRegistryCredentials();

            configFile = mock(FilePath.class);
            doReturn(mock(InputStream.class)).when(configFile).read();
            configFiles = new FilePath[]{configFile};
            doReturn(configFiles).when(deploymentConfig).getConfigFiles();
            remoteDeployFileName = REMOTE_APP_CONFIG_NAME;
            configFileStream = mock(ByteArrayInputStream.class);
            doReturn(configFileStream).when(jobContext).replaceMacro(any(InputStream.class), any(Boolean.TYPE));

            swarmRemoveContainersFirst = true;
            doReturn(swarmRemoveContainersFirst).when(context).isSwarmRemoveContainersFirst();
        }

        ContextBuilder withoutDeploymentConfig() throws IOException, InterruptedException {
            deploymentConfig = null;
            doReturn(null).when(context).getDeploymentConfig();
            return this;
        }

        ContextBuilder withoutContainerRegistryCredentials() {
            registryEndpoints = new ArrayList<>();
            doReturn(registryEndpoints).when(context).getContainerRegistryCredentials();
            return this;
        }

        ContextBuilder withNullDockerRegistryToken() {
            registryToken = null;
            doReturn(null).when(registryEndpoint).getToken(any(Item.class));
            return this;
        }

        ContextBuilder withToken(String token) {
            doReturn(token).when(registryToken).getToken();
            return this;
        }

        ContextBuilder withoutConfigFiles() {
            configFiles = new FilePath[0];
            doReturn(configFiles).when(deploymentConfig).getConfigFiles();
            return this;
        }

        void executeCommand() {
            new SwarmDeploymentCommand(externalUtils).execute(context);
        }
    }

    private static String toToken(final String user, final String password) {
        return base64Encode(user + ":" + password);
    }

    private static String base64Encode(final String value) {
        try {
            return Base64.encodeBase64String(value.getBytes(Constants.DEFAULT_CHARSET));
        } catch (UnsupportedEncodingException e) {
            fail(e.getMessage());
            return null;
        }
    }
}
