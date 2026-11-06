# ScrollLock

<!-- Intro goes here. Neil writes this part. -->

Instagram, YouTube, TikTok, Snapchat and Facebook stay blocked unless a Claude Code session is working. The whole app is blurred, muted and unusable, with no screen detection to get wrong. Discord is left alone. The list is `BLOCKED` in `BlockerService.kt`; check the package names against your phone with `adb shell pm list packages`.

| Piece | What it does |
| --- | --- |
| `server/` | The relay: a few Vercel functions and a Neon database. Hooks post events, devices ask for status. |
| `hooks/` | Reports "working" or "idle" from your Claude Code sessions. |
| `android/` | The phone app: blur lock screen over Instagram, push, lockdown. |
| `extension/` | Desktop Chrome extension with the same lock screen. |
| `scripts/` | Fish helpers for testing the whole chain. |

## Set up

About 20 minutes. Do these in order. Nothing here costs money on free tiers except where noted.

### 1. Database (Neon)

1. Create a Neon project and copy the pooled connection string.
2. Run the migration once:

   ```fish
   psql $DATABASE_URL -f server/migrations/001_init.sql
   ```

   Or paste the file into Neon's SQL editor.

### 2. Relay (Vercel)

1. Import this repo into Vercel and set **Root Directory** to `server`. The per-minute cron in `vercel.json` needs Vercel Pro (Hobby only allows daily crons, and refuses the deploy otherwise). On Hobby, change its schedule to `0 4 * * *`. The phone and extension lock on their own when a lease runs out, so the cron only sends corrective pushes and deletes week-old rows.
2. Add these environment variables (see `.env.example` for what each is):

   | Name | Value |
   | --- | --- |
   | `DATABASE_URL` | the Neon string |
   | `WRITE_TOKEN` | `openssl rand -hex 32` |
   | `READ_TOKEN` | `openssl rand -hex 32` (a different one) |
   | `CRON_SECRET` | any random string |
   | `FCM_SERVICE_ACCOUNT_JSON` | step 3, optional |

3. Deploy, then check it:

   ```fish
   curl -s -H "authorization: Bearer $READ_TOKEN" https://YOUR-RELAY.vercel.app/api/status
   ```

   You should see `"working": false`.

The write token can only post events. The read token can only read status and register a device. Keep them apart: the write token lives in your Claude environment, the read token on your phone and browser.

### 3. Push (Firebase, optional but recommended)

Without push the phone checks the relay when Instagram opens, which is slower to unlock. With push it unlocks in about two seconds.

1. Create a Firebase project. Add an Android app with package name `com.neilluo.scrolllock`, and a second one with `com.neilluo.scrolllock.debug` if you will run debug builds.
2. Download `google-services.json` into `android/app/`. It is gitignored.
3. Project settings, Service accounts, Generate new private key. Paste the whole JSON (or its base64) into the Vercel variable `FCM_SERVICE_ACCOUNT_JSON` and redeploy.

### 4. Claude Code hooks

The hooks need two environment variables where Claude Code runs: `SCROLLLOCK_URL` (your relay URL) and `SCROLLLOCK_WRITE_TOKEN`.

**Claude Code on the web (cloud projects).**

1. In your environment settings, add the two variables.
2. Put this one line at the top of the environment's setup script:

   ```sh
   curl -fsSL https://raw.githubusercontent.com/luohoa97/scrolllock/main/hooks/install.sh | sh
   ```

   It runs on every fresh container and is safe to run twice. It adds the hooks to the container's user-level `~/.claude/settings.json` and keeps anything already there, so every project in that environment is covered, with no per-repo files.

   If the repo is private the container can't fetch that URL. Paste the contents of `hooks/cloud-setup-snippet.sh` instead.

3. Start a new session. Setup scripts only run on fresh containers, so existing sessions won't report.

**Claude Code on your own machine.**

```fish
curl -fsSL https://raw.githubusercontent.com/luohoa97/scrolllock/main/hooks/install.sh | sh
set -Ux SCROLLLOCK_URL https://YOUR-RELAY.vercel.app
set -Ux SCROLLLOCK_WRITE_TOKEN <the write token>
```

Restart Claude Code. The installer needs `python3` or `node` to edit `settings.json`; it won't touch a file that isn't valid JSON.

**Codex, Gemini CLI, or any other tool with hooks.** See [docs/CONNECT.md](docs/CONNECT.md) for step-by-step guides, the event contract for tools of your own, and a wrapper for tools with no hooks.

What the hook sends: a session id, the event name, whether a human typed the prompt, a lease length, the agent's name (Claude, Codex, ...) and how many tasks it has done out of how many. Never prompt text. Task titles are sent only if you set `SCROLLLOCK_TASK_TEXT=1`; see [docs/CONNECT.md](docs/CONNECT.md).

**How "working" stays true.** Each Claude event (a prompt, a tool starting or finishing) tells the relay the session is working for the next 10 seconds, which is the *lease*. While a turn runs, the hook also starts a tiny detached loop that renews the lease every 3 seconds, so a session that dies, loses its container or is killed locks within about 10 seconds. The loop prints nothing, never touches Claude's context, and stops on Stop, on session end, when Claude's own process is gone, or after 2 minutes with no Claude activity (10 minutes while a tool is running). The phone and extension ask the relay again just before a lease runs out, so they stay unlocked while Claude works. An interrupted turn (Esc) fires no Stop event, so it locks when that 2 minutes passes.

Fresh cloud containers get the new hook from the setup script. If you installed an older one, run the install line again (it is safe to repeat) or paste the updated `hooks/cloud-setup-snippet.sh`.

### 5. Android app

Needs JDK 21 and the Android SDK (or just open `android/` in Android Studio).

```fish
cd android
cp local.properties.example local.properties   # set sdk.dir
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For a real build, create a keystore and a `android/keystore.properties` (gitignored) with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`, then `./gradlew :app:assembleRelease`. Lockdown only works in release builds.

On the phone:

1. Open ScrollLock and enter the relay URL and the **read** token. Test connection.
2. Turn on the accessibility service. Sideloaded apps start with that switch greyed out: App info, three dots, **Allow restricted settings**, then try again.
3. Allow background running so pushes arrive promptly.
4. Optional: Settings, Lockdown. See "Lockdown" below.

### 6. Desktop extension

1. Chrome, `chrome://extensions`, turn on Developer mode, **Load unpacked**, pick the `extension/` folder.
2. The options page opens. Enter the relay URL and the **read** token, press Save and test, and accept the permission prompt for your relay's address.

## Check that it works

Run these once, in order. `fake-claude` is in `scripts/` (see "Fish helpers").

1. `fake-claude idle`, then open Instagram. A blurred screen appears at once: "Instagram locked / Claude isn't working / 8s". Try YouTube, TikTok, Snapchat and Facebook too.
2. Same screen on Reels, Explore and Messages. It stays up (it must not drop after a second).
3. Leave the app for another one. The overlay goes. Come back, or open it from Recents or split screen, and it returns. In split screen only that app's half is covered.
4. Back on Reels with the screen still locked, run `fake-claude working`. The overlay lifts within about two seconds (push) or when you reopen Instagram (no push).
5. `fake-claude idle`. About ten seconds later (the grace period) Reels locks again.
6. Stop sending anything. After about 90 seconds an unlocked phone locks again on its own.
7. Tap "Back to Claude". The Claude app comes to the front on whatever it was showing. It must not start a new chat.
8. Settings, "Allow one 5 minute bypass a day": it can't be switched on while locked, only while `fake-claude working` is running. Switch it on, then `fake-claude idle`: the lock screen now has a Bypass button, good for 5 minutes, once a day.
9. Turn the phone's clock forward an hour. A locked phone stays locked, and a bypass doesn't last longer.
10. Audio under the overlay: ScrollLock asks the app to pause (audio focus), and does not touch your media volume. Whether the app listens is unverified. Watch for the blur: it must be there every time, even with battery saver on.
11. Settings, Debug, Capture next Instagram screen in 5s. Open Reels. A toast says the dump was copied.
12. With Lockdown on: App info for ScrollLock bounces you to the home screen, and Uninstall is greyed out.

Chrome: with the relay idle, every `instagram.com` page shows the same screen and the video stops. Only the `/accounts/` login pages pass.

Tests that run without a phone:

```fish
cd server;    pnpm install; pnpm test
cd android;   ./gradlew :app:testDebugUnitTest
cd extension; npm test          # plus: node e2e/run.cjs   (real Chromium, fake relay)
bash hooks/test/run.sh; bash hooks/test/install_test.sh; bash hooks/test/beat_test.sh
```

## Lockdown

Lockdown makes ScrollLock annoying to remove on impulse. It does not make it impossible: anyone with a computer and `adb` can still remove it.

- It registers as a device admin, so the system greys out Uninstall.
- While it is on, Settings and installer screens that show ScrollLock next to words like "Uninstall" or "Use ScrollLock" send you to the home screen.
- Turning it off is a request, then a 24 hour wait, then a confirmation. The wait uses the phone's uptime clock, so changing the date does nothing, and a reboot restarts it.
- It never turns on in debug builds.

The Settings guard reads the labels on Settings screens, only while Lockdown is on, only to match them against the app name and the keywords in `rules.json`. They are not stored.

## What the app can see

- Which windows are on screen and their package names and bounds, to find a blocked app and cover only its window.
- A blocked app's screen structure, only when you ask for a debug dump in Settings: view ids, class names, content descriptions, which tab is selected, sizes. Never the text of messages or text fields.
- If you grant notification access: the title and text of notifications from the Claude app only, to spot "waiting on you". Kept in memory, never stored or sent.
- Nothing is sent anywhere except the relay: your read token to ask for status, and your push token to register.

## Fish helpers

```fish
cp .env.example .env.local        # fill in the URL and tokens
source scripts/init.fish          # add this line to ~/.config/fish/config.fish
```

| Command | What it does |
| --- | --- |
| `fake-claude working\|beat\|idle\|end\|hello\|run` | Pretends to be a Claude Code session |
| `sl-check` | Asks the relay for status and says whether the hooks look alive |
| `sl-latency` | Times `fake-claude working` to the relay unlocking (target: about 2s) |
| `sl-logcat` | `adb logcat` filtered to the app |

## Licence

MIT. See `LICENSE`.
