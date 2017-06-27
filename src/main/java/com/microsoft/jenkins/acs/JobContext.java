package com.microsoft.jenkins.acs;

import com.microsoft.jenkins.acs.util.Constants;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.apache.commons.io.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;

/**
 * Encapsulates the context for a Jenkins build job.
 */
public class JobContext {
    private final Run<?, ?> run;
    private final FilePath workspace;
    private final Launcher launcher;
    private final TaskListener taskListener;

    public JobContext(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener taskListener) {
        this.run = run;
        this.workspace = workspace;
        this.launcher = launcher;
        this.taskListener = taskListener;
    }

    public Run<?, ?> getRun() {
        return run;
    }

    public FilePath getWorkspace() {
        return workspace;
    }

    public Launcher getLauncher() {
        return launcher;
    }

    public TaskListener getTaskListener() {
        return taskListener;
    }

    public EnvVars envVars() {
        try {
            return getRun().getEnvironment(getTaskListener());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to get Job environment variables", e);
        }
    }

    public PrintStream logger() {
        return getTaskListener().getLogger();
    }

    public FilePath workspacePath() {
        return new FilePath(launcher.getChannel(), workspace.getRemote());
    }

    public InputStream replaceMacro(InputStream original, boolean enabled) throws IOException {
        if (!enabled) {
            return original;
        }
        try {
            String content = IOUtils.toString(original, Constants.DEFAULT_CHARSET);
            if (content != null) {
                content = Util.replaceMacro(content, envVars());
                return new ByteArrayInputStream(content.getBytes());
            } else {
                throw new IllegalArgumentException("null content returned");
            }
        } finally {
            original.close();
        }
    }
}
