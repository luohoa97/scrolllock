function sl-latency --description 'Time from fake-claude working to the relay reporting unlocked (target: about 2s)'
    set -q SCROLLLOCK_URL; or sl-env --quiet
    set -l sid latency-test
    fake-claude idle $sid >/dev/null
    sleep 12 # let the idle grace pass so we start locked
    set -l t0 (date +%s%N)
    fake-claude working $sid; or return 1
    for i in (seq 1 40)
        set -l body (curl -sS -m 3 -H "authorization: Bearer $SCROLLLOCK_READ_TOKEN" "$SCROLLLOCK_URL/api/status")
        if string match -q '*"working":true*' -- $body
            set -l t1 (date +%s%N)
            echo "unlocked after "(math --scale=0 "($t1 - $t0) / 1000000")" ms (relay side; the phone adds push latency)"
            fake-claude idle $sid >/dev/null
            return 0
        end
        sleep 0.1
    end
    echo "never unlocked"
    return 1
end
