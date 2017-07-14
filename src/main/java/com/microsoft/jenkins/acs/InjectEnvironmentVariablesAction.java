/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs;

import hudson.EnvVars;
import hudson.model.AbstractBuild;
import hudson.model.EnvironmentContributingAction;

import java.util.HashMap;
import java.util.Map;

/**
 * Inject environment variables so that they can be used during a build.
 *
 * @see hudson.model.EnvironmentContributingAction
 */
public class InjectEnvironmentVariablesAction implements EnvironmentContributingAction {
    private final Map<String, String> envs;

    public InjectEnvironmentVariablesAction(final String name, final String value) {
        this.envs = new HashMap<>();
        this.envs.put(name, value);
    }

    public InjectEnvironmentVariablesAction(final Map<String, String> pairs) {
        this.envs = new HashMap<>(pairs);
    }

    /**
     * This will be called in {@link AbstractBuild#getEnvironment(hudson.model.TaskListener)}
     */
    @Override
    public void buildEnvVars(final AbstractBuild<?, ?> build, final EnvVars env) {
        for (Map.Entry<String, String> entry : envs.entrySet()) {
            env.put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return null;
    }
}
