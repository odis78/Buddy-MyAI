package app.buddy.assistant;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Independent lifecycle coordinator for Buddy's main tunnel and the two Termux-owned MCP tunnels.
 * The original REA MCP server is a stdio channel inside Buddy's main tunnel, not another tunnel.
 */
final class RuntimeConnectionManager {
    private static final int TERMUX_BRIDGE_PORT_PRIMARY = 18080;
    private static final int TERMUX_BRIDGE_PORT_LEGACY = 8080;
    private static final int TERMUX_DROIDMCP_PORT = 41507;

    private final Context app;
    private final RuntimeSupervisor buddySupervisor;
    private final RuntimeRecoveryPolicy termuxRecovery = new RuntimeRecoveryPolicy(60000, 300000);
    private long nextTermuxRecoveryAt;

    RuntimeConnectionManager(Context context) {
        app = context.getApplicationContext();
        buddySupervisor = new RuntimeSupervisor(app);
    }

    boolean startBuddyTunnel() throws Exception {
        return buddySupervisor.start();
    }

    void stopBuddyTunnel() {
        buddySupervisor.stop();
    }

    boolean isBuddyReady() {
        return buddySupervisor.isReady();
    }

    boolean isBuddyMcpDiscovered() {
        return buddySupervisor.isMcpDiscovered(0);
    }

    boolean isBuddyRemoteConnected() {
        return buddySupervisor.isRemoteConnected(0);
    }

    String buddyLastError() {
        return buddySupervisor.lastError();
    }

    RuntimeStateMachine.State buddyState() {
        return buddySupervisor.state();
    }

    boolean isTermuxInstalled() {
        return TermuxRuntimeController.isInstalled(app);
    }

    boolean hasTermuxPermission() {
        return TermuxRuntimeController.hasPermission(app);
    }

    boolean isTermuxManagerInstalled() {
        return app.getSharedPreferences("runtime", Context.MODE_PRIVATE)
                .getBoolean("termux_manager_installed", false);
    }

    void installTermuxManager() {
        TermuxRuntimeController.installManager(app);
    }

    synchronized boolean reconcileTermux(boolean bridgeEnabled, boolean droidEnabled) {
        if (!isTermuxInstalled() || !hasTermuxPermission() || !isTermuxManagerInstalled()) return false;
        TermuxRuntimeController.reconcile(app, bridgeEnabled, droidEnabled);
        nextTermuxRecoveryAt = System.currentTimeMillis() + termuxRecovery.nextDelayMillis();
        return true;
    }

    synchronized void stopTermux() {
        if (isTermuxInstalled() && hasTermuxPermission() && isTermuxManagerInstalled()) {
            TermuxRuntimeController.stop(app);
        }
        nextTermuxRecoveryAt = 0;
        termuxRecovery.reset();
    }

    synchronized void recoverTermuxIfUnavailable(TermuxSnapshot snapshot,
                                                  boolean bridgeEnabled, boolean droidEnabled) {
        if (snapshot == null || !snapshot.managerInstalled || !snapshot.termuxInstalled
                || !snapshot.permissionGranted) return;
        boolean unavailable = (bridgeEnabled && !snapshot.bridgeTransport && !snapshot.bridgeConflict)
                || (droidEnabled && !snapshot.droidTransport);
        if (!unavailable) {
            nextTermuxRecoveryAt = 0;
            termuxRecovery.reset();
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextTermuxRecoveryAt) return;
        TermuxRuntimeController.reconcile(app, bridgeEnabled, droidEnabled);
        nextTermuxRecoveryAt = now + termuxRecovery.nextDelayMillis();
    }

    TermuxSnapshot inspectTermux(boolean bridgeEnabled, boolean droidEnabled) {
        SharedPreferences prefs = app.getSharedPreferences("runtime", Context.MODE_PRIVATE);
        boolean installed = isTermuxInstalled();
        boolean permission = installed && hasTermuxPermission();
        boolean manager = prefs.getBoolean("termux_manager_installed", false);

        boolean bridgePrimary = bridgeEnabled && RuntimeHealthProbe.isReadyAtPort(TERMUX_BRIDGE_PORT_PRIMARY);
        boolean bridgeLegacy = bridgeEnabled && RuntimeHealthProbe.isReadyAtPort(TERMUX_BRIDGE_PORT_LEGACY);
        boolean bridgeConflict = bridgeEnabled && bridgePrimary && bridgeLegacy;
        int bridgePort = bridgePrimary ? TERMUX_BRIDGE_PORT_PRIMARY
                : bridgeLegacy ? TERMUX_BRIDGE_PORT_LEGACY : 0;
        boolean bridgeTransport = !bridgeEnabled || (bridgePort > 0 && !bridgeConflict);
        boolean droidTransport = !droidEnabled || RuntimeHealthProbe.isReadyAtPort(TERMUX_DROIDMCP_PORT);
        boolean bridgeMcp = !bridgeEnabled || (bridgeTransport
                && RuntimeHealthProbe.isMcpDiscoveredAtPort(bridgePort, 6));
        boolean droidMcp = !droidEnabled || (droidTransport
                && RuntimeHealthProbe.isMcpDiscoveredAtPort(TERMUX_DROIDMCP_PORT, 5));
        boolean bridgeRemote = !bridgeEnabled || (bridgeMcp
                && RuntimeHealthProbe.isRemoteConnectedAtPort(bridgePort, 6));
        boolean droidRemote = !droidEnabled || (droidMcp
                && RuntimeHealthProbe.isRemoteConnectedAtPort(TERMUX_DROIDMCP_PORT, 5));

        String command = prefs.getString("termux_last_command", "");
        int exitCode = prefs.getInt("termux_last_exit_code", -1);
        long resultAt = prefs.getLong("termux_last_result_at", 0);
        String error = "";
        if (exitCode != 0 && System.currentTimeMillis() - resultAt < 180000L
                && ("install".equals(command) || "reconcile".equals(command) || "stop".equals(command))) {
            error = firstNonEmpty(prefs.getString("termux_last_stderr", ""),
                    prefs.getString("termux_last_error", ""),
                    prefs.getString("termux_last_stdout", ""));
        }
        return new TermuxSnapshot(installed, permission, manager,
                bridgePrimary, bridgeLegacy, bridgeConflict, bridgePort,
                bridgeTransport, bridgeMcp, bridgeRemote,
                droidTransport, droidMcp, droidRemote, error);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    static final class TermuxSnapshot {
        final boolean termuxInstalled;
        final boolean permissionGranted;
        final boolean managerInstalled;
        final boolean bridgePrimary;
        final boolean bridgeLegacy;
        final boolean bridgeConflict;
        final int bridgePort;
        final boolean bridgeTransport;
        final boolean bridgeMcp;
        final boolean bridgeRemote;
        final boolean droidTransport;
        final boolean droidMcp;
        final boolean droidRemote;
        final String managerError;

        TermuxSnapshot(boolean termuxInstalled, boolean permissionGranted, boolean managerInstalled,
                       boolean bridgePrimary, boolean bridgeLegacy, boolean bridgeConflict, int bridgePort,
                       boolean bridgeTransport, boolean bridgeMcp, boolean bridgeRemote,
                       boolean droidTransport, boolean droidMcp, boolean droidRemote, String managerError) {
            this.termuxInstalled = termuxInstalled;
            this.permissionGranted = permissionGranted;
            this.managerInstalled = managerInstalled;
            this.bridgePrimary = bridgePrimary;
            this.bridgeLegacy = bridgeLegacy;
            this.bridgeConflict = bridgeConflict;
            this.bridgePort = bridgePort;
            this.bridgeTransport = bridgeTransport;
            this.bridgeMcp = bridgeMcp;
            this.bridgeRemote = bridgeRemote;
            this.droidTransport = droidTransport;
            this.droidMcp = droidMcp;
            this.droidRemote = droidRemote;
            this.managerError = managerError;
        }
    }
}
