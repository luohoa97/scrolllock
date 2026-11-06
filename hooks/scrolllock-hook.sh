#!/bin/sh
# ScrollLock hook: tells the relay whether a Claude Code session is working.
#
# Wired to these Claude Code events (see settings.template.json / plugin/hooks/hooks.json):
#   SessionStart -> hello, UserPromptSubmit -> working, PreToolUse -> heartbeat,
#   PostToolUse -> tool_done, Stop -> idle, SessionEnd -> end
#
# Needs:  SCROLLLOCK_URL (https://your-relay.vercel.app)  SCROLLLOCK_WRITE_TOKEN
# Optional: SCROLLLOCK_DEBUG=1 appends a line per call to ${TMPDIR:-/tmp}/scrolllock-hook.log
#
# Rules this script keeps, on purpose:
#   * It ALWAYS exits 0. Exit code 2 on UserPromptSubmit would reject your prompt.
#   * It prints NOTHING. Stdout from UserPromptSubmit is injected into Claude's context.
#   * It never sends prompt text. Only {session_id, event, human}.
#   * It gives up fast (2s, 3s for the first prompt of a turn) so a broken relay never slows Claude.
#   * POSIX sh only. No jq required (falls back to python3, node, then sed).

tmp="${TMPDIR:-/tmp}"
log() { [ "${SCROLLLOCK_DEBUG:-}" = "1" ] && printf '%s %s\n' "$(date +%H:%M:%S 2>/dev/null)" "$*" >>"$tmp/scrolllock-hook.log" 2>/dev/null; return 0; }

[ -n "${SCROLLLOCK_URL:-}" ] && [ -n "${SCROLLLOCK_WRITE_TOKEN:-}" ] || { log "skip: env not set"; exit 0; }

input="$(cat 2>/dev/null)" || input=""

# --- tiny JSON field readers --------------------------------------------------
# json_get <key>: top-level string field. Tries jq, python3, node, then sed.
json_get() {
  key="$1"
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$input" | jq -r --arg k "$key" '.[$k] // empty | tostring' 2>/dev/null && return 0
  fi
  if command -v python3 >/dev/null 2>&1; then
    printf '%s' "$input" | python3 -c 'import sys,json
try:
    v=json.load(sys.stdin).get(sys.argv[1],"")
    sys.stdout.write(v if isinstance(v,str) else "")
except Exception:
    pass' "$key" 2>/dev/null && return 0
  fi
  if command -v node >/dev/null 2>&1; then
    printf '%s' "$input" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{const v=JSON.parse(s)[process.argv[1]];process.stdout.write(typeof v==="string"?v:"")}catch(e){}})' "$key" 2>/dev/null && return 0
  fi
  # Last resort: first match of "key":"value" on the raw text. Fine for session_id and event names.
  printf '%s' "$input" | tr '\n' ' ' | sed -n "s/.*\"$key\" *: *\"\([^\"]*\)\".*/\1/p" | head -n 1
}

# first_char_of_prompt: just the first character, so we never hold the prompt text longer than needed.
first_char_of_prompt() { json_get prompt | sed -n '1{s/^[[:space:]]*\(.\).*/\1/p;}' | head -n 1; }

# --- work out the event -------------------------------------------------------
hook_name="$(json_get hook_event_name)"
event="${1:-}"
if [ -z "$event" ]; then
  case "$hook_name" in
    SessionStart) event=hello ;;
    UserPromptSubmit) event=working ;;
    PreToolUse) event=heartbeat ;;
    PostToolUse) event=tool_done ;;
    Stop) event=idle ;;
    SessionEnd) event=end ;;
    *) log "skip: unknown hook '$hook_name'"; exit 0 ;;
  esac
fi

sid="$(json_get session_id | tr -cd 'A-Za-z0-9._-' | cut -c1-120)"
[ -n "$sid" ] || { log "skip: no session_id"; exit 0; }

# Harness-started turns (project wakes, PR events) arrive as prompts that begin with "<".
# Anything else we treat as typed by a person.
human=false
timeout_s=2
if [ "$event" = "working" ]; then
  timeout_s=3
  fc="$(first_char_of_prompt)"
  [ "$fc" != "<" ] && human=true
fi
[ "$event" = "end" ] && timeout_s=1   # SessionEnd hooks share a ~1.5s budget

# --- throttle the chatty ones (PreToolUse fires hundreds of times a turn) --------
case "$event" in
  heartbeat|tool_done)
    stamp="$tmp/scrolllock-$event-$sid"
    now="$(date +%s 2>/dev/null)" || now=0
    last="$(cat "$stamp" 2>/dev/null)" || last=0
    case "$last" in ''|*[!0-9]*) last=0 ;; esac
    if [ "$now" -gt 0 ] && [ $((now - last)) -lt 15 ]; then log "throttled $event"; exit 0; fi
    printf '%s' "$now" >"$stamp" 2>/dev/null
    ;;
esac

# --- send -------------------------------------------------------------------
url="${SCROLLLOCK_URL%/}/api/event"
payload="{\"session_id\":\"$sid\",\"event\":\"$event\",\"human\":$human}"

if command -v curl >/dev/null 2>&1; then
  code="$(curl -sS -o /dev/null -w '%{http_code}' -m "$timeout_s" -X POST "$url" \
    -H "authorization: Bearer $SCROLLLOCK_WRITE_TOKEN" -H 'content-type: application/json' \
    -d "$payload" 2>/dev/null)" || code=fail
elif command -v wget >/dev/null 2>&1; then
  wget -q -O /dev/null -T "$timeout_s" --header="authorization: Bearer $SCROLLLOCK_WRITE_TOKEN" \
    --header='content-type: application/json' --post-data="$payload" "$url" >/dev/null 2>&1 && code=ok || code=fail
else
  code=no-http-client
fi
log "$event sid=$sid human=$human -> $code"
exit 0
