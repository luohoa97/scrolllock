function sl-logcat --description 'adb logcat filtered to the ScrollLock app'
    set -l pkg com.neilluo.scrolllock
    set -l pid (adb shell pidof -s $pkg | string trim)
    if test -z "$pid"
        echo "sl-logcat: $pkg is not running (open the app, or the service is off)"
        return 1
    end
    adb logcat --pid=$pid $argv
end
