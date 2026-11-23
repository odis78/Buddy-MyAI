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
    @Test public void nullIsSafe() { assertEquals("", SecretRedactor.redact(null)); }
}
