package app.jiti.controlcenter.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SecretRedactor {
    private static final Pattern ASSIGNMENT = Pattern.compile(
        "(?i)(\\b(?:authorization|proxy-authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|buddy[_-]?token|password|secret)\\b\\s*[:=]\\s*)([^\\s,;]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private SecretRedactor() {}

    public static String redact(String input) {
        if (input == null) return "";
        Matcher m = ASSIGNMENT.matcher(input);
        String safe = m.replaceAll("$1[REDACTED]");
        return BEARER.matcher(safe).replaceAll("Bearer [REDACTED]");
    }
}
