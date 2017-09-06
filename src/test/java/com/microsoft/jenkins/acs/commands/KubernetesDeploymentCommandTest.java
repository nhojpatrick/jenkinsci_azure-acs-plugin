/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.microsoft.jenkins.kubernetes.KubernetesClientWrapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.Test;

import java.net.URL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link KubernetesDeploymentCommand}.
 */
public class KubernetesDeploymentCommandTest {
    @Test
    public void testGetMasterHost() {
        KubernetesDeploymentCommand command = new KubernetesDeploymentCommand();

        assertEquals("Unknown", command.getMasterHost(null));

        KubernetesClientWrapper wrapper = mock(KubernetesClientWrapper.class);
        assertEquals("Unknown", command.getMasterHost(wrapper));

        KubernetesClient client = mock(KubernetesClient.class);
        when(wrapper.getClient()).thenReturn(client);
        assertEquals("Unknown", command.getMasterHost(wrapper));

        URL url = mock(URL.class);
        when(client.getMasterUrl()).thenReturn(url);
        assertNull(command.getMasterHost(wrapper));

        when(url.getHost()).thenReturn("example.com");
        assertEquals("example.com", command.getMasterHost(wrapper));
    }
}
