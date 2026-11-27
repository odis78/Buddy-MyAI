#!/usr/bin/env bash
set -euo pipefail

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

mkdir -p "$HOME_DIR/bin" "$(dirname "$BIN")" "$PREFIX/bin" "$STATE"
mkdir -p "$HOME_DIR/.config/tunnel-client-bridge-013" "$HOME_DIR/droidmcp-stage-v0.4.0/profile-dedicated"
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
  listen_addr: "127.0.0.1:41507"
  url_file: "$STATE/droidmcp.health.url"
mcp:
  commands:
    - channel: droidmcp
      command: "node droidmcp-filesystem"
YAML

cat > "$PREFIX/bin/curl" <<'SH'
#!/usr/bin/env bash
url="${@: -1}"
if [[ "$url" == *":8080/"* ]]; then
  [[ "${BUDDY_TEST_LEGACY_BRIDGE:-0}" == "1" ]] && exit 0 || exit 22
fi
if [[ "$url" == *":18080/"* && "${BUDDY_TEST_FAIL_BRIDGE:-0}" == "1" ]]; then exit 22; fi
exit 0
SH
chmod 700 "$PREFIX/bin/curl"

cat > "$BIN" <<'SH'
#!/usr/bin/env bash
set -eu
profile=""
while (($#)); do
  if [[ "$1" == "--config" && $# -ge 2 ]]; then profile="$2"; shift 2; else shift; fi
done
health_file="$(awk -F: '/^[[:space:]]*url_file:/ {sub(/^[^:]*:[[:space:]]*/, "", $0); gsub(/"/, "", $0); print $0; exit}' "$profile")"
case "$profile" in
  *bridge-013.yaml) printf '%s\n' "http://127.0.0.1:18080" > "$health_file" ;;
  *droidmcp-filesystem.yaml) printf '%s\n' "http://127.0.0.1:41507" > "$health_file" ;;
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

bash "$HOME_DIR/bin/buddy-tunnels" start all
test -s "$STATE/buddy-bridge.pid"
test -s "$STATE/buddy-droidmcp.pid"
bash "$HOME_DIR/bin/buddy-tunnels" status
bash "$HOME_DIR/bin/buddy-tunnels" start all

# An owned but unhealthy Bridge is stopped gracefully before a replacement is attempted.
OLD_BRIDGE_PID="$(cat "$STATE/buddy-bridge.pid")"
export BUDDY_TEST_FAIL_BRIDGE=1 BUDDY_HEALTH_ATTEMPTS=1 BUDDY_STOP_ATTEMPTS=1
if bash "$HOME_DIR/bin/buddy-tunnels" start bridge >/dev/null 2>&1; then
  echo "unhealthy Bridge should report a failed replacement health check" >&2
  exit 1
fi
if kill -0 "$OLD_BRIDGE_PID" 2>/dev/null; then
  old_state="$(awk '{print $3}' "/proc/$OLD_BRIDGE_PID/stat" 2>/dev/null || true)"
  [[ "$old_state" == "Z" ]]
fi
test ! -e "$STATE/buddy-bridge.pid"
unset BUDDY_TEST_FAIL_BRIDGE BUDDY_HEALTH_ATTEMPTS BUDDY_STOP_ATTEMPTS

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

echo "Termux manager tests passed: start, status, idempotency, graceful stop, stale PID recovery, foreign PID protection."
