#!/usr/bin/env bash
# Tests for hooks/install.sh: idempotent, merges instead of overwriting, refuses bad JSON.
# Usage: bash hooks/test/install_test.sh
set -u
here="$(cd "$(dirname "$0")" && pwd)"
inst="$here/../install.sh"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected [$3] got [$2]"; fail=1; fi; }
run() { SL_BIN="$work/bin/scrolllock-hook" SL_SET="$work/settings.json" sh "$inst" 2>&1; }
count() { python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));print(sum(1 for e in d["hooks"].values() for g in e for h in g["hooks"] if h["command"]==sys.argv[2]))' "$work/settings.json" "$work/bin/scrolllock-hook"; }

# 1. Fresh install
run >/dev/null
check "fresh: six events wired" "$(count)" 6
check "fresh: hook is executable" "$(test -x "$work/bin/scrolllock-hook" && echo yes)" yes

# 2. Running twice adds nothing
out="$(run)"
check "second run: still six" "$(count)" 6
check "second run: says already installed" "$(printf '%s' "$out" | grep -c 'already installed')" 1

# 3. Existing settings and hooks survive
rm -f "$work/settings.json" "$work/settings.json.scrolllock.bak"
cat > "$work/settings.json" <<'JSON'
{"model":"opus","permissions":{"allow":["Bash(ls)"]},"hooks":{"Stop":[{"hooks":[{"type":"command","command":"echo mine"}]}]}}
JSON
run >/dev/null
check "merge: keeps other keys" "$(python3 -c 'import json;d=json.load(open("'"$work"'/settings.json"));print(d["model"],d["permissions"]["allow"][0])')" "opus Bash(ls)"
check "merge: keeps my Stop hook" "$(python3 -c 'import json;d=json.load(open("'"$work"'/settings.json"));print(any(h["command"]=="echo mine" for g in d["hooks"]["Stop"] for h in g["hooks"]))')" True
check "merge: adds ours" "$(count)" 6
check "merge: backup written" "$(test -f "$work/settings.json.scrolllock.bak" && echo yes)" yes

# 4. Invalid JSON is left alone
echo '{ not json' > "$work/settings.json"
out="$(run)"; rc=$?
check "bad json: untouched" "$(cat "$work/settings.json")" '{ not json'
check "bad json: explains" "$(printf '%s' "$out" | grep -c 'not valid JSON')" 1

# 5. The installed hook is silent and exits 0 with no env
out="$(echo '{"session_id":"x","hook_event_name":"Stop"}' | env -u SCROLLLOCK_URL -u SCROLLLOCK_WRITE_TOKEN sh "$work/bin/scrolllock-hook")"; rc=$?
check "hook: silent without env" "$out" ""
check "hook: exit 0" "$rc" 0

# 6. The embedded hook matches the paste-in snippet, so the two cannot drift
a="$(sed -n "/<<'HOOK'/,/^HOOK\$/p" "$here/../install.sh")"
b="$(sed -n "/<<'HOOK'/,/^HOOK\$/p" "$here/../cloud-setup-snippet.sh")"
check "install.sh and cloud-setup-snippet.sh embed the same hook" "$a" "$b"

[ "$fail" = 0 ] && echo "all passed" || { echo "FAILED"; exit 1; }
