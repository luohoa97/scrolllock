#!/usr/bin/env bash
# The keep-alive loop: starts on a prompt, renews a short lease, stops on Stop, never slows the hook.
# Usage: bash hooks/test/beat_test.sh   (needs node, curl)
set -u
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'pkill -f "scrolllock.*--beat" 2>/dev/null; kill $srv_pid 2>/dev/null; rm -rf "$work"' EXIT
: >"$work/req.jsonl"
node "$here/capture-server.mjs" "$work/req.jsonl" >"$work/port" &
srv_pid=$!
for i in 1 2 3 4 5 6 7 8 9 10; do [ -s "$work/port" ] && break; sleep 0.2; done
port="$(cat "$work/port")"
SL_BIN="$work/hook" SL_SET="$work/settings.json" sh "$here/../install.sh" >/dev/null
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected [$3] got [$2]"; fail=1; fi; }
beats() { grep -c '"event\\":\\"beat\\"' "$work/req.jsonl" 2>/dev/null || true; }
hook() { TMPDIR="$work" SCROLLLOCK_BEAT_EVERY=1 SCROLLLOCK_URL="http://127.0.0.1:$port/" SCROLLLOCK_WRITE_TOKEN=tok sh "$work/hook" <<<"$1"; }

start=$(date +%s%N)
out="$(hook '{"session_id":"b1","hook_event_name":"UserPromptSubmit","prompt":"go"}')"
ms=$(( ($(date +%s%N) - start) / 1000000 ))
check "prints nothing" "$out" ""
check "returns fast, not waiting on the loop (<1500ms)" "$([ "$ms" -lt 1500 ] && echo yes || echo no)" yes
sleep 4
n1=$(beats); check "beats arrive while working (>=2 in 4s)" "$([ "$n1" -ge 2 ] && echo yes || echo no)" yes
check "beats ask for the short lease" "$(grep -c 'lease_ms\\":10000' "$work/req.jsonl" | awk '{print ($1>=2)?"yes":"no"}')" yes

hook '{"session_id":"b1","hook_event_name":"PreToolUse","tool_name":"Bash"}' >/dev/null
sleep 2
check "a second event does not start a second loop (one beater pid file)" "$(ls "$work"/scrolllock-beat-b1 | wc -l | tr -d ' ')" 1

hook '{"session_id":"b1","hook_event_name":"Stop"}' >/dev/null
sleep 1.5
n2=$(beats); sleep 3.5; n3=$(beats)
check "no beats after Stop" "$n3" "$n2"
check "last event is idle" "$(tail -n 1 "$work/req.jsonl" | grep -c idle | awk '{print ($1>=1)?"yes":"no"}')" yes
[ "$fail" = 0 ] && echo "all passed" || { echo "SOME FAILED"; exit 1; }
