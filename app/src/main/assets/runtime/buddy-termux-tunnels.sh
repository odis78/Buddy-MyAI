#!/data/data/com.termux/files/usr/bin/bash
# Buddy Termux tunnel manager. Installs beside existing Termux tooling; never edits profiles or legacy managers.
set -u

PREFIX="${BUDDY_TERMUX_PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${BUDDY_TERMUX_HOME:-/data/data/com.termux/files/home}"
STATE="$HOME_DIR/.local/state/tunnels"
BRIDGE_PROFILE="${BUDDY_BRIDGE_PROFILE:-$HOME_DIR/.config/tunnel-client-bridge-013/bridge-013.yaml}"
DROID_PROFILE="${BUDDY_DROIDMCP_PROFILE:-$HOME_DIR/droidmcp-stage-v0.4.0/profile-dedicated/droidmcp-filesystem.yaml}"
BIN="${BUDDY_TUNNEL_CLIENT_BIN:-}"
HEALTH_ATTEMPTS="${BUDDY_HEALTH_ATTEMPTS:-30}"
STOP_ATTEMPTS="${BUDDY_STOP_ATTEMPTS:-15}"
PATH="$PREFIX/bin:$HOME_DIR/bin:$HOME_DIR/.local/bin:$PATH"
export PATH

resolve_binary() {
  local candidate found
  if [[ -n "$BIN" && -x "$BIN" ]]; then return 0; fi
  found="$(command -v tunnel-client 2>/dev/null || true)"
  if [[ -n "$found" && -x "$found" ]]; then BIN="$found"; return 0; fi
  for candidate in \
    "$HOME_DIR/.local/bin/tunnel-client" \
    "$HOME_DIR/bin/tunnel-client" \
    "$HOME_DIR/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64" \
    "$PREFIX/bin/tunnel-client"; do
    if [[ -x "$candidate" ]]; then BIN="$candidate"; return 0; fi
  done
  return 1
}

discover_profile() {
  local name="$1" directory file expected_id expected_port
  local -a matches=()
  case "$name" in
    bridge)
      expected_id='tunnel_6ae28b8f01788191b42901f090964502'
      expected_port='18080'
      ;;
    droidmcp)
      expected_id='tunnel_6af1637e86788191aa429d5518d41938'
      expected_port='41507'
      ;;
    *) return 2 ;;
  esac
  for directory in "$HOME_DIR/.config" "$HOME_DIR/droidmcp-stage-v0.4.0"; do
    [[ -d "$directory" ]] || continue
    while IFS= read -r -d '' file; do
      if grep -Fq "$expected_id" "$file" && grep -Fq "127.0.0.1:$expected_port" "$file" && grep -q 'url_file:' "$file"; then
        matches+=("$file")
      fi
    done < <(find "$directory" -type f \( -name '*.yaml' -o -name '*.yml' \) -print0 2>/dev/null)
  done
  if (("${#matches[@]}" == 1)); then
    printf '%s\n' "${matches[0]}"
    return 0
  fi
  if (("${#matches[@]}" > 1)); then
    echo "$name: ambiguous profile discovery (${#matches[@]} candidates); set the profile path explicitly" >&2
    return 2
  fi
  return 1
}

profile_health_file() {
  local profile="$1" raw
  raw="$(awk -F: '/^[[:space:]]*url_file:/ {sub(/^[^:]*:[[:space:]]*/, "", $0); gsub(/"/, "", $0); print $0; exit}' "$profile")"
  [[ -n "$raw" ]] || return 1
  case "$raw" in
    "~/"*) printf '%s/%s\n' "$HOME_DIR" "${raw#~/}" ;;
    /*) printf '%s\n' "$raw" ;;
    *) printf '%s/%s\n' "$HOME_DIR" "$raw" ;;
  esac
}

component_paths() {
  case "$1" in
    bridge)
      PROFILE="$BRIDGE_PROFILE"
      PID_FILE="$STATE/buddy-bridge.pid"
      LOG_FILE="$STATE/buddy-bridge-manager.log"
      ;;
    droidmcp)
      PROFILE="$DROID_PROFILE"
      PID_FILE="$STATE/buddy-droidmcp.pid"
      LOG_FILE="$STATE/buddy-droidmcp-manager.log"
      ;;
    *) return 2 ;;
  esac
  if [[ ! -s "$PROFILE" ]]; then
    PROFILE="$(discover_profile "$1")" || {
      echo "$1: profile not found or ambiguous under Termux configuration directories"
      return 2
    }
  fi
  case "$1" in
    bridge) expected_id='tunnel_6ae28b8f01788191b42901f090964502'; expected_port='18080' ;;
    droidmcp) expected_id='tunnel_6af1637e86788191aa429d5518d41938'; expected_port='41507' ;;
  esac
  if ! grep -Fq "$expected_id" "$PROFILE" || ! grep -Fq "127.0.0.1:$expected_port" "$PROFILE"; then
    echo "$1: refusing profile with an unexpected tunnel ID or health port"
    return 2
  fi
  if [[ "$1" == "droidmcp" ]] && ! grep -Fq "droidmcp-readonly" "$PROFILE"; then
    echo "droidmcp: refusing a profile that does not use the verified read-only wrapper"
    return 2
  fi
  HEALTH_FILE="$(profile_health_file "$PROFILE")" || {
    echo "$1: profile has no health.url_file entry"
    return 2
  }
}

pid_alive() {
  local pid="$1" state
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  if [[ -r "/proc/$pid/stat" ]]; then
    state="$(awk '{print $3}' "/proc/$pid/stat" 2>/dev/null || true)"
    [[ "$state" != "Z" ]] || return 1
  fi
  return 0
}

pid_matches() {
  local pid="$1" profile="$2" cmdline
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  [[ -r "/proc/$pid/cmdline" ]] || return 1
  cmdline="$(tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)"
  [[ "$cmdline" == *"tunnel-client"* && "$cmdline" == *" run "* && "$cmdline" == *"$profile"* ]]
}

find_matching_pid() {
  local profile="$1" file pid found=""
  for file in /proc/[0-9]*/cmdline; do
    [[ -r "$file" ]] || continue
    pid="${file#/proc/}"
    pid="${pid%/cmdline}"
    if pid_matches "$pid" "$profile"; then
      if [[ -n "$found" ]]; then
        printf 'CONFLICT: multiple matching processes for %s\n' "$profile" >&2
        return 2
      fi
      found="$pid"
    fi
  done
  [[ -n "$found" ]] || return 1
  printf '%s\n' "$found"
}

legacy_bridge_health_live() {
  [[ -x "$PREFIX/bin/curl" ]] || return 1
  "$PREFIX/bin/curl" --silent --show-error --fail --max-time 2 \
    "http://127.0.0.1:8080/readyz" >/dev/null 2>&1
}

health_endpoint_live() {
  local name="$1" port
  case "$name" in
    bridge) port=18080 ;;
    droidmcp) port=41507 ;;
    *) return 2 ;;
  esac
  [[ -x "$PREFIX/bin/curl" ]] || return 1
  "$PREFIX/bin/curl" --silent --show-error --fail --max-time 2 \
    "http://127.0.0.1:$port/readyz" >/dev/null 2>&1
}

health_ok() {
  local url
  [[ -s "$HEALTH_FILE" && -x "$PREFIX/bin/curl" ]] || return 1
  IFS= read -r url < "$HEALTH_FILE" || return 1
  [[ "$url" == http://127.0.0.1:* ]] || return 1
  "$PREFIX/bin/curl" --silent --show-error --fail --max-time 2 "${url%/}/readyz" >/dev/null 2>&1
}

wait_health() {
  local name="$1" attempt
  for attempt in $(seq 1 "$HEALTH_ATTEMPTS"); do
    (health_ok || health_endpoint_live "$name") && return 0
    sleep 1
  done
  return 1
}

start_one() {
  local name="$1" pid found
  component_paths "$name" || return 2
  [[ -s "$PROFILE" ]] || { echo "$name: profile missing"; return 1; }
  [[ -x "$PREFIX/bin/curl" ]] || { echo "$name: curl is required for safe health checks"; return 1; }
  if [[ "$name" == "bridge" ]] && legacy_bridge_health_live; then
    echo "bridge: LEGACY_READY (port 8080 already live; refusing to start a duplicate 18080 tunnel)"
    return 0
  fi
  mkdir -p "$STATE" || return 1

  if [[ -s "$PID_FILE" ]]; then
    IFS= read -r pid < "$PID_FILE" || pid=""
    if pid_matches "$pid" "$PROFILE"; then
      if wait_health "$name"; then echo "$name: READY (existing PID $pid)"; return 0; fi
      echo "$name: UNHEALTHY (PID $pid); requesting graceful restart"
      stop_one "$name" || return 1
    fi
    if pid_alive "$pid"; then
      echo "$name: CONFLICT (PID file points to a different live process; left untouched)"
      return 1
    fi
    found="$(find_matching_pid "$PROFILE")"
    case "$?" in
      0)
        printf '%s\n' "$found" > "$PID_FILE"
        if wait_health "$name"; then echo "$name: READY (recovered PID $found)"; return 0; fi
        echo "$name: UNHEALTHY (recovered PID $found); requesting graceful restart"
        stop_one "$name" || return 1
        ;;
      2) return 1 ;;
    esac
    if health_ok || health_endpoint_live "$name"; then
      echo "$name: CONFLICT (stale PID file but health endpoint is live; left untouched)"
      return 1
    fi
    rm -f "$PID_FILE" "$HEALTH_FILE"
  fi

  found="$(find_matching_pid "$PROFILE")"
  case "$?" in
    0)
      printf '%s\n' "$found" > "$PID_FILE"
      if wait_health "$name"; then echo "$name: READY (adopted PID $found)"; return 0; fi
      echo "$name: UNHEALTHY (adopted PID $found); requesting graceful restart"
      stop_one "$name" || return 1
      ;;
    2) return 1 ;;
  esac

  if health_ok || health_endpoint_live "$name"; then
    echo "$name: CONFLICT (health endpoint is live but no owned PID was found)"
    return 1
  fi

  resolve_binary || { echo "$name: tunnel-client binary not found in Termux PATH or known install locations"; return 1; }
  nohup "$BIN" run --config "$PROFILE" >> "$LOG_FILE" 2>&1 </dev/null &
  pid="$!"
  printf '%s\n' "$pid" > "$PID_FILE"
  if wait_health "$name"; then
    echo "$name: READY (PID $pid)"
    return 0
  fi

  if pid_matches "$pid" "$PROFILE"; then
    kill -TERM "$pid" 2>/dev/null || true
    for attempt in $(seq 1 "$STOP_ATTEMPTS"); do
      pid_alive "$pid" || break
      sleep 1
    done
    if ! pid_alive "$pid"; then
      rm -f "$PID_FILE" "$HEALTH_FILE"
    fi
  fi
  echo "$name: START_FAILED (health did not become ready; see $LOG_FILE)"
  return 1
}

stop_one() {
  local name="$1" pid attempt found
  component_paths "$name" || return 2
  if [[ ! -s "$PID_FILE" ]]; then
    found="$(find_matching_pid "$PROFILE")"
    case "$?" in
      0) pid="$found"; printf '%s\n' "$pid" > "$PID_FILE" ;;
      1)
        if health_endpoint_live "$name"; then
          echo "$name: CONFLICT (health endpoint is live without a verified PID; left untouched)"
          return 1
        fi
        echo "$name: STOPPED (no matching process)"
        return 0
        ;;
      2) return 1 ;;
    esac
  else
    IFS= read -r pid < "$PID_FILE" || pid=""
  fi
  if ! pid_matches "$pid" "$PROFILE"; then
    if pid_alive "$pid"; then
      echo "$name: CONFLICT (PID ownership could not be verified; live process left untouched)"
      return 1
    fi
    found="$(find_matching_pid "$PROFILE")"
    case "$?" in
      0)
        echo "$name: CONFLICT (matching process PID $found is not the registered owner; left untouched)"
        return 1
        ;;
      2) return 1 ;;
    esac
    if health_ok || health_endpoint_live "$name"; then
      echo "$name: CONFLICT (health endpoint is live without a verified PID; left untouched)"
      return 1
    fi
    rm -f "$PID_FILE" "$HEALTH_FILE"
    echo "$name: STOPPED (stale manager state cleared; no live process found)"
    return 0
  fi
  kill -TERM "$pid" 2>/dev/null || {
    echo "$name: STOP_FAILED (TERM was not accepted)"
    return 1
  }
  for attempt in $(seq 1 "$STOP_ATTEMPTS"); do
    if ! pid_alive "$pid"; then
      rm -f "$PID_FILE" "$HEALTH_FILE"
      echo "$name: STOPPED (graceful)"
      return 0
    fi
    sleep 1
  done
  echo "$name: STOP_TIMEOUT (process left running; no SIGKILL sent)"
  return 1
}

status_one() {
  local name="$1" pid found
  component_paths "$name" || return 2
  if [[ -s "$PID_FILE" ]]; then
    IFS= read -r pid < "$PID_FILE" || pid=""
    if pid_matches "$pid" "$PROFILE"; then
      if health_ok || health_endpoint_live "$name"; then echo "$name: READY (PID $pid)"; else echo "$name: RUNNING (PID $pid), health not ready"; fi
      return 0
    fi
    echo "$name: CONFLICT (PID ownership not verified)"
    return 1
  fi
  found="$(find_matching_pid "$PROFILE")"
  case "$?" in
    0) echo "$name: RUNNING_UNTRACKED (PID $found)"; return 1 ;;
    2) return 1 ;;
  esac
  if health_ok || health_endpoint_live "$name"; then echo "$name: HEALTH_ENDPOINT_UNOWNED"; return 1; fi
  echo "$name: STOPPED"
  return 0
}

case "${1:-status}" in
  start)
    case "${2:-all}" in
      bridge) start_one bridge ;;
      droidmcp) start_one droidmcp ;;
      all)
        bridge_status=0
        droid_status=0
        start_one bridge || bridge_status=$?
        start_one droidmcp || droid_status=$?
        (( bridge_status == 0 && droid_status == 0 ))
        ;;
      *) echo "Usage: buddy-tunnels start [bridge|droidmcp|all]"; exit 2 ;;
    esac
    ;;
  stop)
    case "${2:-all}" in
      bridge) stop_one bridge ;;
      droidmcp) stop_one droidmcp ;;
      all)
        stop_one droidmcp
        droid_status=$?
        stop_one bridge
        bridge_status=$?
        (( droid_status == 0 && bridge_status == 0 ))
        ;;
      *) echo "Usage: buddy-tunnels stop [bridge|droidmcp|all]"; exit 2 ;;
    esac
    ;;
  reconcile)
    [[ "${2:-}" == on || "${2:-}" == off ]] || { echo "Usage: buddy-tunnels reconcile <on|off> <on|off>"; exit 2; }
    [[ "${3:-}" == on || "${3:-}" == off ]] || { echo "Usage: buddy-tunnels reconcile <on|off> <on|off>"; exit 2; }
    reconcile_status=0
    if [[ "${3:-}" == off ]]; then stop_one droidmcp || reconcile_status=1; fi
    if [[ "${2:-}" == off ]]; then stop_one bridge || reconcile_status=1; fi
    if [[ "${2:-}" == on ]]; then start_one bridge || reconcile_status=1; fi
    if [[ "${3:-}" == on ]]; then start_one droidmcp || reconcile_status=1; fi
    (( reconcile_status == 0 ))
    ;;
  status)
    bridge_status=0
    droid_status=0
    status_one bridge || bridge_status=$?
    status_one droidmcp || droid_status=$?
    (( bridge_status == 0 && droid_status == 0 ))
    ;;
  restart)
    "$0" stop all || exit $?
    "$0" start all
    ;;
  logs)
    case "${2:-}" in
      bridge) component_paths bridge ;;
      droidmcp) component_paths droidmcp ;;
      *) echo "Usage: buddy-tunnels logs [bridge|droidmcp]"; exit 2 ;;
    esac
    [[ -f "$LOG_FILE" ]] && tail -n 100 "$LOG_FILE" || echo "No log file: $LOG_FILE"
    ;;
  *)
    echo "Usage: buddy-tunnels {start|status|stop|restart|logs} [bridge|droidmcp|all]"
    exit 2
    ;;
esac
