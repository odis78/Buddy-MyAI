package app.jiti.controlcenter.termux;

public final class TunnelProcessIdentity {
    private TunnelProcessIdentity() {}

    private static final String BINARY_SUFFIX =
            "/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64";

    public static boolean matchesExecutable(String executablePath, String termuxHome) {
        if (executablePath == null || termuxHome == null || !termuxHome.startsWith("/")
                || termuxHome.contains("..")) return false;
        String path = executablePath.trim();
        if (path.endsWith(" (deleted)")) return false;
        String expected = normalizeTermuxPath(termuxHome + BINARY_SUFFIX);
        return normalizeTermuxPath(path).equals(expected);
    }

    public static boolean matchesBuddyConfig(String nulSeparatedCommandLine, String termuxHome) {
        if (nulSeparatedCommandLine == null || termuxHome == null || !termuxHome.startsWith("/")) return false;
        String[] args = nulSeparatedCommandLine.split(String.valueOf((char) 0), -1);
        if (args.length < 3 || !args[0].endsWith("tunnel-client-android-arm64") || !"run".equals(args[1])) return false;
        String expected = normalizeTermuxPath(termuxHome + "/.config/tunnel-client-buddy/buddy-myai.yaml");
        for (int i = 1; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length
                    && expected.equals(normalizeTermuxPath(args[i + 1]))) return true;
            if (args[i].startsWith("--config=")
                    && expected.equals(normalizeTermuxPath(args[i].substring("--config=".length())))) return true;
        }
        return false;
    }

    private static String normalizeTermuxPath(String path) {
        return path.replace("/data/user/0/com.termux", "/data/data/com.termux");
    }

    public static boolean matchesBuddy(String executablePath, String nulSeparatedCommandLine, String termuxHome) {
        return matchesExecutable(executablePath, termuxHome)
                && matchesBuddyConfig(nulSeparatedCommandLine, termuxHome);
    }
}
