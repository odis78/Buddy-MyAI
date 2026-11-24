package app.jiti.controlcenter.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class SecretRedactorTest {
    @Test public void redactsCredentialsAndBearerHeaders() {
        String value = SecretRedactor.redact("api_key=abc Authorization: Bearer xyz buddy_token:secret");
        assertFalse(value.contains("abc"));
        assertFalse(value.contains("xyz"));
        assertFalse(value.contains("secret"));
        assertTrue(value.contains("[REDACTED]"));
    }
    @Test public void redactsPrefixedEnvironmentNamesAndJson() {
        String value = SecretRedactor.redact("{\"CONTROL_PLANE_API_KEY\":\"runtime-secret\",\"BUDDY_AUTH_HEADER\":\"Bearer header-secret\"}");
        assertFalse(value.contains("runtime-secret"));
        assertFalse(value.contains("header-secret"));
    }
    @Test public void redactsCommandLineSecretsAndUrlCredentials() {
        String value = SecretRedactor.redact("--token cli-secret --api-key=key-secret --control-plane-api-key=control-secret -u alice:curl-secret https://alice:pw-secret@example.test/path");
        assertFalse(value.contains("cli-secret"));
        assertFalse(value.contains("key-secret"));
        assertFalse(value.contains("control-secret"));
        assertFalse(value.contains("alice:curl-secret"));
        assertFalse(value.contains("alice:pw-secret"));
        assertTrue(value.contains("[REDACTED]"));
    }
    @Test public void redactsBasicAuthorizationHeader() {
        String value = SecretRedactor.redact("Authorization: Basic dXNlcjpwYXNz");
        assertFalse(value.contains("dXNlcjpwYXNz"));
    }
    @Test public void nullIsSafe() { assertEquals("", SecretRedactor.redact(null)); }
}
