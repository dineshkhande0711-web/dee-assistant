# Dee Assistant — Complete Setup Guide

A voice assistant that opens apps ("open Spotify"), controls basic phone
settings, and answers spoken questions — running on your phone and, after a
one-time manual setup, your friend's phone too.

```
DeeAssistant/
├── backend/
│   ├── main.py            <- FastAPI server (runs on your PyCharm machine)
│   └── requirements.txt
├── android/
│   └── app/
│       ├── build.gradle
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── java/.../MainActivity.kt
│           ├── java/.../DeeAccessibilityService.kt
│           ├── java/.../NetworkClient.kt
│           └── res/...
└── README.md   (this file)
```

## How it works, end to end

1. You tap the mic button → Android's `SpeechRecognizer` converts speech to text.
2. The text is POSTed to your FastAPI backend (`/command`).
3. Claude decides: is this an **action** ("open spotify") or a **question**
   ("what's the capital of France")? It returns one JSON object either way.
4. If it's an action, `DeeAccessibilityService` executes it on-device
   (launches the app, taps a button, etc.).
5. If it's a question, the phone speaks the answer back via TextToSpeech.

## Part 1 — Backend (on your PyCharm machine)

```bash
cd DeeAssistant/backend
python -m venv venv
source venv/bin/activate        # Windows: venv\Scripts\activate
pip install -r requirements.txt

export ANTHROPIC_API_KEY="sk-ant-..."      # Windows: set ANTHROPIC_API_KEY=...
export MY_PHONE_TOKEN="pick-a-long-random-string"
export FRIEND_PHONE_TOKEN="pick-a-different-long-random-string"

uvicorn main:app --host 0.0.0.0 --port 8000
```

Find your machine's LAN IP (needed so phones on the same Wi-Fi can reach it):
- Mac/Linux: `ifconfig | grep inet`
- Windows: `ipconfig`

You'll get something like `192.168.1.100`. Both phones must be on the **same
Wi-Fi network** as this machine for the default setup to work. (For remote
access from outside the house, you'd deploy this to a small cloud VM instead
— happy to walk through that separately if you need it.)

## Part 2 — Android app

**Use Android Studio, not VS Code, for the Android client.** VS Code has no
built-in Android SDK manager, emulator, or Gradle integration — you'd spend
more time fighting tooling than building the app. Android Studio is free and
is what the Kotlin/Android ecosystem actually expects; PyCharm and Android
Studio are both JetBrains IDEs and feel similar, so the jump isn't as jarring
as it sounds.

1. Open Android Studio → **New Project → Empty Views Activity** → language
   Kotlin → minSdk 26.
2. Replace the generated files with the ones from `android/app/src/main/`
   in this project (same folder structure — `MainActivity.kt`,
   `DeeAccessibilityService.kt`, `NetworkClient.kt`,
   `AndroidManifest.xml`, `activity_main.xml`, `strings.xml`,
   `accessibility_service_config.xml`).
3. Merge the `dependencies { }` block from `android/app/build.gradle` into
   your project's `app/build.gradle`.
4. In `NetworkClient.kt`, set:
   - `BASE_URL` → `"http://<your-machine-LAN-IP>:8000"`
   - `DEVICE_ID` / `DEVICE_TOKEN` → `"my-phone"` / your `MY_PHONE_TOKEN`
5. Click **Run** with your phone connected via USB (enable Developer Options
   → USB Debugging first), or build an APK via **Build → Build APK(s)**.

## Part 3 — First-time setup on each phone (yours, then your friend's)

This part cannot be automated or done remotely — Android deliberately
requires a human to do this in person, as an anti-malware protection:

1. Install the app (USB install, or share the built APK file to install).
2. Open it → tap **"1. Enable Accessibility Service"** → it opens Android
   Settings → find "Dee Assistant" under Accessibility → toggle it on →
   confirm the warning dialog (Android 13+ shows this twice, by design).
3. Return to the app → grant the microphone permission if prompted.
4. Tap **"2. Tap and Speak"** → say **"open Spotify"** → it should launch.

For your friend's build: change `DEVICE_ID`/`DEVICE_TOKEN` in their copy of
`NetworkClient.kt` to `"friend-phone"` / `FRIEND_PHONE_TOKEN` before you build
their APK, so the backend tells the two phones apart and rate-limits/audits
them separately.

## Testing the "open Spotify" flow specifically

The app auto-sends its installed-app list to `/register_apps` on launch, so
the backend knows the exact package name for Spotify on that phone. Say
"open Spotify" → Claude matches it against that list → returns
`{"kind":"action","action":"open_app","package_name":"com.spotify.music"}` →
the accessibility service launches it. If Spotify isn't installed, it'll
still try a best-guess package name and report "App not installed."

## Extending it

- More actions: add a new `when` branch in `DeeAccessibilityService.kt`
  and describe it in the backend's `SYSTEM_PROMPT_TEMPLATE`.
- Wake word ("Hey Dee") instead of a tap: look at Porcupine or
  `SpeechRecognizer`'s continuous-listening mode — different enough to be
  its own follow-up if you want it.
- Multi-turn conversation: currently every command is stateless; you'd add
  a per-device message history array to `/command`'s request.

## Security checklist before giving this to a friend

- [ ] Backend requires the `Authorization: Bearer <token>` header (already
      enforced in `main.py` — don't remove `check_auth`).
- [ ] Each phone has its own unique token, not a shared one.
- [ ] The friend understands what the app can see/do (screen content,
      app launching) before they enable the accessibility service —
      this should be their informed choice, not a surprise.
- [ ] `allowBackup="false"` stays set (already in the manifest) so no auto
      cloud-backup of app data.
- [ ] If you ever expose the backend outside your home Wi-Fi, put it behind
      HTTPS (e.g. Caddy/nginx with a real cert) — tokens sent over plain
      HTTP on the open internet are as good as no auth at all.
