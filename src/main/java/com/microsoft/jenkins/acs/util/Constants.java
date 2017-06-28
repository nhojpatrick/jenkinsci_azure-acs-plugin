/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.util;

import com.microsoft.azure.management.compute.ContainerServiceOchestratorTypes;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class Constants {
    public static final String INVALID_OPTION = "*";

    public static final File TEMP_DIR = new File(System.getProperty("java.io.tmpdir"));

    public static final String KUBECONFIG_FILE = ".kube/config";

    public static final String KUBECONFIG_PREFIX = "kubeconfig-";

    public static final String DEFAULT_CHARSET = "UTF-8";

    /**
     * Increment step for the priority of the network security rule.
     */
    public static final int PRIORITY_STEP = 10;
    /**
     * Lowest priority for the network security rule (value range: [100, 4096]). Smaller number got higher priority.
     */
    public static final int LOWEST_PRIORITY = 4096;

    public static final int READ_BUFFER_SIZE = 1024;

    public static final Set<ContainerServiceOchestratorTypes> SUPPORTED_ORCHESTRATOR = new HashSet<>(Arrays.asList(
            ContainerServiceOchestratorTypes.KUBERNETES,
            ContainerServiceOchestratorTypes.DCOS
    ));

    public static final int DCOS_SSH_PORT = 2200;
    public static final int KUBERNETES_SSH_PORT = 22;

    public static int sshPort(final ContainerServiceOchestratorTypes type) {
        switch (type) {
            case DCOS:
                return DCOS_SSH_PORT;
            case KUBERNETES:
                return KUBERNETES_SSH_PORT;
            default:
                return -1;
        }
    }

    private Constants() {
        // hide constructor
    }
}
