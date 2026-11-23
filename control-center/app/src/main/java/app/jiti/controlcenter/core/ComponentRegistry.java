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
        c.add(spec("termux", "Termux", "Среда выполнения", "Внешний командный интерфейс ещё не проверен", EvidenceStatus.PRESENT_CONFIGURED, set("android"), false, null));
        c.add(spec("tunnel-client", "Tunnel Client v0.0.19", "Runtime", "ARM64 executable; launch contract требует проверки", EvidenceStatus.PRESENT_CONFIGURED, set("termux"), false, null));
        c.add(spec("buddy-app", "Buddy-MyAI Android app", "Android", "Package: app.buddy.myai.test; version/state требуют live-проверки", EvidenceStatus.CONFIRMED_WORKING, set("android"), false, null));
        c.add(spec("buddy-accessibility", "BuddyService (Accessibility + MCP host)", "Android service", "Class .BuddyService; hosts local MCP listener on 127.0.0.1:8766", EvidenceStatus.CONFIRMED_WORKING, set("buddy-app"), false, null));
        c.add(spec("buddy-mcp", "Buddy MCP", "MCP server", "Hosted inside BuddyService; 127.0.0.1:8766; Bearer auth required", EvidenceStatus.CONFIRMED_WORKING, set("buddy-accessibility"), false, "http://127.0.0.1:8766/mcp"));
        c.add(spec("buddy-tunnel", "Buddy-MyAI tunnel", "Tunnel Client profile", "Dynamic health URL хранится в health.url", EvidenceStatus.OBSERVED_STOPPED, set("termux", "tunnel-client", "buddy-mcp"), false, null));
        c.add(spec("buddy-tools", "Buddy MCP tool catalog", "MCP catalog", "52 source entries: 44 base + 8 optional browser tools; source audit reconciled", EvidenceStatus.CONFIRMED_WORKING, set("buddy-mcp"), false, null));
        c.add(spec("buddy-linux", "Buddy Linux / proot", "Optional runtime", "Embedded Alpine environment for agent and browser features", EvidenceStatus.UNVERIFIED, set("buddy-app"), false, null));
        c.add(spec("buddy-browser", "Buddy background browser", "Optional feature", "8 browser tools appear only when Buddy browser setting is enabled", EvidenceStatus.UNVERIFIED, set("buddy-accessibility", "buddy-linux"), false, null));
        c.add(spec("buddy-notifications", "Buddy NotificationWatcher", "Optional Android service", "Notification access is a separate user-granted capability", EvidenceStatus.UNVERIFIED, set("buddy-app"), false, null));
        c.add(spec("rea-bridge", "REA-Termux-Bridge 0.1.3", "Tunnel Client profile", "Separate profile; launch order/ID unresolved", EvidenceStatus.OBSERVED_STOPPED, set("termux", "tunnel-client"), false, null));
        c.add(spec("droidmcp", "DroidMCP-Filesystem", "Tunnel Client profile", "Read-only filesystem profile", EvidenceStatus.OBSERVED_STOPPED, set("termux", "tunnel-client"), false, null));
        c.add(spec("rea-android", "REA-Termux-Android", "Tunnel Client profile", "Separate REA Android profile", EvidenceStatus.OBSERVED_STOPPED, set("termux", "tunnel-client"), false, null));
        c.add(spec("rea-mcp", "REA MCP", "MCP server", "Historical inventory: 207 tools / 6 prompts", EvidenceStatus.CONFIRMED_WORKING, set("termux"), false, null));
        c.add(spec("bridge-mcp", "Bridge MCP", "MCP server", "Distinct Bridge runtime; endpoint unresolved", EvidenceStatus.CONFIRMED_WORKING, set("termux"), false, null));
        c.add(spec("control-plane", "OpenAI Control Plane", "Network dependency", "Poll/auth must be checked without exposing key", EvidenceStatus.CONFIRMED_WORKING, set(), false, null));
        c.add(spec("shizuku", "Shizuku", "Optional Android service", "Optionality/dependency not proven", EvidenceStatus.UNVERIFIED, set("android"), false, null));
        c.add(spec("adb", "ADB / Android shell", "Optional capability", "Not assumed to be a separate tunnel", EvidenceStatus.UNVERIFIED, set("android"), false, null));
        c.add(spec("termux-api", "Termux:API", "Optional capability", "Dependency not proven", EvidenceStatus.UNVERIFIED, set("termux"), false, null));
        c.add(spec("termux-boot", "Termux:Boot", "Optional capability", "Boot behavior not proven", EvidenceStatus.UNVERIFIED, set("termux"), false, null));
        c.add(spec("e2e-buddy", "ChatGPT → Buddy → Android", "End-to-end path", "Read-only read_screen probe", EvidenceStatus.CONFIRMED_WORKING, set("buddy-tunnel", "buddy-mcp", "buddy-accessibility"), false, null));
        return new ComponentRegistry(c);
    }

    private static ComponentSpec spec(String id, String title, String category, String detail,
                                      EvidenceStatus evidence, Set<String> deps, boolean controllable, String endpoint) {
        return new ComponentSpec(id, title, category, detail, evidence, deps, controllable, endpoint);
    }
    private static Set<String> set(String... values) { return new LinkedHashSet<>(Arrays.asList(values)); }
}
