package com.sshclientjr;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

final class SshSessionFactory {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int SOCKET_TIMEOUT_MS = 60_000;
    private static final int SERVER_ALIVE_INTERVAL_MS = 10_000;
    private static final int SERVER_ALIVE_COUNT_MAX = 8;

    private SshSessionFactory() {
    }

    static Session connect(String host, int port, String username, String password, String privateKey, String passphrase) throws JSchException {
        JSch jsch = new JSch();
        boolean usePrivateKey = privateKey != null && !privateKey.trim().isEmpty();
        if (usePrivateKey) {
            byte[] passphraseBytes = (passphrase == null || passphrase.isEmpty()) ? null : passphrase.getBytes(StandardCharsets.UTF_8);
            jsch.addIdentity(
                    username + "@sshclientjr",
                    privateKey.getBytes(StandardCharsets.UTF_8),
                    null,
                    passphraseBytes
            );
        }

        Session session = jsch.getSession(username, host, port);
        if (password != null && !password.isEmpty()) {
            session.setPassword(password);
        }

        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        if (usePrivateKey) {
            config.put("PreferredAuthentications", "publickey,password,keyboard-interactive");
            config.put("PubkeyAuthentication", "yes");
        } else {
            config.put("PreferredAuthentications", "password,keyboard-interactive");
            config.put("PubkeyAuthentication", "no");
        }
        session.setConfig(config);
        session.setServerAliveInterval(SERVER_ALIVE_INTERVAL_MS);
        session.setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX);
        session.setTimeout(SOCKET_TIMEOUT_MS);
        session.connect(CONNECT_TIMEOUT_MS);
        return session;
    }
}
