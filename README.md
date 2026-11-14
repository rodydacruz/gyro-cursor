# Gyro Cursor

Control your Android phone without touching the screen.
A red dot follows the phone's movement (gyroscope), and the volume buttons are your finger.

Plain Java + Android SDK only: no Gradle, no AndroidX, no frameworks. Android 8.0+.

## Controls

| Do this | Get this |
|---|---|
| Turn the phone left / right, tilt it up / down | Move the dot |
| Move slowly near a button | Aim assist: the dot slips onto it, slows a little, button gets an outline |
| Volume Down twice, quickly | Tap |
| Volume Down twice, keep holding | Touch and hold |
| Volume Down twice, hold + move the phone | Drag / scroll |
| Volume Up twice, quickly | Back |
| Volume Up twice, on the lock screen | Unlock (swipe up) |
| One press of a volume button | Volume (works as normal) |

Speed, direction and aim assist grip (a slider, 0 = off) can be changed in the app, live.
Aim assist only reads where clickable things are on screen (never their text), and nothing leaves the phone.

Unlock needs the screen to be on (phones don't send the volume buttons to apps while the
screen is off). If you have a PIN, pattern or fingerprint, Android still asks for it.

## Build (Windows)

You need:

- **JDK 17**
- **Android SDK** with `platforms;android-34` and `build-tools;34.0.0`

`build.ps1` finds them in `ANDROID_HOME` / `JAVA_HOME`, or in
`%LOCALAPPDATA%\Android\Sdk` and `%LOCALAPPDATA%\Android\jdk17`.

No Android Studio? Get the "command line tools only" zip from
<https://developer.android.com/studio>, unzip it to
`%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest`, then run:

```
sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

Build the APK:

```
powershell -ExecutionPolicy Bypass -File build.ps1
```

This creates `GyroCursor.apk`. To build **and** install on a phone plugged in by USB
(with USB debugging on):

```
powershell -ExecutionPolicy Bypass -File build.ps1 -Install
```

## First run on the phone

1. Open **Gyro Cursor** and tap **Open accessibility settings**.
2. Pick **Gyro Cursor** and turn it on.
3. If Android says *Restricted setting*: Settings > Apps > Gyro Cursor > ⋮ >
   **Allow restricted settings**, then try again.

After that it starts by itself every time the phone boots.

## How the build works

`build.ps1` runs the SDK tools directly:

1. `aapt2` compiles `res/` + `AndroidManifest.xml`
2. `javac` compiles `src/`
3. `d8` turns the classes into `classes.dex`
4. `zipalign` + `apksigner` make the final signed APK

On the first build it creates a `debug.keystore` (not committed).
A new key means a new signature, so uninstall the old app if the install fails with a signature error.

## Files

```
AndroidManifest.xml          app + accessibility service declaration
res/                         icon, strings, accessibility config
src/.../CursorService.java   the dot, gyroscope, volume buttons, taps/drags
src/.../MainActivity.java    settings screen
build.ps1                    build script
```
