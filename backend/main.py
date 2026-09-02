"""
Dee Assistant Backend
-------------------------
Single endpoint that takes a spoken command from any registered phone,
decides whether it's a DEVICE ACTION (open app, toggle setting, tap
something) or a QUESTION (needs a spoken-back answer), and returns a
structured JSON response the Android client can execute directly.

Run with:
    uvicorn main:app --host 0.0.0.0 --port 8000
"""

import json
import os
import re
from typing import Optional, Literal

import anthropic
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

app = FastAPI(title="Dee Assistant Backend")

# ---------------------------------------------------------------------------
# Auth: every device gets a shared secret token (set on install / pairing).
# Do NOT run this backend on an open network without this check -- it is a
# remote-control endpoint for a phone.
# ---------------------------------------------------------------------------
DEVICE_TOKENS = {
    # device_id : token   (replace with a real DB / .env in production)
    "my-phone": os.environ.get("MY_PHONE_TOKEN", "change-me-1"),
    "friend-phone": os.environ.get("FRIEND_PHONE_TOKEN", "change-me-2"),
}

client = anthropic.Anthropic()  # reads ANTHROPIC_API_KEY from env

# In-memory cache of each device's installed apps, so the LLM can map
# "open spotify" -> the correct package name instead of guessing.
# The Android app pushes this list once on startup / pairing (see below).
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
        raise HTTPException(status_code=403, detail="Unknown device_id")
    if authorization != f"Bearer {expected}":
        raise HTTPException(status_code=401, detail="Bad or missing token")


# ---------------------------------------------------------------------------
# Registration: Android app calls this once on launch / after install so the
# backend knows which apps it can open by name on that specific phone.
# ---------------------------------------------------------------------------
@app.post("/register_apps")
async def register_apps(payload: InstalledAppsPayload, authorization: str = Header(None)):
    check_auth(payload.device_id, authorization)
    INSTALLED_APPS[payload.device_id] = payload.apps
    return {"status": "ok", "app_count": len(payload.apps)}


# ---------------------------------------------------------------------------
# Core command endpoint
# ---------------------------------------------------------------------------
SYSTEM_PROMPT_TEMPLATE = """You are the routing brain for a voice-controlled Android assistant.

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


def call_llm(user_text: str, device_id: str) -> dict:
    resp = client.messages.create(
        model="claude-sonnet-4-6",
        max_tokens=400,
        system=build_system_prompt(device_id),
        messages=[{"role": "user", "content": user_text}],
    )
    raw = resp.content[0].text.strip()
    raw = re.sub(r"^```json|```$", "", raw).strip()
    return json.loads(raw)


@app.post("/command", response_model=AssistantResponse)
async def process_command(cmd: VoiceCommand, authorization: str = Header(None)):
    check_auth(cmd.device_id, authorization)
    parsed = call_llm(cmd.text, cmd.device_id)
    return AssistantResponse(**parsed)


@app.get("/health")
async def health():
    return {"status": "ok"}
