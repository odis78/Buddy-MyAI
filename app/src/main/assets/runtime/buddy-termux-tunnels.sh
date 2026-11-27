#!/data/data/com.termux/files/usr/bin/bash
# Buddy Termux tunnel manager. Installs beside existing Termux tooling; never edits profiles or legacy managers.
set -u

PREFIX="/data/data/com.termux/files/usr"
HOME_DIR="/data/data/com.termux/files/home"
BIN="$HOME_DIR/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64"
STATE="$HOME_DIR/.local/state/tunnels"
BRIDGE_PROFILE="$HOME_DIR/.config/tunnel-client-bridge-013/bridge-013.yaml"
DROID_PROFILE="$HOME_DIR/droidmcp-stage-v0.4.0/profile-dedicated/droidmcp-filesystem.yaml"

component_paths() {
  case "$1" in
    bridge)
      PROFILE="$BRIDGE_PROFILE"
      PID_FILE="$STATE/bridge-013.pid"
      HEALTH_FILE="$STATE/bridge-013.health.url"
      LOG_FILE="$STATE/bridge-013-recovery.log"
      ;;
    droidmcp)
      PROFILE="$DROID_PROFILE"
      PID_FILE="$STATE/droidmcp.pid"
      HEALTH_FILE="$STATE/droidmcp.health.url"
      LOG_FILE="$STATE/droidmcp.log"
      ;;
    *) return 2 ;;
  esac
}

pid_matches() {
  local pid="$1" profile="$2" cmdline
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  [[ -r "/proc/$pid/cmdline" ]] || return 1
  cmdline="$(tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)"
  [[ "$cmdline" == *"$BIN"* && "$cmdline" == *" run "* && "$cmdline" == *"$profile"* ]]
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

health_ok() {
  local url
  [[ -s "$HEALTH_FILE" && -x "$PREFIX/bin/curl" ]] || return 1
  IFS= read -r url < "$HEALTH_FILE" || return 1
  [[ "$url" == http://127.0.0.1:* ]] || return 1
  "$PREFIX/bin/curl" --silent --show-error --fail --max-time 2 "${url%/}/readyz" >/dev/null 2>&1
}

wait_health() {
  local attempt
  for attempt in $(seq 1 30); do
    health_ok && return 0
    sleep 1
  done
  return 1
}

start_one() {
  local name="$1" pid found
  component_paths "$name" || return 2
  [[ -x "$BIN" ]] || { echo "$name: tunnel-client binary missing"; return 1; }
  [[ -s "$PROFILE" ]] || { echo "$name: profile missing"; return 1; }
  mkdir -p "$STATE" || return 1

  if [[ -s "$PID_FILE" ]]; then
    IFS= read -r pid < "$PID_FILE" || pid=""
    if pid_matches "$pid" "$PROFILE"; then
      if wait_health; then echo "$name: READY (existing PID $pid)"; return 0; fi
      echo "$name: RUNNING (PID $pid), health not ready"
      return 1
    fi
    if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
      echo "$name: CONFLICT (PID file points to a different process; left untouched)"
      return 1
    fi
    echo "$name: CONFLICT (stale or invalid PID file; left untouched)"
    return 1
  fi

  found="$(find_matching_pid "$PROFILE")"
  case "$?" in
    0)
      printf '%s\n' "$found" > "$PID_FILE"
      if wait_health; then echo "$name: READY (adopted PID $found)"; return 0; fi
      echo "$name: RUNNING (adopted PID $found), health not ready"
      return 1
      ;;
    2) return 1 ;;
  esac

  if health_ok; then
    echo "$name: CONFLICT (health endpoint is live but no owned PID was found)"
    return 1
  fi

  nohup "$BIN" run --config "$PROFILE" >> "$LOG_FILE" 2>&1 </dev/null &
  pid="$!"
  printf '%s\n' "$pid" > "$PID_FILE"
  if wait_health; then
    echo "$name: READY (PID $pid)"
    return 0
  fi

  if pid_matches "$pid" "$PROFILE"; then
    kill -TERM "$pid" 2>/dev/null || true
    for attempt in $(seq 1 15); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$PID_FILE" "$HEALTH_FILE"
    fi
  fi
  echo "$name: START_FAILED (health did not become ready; see $LOG_FILE)"
  return 1
}

stop_one() {
  local name="$1" pid attempt
  component_paths "$name" || return 2
  if [[ ! -s "$PID_FILE" ]]; then
    echo "$name: STOPPED (no owned PID file)"
    return 0
  fi
  IFS= read -r pid < "$PID_FILE" || pid=""
  if ! pid_matches "$pid" "$PROFILE"; then
    echo "$name: CONFLICT (PID ownership could not be verified; process and files left untouched)"
    return 1
  fi
  kill -TERM "$pid" 2>/dev/null || {
    echo "$name: STOP_FAILED (TERM was not accepted)"
    return 1
  }
  for attempt in $(seq 1 15); do
    if ! kill -0 "$pid" 2>/dev/null; then
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
      if health_ok; then echo "$name: READY (PID $pid)"; else echo "$name: RUNNING (PID $pid), health not ready"; fi
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
  if health_ok; then echo "$name: HEALTH_ENDPOINT_UNOWNED"; return 1; fi
  echo "$name: STOPPED"
  return 0
}

case "${1:-status}" in
  start)
    case "${2:-all}" in
      bridge) start_one bridge ;;
      droidmcp) start_one droidmcp ;;
      all)
        start_one bridge || exit $?
        start_one droidmcp || exit $?
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
    if [[ "${3:-}" == off ]]; then stop_one droidmcp || exit $?; fi
    if [[ "${2:-}" == off ]]; then stop_one bridge || exit $?; fi
    if [[ "${2:-}" == on ]]; then start_one bridge || exit $?; fi
    if [[ "${3:-}" == on ]]; then start_one droidmcp || exit $?; fi
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
