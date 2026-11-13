# Nexus Engine for Android

The Flutter app hosts the Nexus 2D editor offline. The app includes the scene editor, inspector, block logic, Python, preview, and editable ZIP import/export. Use GitHub Actions → **Build Nexus editor APK** → **Nexus-Engine-APK** to download the editor.

## Export a game APK

1. Open a project in the editor and choose **Home → Export Android APK**.
2. Set the app name, unique Android package ID, icon, orientation, version name, and build number. The icon picker accepts common Android image formats and prepares a square PNG.
3. Create a [fine-grained GitHub token](https://github.com/settings/personal-access-tokens/new) scoped to this repository, with **Contents** and **Actions** read/write permissions. Enter it in the dialog for this build session.
4. Start the build, then save the APK when it is ready. You can also open the run and download its artifact from GitHub Actions.

The APK opens the game directly in an immersive Android WebView with the HTML and assets bundled offline. It is an independent app and does not include the editor. The export step needs Internet access. This repository is public and the uploaded HTML and build artifact can be accessible to others. Use a private copy and update the repository setting in `lib/github_build.dart` for private builds. A finished APK only requires Android 7+ and an up-to-date System WebView.

A signing key is optional for installing a first test build. For updates across build runs, configure the same keystore with repository secrets `NEXUS_KEYSTORE_BASE64`, `NEXUS_STORE_PASSWORD`, `NEXUS_KEY_ALIAS`, and `NEXUS_KEY_PASSWORD`. Increment the build number for updates. If signing is unconfigured, the workflows use their runner's debug key and a later build may require uninstalling the earlier version.

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

Use Flutter 3.50.0, JDK 21, and the Android SDK. Run `bash tools/fetch-python.sh`, `flutter pub get`, and `flutter build apk --release`. The workflow downloads the same pinned Python runtime and verifies the SHA-256 of its bundled files.
