# --- ScrollLock: report when a Claude Code session is working (user-level hooks, needs SCROLLLOCK_URL + SCROLLLOCK_WRITE_TOKEN env vars) ---
SL_BIN="${SL_BIN:-/usr/local/bin/scrolllock-hook}"; SL_SET="${SL_SET:-$HOME/.claude/settings.json}"
cat > "$SL_BIN" <<'HOOK'
#!/bin/sh
# ScrollLock hook. Reports working/idle to the relay. While a turn runs it also starts a small
# background loop that renews a short lease every few seconds, so a dead session locks in seconds.
# Prints nothing and always exits 0. The loop is detached, so Claude never waits on it.
T="${TMPDIR:-/tmp}"; LEASE=10000; EVERY="${SCROLLLOCK_BEAT_EVERY:-3}"
AGENT=claude; [ "$1" = --agent ] && { AGENT=$(printf '%s' "$2" | tr -cd 'A-Za-z0-9._-' | cut -c1-24); shift 2; }
post() { curl -sS -o /dev/null -m "$3" -X POST "${SCROLLLOCK_URL%/}/api/event" -H "authorization: Bearer $SCROLLLOCK_WRITE_TOKEN" \
  -H 'content-type: application/json' -d "{\"session_id\":\"$1\",\"event\":\"$2\",\"human\":${4:-false},\"lease_ms\":$LEASE,\"agent\":\"$AGENT\"$5}" >/dev/null 2>&1; }
if [ "$1" = --beat ]; then
  sid=$2; cl=$3; pf="$T/scrolllock-beat-$sid"
  printf '%s' "$$" > "$pf"   # claim the slot; a newer loop overwrites this and this one then exits
  while sleep "$EVERY"; do
    [ "$(cat "$pf" 2>/dev/null)" = "$$" ] || exit 0
    [ -z "$cl" ] || kill -0 "$cl" 2>/dev/null || { rm -f "$pf"; exit 0; }
    n=$(date +%s); l=$(cat "$T/scrolllock-last-$sid" 2>/dev/null); case "$l" in ''|*[!0-9]*) l=0;; esac
    # Staleness fallback only. Real state comes from the hook events; this just stops a session that went
    # silent. While Claude's own process is alive a quiet stretch is the model thinking or writing, so wait longer.
    if [ -n "$cl" ]; then lim=${SCROLLLOCK_QUIET_S:-300}; [ -f "$T/scrolllock-tool-$sid" ] && lim=1800
    else lim=120; [ -f "$T/scrolllock-tool-$sid" ] && lim=600; fi
    [ $((n - l)) -gt $lim ] && { rm -f "$pf"; exit 0; }
    post "$sid" beat 2
  done
  exit 0
fi
[ -n "$SCROLLLOCK_URL" ] && [ -n "$SCROLLLOCK_WRITE_TOKEN" ] || exit 0
in=$(cat 2>/dev/null)
f() { printf '%s' "$in" | tr '\n' ' ' | grep -o "\"$1\" *: *\"[^\"]*\"" | head -n 1 | sed 's/^[^:]*: *"//; s/"$//'; }
case "$(f hook_event_name)" in SessionStart) e=hello;; Notification|PermissionRequest) e=needs_you;; UserPromptSubmit|BeforeAgent) e=working;; PreToolUse|BeforeTool) e=heartbeat;; PostToolUse|PostToolUseFailure|AfterTool) e=tool_done;; Stop|StopFailure|AfterAgent|Interrupt) e=idle;; SessionEnd) e=end;; *) exit 0;; esac
sid=$(f session_id | tr -cd 'A-Za-z0-9._-' | cut -c1-120); [ -n "$sid" ] || exit 0
h=false; t=2; extra=""
# A tool aborted by the user ends the turn, and Esc fires no Stop.
if [ "$(f hook_event_name)" = PostToolUseFailure ] && printf '%s' "$in" | grep -q '"is_interrupt" *: *true'; then e=idle; fi
# The agent is blocked on you (a permission prompt or a question): the phone stays locked until you answer.
# idle_prompt is Claude sitting at its prompt, which is also all that is left after Esc (no Stop fires then),
# so it ends the turn. Sign-in, quota and plugin notices are not the agent waiting on you, so they are ignored.
if [ "$e" = needs_you ]; then
  nt=$(f notification_type); nm=$(f message)
  case "$nt" in
    idle_prompt) e=idle;;
    ''|permission_prompt|elicitation_dialog|elicitation_url_dialog|agent_needs_input) case "$nm" in *"waiting for your input"*) e=idle;; esac;;
    *) exit 0;;
  esac
fi
if [ "$e" = needs_you ]; then
  case "$(f hook_event_name) $nt $nm" in *PermissionRequest*|*permission*) r=permission;; *elicitation*|*question*) r=question;; *) r=other;; esac
  extra=",\"reason\":\"$r\""
  if [ "${SCROLLLOCK_TASK_TEXT:-}" = 1 ]; then d=$(f cwd); extra="$extra,\"label\":\"$(printf '%s' "${d##*/}" | tr -d '\000-\037"\\' | cut -c1-60)\""; fi
fi
# The agent's own task list (Claude Code TodoWrite, Codex update_plan, Gemini write_todos) becomes a progress
# event: counts always, and task text only if you set SCROLLLOCK_TASK_TEXT=1 where the agent runs.
if [ "$e" = heartbeat ]; then case "$(f tool_name)" in TodoWrite|update_plan|write_todos)
  tl=$(printf '%s' "$in" | tr '\n' ' ' | tr '}' '\n')
  tt=$(printf '%s\n' "$tl" | grep -cE '"status" *: *"(pending|in_progress|completed)"')
  td=$(printf '%s\n' "$tl" | grep -cE '"status" *: *"completed"')
  extra=",\"tasks_done\":$td,\"tasks_total\":$tt"
  if [ "${SCROLLLOCK_TASK_TEXT:-}" = 1 ]; then
    g() { sed -n "s/.*\"$1\" *: *\"\([^\"]*\)\".*/\1/p" | head -n 1; }
    clean() { tr -d '\000-\037"\\' | cut -c1-120; }
    a=$(printf '%s\n' "$tl" | grep -E '"status" *: *"in_progress"' | head -n 1)
    n=$(printf '%s' "$a" | g activeForm); [ -n "$n" ] || n=$(printf '%s' "$a" | g content); [ -n "$n" ] || n=$(printf '%s' "$a" | g step); [ -n "$n" ] || n=$(printf '%s' "$a" | g description)
    a=$(printf '%s\n' "$tl" | grep -E '"status" *: *"pending"' | head -n 1)
    x=$(printf '%s' "$a" | g content); [ -n "$x" ] || x=$(printf '%s' "$a" | g step); [ -n "$x" ] || x=$(printf '%s' "$a" | g description)
    extra="$extra,\"tasks_now\":\"$(printf '%s' "$n" | clean)\",\"tasks_next\":\"$(printf '%s' "$x" | clean)\""
  fi
  e=progress;; esac; fi
if [ "$e" = working ]; then t=3; c=$(f prompt | sed -n 's/^[[:space:]]*\(.\).*/\1/p' | head -n 1); [ "$c" != "<" ] && h=true; fi
[ "$e" = end ] && t=1
pf="$T/scrolllock-beat-$sid"; now=$(date +%s)
case "$e" in
  working|heartbeat|progress|tool_done) printf '%s' "$now" > "$T/scrolllock-last-$sid"
    [ "$e" = heartbeat ] || [ "$e" = progress ] && : > "$T/scrolllock-tool-$sid"; [ "$e" = tool_done ] && rm -f "$T/scrolllock-tool-$sid"
    # Start the keep-alive loop if one isn't running for this session. It needs to be Claude's own
    # process to watch, so look for it among our parents; if not found it still stops on its own.
    p=$(cat "$pf" 2>/dev/null)
    if [ -z "$p" ] || ! kill -0 "$p" 2>/dev/null; then
      cl=""; a=$PPID; i=0
      while [ "$i" -lt 8 ] && [ -n "$a" ] && [ "$a" -gt 1 ] 2>/dev/null; do
        if ps -o args= -p "$a" 2>/dev/null | grep -qiE 'claude|codex|gemini'; then cl=$a; break; fi
        a=$(ps -o ppid= -p "$a" 2>/dev/null | tr -d ' '); i=$((i + 1))
      done
      if command -v setsid >/dev/null 2>&1; then setsid sh "$0" --beat "$sid" "$cl" >/dev/null 2>&1 </dev/null &
      else nohup sh "$0" --beat "$sid" "$cl" >/dev/null 2>&1 </dev/null & fi
      printf '%s' "$!" > "$pf"   # provisional; the loop replaces it with its own pid
    fi;;
  idle|end) rm -f "$T/scrolllock-tool-$sid"; p=$(cat "$pf" 2>/dev/null); rm -f "$pf"; [ -n "$p" ] && kill "$p" 2>/dev/null;;
esac
case "$e" in heartbeat|tool_done) s="$T/scrolllock-$e-$sid"; l=$(cat "$s" 2>/dev/null); case "$l" in ''|*[!0-9]*) l=0;; esac
  [ $((now - l)) -lt 15 ] && exit 0; printf '%s' "$now" > "$s";; esac
post "$sid" "$e" "$t" "$h" "$extra"
exit 0
HOOK
chmod +x "$SL_BIN"; mkdir -p "$(dirname "$SL_SET")"
if [ ! -e "$SL_SET" ]; then
  h='{"type":"command","command":"'"$SL_BIN"'","timeout":5}'; g="[{\"hooks\":[$h]}]"
  printf '{"hooks":{"SessionStart":%s,"UserPromptSubmit":%s,"PreToolUse":%s,"PostToolUse":%s,"PostToolUseFailure":%s,"Notification":%s,"Stop":%s,"StopFailure":%s,"SessionEnd":%s}}\n' "$g" "$g" "$g" "$g" "$g" "$g" "$g" "$g" "$g" > "$SL_SET"
else echo "scrolllock: $SL_SET already exists, hooks not added"; fi
