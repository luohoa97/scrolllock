#!/bin/sh
# ScrollLock hook installer. Safe to run any number of times.
#
#   curl -fsSL https://raw.githubusercontent.com/luohoa97/scrolllock/main/hooks/install.sh | sh
#
# Installs one small script and adds it to the six Claude Code events in ~/.claude/settings.json,
# keeping every hook that is already there. The script reports "working" or "idle" to your relay and
# never sends prompt text. It does nothing unless SCROLLLOCK_URL and SCROLLLOCK_WRITE_TOKEN are set.
#
# Overrides: SL_BIN (where the script goes), SL_SET (which settings.json to edit).
set -eu

SL_SET="${SL_SET:-$HOME/.claude/settings.json}"
if [ -z "${SL_BIN:-}" ]; then
  if [ -w /usr/local/bin ] || [ "$(id -u)" = 0 ]; then SL_BIN=/usr/local/bin/scrolllock-hook; else SL_BIN="$HOME/.local/bin/scrolllock-hook"; fi
fi
mkdir -p "$(dirname "$SL_BIN")" "$(dirname "$SL_SET")"

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
for event in ("SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse", "Stop", "SessionEnd"):
    groups = hooks.setdefault(event, [])
    if any(h.get("command") == cmd for g in groups for h in g.get("hooks", [])):
        continue
    groups.append({"hooks": [{"type": "command", "command": cmd, "timeout": 5}]})
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
for (const ev of ["SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse", "Stop", "SessionEnd"]) {
  const groups = (data.hooks[ev] = data.hooks[ev] || []);
  if (groups.some((g) => (g.hooks || []).some((h) => h.command === cmd))) continue;
  groups.push({ hooks: [{ type: "command", command: cmd, timeout: 5 }] });
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
  echo "scrolllock: need python3 or node to edit $SL_SET. The hook script is at $SL_BIN; add it to the six events yourself."
  exit 1
fi

if [ -z "${SCROLLLOCK_URL:-}" ] || [ -z "${SCROLLLOCK_WRITE_TOKEN:-}" ]; then
  echo "scrolllock: set SCROLLLOCK_URL and SCROLLLOCK_WRITE_TOKEN in this environment, or the hook stays silent."
fi
