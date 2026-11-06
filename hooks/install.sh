#!/bin/sh
# ScrollLock hook installer. Safe to run any number of times.
#
#   curl -fsSL https://raw.githubusercontent.com/luohoa97/scrolllock/main/hooks/install.sh | sh
#
# Installs one small script and adds it to the six Claude Code events in ~/.claude/settings.json,
# keeping every hook that is already there. The script reports "working" or "idle" to your relay and
# never sends prompt text. It does nothing unless SCROLLLOCK_URL and SCROLLLOCK_WRITE_TOKEN are set.
#
# Overrides: SL_BIN (where the script goes), SL_SET (which settings file to edit), SL_EVENTS (which
# events to hook, space separated) and SL_TIMEOUT (the hook timeout, in whatever unit the tool uses).
# Defaults suit Claude Code; docs/CONNECT.md has the values for Codex and Gemini CLI.
set -eu

SL_SET="${SL_SET:-$HOME/.claude/settings.json}"
SL_EVENTS="${SL_EVENTS:-SessionStart UserPromptSubmit PreToolUse PostToolUse Stop SessionEnd}"
SL_TIMEOUT="${SL_TIMEOUT:-5}"
export SL_EVENTS SL_TIMEOUT
if [ -z "${SL_BIN:-}" ]; then
  if [ -w /usr/local/bin ] || [ "$(id -u)" = 0 ]; then SL_BIN=/usr/local/bin/scrolllock-hook; else SL_BIN="$HOME/.local/bin/scrolllock-hook"; fi
fi
mkdir -p "$(dirname "$SL_BIN")" "$(dirname "$SL_SET")"

cat > "$SL_BIN" <<'HOOK'
#!/bin/sh
# ScrollLock hook. Reports working/idle to the relay. While a turn runs it also starts a small
# background loop that renews a short lease every few seconds, so a dead session locks in seconds.
# Prints nothing and always exits 0. The loop is detached, so Claude never waits on it.
T="${TMPDIR:-/tmp}"; LEASE=10000; EVERY="${SCROLLLOCK_BEAT_EVERY:-3}"
post() { curl -sS -o /dev/null -m "$3" -X POST "${SCROLLLOCK_URL%/}/api/event" -H "authorization: Bearer $SCROLLLOCK_WRITE_TOKEN" \
  -H 'content-type: application/json' -d "{\"session_id\":\"$1\",\"event\":\"$2\",\"human\":${4:-false},\"lease_ms\":$LEASE}" >/dev/null 2>&1; }
if [ "$1" = --beat ]; then
  sid=$2; cl=$3; pf="$T/scrolllock-beat-$sid"
  printf '%s' "$$" > "$pf"   # claim the slot; a newer loop overwrites this and this one then exits
  while sleep "$EVERY"; do
    [ "$(cat "$pf" 2>/dev/null)" = "$$" ] || exit 0
    [ -z "$cl" ] || kill -0 "$cl" 2>/dev/null || { rm -f "$pf"; exit 0; }
    n=$(date +%s); l=$(cat "$T/scrolllock-last-$sid" 2>/dev/null); case "$l" in ''|*[!0-9]*) l=0;; esac
    lim=120; [ -f "$T/scrolllock-tool-$sid" ] && lim=600
    [ $((n - l)) -gt $lim ] && { rm -f "$pf"; exit 0; }
    post "$sid" beat 2
  done
  exit 0
fi
[ -n "$SCROLLLOCK_URL" ] && [ -n "$SCROLLLOCK_WRITE_TOKEN" ] || exit 0
in=$(cat 2>/dev/null)
f() { printf '%s' "$in" | tr '\n' ' ' | grep -o "\"$1\" *: *\"[^\"]*\"" | head -n 1 | sed 's/^[^:]*: *"//; s/"$//'; }
case "$(f hook_event_name)" in SessionStart) e=hello;; UserPromptSubmit|BeforeAgent) e=working;; PreToolUse|BeforeTool) e=heartbeat;; PostToolUse|AfterTool) e=tool_done;; Stop|AfterAgent|Interrupt) e=idle;; SessionEnd) e=end;; *) exit 0;; esac
sid=$(f session_id | tr -cd 'A-Za-z0-9._-' | cut -c1-120); [ -n "$sid" ] || exit 0
h=false; t=2
if [ "$e" = working ]; then t=3; c=$(f prompt | sed -n 's/^[[:space:]]*\(.\).*/\1/p' | head -n 1); [ "$c" != "<" ] && h=true; fi
[ "$e" = end ] && t=1
pf="$T/scrolllock-beat-$sid"; now=$(date +%s)
case "$e" in
  working|heartbeat|tool_done) printf '%s' "$now" > "$T/scrolllock-last-$sid"
    [ "$e" = heartbeat ] && : > "$T/scrolllock-tool-$sid"; [ "$e" = tool_done ] && rm -f "$T/scrolllock-tool-$sid"
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
post "$sid" "$e" "$t" "$h"
exit 0
HOOK
chmod +x "$SL_BIN"

# Merge into settings.json without touching anything else in it.
merge_py() {
  python3 - "$SL_SET" "$SL_BIN" <<'PY'
import json, os, shutil, sys
path, cmd = sys.argv[1], sys.argv[2]
try:
    with open(path) as f:
        data = json.load(f)
except FileNotFoundError:
    data = {}
except ValueError:
    print("scrolllock: %s is not valid JSON, so I left it alone. Fix it and run this again." % path)
    sys.exit(3)
if not isinstance(data, dict):
    print("scrolllock: %s is not a JSON object, so I left it alone." % path)
    sys.exit(3)
if os.path.exists(path) and not os.path.exists(path + ".scrolllock.bak"):
    shutil.copy2(path, path + ".scrolllock.bak")
hooks = data.setdefault("hooks", {})
added = 0
for event in os.environ["SL_EVENTS"].split():
    groups = hooks.setdefault(event, [])
    if any(h.get("command") == cmd for g in groups for h in g.get("hooks", [])):
        continue
    groups.append({"hooks": [{"type": "command", "command": cmd, "timeout": int(os.environ["SL_TIMEOUT"])}]})
    added += 1
tmp = path + ".tmp"
with open(tmp, "w") as f:
    json.dump(data, f, indent=2)
    f.write("\n")
os.replace(tmp, path)
print("scrolllock: %d hook(s) added to %s" % (added, path) if added else "scrolllock: already installed in %s" % path)
PY
}

merge_node() {
  node - "$SL_SET" "$SL_BIN" <<'JS'
const fs = require("fs");
const [path, cmd] = process.argv.slice(2);
let data = {};
if (fs.existsSync(path)) {
  try { data = JSON.parse(fs.readFileSync(path, "utf8")); }
  catch (e) { console.log(`scrolllock: ${path} is not valid JSON, so I left it alone. Fix it and run this again.`); process.exit(3); }
  if (!fs.existsSync(path + ".scrolllock.bak")) fs.copyFileSync(path, path + ".scrolllock.bak");
}
data.hooks = data.hooks || {};
let added = 0;
for (const ev of process.env.SL_EVENTS.split(/\s+/).filter(Boolean)) {
  const groups = (data.hooks[ev] = data.hooks[ev] || []);
  if (groups.some((g) => (g.hooks || []).some((h) => h.command === cmd))) continue;
  groups.push({ hooks: [{ type: "command", command: cmd, timeout: Number(process.env.SL_TIMEOUT) }] });
  added++;
}
fs.writeFileSync(path + ".tmp", JSON.stringify(data, null, 2) + "\n");
fs.renameSync(path + ".tmp", path);
console.log(added ? `scrolllock: ${added} hook(s) added to ${path}` : `scrolllock: already installed in ${path}`);
JS
}

if command -v python3 >/dev/null 2>&1; then merge_py
elif command -v node >/dev/null 2>&1; then merge_node
else
  echo "scrolllock: need python3 or node to edit $SL_SET. The hook script is at $SL_BIN; add it to the events yourself."
  exit 1
fi

if [ -z "${SCROLLLOCK_URL:-}" ] || [ -z "${SCROLLLOCK_WRITE_TOKEN:-}" ]; then
  echo "scrolllock: set SCROLLLOCK_URL and SCROLLLOCK_WRITE_TOKEN in this environment, or the hook stays silent."
fi
