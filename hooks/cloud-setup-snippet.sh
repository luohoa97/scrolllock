# --- ScrollLock: report when a Claude Code session is working (user-level hooks, needs SCROLLLOCK_URL + SCROLLLOCK_WRITE_TOKEN env vars) ---
SL_BIN="${SL_BIN:-/usr/local/bin/scrolllock-hook}"; SL_SET="${SL_SET:-$HOME/.claude/settings.json}"
cat > "$SL_BIN" <<'HOOK'
#!/bin/sh
[ -n "$SCROLLLOCK_URL" ] && [ -n "$SCROLLLOCK_WRITE_TOKEN" ] || exit 0
in=$(cat 2>/dev/null)
f() { printf '%s' "$in" | tr '\n' ' ' | grep -o "\"$1\" *: *\"[^\"]*\"" | head -n 1 | sed 's/^[^:]*: *"//; s/"$//'; }
case "$(f hook_event_name)" in SessionStart) e=hello;; UserPromptSubmit) e=working;; PreToolUse) e=heartbeat;; PostToolUse) e=tool_done;; Stop) e=idle;; SessionEnd) e=end;; *) exit 0;; esac
sid=$(f session_id | tr -cd 'A-Za-z0-9._-' | cut -c1-120); [ -n "$sid" ] || exit 0
h=false; t=2
if [ "$e" = working ]; then t=3; c=$(f prompt | sed -n 's/^[[:space:]]*\(.\).*/\1/p' | head -n 1); [ "$c" != "<" ] && h=true; fi
[ "$e" = end ] && t=1
case "$e" in heartbeat|tool_done) s="${TMPDIR:-/tmp}/scrolllock-$e-$sid"; n=$(date +%s); l=$(cat "$s" 2>/dev/null); case "$l" in ''|*[!0-9]*) l=0;; esac
  [ $((n - l)) -lt 15 ] && exit 0; printf '%s' "$n" > "$s";; esac
curl -sS -o /dev/null -m "$t" -X POST "${SCROLLLOCK_URL%/}/api/event" -H "authorization: Bearer $SCROLLLOCK_WRITE_TOKEN" \
  -H 'content-type: application/json' -d "{\"session_id\":\"$sid\",\"event\":\"$e\",\"human\":$h}" >/dev/null 2>&1
exit 0
HOOK
chmod +x "$SL_BIN"; mkdir -p "$(dirname "$SL_SET")"
if [ ! -e "$SL_SET" ]; then
  h='{"type":"command","command":"'"$SL_BIN"'","timeout":5}'; g="[{\"hooks\":[$h]}]"
  printf '{"hooks":{"SessionStart":%s,"UserPromptSubmit":%s,"PreToolUse":%s,"PostToolUse":%s,"Stop":%s,"SessionEnd":%s}}\n' "$g" "$g" "$g" "$g" "$g" "$g" > "$SL_SET"
else echo "scrolllock: $SL_SET already exists, hooks not added"; fi
