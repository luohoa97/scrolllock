# Abbreviations. Type the short name, press space, it expands.
abbr -a slb './gradlew installDebug'                                   # build + install the debug app
abbr -a slr './gradlew installRelease'                                 # build + install the release app (Lockdown applies)
abbr -a slt 'pnpm --dir $SCROLLLOCK_ROOT/server test'                  # server unit tests
abbr -a slh 'bash $SCROLLLOCK_ROOT/hooks/test/run.sh'                  # hook script tests
abbr -a sld 'vercel deploy --prod --cwd $SCROLLLOCK_ROOT/server'       # deploy the relay
abbr -a sllog 'sl-logcat'                                              # logcat for the app only
abbr -a sltail 'vercel logs --follow'                                  # tail relay logs (run from server/ or pass the deployment URL)
abbr -a slc 'sl-check'                                                 # is the relay up, are hooks alive
