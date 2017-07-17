package com.microsoft.jenkins.acs.util;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Util;
import hudson.model.Action;
import hudson.model.EnvironmentContributor;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.VariableResolver;
import org.apache.commons.lang.StringUtils;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

public final class DeployHelper {

    private DeployHelper() {
        // Hide
    }

    public static String generateRandomDeploymentFileName(final String suffix) {
        return "acsDep" + Calendar.getInstance().getTimeInMillis() + "." + suffix;
    }

    /**
     * Escape the ' (single quote) in the argument so that it can be safely used in a singly quoted shell command
     * argument.
     * <p>
     * For example, <code>let's go</code> will be quoted to <code>let'"'"'s go</code>. If this is fed as a singly
     * quoted argument, it will be <code>'let'"'"'s go'</code>, that is, the concatenation of <code>'let'</code>,
     * <code>"'"</code> and <code>'s go'</code>.
     *
     * @param arg the command line argument which may have single quote
     * @return escaped argument which can be safely enclosed in single quotes as shell command argument.
     */
    public static String escapeSingleQuote(final String arg) {
        return arg.replaceAll("[']", "'\"'\"'");
    }

    public static String encodeURIPath(final String path) {
        try {
            URI uri = new URI(null, null, path, null);
            return uri.toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Remove variables (in the form $VAR or ${VAR}) from the given string
     *
     * @param value the input string
     * @return the result string with all variables removed
     */
    public static String removeVariables(final String value) {
        return replaceVariables(value, "");
    }

    /**
     * Replace each of the variables (in the form $VAR or ${VAR}) from the given string with the given replacement.
     *
     * @param value       the input string
     * @param replacement the replacement for all the variables
     * @return the result string with all variables replaced with the given replacement
     */
    public static String replaceVariables(final String value, final String replacement) {
        return Util.replaceMacro(value, new VariableResolver<String>() {
            @Override
            public String resolve(final String name) {
                return replacement;
            }
        });
    }

    /**
     * Check if the URI is Marathon compatible.
     * <p>
     * Due to the limitation in the underlying Mesos fetcher used by Marathon, some special characters as well as
     * the URI escaped sequences are not allowed in the file:// URI paths.
     * <p>
     * At the time of the implementation (Mesos 1.3.x), these limitations are still there, we need to warn the user
     * of such problems.
     *
     * @param path the URI path being checked
     * @return if the URI path can be accepted by the Marathon
     */
    public static boolean checkURIForMarathon(final String path) {
        if (StringUtils.isEmpty(path)) {
            return false;
        }

        // https://github.com/apache/mesos/blob/1.3.x/src/slave/containerizer/fetcher.cpp#L119
        // Illegal characters defined by the Mesos fetcher
        if (path.indexOf('\\') >= 0
                || path.indexOf('\'') >= 0
                || path.indexOf('\0') >= 0) {
            return false;
        }

        // https://github.com/apache/mesos/blob/1.3.x/src/slave/containerizer/fetcher.cpp#L219
        // URI escaped characters will not be unescaped before returning the local path for the file:// URI.
        return path.equals(encodeURIPath(path));
    }

    /**
     * Add an environment variable binding for the given Run.
     * <p>
     * The {@link hudson.model.EnvironmentContributingAction} is more preferred for the environment variable injection.
     * However, is not compatible with workflow at the time of implementation.
     * <p>
     * We register the {@link CustomEnvironmentContributor} which scans for private
     * {@code EnvironmentInjectionAction} bound to the Run instance, and updates the environment variables
     * accordingly. This will be called by {@link Run#getEnvironment(TaskListener)}.
     *
     * @param run   the run object
     * @param name  the variable name
     * @param value the variable value
     * @see CustomEnvironmentContributor
     * @see <a href="https://issues.jenkins-ci.org/browse/JENKINS-29537">JENKINS-29537</a>
     */
    public static void injectEnvironmentVariable(final Run<?, ?> run, final String name, final Object value) {
        run.addAction(new EnvironmentInjectionAction(name, value.toString()));
    }

    @Extension
    public static final class CustomEnvironmentContributor extends EnvironmentContributor {
        @Override
        public void buildEnvironmentFor(@Nonnull final Run r,
                                        @Nonnull final EnvVars envs,
                                        @Nonnull final TaskListener listener) throws IOException, InterruptedException {
            super.buildEnvironmentFor(r, envs, listener);
            EnvironmentInjectionAction action = r.getAction(EnvironmentInjectionAction.class);
            if (action != null) {
                envs.putAll(action.getEnvs());
            }
        }
    }

    private static class EnvironmentInjectionAction implements Action {
        private final Map<String, String> pairs;

        EnvironmentInjectionAction(final String name, final String value) {
            pairs = new HashMap<>();
            pairs.put(name, value);
        }

        EnvironmentInjectionAction(final Map<String, String> inputs) {
            pairs = new HashMap<>(inputs);
        }

        Map<String, String> getEnvs() {
            // no need to copy as it will only be used internally
            return pairs;
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
}
