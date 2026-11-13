# Nexus Engine for Android

The Flutter app hosts the Nexus 2D editor offline. The app includes the scene editor, inspector, block logic, Python, preview, and editable ZIP import/export. Use GitHub Actions → **Build Nexus editor APK** → **Nexus-Engine-APK** to download the editor.

## Export a game APK

1. Open a project and choose **Home → Export Android APK**.
2. Set the app name, unique Android package ID, launcher icon, orientation, version name, and build number.
3. Tap **Build APK** and then **Save APK**. The editor packages the game HTML and assets into a lightweight Android player, signs it on the device, and opens the Android save dialog.

The export works offline. The finished APK opens directly in a full-screen Android WebView on Android 7+. Games run without the editor installed. The editor keeps the signing key in Android Keystore. To update an installed game, export from the same Nexus installation, reuse its package ID, and increase the build number. Clearing the editor's data or switching devices may require uninstalling the old game before installing a new build.

## Python and blocks

Choose **Logic → Python** and select an object. Apply a script and use Play. Both Python and linked block graphs can run on the same object. The original Nexus Script behavior still works for existing projects.

```python
from math import sin

time = 0

def on_event(event, dt, game):
    global time
    if event == "OnUpdate":
        time += dt
        game["x"] += sin(time) * 40 * dt
```

Python 3 scripting is powered by [Skulpt](https://github.com/skulpt/skulpt). Its supported syntax and built-ins include classes, comprehensions and functions. Nexus exposes the object position, velocity, input, and event data through the `game` dictionary and includes `math` and `random` imports. Scripts have a time limit and run offline in editor preview and exported games. Project ZIPs preserve the scripts.

## Local build

Use Flutter 3.50.0, JDK 21, and the Android SDK. Run `bash tools/fetch-python.sh`, `bash tools/build-player.sh`, `flutter pub get`, and `flutter build apk --release`. The workflow downloads the same pinned Python runtime and verifies the SHA-256 of its bundled files.

GitHub Actions builds the editor and embeds the reusable player template. Its packaging smoke test verifies the signed APK, application ID, version, orientation, custom icon and embedded HTML.
