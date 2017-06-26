package com.microsoft.jenkins.acs.util;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.microsoft.jenkins.acs.Messages;
import hudson.util.Secret;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Properties;

/**
 * Helper methods that use JSch library to talk to SSH server.
 */
public final class JSchHelper {
    public static final int DEFAULT_SSH_PORT = 22;

    private static final int BUFFER_SIZE = 1024;
    private static final int FILE_PERM_LENGTH = 5;     // '0644
    private static final long SIZE_RADIX = 10L;
    private static final byte LF = 0x0A;

    private static final String CHARSET = "UTF-8";

    /**
     * Using scp to transfer remote file to local.
     *
     * @param logger         Logger for informational messages.
     * @param host           SSH host
     * @param port           SSH port
     * @param username       SSH username
     * @param sshCredentials SSH credentials used to authenticate with the server.
     * @param remoteFile     Remote file to be transferred
     * @param localFile      Local file to be stored
     * @see <a href="http://www.jcraft.com/jsch/examples/ScpFrom.java.html">ScpFrom.java</a>
     */
    public static void scpFrom(
            final PrintStream logger,
            final String host, final int port,
            final String username,
            final SSHUserPrivateKey sshCredentials,
            final String remoteFile,
            final File localFile) throws Exception {

        logger.println(Messages.JSchHelper_start(host, remoteFile, localFile.getAbsolutePath()));

        FileOutputStream fos = null;
        JSch jsch = new JSch();

        try {
            final Secret userPassphrase = sshCredentials.getPassphrase();
            String passphrase = null;
            if (userPassphrase != null) {
                passphrase = userPassphrase.getPlainText();
            }
            byte[] passphraseBytes = null;
            if (passphrase != null) {
                passphraseBytes = passphrase.getBytes(CHARSET);
            }
            int seq = 0;
            for (String privateKey : sshCredentials.getPrivateKeys()) {
                String name = username;
                if (seq++ != 0) {
                    name += "-" + seq;
                }
                jsch.addIdentity(name, privateKey.getBytes(CHARSET), null, passphraseBytes);
            }

            Session session = jsch.getSession(username, host, port);

            Properties props = new Properties();
            props.put("StrictHostKeyChecking", "no");
            session.setConfig(props);
            session.connect();

            try {
                // exec 'scpFrom -f rfile' remotely
                String command = "scp -f " + remoteFile;
                Channel channel = session.openChannel("exec");
                ((ChannelExec) channel).setCommand(command);

                // get I/O streams for remote scpFrom
                OutputStream out = channel.getOutputStream();
                InputStream in = channel.getInputStream();

                channel.connect();

                byte[] buf = new byte[BUFFER_SIZE];

                // send '\0'
                buf[0] = 0;
                out.write(buf, 0, 1);
                out.flush();

                int expectedLength;
                int readLength;
                while (true) {
                    int c = checkAck(in);
                    if (c != 'C') {
                        break;
                    }

                    // read '0644 '
                    expectedLength = FILE_PERM_LENGTH;
                    while (expectedLength > 0) {
                        readLength = in.read(buf, 0, expectedLength);
                        if (readLength < 0) {
                            failedToRead();
                        }
                        expectedLength -= readLength;
                    }

                    long filesize = 0L;
                    while (true) {
                        if (in.read(buf, 0, 1) < 0) {
                            failedToRead();
                        }
                        if (buf[0] == ' ') {
                            break;
                        }
                        filesize = filesize * SIZE_RADIX + (long) (buf[0] - '0');
                    }

                    //String file;
                    for (int i = 0; true; i++) {
                        readLength = in.read(buf, i % buf.length, 1); // modular hack to prevent overflow
                        if (readLength < 0) {
                            failedToRead();
                        } else if (readLength == 0) {
                            continue;
                        }
                        if (buf[i] == LF) {
                            // file = new String(buf, 0, i);
                            break;
                        }
                    }

                    // send '\0'
                    buf[0] = 0;
                    out.write(buf, 0, 1);
                    out.flush();

                    // read a content of lfile
                    fos = new FileOutputStream(localFile);
                    while (true) {
                        if (buf.length < filesize) {
                            readLength = buf.length;
                        } else {
                            readLength = (int) filesize;
                        }
                        readLength = in.read(buf, 0, readLength);
                        if (readLength < 0) {
                            failedToRead();
                        }
                        fos.write(buf, 0, readLength);
                        filesize -= readLength;
                        if (filesize == 0L) {
                            break;
                        }
                    }
                    fos.close();
                    fos = null;

                    c = checkAck(in);
                    if (c != 0) {
                        throw new RuntimeException(Messages.JSchHelper_unexpectedStatus() + c);
                    }

                    // send '\0'
                    buf[0] = 0;
                    out.write(buf, 0, 1);
                    out.flush();
                }
                logger.println(Messages.JSchHelper_success());
            } finally {
                session.disconnect();
            }
        } finally {
            if (fos != null) {
                fos.close();
            }
        }
    }

    private static void failedToRead() {
        throw new RuntimeException(Messages.JSchHelper_failToRead());
    }

    private static int checkAck(final InputStream in) throws IOException {
        int b = in.read();
        // b may be 0 for success,
        //          1 for error,
        //          2 for fatal error,
        //          -1
        switch (b) {
            case 0:
            case -1:
                return b;
            case 1:
            case 2:
                StringBuilder sb = new StringBuilder();
                if (b == 2) {
                    sb.append(Messages.JSchHelper_fatal());
                }

                int c;
                do {
                    c = in.read();
                    sb.append((char) c);
                } while (c != '\n');

                throw new RuntimeException(sb.toString());
            default:
                return b;
        }
    }

    private JSchHelper() {
        // hide constructor
    }
}
