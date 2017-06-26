/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs.util;

import java.io.File;

public class Constants {
    /**
     * Status messages
     */
    public static final String OP_SUCCESS = "Success";

    public static final String INVALID_OPTION = "*";

    public static final File TEMP_DIR = new File(System.getProperty("java.io.tmpdir"));

    public static final String KUBECONFIG_FILE = ".kube/config";

    public static final String KUBECONFIG_PREFIX = "kubeconfig-";
}
