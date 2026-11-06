function fake-claude --description 'Pretend to be a Claude Code session: fake-claude working|beat|idle|end|hello|run [session-id]'
    if test (count $argv) -lt 1
        echo "usage: fake-claude working|beat|idle|end|hello|run [session-id]"
        echo "  working  UserPromptSubmit (a human prompt): unlocks the phone"
        echo "  beat     PreToolUse heartbeat (10 min tool lease)"
        echo "  idle     Stop: locks after the grace period"
        echo "  end      SessionEnd"
        echo "  run      working, then a heartbeat every 20s until Ctrl-C (then stop beating: it locks in ~90s)"
        return 2
    end
    set -q SCROLLLOCK_URL; or sl-env --quiet
    if not set -q SCROLLLOCK_URL; or not set -q SCROLLLOCK_WRITE_TOKEN
        echo "fake-claude: SCROLLLOCK_URL / SCROLLLOCK_WRITE_TOKEN not set (see .env.local)"
        return 1
    end

    set -l what $argv[1]
    set -l sid fake-session
    test (count $argv) -ge 2; and set sid $argv[2]

    set -l ev
    switch $what
        case working; set ev working
        case beat; set ev heartbeat
        case idle; set ev idle
        case end; set ev end
        case hello; set ev hello
        case run
            __fake_claude_post $sid working true; or return 1
            echo "beating every 20s as '$sid'. Ctrl-C to stop; the phone should lock about 90s later."
            while true
                sleep 20
                __fake_claude_post $sid tool_done false
                __fake_claude_post $sid heartbeat false
            end
            return 0
        case '*'
            echo "fake-claude: unknown action '$what'"
            return 2
    end
    set -l human false
    test $ev = working; and set human true
    __fake_claude_post $sid $ev $human
end

function __fake_claude_post --argument-names sid ev human
    set -l res (curl -sS -m 5 -o /dev/null -w '%{http_code} %{time_total}s' -X POST "$SCROLLLOCK_URL/api/event" \
        -H "authorization: Bearer $SCROLLLOCK_WRITE_TOKEN" -H 'content-type: application/json' \
        -d "{\"session_id\":\"$sid\",\"event\":\"$ev\",\"human\":$human}")
    echo "$ev -> $res"
    string match -q '2*' -- $res
end
