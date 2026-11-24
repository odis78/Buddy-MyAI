package app.jiti.controlcenter.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ComponentRegistry {
    private final Map<String, ComponentSpec> byId;

    public ComponentRegistry(List<ComponentSpec> components) {
        Map<String, ComponentSpec> map = new LinkedHashMap<>();
        for (ComponentSpec item : components) {
            if (map.put(item.id, item) != null) throw new IllegalArgumentException("duplicate component: " + item.id);
        }
        for (ComponentSpec item : components) {
            for (String dependency : item.dependencies) {
                if (!map.containsKey(dependency)) throw new IllegalArgumentException(
                        "unknown dependency " + dependency + " for " + item.id);
            }
        }
        byId = Collections.unmodifiableMap(map);
    }

    public ComponentSpec get(String id) { return byId.get(id); }
    public List<ComponentSpec> all() { return Collections.unmodifiableList(new ArrayList<>(byId.values())); }
    public Set<String> ids() { return Collections.unmodifiableSet(new LinkedHashSet<>(byId.keySet())); }

    public static ComponentRegistry historicalBaseline() {
        List<ComponentSpec> c = new ArrayList<>();
        c.add(spec("android", "Android OS", "Платформа", "Хост-система", EvidenceStatus.PRESENT_CONFIGURED, set(), false, null));
        c.add(spec("jiti-control-center", "JITI Control Center", "Android app", "Version 0.2.0-alpha; not yet device-installed/verified", EvidenceStatus.UNVERIFIED, set("android"), false, null));
        c.add(spec("termux-run-command", "Termux RUN_COMMAND adapter", "Integration", "Fixed argv diagnostics and guarded Buddy-only start/stop prototype; device permission and external-app policy not yet verified", EvidenceStatus.UNVERIFIED, set("jiti-control-center", "termux"), false, null));
        c.add(spec("jiti-monitor-service", "JITI foreground monitor", "Android service", "specialUse foreground service; Buddy health poll every 60 seconds; REA profiles currently checked only during user-started preflight", EvidenceStatus.UNVERIFIED, set("jiti-control-center", "termux-run-command", "jiti-event-store"), false, null));
        c.add(spec("jiti-event-store", "JITI SQLite event journal", "Local storage", "Bounded to 2,000 events; messages redacted before persistence", EvidenceStatus.UNVERIFIED, set("jiti-control-center"), false, null));
        c.add(spec("termux", "Termux", "Среда выполнения", "Внешний командный интерфейс ещё не проверен", EvidenceStatus.PRESENT_CONFIGURED, set("android"), false, null));
        c.add(spec("termux-curl", "Termux curl", "Optional package", "Used only for local MCP listener challenge; presence checked by --version", EvidenceStatus.UNVERIFIED, set("termux"), false, null));
        c.add(spec("termux-python", "Termux Python 3", "Optional package", "Used by fixed authenticated MCP probe; no secret values are returned", EvidenceStatus.UNVERIFIED, set("termux"), false, null));
        c.add(spec("tunnel-client", "Tunnel Client v0.0.19", "Runtime", "ARM64 executable; launch contract requires per-profile verification", EvidenceStatus.PRESENT_CONFIGURED, set("termux"), false, null));
        c.add(spec("control-plane", "OpenAI Control Plane", "Network dependency", "Poll/auth must be checked without exposing secrets", EvidenceStatus.CONFIRMED_WORKING, set(), false, null));
        c.add(spec("remote-tunnel-buddy", "OpenAI remote tunnel: Buddy-MyAI", "Remote tunnel resource", "Known ID: tunnel_6b02e7e420948191a1829df4a5373d20", EvidenceStatus.CONFIRMED_WORKING, set("control-plane"), false, null));
        c.add(spec("remote-tunnel-bridge", "OpenAI remote tunnel: REA-Termux-Bridge", "Remote tunnel resource", "Known ID: tunnel_6ae28b8f01788191b42901f090964502; historical runtime confirmed", EvidenceStatus.CONFIRMED_WORKING, set("control-plane"), false, null));
        c.add(spec("remote-tunnel-droidmcp", "OpenAI remote tunnel: DroidMCP-Filesystem", "Remote tunnel resource", "Known ID: tunnel_6af1637e86788191aa429d5518d41938; historical runtime confirmed", EvidenceStatus.CONFIRMED_WORKING, set("control-plane"), false, null));
        c.add(spec("remote-tunnel-rea", "OpenAI remote tunnel: REA-Termux-Android", "Remote tunnel resource", "Known ID: tunnel_6b0070cbe1508191b1b1463fa6ffeaa2; historical runtime confirmed", EvidenceStatus.CONFIRMED_WORKING, set("control-plane"), false, null));
        c.add(spec("buddy-app", "Buddy-MyAI Android app", "Android", "Runbook: app.buddy.myai.test; checkpoint Gradle: app.buddy.myai; installed package requires live verification", EvidenceStatus.CONFIRMED_WORKING, set("android"), false, null));
        c.add(spec("buddy-accessibility", "BuddyService (Accessibility + MCP host)", "Android service", "Class .BuddyService; hosts local MCP listener on 127.0.0.1:8766", EvidenceStatus.CONFIRMED_WORKING, set("buddy-app"), false, null));
        c.add(spec("buddy-mcp", "Buddy MCP", "MCP server", "Hosted inside BuddyService; 127.0.0.1:8766; Bearer auth required", EvidenceStatus.CONFIRMED_WORKING, set("buddy-accessibility"), false, "http://127.0.0.1:8766/mcp"));
        c.add(spec("buddy-mcp-auth", "Buddy MCP authentication", "Authenticated probe", "Authenticated initialize and paginated tools/list using the tunnel process environment; secret values never returned", EvidenceStatus.UNVERIFIED, set("buddy-mcp", "termux-run-command", "termux-python"), false, "http://127.0.0.1:8766/mcp"));
        c.add(spec("buddy-tunnel", "Buddy-MyAI tunnel", "Tunnel Client profile", "Dynamic health URL хранится в health.url", EvidenceStatus.OBSERVED_STOPPED, set("termux", "tunnel-client", "buddy-mcp", "remote-tunnel-buddy"), false, null));
        c.add(spec("buddy-tools", "Buddy MCP tool catalog", "MCP catalog", "52 source entries: 44 base + 8 optional browser tools; live authenticated tools/list still pending", EvidenceStatus.CONFIRMED_WORKING, set("buddy-mcp-auth"), false, null));
        c.add(spec("buddy-linux", "Buddy Linux / proot", "Optional runtime", "Embedded Alpine environment for agent and browser features", EvidenceStatus.UNVERIFIED, set("buddy-app"), false, null));
        c.add(spec("buddy-browser", "Buddy background browser", "Optional feature", "8 browser tools appear only when Buddy browser setting is enabled", EvidenceStatus.UNVERIFIED, set("buddy-accessibility", "buddy-linux"), false, null));
        c.add(spec("buddy-notifications", "Buddy NotificationWatcher", "Optional Android service", "Notification access is a separate user-granted capability", EvidenceStatus.UNVERIFIED, set("buddy-app"), false, null));
        c.add(spec("buddy-tailnet-mcp", "Buddy Tailscale MCP listener", "Optional network listener", "Uses port 8766 on Tailscale VPN address only when enabled and paired", EvidenceStatus.UNVERIFIED, set("buddy-accessibility"), false, null));
        c.add(spec("buddy-remote-session", "Buddy Claude Remote Control", "Optional runtime", "Starts only when remote_session is enabled and Claude is signed in; default off", EvidenceStatus.UNVERIFIED, set("buddy-linux"), false, null));
        c.add(spec("buddy-ssh", "Buddy SSH server", "Optional network listener", "OpenSSH in Buddy Linux on port 8767; ssh_server default off", EvidenceStatus.UNVERIFIED, set("buddy-linux"), false, null));
        c.add(spec("buddy-codex-remote", "Buddy Codex Remote", "Optional runtime", "Starts only when codex_remote is enabled and Codex is signed in; default off", EvidenceStatus.UNVERIFIED, set("buddy-linux"), false, null));
        c.add(spec("rea-bridge", "REA-Termux-Bridge 0.1.3", "Tunnel Client profile", "--config profile; health URL bridge-013.health.url, PID bridge-013.pid; manual read-only identity probe; launcher order 1/3; tunnel ID tunnel_6ae28b8f01788191b42901f090964502", EvidenceStatus.OBSERVED_STOPPED, set("termux", "tunnel-client", "remote-tunnel-bridge"), false, null));
        c.add(spec("droidmcp-server", "DroidMCP-Filesystem MCP server", "MCP server", "Read-only filesystem server; exact endpoint/process identity still to verify", EvidenceStatus.CONFIRMED_WORKING, set("termux"), false, null));
        c.add(spec("droidmcp", "DroidMCP-Filesystem", "Tunnel Client profile", "--config profile; health URL droidmcp.health.url, PID droidmcp.pid; no env file; manual read-only identity probe; launcher order 2/3; observed stopped on 23-Nov", EvidenceStatus.OBSERVED_STOPPED, set("termux", "tunnel-client", "remote-tunnel-droidmcp"), false, null));
        c.add(spec("droidmcp-tools", "DroidMCP-Filesystem tool catalog", "MCP catalog", "Exact tool list not yet reconciled", EvidenceStatus.UNVERIFIED, set("droidmcp-server"), false, null));
        c.add(spec("rea-android", "REA-Termux-Android", "Tunnel Client profile", "--profile-file profile; health URL rea-android.health.url, PID rea-android.pid; manual read-only identity probe; launcher order 3/3; tunnel ID tunnel_6b0070cbe1508191b1b1463fa6ffeaa2", EvidenceStatus.OBSERVED_STOPPED, set("termux", "tunnel-client", "remote-tunnel-rea"), false, null));
        c.add(spec("rea-mcp", "REA MCP", "MCP server", "Local REA MCP runtime; endpoint/process identity needs live verification", EvidenceStatus.CONFIRMED_WORKING, set("termux"), false, null));
        c.add(spec("rea-tools", "REA MCP tool catalog", "MCP catalog", "Latest saved inventory: 207/207 tools and 6/6 prompts; exact names pending import", EvidenceStatus.CONFIRMED_WORKING, set("rea-mcp"), false, null));
        c.add(spec("bridge-mcp", "Bridge MCP", "MCP server", "Distinct Bridge runtime; endpoint/process identity needs live verification", EvidenceStatus.CONFIRMED_WORKING, set("termux"), false, null));
        c.add(spec("bridge-tools", "Bridge MCP tool catalog", "MCP catalog", "Exact tool list not yet reconciled", EvidenceStatus.UNVERIFIED, set("bridge-mcp"), false, null));
        c.add(spec("chatgpt-connector-buddy", "ChatGPT custom MCP connection: Buddy-MyAI", "ChatGPT connector", "Successful discovery and read_screen call confirmed 22-Nov", EvidenceStatus.CONFIRMED_WORKING, set("buddy-tunnel"), false, null));
        c.add(spec("shizuku", "Shizuku", "Optional Android service", "Optionality/dependency not proven", EvidenceStatus.UNVERIFIED, set("android"), false, null));
        c.add(spec("adb", "ADB / Android shell", "Optional capability", "Not assumed to be a separate tunnel", EvidenceStatus.UNVERIFIED, set("android"), false, null));
        c.add(spec("termux-api", "Termux:API", "Optional capability", "Dependency not proven", EvidenceStatus.UNVERIFIED, set("termux"), false, null));
        c.add(spec("termux-boot", "Termux:Boot", "Optional capability", "Boot behavior not proven", EvidenceStatus.UNVERIFIED, set("termux"), false, null));
        c.add(spec("e2e-buddy", "ChatGPT → Buddy → Android", "End-to-end path", "Read-only read_screen probe", EvidenceStatus.CONFIRMED_WORKING, set("chatgpt-connector-buddy", "buddy-tunnel", "buddy-mcp", "buddy-accessibility"), false, null));
        return new ComponentRegistry(c);
    }

    private static ComponentSpec spec(String id, String title, String category, String detail,
                                      EvidenceStatus evidence, Set<String> deps, boolean controllable, String endpoint) {
        return new ComponentSpec(id, title, category, detail, evidence, deps, controllable, endpoint);
    }
    private static Set<String> set(String... values) { return new LinkedHashSet<>(Arrays.asList(values)); }
}
