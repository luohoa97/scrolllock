# ScrollLock dev helpers for fish. Add this to ~/.config/fish/config.fish:
#
#   source /path/to/scrolllock/scripts/init.fish
#
# Env comes from <repo>/.env.local (gitignored): SCROLLLOCK_URL, SCROLLLOCK_WRITE_TOKEN, SCROLLLOCK_READ_TOKEN.

set -g SCROLLLOCK_ROOT (path resolve (dirname (status filename))/..)
contains -- $SCROLLLOCK_ROOT/scripts/functions $fish_function_path
or set -ga fish_function_path $SCROLLLOCK_ROOT/scripts/functions
source $SCROLLLOCK_ROOT/scripts/conf.d/scrolllock.fish
sl-env --quiet
