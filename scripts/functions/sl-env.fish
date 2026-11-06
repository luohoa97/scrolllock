function sl-env --description 'Load SCROLLLOCK_* variables from <repo>/.env.local'
    set -l quiet 0
    contains -- --quiet $argv; and set quiet 1
    set -l file $SCROLLLOCK_ROOT/.env.local
    if not test -f $file
        test $quiet = 0; and echo "sl-env: no $file (copy .env.example and fill it in)"
        return 1
    end
    for line in (string match -rv '^\s*(#|$)' < $file)
        set -l kv (string split -m1 = -- $line)
        test (count $kv) = 2; or continue
        set -gx $kv[1] (string trim -c '"\'' -- $kv[2])
    end
    test $quiet = 0; and echo "sl-env: loaded $file"
end
