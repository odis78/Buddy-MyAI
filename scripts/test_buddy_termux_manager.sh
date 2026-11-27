#!/usr/bin/env bash
set -euo pipefail
export BUDDY_HEALTH_ATTEMPTS=3 BUDDY_STOP_ATTEMPTS=3

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANAGER="$ROOT/app/src/main/assets/runtime/buddy-termux-tunnels.sh"
TMP="$(mktemp -d)"
HOME_DIR="$TMP/home"
PREFIX="$TMP/prefix"
BIN="$HOME_DIR/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64"
STATE="$HOME_DIR/.local/state/tunnels"
CONFLICT_PID=""

cleanup() {
  if [[ -x "$HOME_DIR/bin/buddy-tunnels" ]]; then
    BUDDY_TERMUX_HOME="$HOME_DIR" BUDDY_TERMUX_PREFIX="$PREFIX" bash "$HOME_DIR/bin/buddy-tunnels" stop all >/dev/null 2>&1 || true
  fi
  if [[ -n "$CONFLICT_PID" ]]; then kill "$CONFLICT_PID" 2>/dev/null || true; fi
  rm -rf "$TMP"
}
trap cleanup EXIT
trap 'rc=$?; printf "TEST FAILURE line=%s command=%s exit=%s\n" "$LINENO" "$BASH_COMMAND" "$rc" >&2; exit "$rc"' ERR

mkdir -p "$HOME_DIR/bin" "$(dirname "$BIN")" "$PREFIX/bin" "$STATE"
mkdir -p "$HOME_DIR/.config/tunnel-client-bridge-013" "$HOME_DIR/.config/termux-bridge" "$HOME_DIR/droidmcp-stage-v0.4.0/profile-dedicated"
printf 'export CONTROL_PLANE_API_KEY=test-key\n' > "$HOME_DIR/.config/termux-bridge/runtime.env"
chmod 600 "$HOME_DIR/.config/termux-bridge/runtime.env"
cat > "$HOME_DIR/droidmcp-stage-v0.4.0/droidmcp-readonly" <<'SH'
#!/usr/bin/env bash
export DROIDMCP_ROOT="$HOME/droidmcp-sandbox"
export DROIDMCP_TRANSPORT=stdio
export DROIDMCP_FILESYSTEM_DISABLED_TOOLS=write_file,delete_file,move_file,copy_file
exec "$HOME/droidmcp-stage-v0.4.0/droidmcp-filesystem"
SH
chmod 700 "$HOME_DIR/droidmcp-stage-v0.4.0/droidmcp-readonly"
mkdir -p "$HOME_DIR/droidmcp-sandbox"
cat > "$HOME_DIR/droidmcp-stage-v0.4.0/droidmcp-filesystem" <<'SH'
#!/usr/bin/env bash
exit 0
SH
chmod 700 "$HOME_DIR/droidmcp-stage-v0.4.0/droidmcp-filesystem"
cat > "$HOME_DIR/.config/tunnel-client-bridge-013/bridge-013.yaml" <<YAML
control_plane:
  tunnel_id: "tunnel_6ae28b8f01788191b42901f090964502"
health:
  listen_addr: "127.0.0.1:18080"
  url_file: "$STATE/bridge-013.health.url"
mcp:
  commands:
    - channel: bridge
      command: "node bridge-013"
YAML
cat > "$HOME_DIR/droidmcp-stage-v0.4.0/profile-dedicated/droidmcp-filesystem.yaml" <<YAML
control_plane:
  tunnel_id: "tunnel_6af1637e86788191aa429d5518d41938"
health:
  listen_addr: "127.0.0.1:0"
  url_file: "$STATE/droidmcp.health.url"
mcp:
  commands:
    - channel: droidmcp
      command: "~/droidmcp-stage-v0.4.0/droidmcp-readonly"
YAML

cat > "$PREFIX/bin/curl" <<'SH'
#!/usr/bin/env bash
url="${@: -1}"
case "$url" in
  *":8080/"*)
    [[ "${BUDDY_TEST_LEGACY_BRIDGE:-0}" == "1" ]] && exit 0 || exit 22
    ;;
  *":18080/"*)
    [[ "${BUDDY_TEST_FAIL_BRIDGE:-0}" != "1" ]] || exit 22
    if [[ "${BUDDY_TEST_BRIDGE_FIXED:-0}" == "1" ]]; then
      pid_file="$BUDDY_TERMUX_HOME/.local/state/tunnels/buddy-bridge.pid"
      [[ -s "$pid_file" ]] || exit 22
      pid="$(cat "$pid_file")"
      kill -0 "$pid" 2>/dev/null
      exit $?
    fi
    test -s "$BUDDY_TERMUX_HOME/.local/state/tunnels/bridge-013.health.url"
    exit $?
    ;;
  *"/readyz")
    port="${url#http://127.0.0.1:}"
    port="${port%%/*}"
    expected="$(cat "$BUDDY_TERMUX_HOME/.local/state/tunnels/droidmcp.health.url" 2>/dev/null || true)"
    [[ "$expected" == "http://127.0.0.1:$port" ]] && exit 0
    ;;
esac
exit 22
SH
chmod 700 "$PREFIX/bin/curl"
cat > "$PREFIX/bin/node" <<'SH'
#!/usr/bin/env bash
echo "v24.18.0"
SH
chmod 700 "$PREFIX/bin/node"

cat > "$BIN" <<'SH'
#!/usr/bin/env bash
set -eu
profile=""
while (($#)); do
  if [[ "$1" == "--config" && $# -ge 2 ]]; then profile="$2"; shift 2; else shift; fi
done
health_file="$(awk -F: '/^[[:space:]]*url_file:/ {sub(/^[^:]*:[[:space:]]*/, "", $0); gsub(/"/, "", $0); print $0; exit}' "$profile")"
case "$profile" in
  *bridge-013.yaml)
    [[ -z "$health_file" ]] || printf '%s\n' "http://127.0.0.1:18080" > "$health_file"
    [[ -z "${CONTROL_PLANE_API_KEY:-}" ]] || printf '%s' "$CONTROL_PLANE_API_KEY" > "$BUDDY_TERMUX_HOME/.local/state/tunnels/bridge-key-observed"
    ;;
  *droidmcp-filesystem.yaml) printf '%s\n' "http://127.0.0.1:${BUDDY_TEST_DROID_PORT:-46577}" > "$health_file" ;;
  *) exit 20 ;;
esac
child=""
stop() {
  if [[ -n "$child" ]]; then kill "$child" 2>/dev/null || true; wait "$child" 2>/dev/null || true; fi
  exit 0
}
trap stop TERM INT
while :; do sleep 1 & child="$!"; wait "$child" || true; child=""; done
SH
chmod 700 "$BIN"

cp "$MANAGER" "$HOME_DIR/bin/buddy-tunnels"
chmod 700 "$HOME_DIR/bin/buddy-tunnels"
export BUDDY_TERMUX_HOME="$HOME_DIR"
export BUDDY_TERMUX_PREFIX="$PREFIX"

# A legacy Bridge on 8080 is accepted as already live; Buddy must not start a second tunnel on 18080.
export BUDDY_TEST_LEGACY_BRIDGE=1
legacy_output="$(bash "$HOME_DIR/bin/buddy-tunnels" start bridge)"
[[ "$legacy_output" == *"LEGACY_READY"* ]]
test ! -e "$STATE/buddy-bridge.pid"
unset BUDDY_TEST_LEGACY_BRIDGE

# Bridge must load its own protected runtime.env without exposing the key in command arguments.
mv "$HOME_DIR/.config/termux-bridge/runtime.env" "$HOME_DIR/.config/termux-bridge/runtime.env.disabled"
if bash "$HOME_DIR/bin/buddy-tunnels" start bridge >/dev/null 2>&1; then
  echo "Bridge started without its runtime.env" >&2
  exit 1
fi
test ! -e "$STATE/buddy-bridge.pid"
mv "$HOME_DIR/.config/termux-bridge/runtime.env.disabled" "$HOME_DIR/.config/termux-bridge/runtime.env"

# A modified wrapper that no longer disables every write operation must be rejected.
cp "$HOME_DIR/droidmcp-stage-v0.4.0/droidmcp-readonly" "$HOME_DIR/droidmcp-stage-v0.4.0/droidmcp-readonly.backup"
sed -i 's/write_file,delete_file,move_file,copy_file/write_file/' "$HOME_DIR/droidmcp-stage-v0.4.0/droidmcp-readonly"
if bash "$HOME_DIR/bin/buddy-tunnels" start droidmcp >/dev/null 2>&1; then
  echo "unsafe DroidMCP wrapper was accepted" >&2
  exit 1
fi
test ! -e "$STATE/buddy-droidmcp.pid"
mv "$HOME_DIR/droidmcp-stage-v0.4.0/droidmcp-readonly.backup" "$HOME_DIR/droidmcp-stage-v0.4.0/droidmcp-readonly"

# A live health endpoint without a verifiable process must never be duplicated or killed.
printf '%s\n' "http://127.0.0.1:18080" > "$STATE/bridge-013.health.url"
if bash "$HOME_DIR/bin/buddy-tunnels" start bridge >/dev/null 2>&1; then
  echo "unowned live health endpoint should be treated as a conflict" >&2
  exit 1
fi
test ! -e "$STATE/buddy-bridge.pid"
rm -f "$STATE/bridge-013.health.url"

# A live matching tunnel without Buddy's PID ownership marker is external and must never be adopted or stopped.
"$BIN" run --config "$HOME_DIR/.config/tunnel-client-bridge-013/bridge-013.yaml" >> "$STATE/external-bridge.log" 2>&1 &
CONFLICT_PID="$!"
for attempt in $(seq 1 10); do
  [[ -s "$STATE/bridge-013.health.url" ]] && break
  sleep 0.1
done
external_output="$(bash "$HOME_DIR/bin/buddy-tunnels" start bridge)"
[[ "$external_output" == *"EXTERNAL_READY"* ]]
test ! -e "$STATE/buddy-bridge.pid"
if bash "$HOME_DIR/bin/buddy-tunnels" stop bridge >/dev/null 2>&1; then
  echo "Buddy manager stopped an unowned external tunnel" >&2
  exit 1
fi
kill -0 "$CONFLICT_PID"
kill -TERM "$CONFLICT_PID"
wait "$CONFLICT_PID" 2>/dev/null || true
CONFLICT_PID=""
rm -f "$STATE/bridge-013.health.url"

# A Bridge profile failure must not prevent the independent DroidMCP channel from being attempted.
DROID_PROFILE_TEST="$HOME_DIR/droidmcp-stage-v0.4.0/profile-dedicated/droidmcp-filesystem.yaml"
mv "$DROID_PROFILE_TEST" "$DROID_PROFILE_TEST.disabled"
if bash "$HOME_DIR/bin/buddy-tunnels" start all >/dev/null 2>&1; then
  echo "start all should report the missing DroidMCP profile" >&2
  exit 1
fi
test -s "$STATE/buddy-bridge.pid"
test ! -e "$STATE/buddy-droidmcp.pid"
bash "$HOME_DIR/bin/buddy-tunnels" stop bridge
mv "$DROID_PROFILE_TEST.disabled" "$DROID_PROFILE_TEST"

start_output="$(bash "$HOME_DIR/bin/buddy-tunnels" start all)"
[[ "$start_output" == *"BUDDY_DROIDMCP_PORT=46577"* ]]
test "$(cat "$STATE/bridge-key-observed")" = "test-key"
test -s "$STATE/buddy-bridge.pid"
test -s "$STATE/buddy-droidmcp.pid"
status_output="$(bash "$HOME_DIR/bin/buddy-tunnels" status)"
[[ "$status_output" == *"BUDDY_DROIDMCP_PORT=46577"* ]]
bash "$HOME_DIR/bin/buddy-tunnels" start all

# A port-0 profile must publish the actual ephemeral listener, not a hardcoded default.
bash "$HOME_DIR/bin/buddy-tunnels" stop all
export BUDDY_TEST_DROID_PORT=53219
dynamic_output="$(bash "$HOME_DIR/bin/buddy-tunnels" start droidmcp)"
[[ "$dynamic_output" == *"BUDDY_DROIDMCP_PORT=53219"* ]]
printf '%s' "http://127.0.0.1:53219" > "$STATE/droidmcp.health.url"
no_newline_status="$(bash "$HOME_DIR/bin/buddy-tunnels" status)"
[[ "$no_newline_status" == *"BUDDY_DROIDMCP_PORT=53219"* ]]
bash "$HOME_DIR/bin/buddy-tunnels" stop droidmcp
unset BUDDY_TEST_DROID_PORT
bash "$HOME_DIR/bin/buddy-tunnels" start bridge

# An owned process that misses its health deadline is retained for diagnosis, never killed by timeout.
OLD_BRIDGE_PID="$(cat "$STATE/buddy-bridge.pid")"
export BUDDY_TEST_FAIL_BRIDGE=1 BUDDY_HEALTH_ATTEMPTS=1 BUDDY_STOP_ATTEMPTS=1
if timeout_output="$(bash "$HOME_DIR/bin/buddy-tunnels" start bridge 2>&1)"; then
  echo "unhealthy Bridge should report health timeout" >&2
  exit 1
fi
[[ "$timeout_output" == *"process left running"* ]]
NEW_BRIDGE_PID="$(cat "$STATE/buddy-bridge.pid")"
[[ "$NEW_BRIDGE_PID" != "$OLD_BRIDGE_PID" ]]
kill -0 "$NEW_BRIDGE_PID"
unset BUDDY_TEST_FAIL_BRIDGE BUDDY_HEALTH_ATTEMPTS BUDDY_STOP_ATTEMPTS
bash "$HOME_DIR/bin/buddy-tunnels" stop bridge
test ! -e "$STATE/buddy-bridge.pid"

bash "$HOME_DIR/bin/buddy-tunnels" stop all
test ! -e "$STATE/buddy-bridge.pid"
test ! -e "$STATE/buddy-droidmcp.pid"

# A dead PID is safe to recover because no matching process or health endpoint remains.
printf '99999999\n' > "$STATE/buddy-bridge.pid"
bash "$HOME_DIR/bin/buddy-tunnels" start bridge
test -s "$STATE/buddy-bridge.pid"
bash "$HOME_DIR/bin/buddy-tunnels" stop bridge

# A live PID owned by another process must never be adopted, signalled, or overwritten.
sleep 60 &
CONFLICT_PID="$!"
printf '%s\n' "$CONFLICT_PID" > "$STATE/buddy-bridge.pid"
if bash "$HOME_DIR/bin/buddy-tunnels" start bridge >/dev/null 2>&1; then
  echo "unsafe start accepted a foreign live PID" >&2
  exit 1
fi
kill -0 "$CONFLICT_PID"
kill "$CONFLICT_PID"
wait "$CONFLICT_PID" 2>/dev/null || true
CONFLICT_PID=""
rm -f "$STATE/buddy-bridge.pid"

# The manager controls only Bridge and DroidMCP and never uses force-kill.
! grep -q 'rea-android' "$MANAGER"
! grep -q 'rea-all' "$MANAGER"
! grep -Eq 'kill[[:space:]]+(-9|-KILL)' "$MANAGER"
grep -q 'kill -TERM' "$MANAGER"

# Bridge health may be derived from its fixed loopback listen_addr when url_file is absent.
bash "$HOME_DIR/bin/buddy-tunnels" stop all >/dev/null 2>&1 || true
sed -i '/url_file:/d' "$HOME_DIR/.config/tunnel-client-bridge-013/bridge-013.yaml"
export BUDDY_TEST_BRIDGE_FIXED=1
fixed_bridge_output="$(bash "$HOME_DIR/bin/buddy-tunnels" start bridge)"
[[ "$fixed_bridge_output" == *"bridge: READY"* ]]
bash "$HOME_DIR/bin/buddy-tunnels" stop bridge
unset BUDDY_TEST_BRIDGE_FIXED

echo "Termux manager tests passed: start/status, idempotency, legacy Bridge avoidance, independent channels, graceful stop/recovery, stale and foreign PID protection."
