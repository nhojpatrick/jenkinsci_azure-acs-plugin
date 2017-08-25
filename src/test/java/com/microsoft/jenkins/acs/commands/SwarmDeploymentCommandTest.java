/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.microsoft.jenkins.azurecommons.remote.SSHClient;
import com.microsoft.jenkins.kubernetes.credentials.ResolvedDockerRegistryEndpoint;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryToken;
import org.junit.Before;
import org.junit.Test;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static com.microsoft.jenkins.acs.commands.SwarmDeploymentCommand.prepareCredentialsForSwarm;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests for the {@link SwarmDeploymentCommand}.
 */
public class SwarmDeploymentCommandTest {
    private ResolvedDockerRegistryEndpoint[] endpoints;

    @Before
    public void setup() throws Exception {
        endpoints = new ResolvedDockerRegistryEndpoint[]{
                new ResolvedDockerRegistryEndpoint(
                        new URL("https://index.docker.io/v1/"),
                        new DockerRegistryToken("user", "dXNlcjpwYXNzd29yZA==")), // user:password
                new ResolvedDockerRegistryEndpoint(
                        new URL("http://acr.azurecr.io"),
                        new DockerRegistryToken("anotherUser", "YW5vdGhlclVzZXI6aGFoYWhh")) // anotherUser:hahaha
        };
    }

    @Test
    public void testPrepareCredentialsOnAgents() throws Exception {
        SSHClient client = mock(SSHClient.class);
        prepareCredentialsForSwarm(client, new ArrayList<ResolvedDockerRegistryEndpoint>(), System.out);
        verify(client, never()).execRemote(any(String.class), any(Boolean.TYPE), any(Boolean.TYPE));

        client = mock(SSHClient.class);
        prepareCredentialsForSwarm(client, Arrays.asList(endpoints), System.out);
        verify(client, times(1)).execRemote("docker login -u 'user' -p 'password' 'https://index.docker.io/v1/'", false, false);
        verify(client, times(1)).execRemote("docker login -u 'anotherUser' -p 'hahaha' 'http://acr.azurecr.io'", false, false);

        ResolvedDockerRegistryEndpoint emptyAuth = new ResolvedDockerRegistryEndpoint(
                new URL("https://index.docker.io/v1/"),
                new DockerRegistryToken("user", ""));
        try {
            prepareCredentialsForSwarm(client, Collections.singletonList(emptyAuth), System.out);
            fail("Should throw if password is missing");
        } catch (IllegalArgumentException e) {
            // expected
        }

        ResolvedDockerRegistryEndpoint missingPassword = new ResolvedDockerRegistryEndpoint(
                new URL("https://index.docker.io/v1/"),
                new DockerRegistryToken("user", "dXNlcg==")); // user
        try {
            prepareCredentialsForSwarm(client, Collections.singletonList(missingPassword), System.out);
            fail("Should throw on empty auth");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }
}
