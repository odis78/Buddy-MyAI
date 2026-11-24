package app.jiti.controlcenter.termux;

public final class TunnelProfileIdentity {
    private static final String BINARY_SUFFIX =
            "/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64";

    private TunnelProfileIdentity() {}

    public static boolean matchesExecutable(String executablePath) {
        if (executablePath == null) return false;
        String path = executablePath.trim();
        return !path.endsWith(" (deleted)") && path.endsWith(BINARY_SUFFIX);
    }

    public static boolean matchesProfile(String executablePath, String nulSeparatedCommandLine,
                                         String termuxHome, String configRelativePath, String configFlag) {
        if (!matchesExecutable(executablePath) || nulSeparatedCommandLine == null
                || termuxHome == null || !termuxHome.startsWith("/")
                || configRelativePath == null || !configRelativePath.startsWith("/")
                || configRelativePath.contains("..")
                || !("--config".equals(configFlag) || "--profile-file".equals(configFlag))) {
            return false;
        }
        String expected = normalize(termuxHome + configRelativePath);
        String[] args = nulSeparatedCommandLine.split(String.valueOf((char) 0), -1);
        if (args.length < 3 || !"run".equals(args[1])) return false;
        for (int i = 0; i < args.length; i++) {
            if (configFlag.equals(args[i]) && i + 1 < args.length
                    && expected.equals(normalize(args[i + 1]))) return true;
            if (args[i].startsWith(configFlag + "=")
                    && expected.equals(normalize(args[i].substring(configFlag.length() + 1)))) return true;
        }
        return false;
    }

    private static String normalize(String path) {
        return path.replace("/data/user/0/com.termux", "/data/data/com.termux");
    }
}
