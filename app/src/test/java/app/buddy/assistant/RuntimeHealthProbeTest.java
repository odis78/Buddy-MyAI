package app.buddy.assistant;

import org.junit.Test;
import java.io.File;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
        int port;
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = server.getLocalPort();
            assertTrue(RuntimeHealthProbe.isLoopbackPortOpen(port));
        }
        assertFalse(RuntimeHealthProbe.isLoopbackPortOpen(port));
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

    @Test public void requiresSameChildInitializeAndCompleteToolsList() throws Exception {
        File dir = Files.createTempDirectory("buddy-mcp-health-test").toFile();
        String body = "{\"schema_version\":1,\"component\":\"mcp\",\"status\":\"ok\",\"state\":\"discovered\",\"limited\":false,"
                + "\"details\":{\"transport\":\"stdio\",\"child_state\":\"running\",\"child_generation\":\"child-1\",\"evidence\":\"same_child\","
                + "\"initialize\":{\"ok\":true,\"identity_complete\":true,\"limited\":false},"
                + "\"tools_list\":{\"ok\":true,\"complete\":true,\"partial\":false,\"limited\":false,\"retained_count\":2,\"tool_names\":[\"alpha\",\"beta\"]}}}";
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread responder = respondOnce(server, body);
            File url = new File(dir, "health.url");
            Files.write(url.toPath(), ("http://127.0.0.1:" + server.getLocalPort()).getBytes(StandardCharsets.UTF_8));
            assertTrue(RuntimeHealthProbe.isMcpDiscovered(url, 2));
            responder.join(2000);
        } finally {
            new File(dir, "health.url").delete();
            dir.delete();
        }
    }

    @Test public void rejectsIncompleteOrWrongToolCatalogEvidence() throws Exception {
        File dir = Files.createTempDirectory("buddy-mcp-health-test").toFile();
        String body = "{\"schema_version\":1,\"component\":\"mcp\",\"status\":\"ok\",\"state\":\"discovered\",\"limited\":false,"
                + "\"details\":{\"transport\":\"stdio\",\"child_state\":\"running\",\"child_generation\":\"child-1\",\"evidence\":\"same_child\","
                + "\"initialize\":{\"ok\":true,\"identity_complete\":true,\"limited\":false},"
                + "\"tools_list\":{\"ok\":true,\"complete\":false,\"partial\":true,\"limited\":false,\"retained_count\":2,\"tool_names\":[\"alpha\",\"beta\"]}}}";
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread responder = respondOnce(server, body);
            File url = new File(dir, "health.url");
            Files.write(url.toPath(), ("http://127.0.0.1:" + server.getLocalPort()).getBytes(StandardCharsets.UTF_8));
            assertFalse(RuntimeHealthProbe.isMcpDiscovered(url, 2));
            responder.join(2000);
        } finally {
            new File(dir, "health.url").delete();
            dir.delete();
        }
    }


    @Test public void requiresRecentPollAndAcceptedRemoteResponse() throws Exception {
        File dir = Files.createTempDirectory("buddy-remote-health-test").toFile();
        String now = Instant.now().toString();
        String body = "{\"schema_version\":1,\"live\":true,\"ready\":true,\"components\":{"
                + "\"mcp\":{\"status\":\"ok\",\"state\":\"discovered\",\"limited\":false,\"details\":{"
                + "\"transport\":\"stdio\",\"child_state\":\"running\",\"child_generation\":\"child-1\",\"evidence\":\"same_child\","
                + "\"initialize\":{\"ok\":true,\"identity_complete\":true,\"limited\":false},"
                + "\"tools_list\":{\"ok\":true,\"complete\":true,\"partial\":false,\"limited\":false,\"retained_count\":2,\"tool_names\":[\"alpha\",\"beta\"]}}},"
                + "\"control-plane\":{\"status\":\"ok\",\"state\":\"idle\",\"details\":{\"consecutive_failures\":0,\"configured_wait_seconds\":30,\"deadline_seconds\":60,\"last_success\":\"" + now + "\"}},"
                + "\"response-delivery\":{\"status\":\"ok\",\"state\":\"accepted\",\"details\":{\"accepted\":1,\"last_accepted\":\"" + now + "\"}}}}";
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread responder = respondOnce(server, body);
            File url = new File(dir, "health.url");
            Files.write(url.toPath(), ("http://127.0.0.1:" + server.getLocalPort()).getBytes(StandardCharsets.UTF_8));
            assertTrue(RuntimeHealthProbe.isRemoteConnected(url, 2));
            responder.join(2000);
        } finally {
            new File(dir, "health.url").delete();
            dir.delete();
        }
    }

    @Test public void rejectsStaleAcceptedRemoteResponse() throws Exception {
        File dir = Files.createTempDirectory("buddy-remote-health-test").toFile();
        String now = Instant.now().toString();
        String stale = Instant.now().minusSeconds(7200).toString();
        String body = "{\"schema_version\":1,\"live\":true,\"ready\":true,\"components\":{"
                + "\"mcp\":{\"status\":\"ok\",\"state\":\"discovered\",\"limited\":false,\"details\":{"
                + "\"transport\":\"stdio\",\"child_state\":\"running\",\"child_generation\":\"child-1\",\"evidence\":\"same_child\","
                + "\"initialize\":{\"ok\":true,\"identity_complete\":true,\"limited\":false},"
                + "\"tools_list\":{\"ok\":true,\"complete\":true,\"partial\":false,\"limited\":false,\"retained_count\":2,\"tool_names\":[\"alpha\",\"beta\"]}}},"
                + "\"control-plane\":{\"status\":\"ok\",\"state\":\"idle\",\"details\":{\"consecutive_failures\":0,\"configured_wait_seconds\":30,\"deadline_seconds\":60,\"last_success\":\"" + now + "\"}},"
                + "\"response-delivery\":{\"status\":\"ok\",\"state\":\"accepted\",\"details\":{\"accepted\":1,\"last_accepted\":\"" + stale + "\"}}}}";
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread responder = respondOnce(server, body);
            File url = new File(dir, "health.url");
            Files.write(url.toPath(), ("http://127.0.0.1:" + server.getLocalPort()).getBytes(StandardCharsets.UTF_8));
            assertFalse(RuntimeHealthProbe.isRemoteConnected(url, 2));
            responder.join(2000);
        } finally {
            new File(dir, "health.url").delete();
            dir.delete();
        }
    }

    private Thread respondOnce(ServerSocket server, String body) {
        Thread responder = new Thread(() -> {
            try (Socket socket = server.accept()) {
                byte[] request = new byte[2048];
                socket.getInputStream().read(request);
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                        + payload.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.write(payload);
                out.flush();
            } catch (Exception ignored) { }
        });
        responder.start();
        return responder;
    }
}
