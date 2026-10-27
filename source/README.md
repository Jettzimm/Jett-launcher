# Jett's TV Launcher

Custom Google TV home screen: greeting, live-sun background, sports bar, your 12 apps, and the
Saturday/Sunday auto-switch rules. `JettLauncher.apk` is already built; you only need to install it.

## One-time install (Mac, ~20 min)

### 1. Set up the box normally
Plug it in, sign in to Google, install and sign in to YouTube TV, ESPN, Prime and the rest.

### 2. Turn on developer mode on the box
1. Settings → System → About → click **Android TV OS build** 7 times until it says you're a developer.
2. Settings → System → Developer options → turn on **USB debugging** (and **Wireless debugging** if listed).
3. Settings → Network & Internet → click your Wi-Fi → write down the **IP address** (like `192.168.1.42`).

### 3. Install adb on the Mac
Open Terminal and run:
```
brew install android-platform-tools
```
(No Homebrew? Install it first from https://brew.sh.)

### 4. Connect and install
`cd` to the folder with the APK, then (swap in your IP):
```
adb connect 192.168.1.42:5555
```
A prompt appears on the TV: tick **Always allow** and press OK. Run it again if it says "unauthorized".

> If `5555` refuses: go to Developer options → Wireless debugging → **Pair device with pairing code**,
> then `adb pair <IP>:<pairing port>` (type the code), then `adb connect <IP>:<port shown on that screen>`.

```
adb install -r JettLauncher.apk
adb shell appops set com.jett.launcher SYSTEM_ALERT_WINDOW allow
adb shell appops set com.jett.launcher REQUEST_INSTALL_PACKAGES allow
adb shell dpm set-active-admin com.jett.launcher/.SleepAdmin
```
- Line 2 lets the Sunday RedZone/primetime switch pop up while you're inside another app.
- Line 3 lets the app install its own updates (only of itself).
- Line 4 lets the sleep timer turn the box off (the Samsung follows through HDMI-CEC).

### 5. Make it the home screen
Google TV has no setting to change the home app, so we switch off the stock one:
```
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```
Press Home on the remote. Your launcher appears.

**To undo at any time:**
```
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
adb shell am broadcast -a com.jett.launcher.RELEASE_ADMIN -n com.jett.launcher/.AdminReleaseReceiver
adb uninstall com.jett.launcher
```
(If uninstall fails right after the broadcast, wait a second and run it again.)
(The broadcast releases the sleep-timer permission; Android won't uninstall the app until it's released.)

**After a Google TV system update:** if the old Google home screen ever comes back, re-run the two
`pm disable-user` commands above.
The gear tile at the end of the app row opens Settings, since the stock home screen is off.

### 6. Box settings (on the Onn, with its remote)
- Settings → System → Power & energy → **Energy mode: Increased** (or Optimized; not "Low energy"),
  so the box can finish installing an update after the TV turns off.
- Settings → Display & Sound → **HDMI-CEC**: turn on **CEC**, **TV auto power off** and
  **Device auto power off**, so the box and TV go to sleep together. (Exact names vary slightly.)

### 7. Samsung TV settings
- Settings → General → External Device Manager → **Anynet+ (HDMI-CEC): On**
- Find the start-up option (named "Start Screen Option" / "Autorun Smart Hub" depending on model year)
  and set it to **last input** / turn Smart Hub autorun **off**.
- Switch to the box's HDMI input and leave it there.

## Updates and photos (automatic)
Everything after the first install comes from GitHub (github.com/Jettzimm/jett-launcher):
- `feed.json` — the latest version number, the APK link, channel codes and the photo library link.
  The box checks it on boot, daily at 4am, and every 6 hours while the TV is on.
- A newer version downloads in the background and installs the next time the TV turns off.
- `photos.json` + `photos/` — the full photo library (about 1,200, in 4K). The app ships with 179 built in (1080p)
  and downloads the rest in the background over the first hour or so. Adding/removing a photo there
  (and bumping `photosVersion` in feed.json) updates the TV within a day.

Manual install still works any time (`-d` allows going back a version):
```
adb connect 192.168.1.42:5555
adb install -r -d JettLauncher.apk
```

## Channel codes (direct jumps into YouTube TV)
YouTube TV opens a live channel from `https://tv.youtube.com/watch/<code>`; the codes are the same
for everyone but YouTube changes them every month or few. They live in the update feed's
`channels` list, so a new code reaches the box without reinstalling. To read a code: tv.youtube.com
on a computer → Live → click the channel → copy what follows `/watch/`.
A channel with no code (or a stale one) just opens YouTube TV.

**First-night test (once):** with a real code, see which form actually tunes the channel:
```
adb shell am start -a android.intent.action.VIEW -d "watch/<code>" -n com.google.android.youtube.tvunplugged/com.google.android.apps.youtube.tvunplugged.activity.MainActivity
adb shell am start -a android.intent.action.VIEW -d "https://tv.youtube.com/watch/<code>" com.google.android.youtube.tvunplugged
```
The first form is the default. If only the second works, set `"linkStyle": "web"` in the update
feed (no reinstall needed).

## What's where (for the code)
- `Config.kt` — **everything you'd tweak**: name, app order, teams, leagues, routing, rules.
- `Sports.kt` — ESPN scoreboard data and the Top 25 / Big Ten / playoffs filters.
- `Router.kt` — which app or channel a game opens in.
- `Updater.kt` — self-update: background download, installs when the TV turns off.
- `PhotoLibrary.kt` — mirrors the online photo library to the box.
- `JettApp.kt` — TV on/off listener (power-on rules, update install).
- `Sleep.kt` — bedroom sleep timer.
- `Rules.kt` — Saturday GameDay, Sunday RedZone → primetime, the 10-second countdown.
- `Sky.kt` — sunrise/sunset maths and the photo background (cross-fades every 3 minutes).
  Photos live in `app/src/main/assets/bg/{dawn,morning,midday,golden,dusk,night}/`; add or delete
  .webp files to change the built-in starter set (179). The full library is online (see Updates).
  Photos are from Unsplash (free to use under the Unsplash License).
- `Morning.kt` — the CNBC + morning-show cards (schedule in `Config.kt`).
- `MainActivity.kt` — the screen itself and remote/focus behaviour.

Build yourself (optional): install Android Studio, open this folder, Build → Build APK.

Signing: builds are signed with `jett.keystore` (password in `keystore.properties`). Both are kept
out of GitHub. Keep a copy of them: without that key, future versions can't install over this one.
