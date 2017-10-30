package com.microsoft.jenkins.acs.util;

import com.microsoft.jenkins.azurecommons.Constants;
import com.microsoft.jenkins.azurecommons.Messages;
import hudson.EnvVars;
import hudson.Util;
import hudson.util.VariableResolver;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Calendar;
import java.util.Map;

public final class DeployHelper {

    private DeployHelper() {
        // Hide
    }

    public static String generateRandomDeploymentFileName(String suffix) {
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
    public static String escapeSingleQuote(String arg) {
        return arg.replaceAll("[']", "'\"'\"'");
    }

    public static String encodeURIPath(String path) {
        try {
            URI uri = new URI(null, null, path, null);
            return uri.toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Remove variables (in the form $VAR or ${VAR}) from the given string.
     *
     * @param value the input string
     * @return the result string with all variables removed
     */
    public static String removeVariables(String value) {
        return replaceVariables(value, "");
    }

    /**
     * Replace each of the variables (in the form $VAR or ${VAR}) from the given string with the given replacement.
     *
     * @param value       the input string
     * @param replacement the replacement for all the variables
     * @return the result string with all variables replaced with the given replacement
     */
    public static String replaceVariables(String value, final String replacement) {
        return Util.replaceMacro(value, new VariableResolver<String>() {
            @Override
            public String resolve(String name) {
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
    public static boolean checkURIForMarathon(String path) {
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

    public static ByteArrayInputStream replaceMacro(
            InputStream original, EnvVars envVars, boolean enabled) throws IOException {
        try {
            String content = IOUtils.toString(original, com.microsoft.jenkins.azurecommons.Constants.UTF8);
            if (enabled) {
                content = Util.replaceMacro(content, envVars);
            }
            if (content != null) {
                return new ByteArrayInputStream(content.getBytes(Constants.UTF8));
            } else {
                throw new IllegalArgumentException(Messages.JobContext_nullContent());
            }
        } finally {
            original.close();
        }
    }

    public static <T> T getProperty(Object properties, String path, Class<T> type) {
        return getProperty(properties, "", path, type);
    }

    private static <T> T getProperty(Object properties, String visited, String remain, Class<T> type) {
        if (StringUtils.isBlank(remain)) {
            return type.cast(properties);
        }
        if (properties == null || !(properties instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(visited + " is not a map: " + properties);
        }
        Map<?, ?> map = (Map<?, ?>) properties;
        String[] parts = remain.split("\\.", 2);
        if (parts.length == 2) {
            remain = parts[1];
        } else {
            remain = "";
        }
        visited = StringUtils.isEmpty(visited) ? parts[0] : (visited + "." + parts[0]);
        return getProperty(map.get(parts[0]), visited, remain, type);
    }
}
