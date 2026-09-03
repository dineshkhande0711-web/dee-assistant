"""
Unit and functional tests for Dee Assistant Backend
"""

import json
from fastapi.testclient import TestClient
from main import app, parse_json_response, build_system_prompt, INSTALLED_APPS

client = TestClient(app)


def test_health():
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert "provider" in data
    print("[OK] Health check endpoint passed")


def test_root():
    response = client.get("/")
    assert response.status_code == 200
    data = response.json()
    assert data["service"] == "Dee Assistant Backend"
    print("[OK] Root endpoint passed")


def test_json_parser():
    # Test raw json
    raw1 = '{"kind": "action", "action": "open_app", "package_name": "com.spotify.music"}'
    parsed1 = parse_json_response(raw1)
    assert parsed1["kind"] == "action"
    assert parsed1["package_name"] == "com.spotify.music"

    # Test markdown fenced json
    raw2 = '```json\n{"kind": "answer", "speech": "The weather is sunny."}\n```'
    parsed2 = parse_json_response(raw2)
    assert parsed2["kind"] == "answer"
    assert parsed2["speech"] == "The weather is sunny."

    # Test json with leading/trailing text
    raw3 = 'Sure! Here is the response:\n{"kind": "action", "action": "toggle_wifi", "enable": true}\nHope this helps!'
    parsed3 = parse_json_response(raw3)
    assert parsed3["kind"] == "action"
    assert parsed3["enable"] is True
    print("[OK] JSON parser passed all edge cases")


def test_auth():
    # Bad token
    response = client.post(
        "/command",
        json={"text": "hello", "device_id": "my-phone"},
        headers={"Authorization": "Bearer wrong-token"}
    )
    assert response.status_code == 401

    # Unknown device
    response = client.post(
        "/command",
        json={"text": "hello", "device_id": "random-phone"},
        headers={"Authorization": "Bearer change-me-1"}
    )
    assert response.status_code == 403
    print("[OK] Auth check passed")


def test_register_apps():
    response = client.post(
        "/register_apps",
        json={
            "device_id": "my-phone",
            "apps": {
                "Spotify": "com.spotify.music",
                "WhatsApp": "com.whatsapp"
            }
        },
        headers={"Authorization": "Bearer change-me-1"}
    )
    assert response.status_code == 200
    assert response.json()["status"] == "ok"
    assert response.json()["app_count"] == 2
    assert "Spotify" in INSTALLED_APPS["my-phone"]

    # Verify prompt includes apps
    prompt = build_system_prompt("my-phone")
    assert "com.spotify.music" in prompt
    print("[OK] App registration passed")


if __name__ == "__main__":
    test_health()
    test_root()
    test_json_parser()
    test_auth()
    test_register_apps()
    print("\nALL BACKEND VERIFICATION TESTS PASSED SUCCESSFULLY!")
