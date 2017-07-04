package com.microsoft.jenkins.acs.util;

import java.util.Calendar;

public final class DeployHelper {

    private DeployHelper() {
        // Hide
    }

    public static String generateRandomDeploymentFileName(final String suffix) {
        return "acsDep" + Calendar.getInstance().getTimeInMillis() + "." + suffix;
    }
}
