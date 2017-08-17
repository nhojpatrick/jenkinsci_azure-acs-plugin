/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import hudson.model.Result;

/**
 * The conditions when the deployment should be proceeded.
 */
public enum RunOn {
    Success,
    SuccessOrUnstable;

    public boolean shouldRunOn(Result result) {
        if (result == null) {
            // when running in pipeline, the result will be null, skip the check
            return true;
        }
        switch (this) {
            case Success:
                return result == Result.SUCCESS;
            case SuccessOrUnstable:
                return result.isBetterOrEqualTo(Result.UNSTABLE);
            default:
                return false;
        }
    }

    public static RunOn fromString(String value) {
        switch (value) {
            case "SuccessOrUnstable":
                return SuccessOrUnstable;
            default:
                return Success;
        }
    }
}
