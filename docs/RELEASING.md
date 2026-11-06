# Releasing

Release builds are signed with your own key so the Lockdown guard (device admin) works and updates install over each other.

1. Keep `scrolllock-release.jks` and its passwords somewhere safe and backed up. Lose it and nobody can update the app without uninstalling.
2. Copy `android/keystore.properties.example` to `android/keystore.properties` (gitignored) and fill it in.
3. `cd android && ./gradlew :app:assembleRelease` gives `app/build/outputs/apk/release/app-release.apk`.
4. Never commit the keystore or `keystore.properties`.
