package com.microsoft.jenkins.acs.util;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.microsoft.jenkins.acs.commands.IBaseCommandData;
import hudson.util.Secret;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Properties;

/**
 * An SSH client used to talk to the remote server via JSch.
 */
public class JSchClient {
    private final String host;
    private final int port;
    private final String username;
    private final IBaseCommandData context;

    private final JSch jsch;
    private final Session session;

    public JSchClient(
            final String host,
            final int port,
            @Nullable final String username,
            final SSHUserPrivateKey credentials,
            IBaseCommandData context) {
        this.host = host;
        this.port = port;
        this.username = StringUtils.isBlank(username) ? username : credentials.getUsername();
        this.context = context;

        this.jsch = new JSch();
        final Secret userPassphrase = credentials.getPassphrase();
        String passphrase = null;
        if (userPassphrase != null) {
            passphrase = userPassphrase.getPlainText();
        }
        byte[] passphraseBytes = null;
        try {
            if (passphrase != null) {
                passphraseBytes = passphrase.getBytes(Constants.DEFAULT_CHARSET);
            }
            int seq = 0;
            for (String privateKey : credentials.getPrivateKeys()) {
                String name = this.username;
                if (seq++ != 0) {
                    name += "-" + seq;
                }
                jsch.addIdentity(name, privateKey.getBytes(Constants.DEFAULT_CHARSET), null, passphraseBytes);
            }

            this.session = jsch.getSession(this.username, this.host, this.port);
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            this.session.setConfig(config);
            this.session.connect();
        } catch (JSchException | UnsupportedEncodingException e) {
            throw new IllegalArgumentException("Failed to create SSH session", e);
        }
    }

    public void copyTo(File sourceFile, String remotePath) throws JSchException {
        ChannelSftp channel = null;
        try {
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect();
            try {
                channel.put(sourceFile.getAbsolutePath(), remotePath);
            } catch (SftpException e) {
                throw new JSchException(String.format("Error uploading file `%s'", sourceFile.getAbsolutePath()), e);
            }
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    public void copyFrom(String remotePath, File destFile) throws JSchException {
        ChannelSftp channel = null;
        try {
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect();
            try {
                channel.get(remotePath, destFile.getAbsolutePath());
            } catch (SftpException e) {
                throw new JSchException(String.format("Error fetching remote file `%s'", remotePath), e);
            }
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    public void execRemote(String command) throws JSchException, IOException {
        ChannelExec channel = null;
        try {

            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);

            context.logStatus("==> exec: " + command);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];

            channel.connect();
            InputStream in = channel.getInputStream();

            while (true) {
                do {
                    // blocks on IO
                    int len = in.read(buffer, 0, buffer.length);
                    if (len < 0) {
                        break;
                    }
                    output.write(buffer, 0, len);
                } while (in.available() > 0);

                if (channel.isClosed()) {
                    if (in.available() > 0) {
                        continue;
                    }
                    context.logStatus("<== command exit status: " + channel.getExitStatus());
                    if (channel.getExitStatus() < 0) {
                        throw new RuntimeException("Error building or running docker image. Process exected with status: " +
                                channel.getExitStatus());
                    }
                    System.out.println("exit-status: " + channel.getExitStatus());
                    break;
                }
            }
            String serverOutput = output.toString(Constants.DEFAULT_CHARSET);
            context.logStatus("<== " + serverOutput);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalArgumentException("Failed to execute command", e);
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    public void close() {
        if (this.session != null) {
            this.session.disconnect();
        }
    }
}
