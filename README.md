# Dee — Mobile AI Voice Assistant

A personal voice assistant for Android that opens apps ("open Spotify"), controls basic phone settings (Wi-Fi panel, volume), and answers spoken questions via conversational AI — running directly on your phone.

```
DeeAssistant/
├── .github/
│   └── workflows/
│       └── build-apk.yml       <- Automated APK compiler in GitHub Actions
├── backend/
│   ├── main.py                <- FastAPI server (Gemini, Claude, or OpenAI)
│   ├── requirements.txt
│   ├── .env.example
│   └── test_backend.py        <- Verification tests
├── android/                   <- Full Android Studio Gradle Project
│   ├── settings.gradle
│   ├── build.gradle
│   ├── gradle.properties
│   ├── gradlew / gradlew.bat
│   └── app/
│       ├── build.gradle
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── java/.../MainActivity.kt
│           ├── java/.../DeeAccessibilityService.kt
│           ├── java/.../NetworkClient.kt
│           └── res/...
├── render.yaml                 <- 1-click Render cloud deploy config
└── README.md
```

---

## Quick Start Guide

### Step 1: Run or Deploy the Backend

You can choose **Option A (Deploy to Render for Free)** or **Option B (Run Locally on your PC)**.

#### Option A: Deploy to Render (Cloud, Always Available)
1. Push this folder to a GitHub repository (see Part 1 below).
2. Go to [render.com](https://render.com) → **New → Blueprint**.
3. Select your repository. Render reads `render.yaml` automatically.
4. Set your environment variables:
   - `AI_PROVIDER`: `gemini` (recommended, free tier available), `anthropic`, or `openai`
   - `GEMINI_API_KEY` (or `ANTHROPIC_API_KEY` or `OPENAI_API_KEY`)
   - `MY_PHONE_TOKEN`: Any secret password you choose (e.g. `secret123`)
5. Click **Apply**. Once deployed, copy your live URL:
   `https://dee-assistant-backend.onrender.com`

#### Option B: Run Locally on your PC
1. Open a terminal in `DeeAssistant/backend`.
2. Copy `.env.example` to `.env` and enter your API key:
   ```env
   AI_PROVIDER=gemini
   GEMINI_API_KEY=your_key_here
   MY_PHONE_TOKEN=change-me-1
   ```
3. Run:
   ```bash
   uvicorn main:app --host 0.0.0.0 --port 8000 --reload
   ```
4. Verify by opening `http://localhost:8000/health` in your browser.

---

### Step 2: Get the Android App on Your Phone

#### Option A: Automated Cloud Build (Recommended — No Android Studio Needed)
1. Push this repository to GitHub:
   ```bash
   git init
   git add .
   git commit -m "Dee mobile assistant setup"
   git remote add origin https://github.com/<your-username>/dee-assistant.git
   git branch -M main
   git push -u origin main
   ```
2. In your GitHub repository, click on the **Actions** tab.
3. You'll see the **Build Dee Assistant Android APK** workflow running.
4. Once completed (takes ~2 minutes), click on the run and download the **DeeAssistant-debug-apk** artifact.
5. Transfer the `.apk` file to your Android phone (or download it directly from GitHub on your phone) and tap to install!

#### Option B: Build with Android Studio
1. Open Android Studio.
2. Select **Open** and choose the `DeeAssistant/android` folder.
3. Wait for Gradle sync to complete.
4. Connect your Android phone with a USB cable (USB Debugging enabled in Developer Options) and click **Run** (or select **Build → Build Bundle(s) / APK(s) → Build APK(s)**).

---

### Step 3: First-Time Phone Permissions Setup

Android requires granting two permissions for the voice assistant:

1. **Accessibility Service**:
   - Open the **Dee** app.
   - Tap **"1. Accessibility Service Settings"**.
   - Find **Dee** in the Accessibility list and toggle it **ON**.
   - Confirm the system dialog (this allows Dee to open apps and tap buttons on your command).
2. **Microphone**:
   - Return to the Dee app.
   - Grant microphone permission when prompted.
3. **Configure Backend URL**:
   - Tap **"⚙️ Server Settings"** in the app.
   - Enter your backend URL (e.g., `https://your-backend.onrender.com` or `http://192.168.x.x:8000`) and your Device Token.
   - Tap **Save**.

---

### Step 4: Use Your Assistant!

1. Tap **"🎤 Tap to Speak"**.
2. Try speaking:
   - *"Open Spotify"* or *"Open WhatsApp"*
   - *"Turn on Wi-Fi"* or *"Open volume settings"*
   - *"What is the distance to the moon?"*
   - *"Tell me a quick joke"*
3. Dee will execute the action or answer aloud via Text-to-Speech!
