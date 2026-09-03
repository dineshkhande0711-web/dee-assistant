"""
Dee Assistant Backend
---------------------
Single endpoint that takes a spoken command from any registered phone,
decides whether it's a DEVICE ACTION (open app, toggle setting, tap
something) or a QUESTION (needs a spoken-back answer), and returns a
structured JSON response the Android client can execute directly.

Supports Google Gemini, Anthropic Claude, and OpenAI.

Run with:
    uvicorn main:app --host 0.0.0.0 --port 8000 --reload
"""

import json
import logging
import os
import re
from typing import Optional, Literal

from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse
from pydantic import BaseModel

# Load environment variables from .env if present
load_dotenv()

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("dee-backend")

app = FastAPI(
    title="Dee Assistant Backend",
    description="Voice Assistant brain supporting mobile device actions and voice Q&A",
    version="1.1.0"
)

# Enable CORS for local testing / dashboard tools
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ---------------------------------------------------------------------------
# Auth: every device gets a shared secret token (set on install / pairing).
# ---------------------------------------------------------------------------
DEVICE_TOKENS = {
    "my-phone": os.environ.get("MY_PHONE_TOKEN", "change-me-1"),
    "friend-phone": os.environ.get("FRIEND_PHONE_TOKEN", "change-me-2"),
}

# In-memory cache of each device's installed apps
INSTALLED_APPS: dict[str, dict[str, str]] = {}


# ---------------------------------------------------------------------------
# Schemas
# ---------------------------------------------------------------------------
class VoiceCommand(BaseModel):
    text: str
    device_id: str


class InstalledAppsPayload(BaseModel):
    device_id: str
    # {"Spotify": "com.spotify.music", "WhatsApp": "com.whatsapp", ...}
    apps: dict[str, str]


class AssistantResponse(BaseModel):
    kind: Literal["action", "answer"]
    # populated when kind == "action"
    action: Optional[str] = None
    enable: Optional[bool] = None
    level: Optional[int] = None
    package_name: Optional[str] = None
    x: Optional[float] = None
    y: Optional[float] = None
    text: Optional[str] = None
    panel: Optional[str] = None
    # populated when kind == "answer" -- spoken back via TTS on the phone
    speech: Optional[str] = None


# ---------------------------------------------------------------------------
# Auth dependency
# ---------------------------------------------------------------------------
def check_auth(device_id: str, authorization: Optional[str]):
    expected = DEVICE_TOKENS.get(device_id)
    if not expected:
        raise HTTPException(status_code=403, detail=f"Unknown device_id: {device_id}")
    if authorization != f"Bearer {expected}":
        raise HTTPException(status_code=401, detail="Bad or missing authorization token")


# ---------------------------------------------------------------------------
# System Prompt
# ---------------------------------------------------------------------------
SYSTEM_PROMPT_TEMPLATE = """You are Dee, the routing brain for a voice-controlled Android assistant.
When answering questions (not actions), speak in first person as Dee -- warm,
brief, and direct, like a helpful assistant, not a search engine.

Given the user's spoken text, decide if it is:
1. A DEVICE ACTION -- something to DO on the phone (open an app, change volume,
   toggle wifi, tap something, open a settings panel).
2. A QUESTION -- something to ANSWER out loud (facts, general knowledge, chit-chat).

The apps installed on this specific phone are (name -> package name):
{installed_apps}

Respond with ONLY one JSON object, no markdown fences, no extra text.

If it's an action, use this shape:
{{
  "kind": "action",
  "action": "open_app" | "toggle_wifi" | "set_volume" | "tap_coordinates" | "click_text" | "open_settings_panel",
  "package_name": "<from the installed app list above, if action is open_app>",
  "enable": true/false,        // for toggle_wifi
  "level": 0-10,                // for set_volume
  "text": "<visible button/label text>",  // for click_text
  "panel": "internet"|"nfc"|"volume"      // for open_settings_panel
}}
Only include the fields relevant to the chosen action.

If it's a question, use this shape:
{{
  "kind": "answer",
  "speech": "<a short, natural spoken-style answer, 1-3 sentences>"
}}

If the requested app is not in the installed app list, still return kind
"action"/"open_app" with your best-guess common package name for that app.
"""


def build_system_prompt(device_id: str) -> str:
    apps = INSTALLED_APPS.get(device_id, {})
    apps_str = json.dumps(apps, indent=2) if apps else "(no app list registered yet)"
    return SYSTEM_PROMPT_TEMPLATE.format(installed_apps=apps_str)


def parse_json_response(raw: str) -> dict:
    raw = raw.strip()
    # Strip markdown block quotes if present
    raw = re.sub(r"^```(?:json)?\s*", "", raw, flags=re.IGNORECASE)
    raw = re.sub(r"\s*```$", "", raw)
    match = re.search(r"\{.*\}", raw, re.DOTALL)
    if match:
        raw = match.group(0)
    return json.loads(raw)


# ---------------------------------------------------------------------------
# LLM Providers (Gemini, Anthropic, OpenAI)
# ---------------------------------------------------------------------------
def get_ai_provider() -> str:
    configured = os.environ.get("AI_PROVIDER", "").strip().lower()
    if configured:
        return configured
    if os.environ.get("GEMINI_API_KEY"):
        return "gemini"
    if os.environ.get("ANTHROPIC_API_KEY"):
        return "anthropic"
    if os.environ.get("OPENAI_API_KEY"):
        return "openai"
    return "gemini"  # default fallback


def call_llm(user_text: str, device_id: str) -> dict:
    provider = get_ai_provider()
    system_prompt = build_system_prompt(device_id)

    if provider == "gemini":
        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            raise HTTPException(
                status_code=500,
                detail="GEMINI_API_KEY is not set in environment or .env file"
            )
        try:
            try:
                from google import genai
                from google.genai import types
                client = genai.Client(api_key=api_key)
                model_name = os.environ.get("GEMINI_MODEL", "gemini-2.0-flash")
                response = client.models.generate_content(
                    model=model_name,
                    contents=user_text,
                    config=types.GenerateContentConfig(
                        system_instruction=system_prompt,
                        temperature=0.2,
                        response_mime_type="application/json",
                    ),
                )
                return parse_json_response(response.text)
            except (ImportError, AttributeError):
                import google.generativeai as genai
                genai.configure(api_key=api_key)
                model_name = os.environ.get("GEMINI_MODEL", "gemini-3.6-flash")
                model = genai.GenerativeModel(
                    model_name=model_name,
                    system_instruction=system_prompt,
                    generation_config={"response_mime_type": "application/json", "temperature": 0.2}
                )
                response = model.generate_content(user_text)
                return parse_json_response(response.text)
        except Exception as e:
            logger.error(f"Gemini error: {e}")
            raise HTTPException(status_code=500, detail=f"Gemini error: {str(e)}")

    elif provider == "anthropic":
        api_key = os.environ.get("ANTHROPIC_API_KEY")
        if not api_key:
            raise HTTPException(
                status_code=500,
                detail="ANTHROPIC_API_KEY is not set in environment or .env file"
            )
        try:
            import anthropic
            client = anthropic.Anthropic(api_key=api_key)
            model_name = os.environ.get("ANTHROPIC_MODEL", "claude-3-5-sonnet-20241022")
            resp = client.messages.create(
                model=model_name,
                max_tokens=400,
                system=system_prompt,
                messages=[{"role": "user", "content": user_text}],
            )
            raw = resp.content[0].text
            return parse_json_response(raw)
        except Exception as e:
            logger.error(f"Anthropic error: {e}")
            raise HTTPException(status_code=500, detail=f"Anthropic error: {str(e)}")

    elif provider == "openai":
        api_key = os.environ.get("OPENAI_API_KEY")
        if not api_key:
            raise HTTPException(
                status_code=500,
                detail="OPENAI_API_KEY is not set in environment or .env file"
            )
        try:
            import openai
            client = openai.OpenAI(api_key=api_key)
            model_name = os.environ.get("OPENAI_MODEL", "gpt-4o-mini")
            resp = client.chat.completions.create(
                model=model_name,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_text},
                ],
                response_format={"type": "json_object"},
            )
            raw = resp.choices[0].message.content or "{}"
            return parse_json_response(raw)
        except Exception as e:
            logger.error(f"OpenAI error: {e}")
            raise HTTPException(status_code=500, detail=f"OpenAI error: {str(e)}")

    else:
        raise HTTPException(
            status_code=500,
            detail=f"Unsupported AI_PROVIDER: {provider}. Choose gemini, anthropic, or openai."
        )


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------
@app.get("/", response_class=HTMLResponse)
async def root():
    return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Dee — Mobile AI Voice Assistant</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
        body { background: #0f172a; color: #f8fafc; display: flex; flex-direction: column; min-height: 100vh; }
        header { padding: 1rem 1.5rem; background: #1e293b; border-bottom: 1px solid #334155; display: flex; align-items: center; justify-content: space-between; }
        .logo { font-size: 1.3rem; font-weight: 700; background: linear-gradient(135deg, #818cf8, #c084fc); -webkit-background-clip: text; -webkit-text-fill-color: transparent; }
        .badge { font-size: 0.75rem; background: #065f46; color: #34d399; padding: 0.25rem 0.65rem; border-radius: 9999px; font-weight: 600; }
        .chat-container { flex: 1; overflow-y: auto; padding: 1.5rem; display: flex; flex-direction: column; gap: 1rem; max-width: 600px; width: 100%; margin: 0 auto; }
        .msg { max-width: 85%; padding: 0.85rem 1.2rem; border-radius: 1.2rem; font-size: 0.95rem; line-height: 1.45; word-wrap: break-word; }
        .user-msg { align-self: flex-end; background: #6366f1; color: white; border-bottom-right-radius: 0.2rem; }
        .bot-msg { align-self: flex-start; background: #1e293b; border: 1px solid #334155; border-bottom-left-radius: 0.2rem; }
        .action-tag { display: inline-block; background: #0284c7; color: white; padding: 0.2rem 0.6rem; border-radius: 4px; font-size: 0.75rem; margin-bottom: 0.4rem; font-weight: bold; }
        .controls { background: #1e293b; border-top: 1px solid #334155; padding: 1.2rem; display: flex; flex-direction: column; align-items: center; gap: 0.85rem; }
        .mic-btn { width: 72px; height: 72px; border-radius: 50%; border: none; background: linear-gradient(135deg, #6366f1, #a855f7); color: white; font-size: 2rem; cursor: pointer; box-shadow: 0 4px 18px rgba(99, 102, 241, 0.45); display: flex; align-items: center; justify-content: center; transition: all 0.2s ease; }
        .mic-btn:hover { transform: scale(1.05); }
        .mic-btn.listening { animation: pulse 1.5s infinite; background: #ef4444; }
        @keyframes pulse { 0% { box-shadow: 0 0 0 0 rgba(239, 68, 68, 0.7); } 70% { box-shadow: 0 0 0 22px rgba(239, 68, 68, 0); } 100% { box-shadow: 0 0 0 0 rgba(239, 68, 68, 0); } }
        .status-text { font-size: 0.85rem; color: #94a3b8; }
        .input-row { display: flex; width: 100%; max-width: 600px; gap: 0.5rem; }
        .text-input { flex: 1; padding: 0.75rem 1rem; border-radius: 0.75rem; border: 1px solid #334155; background: #0f172a; color: white; font-size: 0.95rem; outline: none; }
        .text-input:focus { border-color: #6366f1; }
        .send-btn { padding: 0.75rem 1.25rem; border-radius: 0.75rem; border: none; background: #6366f1; color: white; font-weight: 600; cursor: pointer; }
        .download-banner { background: #1e1b4b; border: 1px solid #4338ca; padding: 0.75rem 1rem; border-radius: 0.75rem; max-width: 600px; width: 100%; margin: 0.5rem auto; font-size: 0.85rem; display: flex; justify-content: space-between; align-items: center; }
        .download-banner a { color: #818cf8; font-weight: bold; text-decoration: none; }
    </style>
</head>
<body>
    <header>
        <div class="logo">Dee AI Assistant</div>
        <div class="badge">Online</div>
    </header>

    <div class="chat-container" id="chat">
        <div class="download-banner">
            <span>📱 Need the Android App?</span>
            <a href="/download-apk">Download DeeAssistant.apk</a>
        </div>
        <div class="msg bot-msg">
            Hello! I am Dee, your mobile AI voice assistant. Tap the microphone below and speak, or type your question or command!
        </div>
    </div>

    <div class="controls">
        <button class="mic-btn" id="micBtn" title="Tap to speak">🎤</button>
        <div class="status-text" id="statusText">Tap microphone to speak</div>
        <div class="input-row">
            <input type="text" id="textInput" class="text-input" placeholder="Type a message or command..." />
            <button class="send-btn" id="sendBtn">Send</button>
        </div>
    </div>

    <script>
        const chat = document.getElementById('chat');
        const micBtn = document.getElementById('micBtn');
        const statusText = document.getElementById('statusText');
        const textInput = document.getElementById('textInput');
        const sendBtn = document.getElementById('sendBtn');

        let isListening = false;
        let recognition = null;

        const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
        if (SpeechRecognition) {
            recognition = new SpeechRecognition();
            recognition.continuous = false;
            recognition.interimResults = false;
            recognition.lang = 'en-US';

            recognition.onstart = () => {
                isListening = true;
                micBtn.classList.add('listening');
                statusText.innerText = 'Listening... Speak now!';
            };

            recognition.onresult = (event) => {
                const text = event.results[0][0].transcript;
                statusText.innerText = 'Sending command...';
                handleUserCommand(text);
            };

            recognition.onerror = (event) => {
                statusText.innerText = 'Mic error: ' + event.error;
                micBtn.classList.remove('listening');
                isListening = false;
            };

            recognition.onend = () => {
                isListening = false;
                micBtn.classList.remove('listening');
            };
        } else {
            statusText.innerText = 'Voice recognition not supported in this browser. Use text below.';
        }

        micBtn.addEventListener('click', () => {
            if (!recognition) {
                alert('Speech recognition is not available in your browser. Please use Chrome/Edge or type below.');
                return;
            }
            if (isListening) {
                recognition.stop();
            } else {
                recognition.start();
            }
        });

        sendBtn.addEventListener('click', () => {
            const text = textInput.value.trim();
            if (text) {
                textInput.value = '';
                handleUserCommand(text);
            }
        });

        textInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') sendBtn.click();
        });

        function addMessage(text, isUser, actionTag = null) {
            const div = document.createElement('div');
            div.className = 'msg ' + (isUser ? 'user-msg' : 'bot-msg');
            if (actionTag) {
                div.innerHTML = '<span class="action-tag">' + actionTag + '</span><br>' + text;
            } else {
                div.innerText = text;
            }
            chat.appendChild(div);
            chat.scrollTop = chat.scrollHeight;
        }

        function speak(text) {
            if ('speechSynthesis' in window) {
                window.speechSynthesis.cancel();
                const utter = new SpeechSynthesisUtterance(text);
                window.speechSynthesis.speak(utter);
            }
        }

        async function handleUserCommand(text) {
            addMessage(text, true);
            statusText.innerText = 'Dee is thinking...';

            try {
                const res = await fetch('/command', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'Authorization': 'Bearer change-me-1'
                    },
                    body: JSON.stringify({ text: text, device_id: 'my-phone' })
                });

                const data = await res.json();
                statusText.innerText = 'Ready';

                if (data.kind === 'action') {
                    const desc = 'Action: ' + data.action + (data.package_name ? ' (' + data.package_name + ')' : '');
                    addMessage(desc, false, 'Action Triggered');
                    speak('Executing ' + data.action);
                } else if (data.kind === 'answer') {
                    addMessage(data.speech, false);
                    speak(data.speech);
                } else {
                    addMessage('Response: ' + JSON.stringify(data), false);
                }
            } catch (err) {
                statusText.innerText = 'Error: ' + err.message;
                addMessage('Error connecting to backend: ' + err.message, false);
            }
        }
    </script>
</body>
</html>
"""


@app.get("/download-apk")
async def download_apk():
    from fastapi.responses import FileResponse
    apk_path = "c:/Users/DELL/Downloads/DeeAssistant/DeeAssistant.apk"
    if os.path.exists(apk_path):
        return FileResponse(apk_path, media_type="application/vnd.android.package-archive", filename="DeeAssistant.apk")
    return {"error": "APK not found"}


@app.get("/api")
async def api_info():
    return {
        "service": "Dee Assistant Backend",
        "status": "online",
        "provider": get_ai_provider(),
        "registered_devices": list(INSTALLED_APPS.keys()),
        "endpoints": {
            "web_ui": "/",
            "download_apk": "/download-apk",
            "health": "/health",
            "command": "POST /command",
            "register_apps": "POST /register_apps",
            "docs": "/docs"
        }
    }


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "provider": get_ai_provider(),
    }


@app.post("/register_apps")
async def register_apps(payload: InstalledAppsPayload, authorization: str = Header(None)):
    check_auth(payload.device_id, authorization)
    INSTALLED_APPS[payload.device_id] = payload.apps
    logger.info(f"Registered {len(payload.apps)} apps for {payload.device_id}")
    return {"status": "ok", "app_count": len(payload.apps)}


@app.post("/command", response_model=AssistantResponse)
async def process_command(cmd: VoiceCommand, authorization: str = Header(None)):
    check_auth(cmd.device_id, authorization)
    logger.info(f"Command from {cmd.device_id}: {cmd.text}")
    parsed = call_llm(cmd.text, cmd.device_id)
    return AssistantResponse(**parsed)
