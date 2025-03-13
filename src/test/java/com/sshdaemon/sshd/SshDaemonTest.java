package com.sshdaemon.sshd;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;

import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.apache.sshd.common.compression.BuiltinCompressions.delayedZlib;
import static org.apache.sshd.common.compression.BuiltinCompressions.zlib;

class SshDaemonTest {

    private SshClient client;
    private ClientSession session;
    private SftpClient sftpClient;

    @BeforeEach
    void setUp() throws Exception {
        client = SshClient.setUpDefaultClient();
        client.setCompressionFactories(List.of(zlib, delayedZlib));
        client.start();

        session = client.connect("user", "localhost", 8022)
                .verify(10, TimeUnit.SECONDS)
                .getSession();
        session.addPasswordIdentity("pass");
        session.auth().verify(10, TimeUnit.SECONDS);
        sftpClient = SftpClientFactory.instance().createSftpClient(session);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (sftpClient != null) {
            sftpClient.close();
        }
        if (session != null) {
            session.close();
        }
        if (client != null) {
            client.stop();
        }
    }

    @RepeatedTest(5)
    void uploadLatency() throws Exception {
        var uploadSizes = Arrays.asList(1, 5, 10, 50, 100);
        var totalSpeed = 0d;
        for (int megabytes : uploadSizes) {
            long t0 = System.currentTimeMillis();
            try (SftpClient client = SftpClientFactory.instance().createSftpClient(session)) {
                try (OutputStream os = client.write("out.txt", 32768,
                        SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate)) {
                    byte[] bytes = "123456789abcdef\n".getBytes();
                    for (int i = 0; i < 1024 * 1024 * megabytes / bytes.length; i++) {
                        os.write(bytes);
                    }
                }
            }
            long t1 = System.currentTimeMillis();
            long uploadDuration = t1 - t0;
            var speed = ((double) (megabytes * 1000 * 1024)) / (uploadDuration);
            totalSpeed += speed;
            System.out.println("Upload duration: " + uploadDuration + " ms for " + megabytes + " MB" + " speed: " + speed + " kB/s");
        }
        System.out.println("Average speed: " + totalSpeed / uploadSizes.size() + " kB/s");
    }
}