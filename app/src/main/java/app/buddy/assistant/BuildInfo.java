package app.buddy.assistant;

/** Single source of the app version; read by build.sh and app/build.gradle.kts. */
final class BuildInfo {
    static final String VERSION = "2.3.20-GT";
    static final int VERSION_CODE = 217;

    private BuildInfo() {
    }
}
