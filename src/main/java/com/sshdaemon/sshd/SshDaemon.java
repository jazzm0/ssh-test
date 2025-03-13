package com.sshdaemon.sshd;

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.common.util.security.bouncycastle.BouncyCastleSecurityProviderRegistrar;
import org.apache.sshd.common.util.threads.ThreadUtils;
import org.apache.sshd.contrib.server.subsystem.sftp.SimpleAccessControlSftpEventListener;
import org.apache.sshd.server.ServerBuilder;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.InteractiveProcessShellFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Paths;
import java.security.Security;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static java.lang.Math.max;
import static org.apache.sshd.common.cipher.BuiltinCiphers.aes128ctr;
import static org.apache.sshd.common.cipher.BuiltinCiphers.aes128gcm;
import static org.apache.sshd.common.cipher.BuiltinCiphers.aes192ctr;
import static org.apache.sshd.common.cipher.BuiltinCiphers.aes256ctr;
import static org.apache.sshd.common.cipher.BuiltinCiphers.aes256gcm;
import static org.apache.sshd.common.compression.BuiltinCompressions.delayedZlib;
import static org.apache.sshd.common.compression.BuiltinCompressions.zlib;

/***
 * __     _        ___
 * / _\___| |__    /   \__ _  ___ _ __ ___   ___  _ __
 * \ \/ __| '_ \  / /\ / _` |/ _ \ '_ ` _ \ / _ \| '_ \
 * _\ \__ \ | | |/ /_// (_| |  __/ | | | | | (_) | | | |
 * \__/___/_| |_/___,' \__,_|\___|_| |_| |_|\___/|_| |_|
 */


public class SshDaemon implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    public static final String SSH_DAEMON = "SshDaemon";

    private static final int THREAD_POOL_SIZE = 10;

    static {
        Security.removeProvider("BC");
        if (SecurityUtils.isRegistrationCompleted()) {
            logger.info("Security provider registration is already completed");
        } else {
            try {
                SecurityUtils.registerSecurityProvider(new BouncyCastleSecurityProviderRegistrar());
                logger.info("Set security provider to:{}, registration completed:{}", BouncyCastleSecurityProviderRegistrar.PROVIDER_CLASS, SecurityUtils.isRegistrationCompleted());
            } catch (Exception e) {
                logger.error("Exception while registering security provider: ", e);
            }
        }
    }

    private SshServer sshd;

    public SshDaemon() {
    }

    public SshDaemon(int port, String user, String password, boolean readOnly) {
        init(port, user, password, readOnly);
    }

    private void init(int port, String user, String password, boolean readOnly) {
        final var rootPath = System.getProperty("user.home");
        final var path = rootPath + "/" + SSH_DAEMON;
        System.setProperty("user.home", rootPath);

        this.sshd = ServerBuilder
                .builder()
                .cipherFactories(List.of(aes128ctr, aes192ctr, aes256ctr, aes128gcm, aes256gcm))
                .compressionFactories(List.of(zlib, delayedZlib))
                .build();

        sshd.setPort(port);

        sshd.setPasswordAuthenticator(new SshPasswordAuthenticator(user, password));

        var simpleGeneratorHostKeyProvider = new SimpleGeneratorHostKeyProvider(Paths.get(path + "/ssh_host_rsa_key"));

        sshd.setKeyPairProvider(simpleGeneratorHostKeyProvider);
        sshd.setShellFactory(new InteractiveProcessShellFactory());

        var threadPools = max(THREAD_POOL_SIZE, Runtime.getRuntime().availableProcessors() * 2);
        logger.info("Thread pool size: {}", threadPools);

        var factory = new SftpSubsystemFactory.Builder()
                .withExecutorServiceProvider(() ->
                        ThreadUtils.newFixedThreadPool("SFTP-Subsystem", threadPools))
                .build();

        if (readOnly) {
            factory.addSftpEventListener((SimpleAccessControlSftpEventListener.READ_ONLY_ACCESSOR));
        }

        sshd.setSubsystemFactories(Collections.singletonList(factory));
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(Paths.get(rootPath)));

        simpleGeneratorHostKeyProvider.loadKeys(null);
    }

    public void start() throws IOException {
        init(8022, "user", "pass", false);
        sshd.start();
    }

    @Override
    public void run() {
        try {
            start();
        } catch (IOException e) {
            logger.error("Failed to start SshDaemon", e);
        }
    }

    public static void main(String[] args) throws IOException {
        SshDaemon sshDaemon = new SshDaemon(8022, "user", "pass", false);
        sshDaemon.start();

        // Create a CountDownLatch to keep the main thread running
        CountDownLatch latch = new CountDownLatch(1);
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Main thread interrupted", e);
        }
    }

}
