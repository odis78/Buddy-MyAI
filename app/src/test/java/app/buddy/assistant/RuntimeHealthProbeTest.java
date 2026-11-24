package app.buddy.assistant;

import org.junit.Test;
import java.io.File;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class RuntimeHealthProbeTest {
    @Test public void acceptsOnlySuccessfulLoopbackReadyz() throws Exception {
        File dir = Files.createTempDirectory("buddy-health-test").toFile();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread responder = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    byte[] request = new byte[2048];
                    socket.getInputStream().read(request);
                    OutputStream out = socket.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                            .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } catch (Exception ignored) { }
            });
            responder.start();
            File url = new File(dir, "health.url");
            Files.write(url.toPath(), ("http://127.0.0.1:" + server.getLocalPort())
                    .getBytes(StandardCharsets.UTF_8));
            assertTrue(RuntimeHealthProbe.isReady(url));
            responder.join(2000);
        } finally {
            new File(dir, "health.url").delete();
            dir.delete();
        }
    }


    @Test public void checksBuddyMcpPortOnlyOnLoopback() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            assertTrue(RuntimeHealthProbe.isLoopbackPortOpen(server.getLocalPort()));
            assertFalse(RuntimeHealthProbe.isLoopbackPortOpen(65535));
        }
    }

    @Test public void rejectsRemoteHealthUrlWithoutConnecting() throws Exception {
        File dir = Files.createTempDirectory("buddy-health-test").toFile();
        File url = new File(dir, "health.url");
        try {
            Files.write(url.toPath(), "http://example.com:8080".getBytes(StandardCharsets.UTF_8));
            assertFalse(RuntimeHealthProbe.isReady(url));
        } finally {
            url.delete();
            dir.delete();
        }
    }

    @Test public void rejectsNonReadyResponse() throws Exception {
        File dir = Files.createTempDirectory("buddy-health-test").toFile();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread responder = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.getInputStream().read(new byte[2048]);
                    socket.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (Exception ignored) { }
            });
            responder.start();
            File url = new File(dir, "health.url");
            Files.write(url.toPath(), ("http://127.0.0.1:" + server.getLocalPort())
                    .getBytes(StandardCharsets.UTF_8));
            assertFalse(RuntimeHealthProbe.isReady(url));
            responder.join(2000);
        } finally {
            new File(dir, "health.url").delete();
            dir.delete();
        }
    }
}
