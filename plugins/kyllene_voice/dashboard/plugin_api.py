"""Subscription-only GPT-Live. Exchange adapted from MIT Conduit cdbf0acd; see LICENSE."""
import json
import os
import re
import stat
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool

router = APIRouter()
URL = 'https://chatgpt.com/backend-api/codex/realtime/calls?intent=quicksilver&architecture=avas'
BASE = 'https://chatgpt.com/backend-api/codex'
MAX_BODY = 160 * 1024
MAX_ANSWER = 64 * 1024
# Codex built-in v3 voices and Hermes' default. Provider acceptance of marin is unverified.
VOICES = frozenset(('marin', 'juniper', 'maple', 'spruce', 'ember', 'vale', 'breeze',
                    'arbor', 'sol', 'cove', 'aube', 'bubbie', 'haetsal',
                    'hanul', 'himari', 'leher', 'miko', 'neer', 'porto',
                    'rindo', 'rio', 'rivage', 'selva', 'sonna', 'tinta', 'viola'))
_slots = threading.BoundedSemaphore(2)


class UnsupportedProfileVoice(ValueError):
    pass


class ProviderCredentialsError(ValueError):
    pass


def _credentials(refresh):
    from hermes_cli.auth_codex import resolve_codex_runtime_credentials
    from hermes_cli.auth_constants import _decode_jwt_claims
    # Status must not refresh/adopt; session may refresh as explicitly authorized.
    result = resolve_codex_runtime_credentials(refresh_if_expiring=refresh, read_only=not refresh)
    if not isinstance(result, dict) or result.get('base_url') != BASE:
        raise ValueError('nonstandard Codex credential destination')
    token = result.get('api_key')
    if not isinstance(token, str) or not token or any(c in token for c in '\r\n'):
        raise ValueError('invalid credential')
    claims = _decode_jwt_claims(token)
    auth = claims.get('https://api.openai.com/auth') if isinstance(claims, dict) else None
    account = auth.get('chatgpt_account_id') if isinstance(auth, dict) else None
    if not isinstance(account, str) or not account.strip() or any(c in account for c in '\r\n'):
        raise ValueError('invalid account')
    return token, account.strip()


def credentials(refresh):
    try:
        return _credentials(refresh)
    except Exception as exc:
        raise ProviderCredentialsError() from exc


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def validate_history(history):
    if not isinstance(history, list) or len(history) > 24:
        raise ValueError('invalid history')
    size = 0
    for item in history:
        if not isinstance(item, dict) or set(item) != {'type', 'role', 'content'}:
            raise ValueError('invalid message')
        role = item['role']
        if item['type'] != 'message' or role not in ('user', 'assistant'):
            raise ValueError('invalid role')
        content = item['content']
        if not isinstance(content, list) or len(content) != 1:
            raise ValueError('invalid content')
        part = content[0]
        if (not isinstance(part, dict) or set(part) != {'type', 'text'}
                or part['type'] != ('input_text' if role == 'user' else 'output_text')
                or not isinstance(part['text'], str) or not part['text'].strip()
                or len(part['text']) > 1200):
            raise ValueError('invalid text')
        size += len(part['text'])
    if size > 6000:
        raise ValueError('history too large')
    return history


def stock_settings():
    # Exactly the same profile-scoped instructions and voice as stock GPT-Live.
    from tools.voice_live import build_session_config
    config = build_session_config()
    voice = config['audio']['output']['voice']
    instructions = config['instructions']
    if not isinstance(voice, str) or voice not in VOICES:
        raise UnsupportedProfileVoice()
    if not isinstance(instructions, str) or len(instructions) > 65536:
        raise ValueError('Invalid profile voice instructions.')
    return instructions, voice


def exchange(token, account, sdp, persona, voice, history):
    # This provider's acceptance of stock delegation and history has not yet been verified live.
    payload = {'sdp': sdp, 'session': {'model': 'gpt-live-1-codex',
               'instructions': persona, 'audio': {'output': {'voice': voice}},
               'delegation': {'type': 'client'}, 'initial_items': history}}
    req = urllib.request.Request(URL, data=json.dumps(payload).encode(), method='POST',
        headers={'Authorization': 'Bearer ' + token, 'ChatGPT-Account-Id': account,
                 'OpenAI-Alpha': 'quicksilver=v2', 'Content-Type': 'application/json'})
    opener = urllib.request.build_opener(NoRedirect())
    with opener.open(req, timeout=30) as reply:
        data = reply.read(MAX_ANSWER + 1)
        location = reply.headers.get('Location', '')
        if not 200 <= reply.status < 300 or len(data) > MAX_ANSWER:
            raise ValueError('invalid upstream response')
    answer = data.decode('utf-8')
    call_id = urllib.parse.urlparse(location).path.rstrip('/').rsplit('/', 1)[-1]
    if not answer.startswith('v=0') or not re.fullmatch(r'rtc_[A-Za-z0-9_-]+|[0-9a-fA-F-]{36}', call_id):
        raise ValueError('invalid upstream answer')
    return answer


def scoped(profile, action):
    from hermes_cli.web_server_profiles import _config_profile_scope
    with _config_profile_scope(profile):
        return action()


def require(request):
    from hermes_cli.web_server import _require_token
    _require_token(request)
    # Hermes strips profile when forwarding to the chosen secondary profile host.
    profile = request.query_params.get('profile')
    if profile is None and getattr(request.state, 'hosted_http_authenticated', None) is True:
        return None
    if not profile or not profile.strip() or profile.strip().lower() == 'current':
        raise HTTPException(400, 'An explicit profile is required')
    return profile.strip()


def response(data, status=200):
    return JSONResponse(data, status_code=status, headers={'Cache-Control': 'no-store'})


@router.get('/gpt-live/status')
async def status(request: Request):
    profile = require(request)
    def check():
        stock_settings()
        credentials(False)
    try:
        await run_in_threadpool(scoped, profile, check)
    except UnsupportedProfileVoice:
        return response({'ok': False, 'detail': 'Profile voice is unsupported. Set voice.gpt_live.voice to a supported subscription voice, such as ember.'}, 400)
    except ProviderCredentialsError:
        return response({'ok': False, 'detail': 'ChatGPT/Codex subscription credentials or account ID are unavailable in this profile. Sign in with Codex on the gateway; dashboard sign-in is separate.'}, 400)
    except Exception:
        return response({'ok': False, 'detail': 'Could not check profile voice setup; the plugin may be incompatible with this Hermes version.'}, 503)
    return response({'ok': True, 'source': 'plugin', 'contract': 1})


@router.post('/gpt-live/session')
async def session(request: Request):
    profile = require(request)
    chunks = bytearray()
    async for chunk in request.stream():
        chunks.extend(chunk)
        if len(chunks) > MAX_BODY:
            return response({'ok': False, 'detail': 'Request too large.'}, 413)
    try:
        body = json.loads(chunks)
        if not isinstance(body, dict) or set(body) != {'sdp', 'billing', 'history'}:
            raise ValueError('invalid fields')
        sdp = body['sdp']
        if (body['billing'] != 'subscription' or not isinstance(sdp, str)
                or not sdp.startswith('v=0\r\n') or len(sdp) > 65536
                or not all(marker in sdp for marker in ('\r\no=', '\r\ns=', '\r\nt=', '\r\nm='))):
            raise ValueError('invalid offer')
        history = validate_history(body['history'])
    except (ValueError, UnicodeError, TypeError):
        return response({'ok': False, 'detail': 'Invalid subscription request.'}, 400)
    if not _slots.acquire(blocking=False):
        return response({'ok': False, 'detail': 'Voice service busy.'}, 503)
    def work():
        try:
            def run():
                persona, voice = stock_settings()
                token, account = credentials(True)
                return exchange(token, account, sdp, persona, voice, history)
            return scoped(profile, run)
        finally:
            _slots.release()
    try:
        answer = await run_in_threadpool(work)
    except UnsupportedProfileVoice:
        return response({'ok': False, 'detail': 'The profile voice is not supported by the subscription provider. Set voice.gpt_live.voice in the selected profile to a supported subscription voice, such as ember. No API fallback was used.'}, 400)
    except ProviderCredentialsError:
        return response({'ok': False, 'detail': 'ChatGPT/Codex subscription credentials or account ID are unavailable. Sign in with Codex on the gateway; dashboard sign-in is separate. No API fallback was used.'}, 400)
    except urllib.error.HTTPError as exc:
        if exc.code in (401, 403):
            return response({'ok': False, 'detail': 'The subscription provider rejected the ChatGPT/Codex credentials or account access. Check the gateway Codex sign-in and subscription entitlement; dashboard sign-in is separate. No API fallback was used.'}, 502)
        return response({'ok': False, 'detail': 'Subscription provider request failed. No API fallback was used.'}, 502)
    except HTTPException:
        raise
    except Exception:
        return response({'ok': False, 'detail': 'Subscription session failed; check profile settings and credentials. No API fallback was used.'}, 502)
    return response({'ok': True, 'auth': 'subscription', 'source': 'plugin',
                     'transport': {'type': 'webrtc', 'sdp': answer}})
