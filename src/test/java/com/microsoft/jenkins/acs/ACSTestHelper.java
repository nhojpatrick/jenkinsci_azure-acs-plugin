/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs;

import org.hamcrest.Matcher;
import org.hamcrest.core.IsAnything;

import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

/**
 * Helper methods for {@link ACSDeploymentContext} related tests.
 */
public final class ACSTestHelper {
    public static void expectException(
            final Class<? extends Exception> clazz,
            final ExceptionRunnable runnable) {
        expectException(clazz, new IsAnything<String>(), runnable);
    }

    public static void expectException(
            final Class<? extends Exception> clazz,
            final Matcher<String> matcher,
            final ExceptionRunnable runnable) {
        try {
            runnable.run();
            fail(String.format("Expecting exception %s, nothing thrown", clazz.getName()));
        } catch (Exception e) {
            if (!clazz.isAssignableFrom(e.getClass())) {
                fail(String.format("Expecting exception %s, got %s", clazz.getName(), e.getClass().getName()));
            }
            assertThat("Exception message", e.getMessage(), matcher);
        }
    }

    public interface ExceptionRunnable {
        void run() throws Exception;
    }

    private ACSTestHelper() {
        // hide constructor
    }
}
