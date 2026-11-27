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
    BUDDY_TERMUX_HOME="$HOME_DIR" BUDDY_TERMUX_PREFIX="$PREFIX" "$HOME_DIR/bin/buddy-tunnels" stop all >/dev/null 2>&1 || true
  fi
  if [[ -n "$CONFLICT_PID" ]]; then kill "$CONFLICT_PID" 2>/dev/null || true; fi
  rm -rf "$TMP"
}
trap cleanup EXIT

mkdir -p "$HOME_DIR/bin" "$(dirname "$BIN")" "$PREFIX/bin" "$STATE"
mkdir -p "$HOME_DIR/.config/tunnel-client-bridge-013" "$HOME_DIR/droidmcp-stage-v0.4.0/profile-dedicated"
: > "$HOME_DIR/.config/tunnel-client-bridge-013/bridge-013.yaml"
: > "$HOME_DIR/droidmcp-stage-v0.4.0/profile-dedicated/droidmcp-filesystem.yaml"

cat > "$PREFIX/bin/curl" <<'SH'
#!/usr/bin/env bash
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
case "$profile" in
  *bridge-013.yaml)
    printf '%s\n' "http://127.0.0.1:18080" > "$BUDDY_TERMUX_HOME/.local/state/tunnels/bridge-013.health.url"
    ;;
  *droidmcp-filesystem.yaml)
    printf '%s\n' "http://127.0.0.1:41507" > "$BUDDY_TERMUX_HOME/.local/state/tunnels/droidmcp.health.url"
    ;;
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

"$HOME_DIR/bin/buddy-tunnels" start all
test -s "$STATE/bridge-013.pid"
test -s "$STATE/droidmcp.pid"
"$HOME_DIR/bin/buddy-tunnels" status
"$HOME_DIR/bin/buddy-tunnels" start all
"$HOME_DIR/bin/buddy-tunnels" stop all
test ! -e "$STATE/bridge-013.pid"
test ! -e "$STATE/droidmcp.pid"

# A dead PID is safe to recover because no matching process or health endpoint remains.
printf '99999999\n' > "$STATE/bridge-013.pid"
"$HOME_DIR/bin/buddy-tunnels" start bridge
test -s "$STATE/bridge-013.pid"
"$HOME_DIR/bin/buddy-tunnels" stop bridge

# A live PID owned by another process must never be adopted, signalled, or overwritten.
sleep 60 &
CONFLICT_PID="$!"
printf '%s\n' "$CONFLICT_PID" > "$STATE/bridge-013.pid"
if "$HOME_DIR/bin/buddy-tunnels" start bridge >/dev/null 2>&1; then
  echo "unsafe start accepted a foreign live PID" >&2
  exit 1
fi
kill -0 "$CONFLICT_PID"
kill "$CONFLICT_PID"
wait "$CONFLICT_PID" 2>/dev/null || true
CONFLICT_PID=""
rm -f "$STATE/bridge-013.pid"

# The manager controls only Bridge and DroidMCP and never uses force-kill.
! grep -q 'rea-android' "$MANAGER"
! grep -q 'rea-all' "$MANAGER"
! grep -Eq 'kill[[:space:]]+(-9|-KILL)' "$MANAGER"
grep -q 'kill -TERM' "$MANAGER"

echo "Termux manager tests passed: start, status, idempotency, graceful stop, stale PID recovery, foreign PID protection."
