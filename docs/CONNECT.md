# Connect your agent to ScrollLock

ScrollLock unlocks your apps while an agent is working. It learns that from one thing: a tiny HTTP call from your agent to your relay when a turn starts, while it runs, and when it stops. Any tool that can run a command at those moments can drive it.

Everything below needs two values, the same ones the README sets up:

- `SCROLLLOCK_URL`: your relay, for example `https://your-relay.vercel.app`
- `SCROLLLOCK_WRITE_TOKEN`: the **write** token (not the read token)

Never put the write token in a repo. Keep it in your shell environment.

The hook sends a session id, the event name, whether a person typed the prompt, a lease length, the agent's name, and task counts (see "Agents and task lists"). It never sends prompt text, and task titles only if you opt in. It prints nothing and always exits 0, so it can't interfere with the agent.

## Claude Code on your own machine

1. Set the two variables once, in Fish:

   ```fish
   set -Ux SCROLLLOCK_URL https://YOUR-RELAY.vercel.app
   set -Ux SCROLLLOCK_WRITE_TOKEN <the write token>
   ```

2. Install the hook. It goes into your user-level `~/.claude/settings.json`, so every project is covered, and anything already in that file is kept:

   ```fish
   curl -fsSL https://raw.githubusercontent.com/luohoa97/scrolllock/main/hooks/install.sh | sh
   ```

   It is safe to run again. If the repo is private, paste `hooks/cloud-setup-snippet.sh` into a file and run that with `sh` instead.

3. Restart Claude Code. Hooks are read at start-up.
4. Check it. Start a prompt, then run `sl-check` (see "Fish helpers" in the README). You should see `working=true`. When the turn ends, the phone locks after the grace period.

For Claude Code on the web, see the README, "Claude Code on the web".

## Codex CLI

Codex has lifecycle hooks with the same event names as Claude Code (`SessionStart`, `UserPromptSubmit`, `PreToolUse`, `PostToolUse`, `Stop`, `SessionEnd`), and passes `session_id` and `hook_event_name` on stdin, so the same script works. Codex reads hooks from `~/.codex/hooks.json`.

1. Set the two variables as above.
2. Install into Codex's file:

   ```fish
   curl -fsSL https://raw.githubusercontent.com/luohoa97/scrolllock/main/hooks/install.sh | env SL_SET=$HOME/.codex/hooks.json SL_AGENT=codex sh
   ```

   `SL_AGENT=codex` makes the hook report itself as "Codex", so the phone shows it next to your other agents.

3. Start Codex and run `/hooks`. Codex skips new or changed hooks until you trust them, so review and trust the ScrollLock entries there.
4. Check with `sl-check` as above.

Written from [OpenAI's hooks docs](https://learn.chatgpt.com/docs/hooks) on 6 November 2026 and tested against the hook script, not against a live Codex install. If Codex changes its hook file or events, that page is the source of truth. If you already keep hooks in `~/.codex/config.toml`, Codex merges both and warns at start-up, which is harmless.

## Gemini CLI

Gemini CLI has hooks in `settings.json`, with different event names. The hook script already understands them (`BeforeAgent` starts work, `BeforeTool` and `AfterTool` keep it alive, `AfterAgent` stops it). Gemini's hook timeout is in milliseconds, so set it to match:

```fish
curl -fsSL https://raw.githubusercontent.com/luohoa97/scrolllock/main/hooks/install.sh | env SL_SET=$HOME/.gemini/settings.json SL_AGENT=gemini SL_EVENTS="SessionStart BeforeAgent BeforeTool AfterTool AfterAgent SessionEnd" SL_TIMEOUT=5000 sh
```

Restart Gemini CLI afterwards. Written from [Gemini CLI's hooks reference](https://geminicli.com/docs/hooks/reference/) and tested against the hook script, not a live install.

## Anything else with hooks (Cursor, Aider, your own agent)

If the tool can run a command on "turn started", "tool started", "tool finished" and "turn stopped", map those to the relay's events. It is one POST:

```fish
curl -sS -X POST $SCROLLLOCK_URL/api/event \
  -H "authorization: Bearer $SCROLLLOCK_WRITE_TOKEN" \
  -H 'content-type: application/json' \
  -d '{"session_id":"my-agent-1","event":"working","human":true,"lease_ms":10000}'
```

| `event` | Send it when | Effect |
| --- | --- | --- |
| `hello` | the session starts | the relay knows the session exists; nothing unlocks |
| `working` | a turn starts (set `"human": true` when a person typed the prompt, not an automated one) | unlocked for `lease_ms` |
| `heartbeat` | a tool starts running | unlocked for `lease_ms`, or 10 minutes if you leave it out, because tools can run long |
| `progress` | the agent updates its task list | renews the lease, and records the counts below |
| `tool_done` | a tool finishes | renews the lease |
| `beat` | every few seconds while busy | renews the lease, but only if the session is already working, so a stray one can't unlock anything |
| `needs_you` | the agent is blocked on you (a permission prompt, a question) | **locks, even while other sessions work**, until you answer. Add `"reason":"permission"` (or `question`, `input`, `other`) and, if you opt in, `"label":"project name"` |
| `resolved` | you answered | clears that session's wait |
| `idle` | the turn stops | locks after the 10 second grace period |
| `end` | the session closes | locks at once |

Add `"agent": "my-agent"` to any event to name the session (letters, digits, space, `.`, `_`, `-`, up to 24 characters). The phone lists every working agent by that name.

`session_id` is anything stable per session, up to 120 characters of letters, digits, `.`, `_` and `-`. Use a different one per session. Several sessions can be working at once, and the phone stays unlocked while any one is.

**The lease.** `lease_ms` (3000 to 120000) is how long a `working` event keeps things unlocked before the relay assumes the agent died. The shipped hook uses 10 seconds and renews it every 3 seconds from a small background loop, so a killed agent locks within about 10 seconds. If your tool can't run a background loop, you have two choices:

- Send `working` or `beat` every few seconds while it's busy (best), or
- Send `working` with a longer lease such as `"lease_ms": 60000` and an `idle` when it ends. A crash then stays unlocked until the lease runs out, at most a minute here.

A turn that is interrupted (Escape, Ctrl-C) may fire no stop event. The shipped hook stops renewing after 2 minutes without activity (10 minutes while a tool is running) so that case locks on its own.

### Tools with no hooks at all

Wrap the command. This Fish function reports working for as long as the command runs and idle afterwards, even if it fails:

```fish
function sl-wrap --description 'Run a command and tell ScrollLock while it runs'
    set -l sid "wrap-"(random)
    set -l post curl -sS -m 3 -o /dev/null -X POST $SCROLLLOCK_URL/api/event \
        -H "authorization: Bearer $SCROLLLOCK_WRITE_TOKEN" -H 'content-type: application/json'
    $post -d '{"session_id":"'$sid'","event":"working","human":true,"lease_ms":60000}'
    command $argv
    set -l code $status
    $post -d '{"session_id":"'$sid'","event":"idle"}'
    return $code
end
```

Use it as `sl-wrap aider` or `sl-wrap my-agent --task build`. Because the lease is 60 seconds, a command killed with `kill -9` stays unlocked for up to a minute.

## Agents and task lists

Several agents can work at once, in any mix of tools. The phone's notification and Status screen list each one by name with its task progress: "Claude 3/7 · Codex working". A rolled-up count appears when two or more report one.

The shipped hook reads the agent's own task list when it updates it: Claude Code's `TodoWrite`, Codex's `update_plan` and Gemini's `write_todos`. That works only where the tool shows its plan to hooks. Claude Code does. For Codex and Gemini this is written from their docs and not tested live, so if counts don't show up there, post them yourself:

```fish
curl -sS -X POST $SCROLLLOCK_URL/api/event \
  -H "authorization: Bearer $SCROLLLOCK_WRITE_TOKEN" -H 'content-type: application/json' \
  -d '{"session_id":"my-agent-1","event":"progress","agent":"my-agent","tasks_done":3,"tasks_total":7,"lease_ms":10000}'
```

**Task text is opt-in, twice.** By default only counts leave your machine. To also share the current and next task title, set this where the agent runs:

```fish
set -Ux SCROLLLOCK_TASK_TEXT 1
```

Titles then go to your relay, its database and the phone. Firebase pushes never carry them. On the phone, Settings, "Show task titles" must also be on before they appear, and they never appear on the lock screen: that shows a public version with names and counts only. In your own calls, send `tasks_now` and `tasks_next` (up to 120 characters each).

## Waiting on you

If any agent is waiting on you, the blocked apps stay locked, even while other agents are working, and the lock screen says who is waiting instead of "Claude isn't working". Two sources feed it:

- **Hooks.** The shipped hook sends `needs_you` for Claude Code's `Notification` event (permission prompts, questions) and Codex's `PermissionRequest`, and clears it when the tool finishes, when you send a new prompt, or when the turn ends. It is the same relay call as above if your own tool wants to send it. A wait that is never cleared expires after 3 hours.
- **The Claude app on your phone.** Settings, "Waiting on you", Notification access. ScrollLock then reads notifications from the Claude app only (never other apps), keeps them in memory, and sends nothing anywhere. This is the one that covers coordinators, projects and accounts you can't hook. Sideloaded apps start with this switch greyed out: App info, three dots, Allow restricted settings. The words it matches are a first guess: use "Capture Claude notifications" in Settings to copy real ones into an issue.

Project or thread names appear only if you turn on "Show task titles" and your sender opted in. The lock screen only ever says how many things are waiting.

## If it isn't working

- `sl-check` shows `hooks_stale=true`, or `working=false` mid-turn: the agent isn't reaching the relay. Check both variables are set in the shell the agent runs in (`echo $SCROLLLOCK_URL`), and that you used the write token.
- Nothing happens in Codex: run `/hooks` and trust the entries.
- Nothing happens in Claude Code or Gemini CLI: restart it. Hooks are read at start-up.
- Test the relay on its own: `fake-claude working` then `sl-check`. If that works, the relay is fine and the problem is the hook wiring.
