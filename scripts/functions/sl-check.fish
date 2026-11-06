function sl-check --description 'Ask the relay for status and say whether the hooks look alive'
    set -q SCROLLLOCK_URL; or sl-env --quiet
    if not set -q SCROLLLOCK_URL; or not set -q SCROLLLOCK_READ_TOKEN
        echo "sl-check: SCROLLLOCK_URL / SCROLLLOCK_READ_TOKEN not set (see .env.local)"
        return 1
    end
    set -l mode claude_busy
    test (count $argv) -ge 1; and set mode $argv[1]
    set -l body (curl -sS -m 8 -H "authorization: Bearer $SCROLLLOCK_READ_TOKEN" "$SCROLLLOCK_URL/api/status?mode=$mode")
    or begin
        echo "sl-check: could not reach $SCROLLLOCK_URL"
        return 1
    end
    echo $body
    if command -q jq
        echo $body | jq -r '"working=\(.working)  reason=\(.unlockReason)  sessions=\(.activeSessions)  hooks_stale=\(.relayStale)"'
    end
    string match -q '*"relayStale":true*' -- $body
    and echo "WARNING: no hook events for 24h+ (or ever). Check the plugin, env vars and the token."
    return 0
end
