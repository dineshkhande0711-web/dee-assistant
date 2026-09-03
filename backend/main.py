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
                model_name = os.environ.get("GEMINI_MODEL", "gemini-1.5-flash")
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
@app.get("/")
async def root():
    return {
        "service": "Dee Assistant Backend",
        "status": "online",
        "provider": get_ai_provider(),
        "registered_devices": list(INSTALLED_APPS.keys()),
        "endpoints": {
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
