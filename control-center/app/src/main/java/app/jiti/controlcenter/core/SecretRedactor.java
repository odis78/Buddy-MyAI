package app.jiti.controlcenter.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SecretRedactor {
    private static final Pattern ASSIGNMENT = Pattern.compile(
        "(?i)([\"']?(?:[A-Z0-9_]*_)?(?:AUTHORIZATION|PROXY_AUTHORIZATION|API_KEY|ACCESS_TOKEN|REFRESH_TOKEN|BUDDY_TOKEN|BUDDY_AUTH_HEADER|AUTH_HEADER|PASSWORD|SECRET|TOKEN|KEY)[\"']?\\s*[:=]\\s*[\"']?)(?:Bearer\\s+)?([^\"'\\s,;}]+)([\"']?)");
    private static final Pattern AUTHORIZATION = Pattern.compile(
        "(?i)(\\b(?:proxy-)?authorization\\s*[:=]\\s*)(?:bearer|basic)\\s+[^\\s,;}\"']+");
    private static final Pattern CLI_SECRET = Pattern.compile(
        "(?i)(--(?:api[-_]?key|access[-_]?token|refresh[-_]?token|buddy[-_]?token|auth(?:orization)?|password|secret|key)(?:=|\\s+))(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(https?://)[^/@\\s]+@");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");

    private SecretRedactor() {}

    public static String redact(String input) {
        if (input == null) return "";
        String safe = AUTHORIZATION.matcher(input).replaceAll("$1[REDACTED]");
        safe = CLI_SECRET.matcher(safe).replaceAll("$1[REDACTED]");
        safe = URL_CREDENTIALS.matcher(safe).replaceAll("$1[REDACTED]@");
        Matcher m = ASSIGNMENT.matcher(safe);
        safe = m.replaceAll("$1[REDACTED]$3");
        return BEARER.matcher(safe).replaceAll("Bearer [REDACTED]");
    }
}
