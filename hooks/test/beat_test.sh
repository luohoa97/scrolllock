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

# Other tools: Gemini CLI event names and Codex's Interrupt map onto the same events.
ev() { tail -n 1 "$work/req.jsonl" | grep -o 'event\\":\\"[a-z_]*' | sed 's/.*"//'; }
hook '{"session_id":"g1","hook_event_name":"BeforeAgent","prompt":"go"}' >/dev/null; sleep 0.6
check "Gemini BeforeAgent is working" "$(ev)" working
hook '{"session_id":"g1","hook_event_name":"AfterAgent"}' >/dev/null; sleep 0.6
check "Gemini AfterAgent is idle" "$(ev)" idle
hook '{"session_id":"c1","hook_event_name":"UserPromptSubmit","prompt":"go"}' >/dev/null; sleep 0.6
hook '{"session_id":"c1","hook_event_name":"Interrupt"}' >/dev/null; sleep 0.6
check "Codex Interrupt is idle" "$(ev)" idle

# Task progress: the agent's task list becomes counts, and text only when the sender opted in.
lf() { grep -v 'event\\":\\"beat' "$work/req.jsonl" | tail -n 1 | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const b=JSON.parse(JSON.parse(s).body);console.log(b[process.argv[1]]===undefined?"-":b[process.argv[1]])})' "$1"; }
todos='{"session_id":"t1","hook_event_name":"PreToolUse","tool_name":"TodoWrite","tool_input":{"todos":[{"content":"Plan it","status":"completed","activeForm":"Planning"},{"content":"Write the \"migration\"","status":"in_progress","activeForm":"Writing the migration"},{"content":"Ship it","status":"pending","activeForm":"Shipping"},{"content":"Old","status":"cancelled","activeForm":"Cancelling"}]}}'
hook '{"session_id":"t1","hook_event_name":"UserPromptSubmit","prompt":"go"}' >/dev/null; sleep 0.5
hook "$todos" >/dev/null; sleep 0.6
check "TodoWrite becomes a progress event" "$(lf event)" progress
check "counts done" "$(lf tasks_done)" 1
check "counts total, ignoring cancelled" "$(lf tasks_total)" 3
check "no task text unless opted in" "$(lf tasks_now)" -
check "agent label defaults to claude" "$(lf agent)" claude
SCROLLLOCK_TASK_TEXT=1 hook "$todos" >/dev/null; sleep 0.6
check "opted in: current task" "$(lf tasks_now)" "Writing the migration"
check "opted in: next task" "$(lf tasks_next)" "Ship it"
# A second agent labelled by the installer's --agent flag
TMPDIR="$work" SCROLLLOCK_URL="http://127.0.0.1:$port/" SCROLLLOCK_WRITE_TOKEN=tok sh "$work/hook" --agent codex <<<'{"session_id":"x1","hook_event_name":"UserPromptSubmit","prompt":"go"}' >/dev/null; sleep 0.6
check "--agent labels the session" "$(lf agent)" codex
[ "$fail" = 0 ] && echo "all passed" || { echo "SOME FAILED"; exit 1; }
