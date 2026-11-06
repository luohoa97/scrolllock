#!/usr/bin/env bash
# Offline tests for scrolllock-hook.sh against a local capture server.
# Usage: bash hooks/test/run.sh   (needs node, curl; jq and python3 optional)
set -u
here="$(cd "$(dirname "$0")" && pwd)"
hook="$here/../scrolllock-hook.sh"
work="$(mktemp -d)"
trap 'kill $srv_pid 2>/dev/null; rm -rf "$work"' EXIT
: >"$work/req.jsonl"
node "$here/capture-server.mjs" "$work/req.jsonl" >"$work/port" &
srv_pid=$!
for i in 1 2 3 4 5 6 7 8 9 10; do [ -s "$work/port" ] && break; sleep 0.2; done
port="$(cat "$work/port")"
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected [$3] got [$2]"; fail=1; fi; }
count() { wc -l <"$work/req.jsonl" | tr -d ' '; }
last_field() { tail -n 1 "$work/req.jsonl" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s);const b=JSON.parse(j.body);const f=process.argv[1];console.log(f==="auth"?j.auth:f==="url"?j.url:b[f])})' "$1"; }
run() { # run <input-json> [event-arg]; env: PATHX optional
  TMPDIR="$work" SCROLLLOCK_URL="http://127.0.0.1:$port/" SCROLLLOCK_WRITE_TOKEN=tok sh "$hook" ${2:-} <<EOF2
$1
EOF2
}

# 1. UserPromptSubmit typed by a human
out="$(run '{"session_id":"s1","hook_event_name":"UserPromptSubmit","prompt":"fix the bug"}')"
check "prints nothing" "$out" ""
check "event working" "$(last_field event)" working
check "human true" "$(last_field human)" true
check "bearer" "$(last_field auth)" "Bearer tok"
check "path" "$(last_field url)" /api/event

# 2. harness wake is not human
run '{"session_id":"s1","hook_event_name":"UserPromptSubmit","prompt":"<wake reason=\"mention\">hi</wake>"}' >/dev/null
check "wake not human" "$(last_field human)" false

# 3. heartbeat throttled to one per 15s per session, tool_done separate stamp
n0="$(count)"
run '{"session_id":"s2","hook_event_name":"PreToolUse","tool_name":"Bash"}' >/dev/null
run '{"session_id":"s2","hook_event_name":"PreToolUse","tool_name":"Bash"}' >/dev/null
check "heartbeat throttled" "$(( $(count) - n0 ))" 1
check "heartbeat event" "$(last_field event)" heartbeat
run '{"session_id":"s2","hook_event_name":"PostToolUse"}' >/dev/null
check "tool_done sent" "$(last_field event)" tool_done

# 4. Stop / SessionEnd / SessionStart mapping
run '{"session_id":"s1","hook_event_name":"Stop"}' >/dev/null;         check "stop -> idle" "$(last_field event)" idle
run '{"session_id":"s1","hook_event_name":"SessionEnd"}' >/dev/null;   check "end" "$(last_field event)" end
run '{"session_id":"s1","hook_event_name":"SessionStart"}' >/dev/null; check "start -> hello" "$(last_field event)" hello

# 5. explicit event argument wins
run '{"session_id":"s3"}' idle >/dev/null; check "arg event" "$(last_field event)" idle

# 6. hostile session id is sanitised
run '{"session_id":"a\"b$(x);c","hook_event_name":"Stop"}' >/dev/null
check "sid sanitised" "$(last_field session_id)" "abxc"

# 7. never fails: missing env, bad json, dead server, no session id
n1="$(count)"
env -u SCROLLLOCK_URL -u SCROLLLOCK_WRITE_TOKEN sh "$hook" </dev/null; check "exit 0 no env" "$?" 0
check "no request without env" "$(count)" "$n1"
TMPDIR="$work" SCROLLLOCK_URL="http://127.0.0.1:$port" SCROLLLOCK_WRITE_TOKEN=t sh "$hook" <<<'not json' >/dev/null; check "exit 0 bad json" "$?" 0
TMPDIR="$work" SCROLLLOCK_URL="http://127.0.0.1:9" SCROLLLOCK_WRITE_TOKEN=t sh "$hook" <<<'{"session_id":"z","hook_event_name":"Stop"}' >/dev/null; check "exit 0 dead server" "$?" 0

# 8. parser fallbacks: hide jq, then jq+python3, leaving only node / only sed
bin="$work/bin"; mkdir -p "$bin"
for t in sh cat tr cut sed head tail date curl printf wc rm mktemp dirname sleep; do p="$(command -v $t 2>/dev/null)" && ln -sf "$p" "$bin/$t"; done
try_path() { # try_path <label> <extra tools...>
  label="$1"; shift
  b="$work/bin-$label"; mkdir -p "$b"; cp -P "$bin"/* "$b"/ 2>/dev/null
  for t in "$@"; do p="$(command -v $t 2>/dev/null)" && ln -sf "$p" "$b/$t"; done
  n="$(count)"
  PATH="$b" TMPDIR="$work" SCROLLLOCK_URL="http://127.0.0.1:$port" SCROLLLOCK_WRITE_TOKEN=t /bin/sh "$hook" <<EOF3 >/dev/null
{"session_id":"fb-$label","hook_event_name":"UserPromptSubmit","prompt":"hello"}
EOF3
  check "fallback $label sent" "$(( $(count) - n ))" 1
  check "fallback $label sid" "$(last_field session_id)" "fb-$label"
  check "fallback $label human" "$(last_field human)" true
}
try_path sed-only
try_path node-only node
command -v python3 >/dev/null 2>&1 && try_path python-only python3
command -v jq >/dev/null 2>&1 && try_path jq-only jq

[ "$fail" = 0 ] && echo "ALL HOOK TESTS PASSED" || echo "HOOK TESTS FAILED"
exit "$fail"
