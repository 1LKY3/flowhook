"""
Flowhook — Shizuku-backed remote phone control plane.

- Phones dial out via WebSocket at /agent, auth via JWT bearer token.
- Callers (Claude Code / CLI) hit REST endpoints to execute commands on enrolled phones.
- Server brokers command → phone → Shizuku → response.
"""
import asyncio
import hashlib
import json
import os
import re
import secrets
import smtplib
import sqlite3
import time
import uuid
from contextlib import asynccontextmanager
from email.mime.text import MIMEText
from pathlib import Path

import bcrypt
import jwt
from fastapi import (
    Depends,
    FastAPI,
    File,
    Form,
    HTTPException,
    Request,
    UploadFile,
    WebSocket,
    WebSocketDisconnect,
    status,
)
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel

BASE = Path(__file__).parent
DB_PATH = BASE / "db" / "flowhook.db"
JWT_SECRET = os.environ.get("FLOWHOOK_JWT_SECRET", "change-me-in-production-FLOWHOOK")
JWT_ALG = "HS256"
JWT_TTL = 60 * 60 * 24 * 30  # 30 days (phone tokens long-lived)
PORT = int(os.environ.get("FLOWHOOK_PORT", "8700"))

DB_PATH.parent.mkdir(parents=True, exist_ok=True)

# ---------------------------------------------------------------------------
# DB
# ---------------------------------------------------------------------------
def db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    with db() as c:
        c.executescript("""
        CREATE TABLE IF NOT EXISTS waitlist (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            email TEXT UNIQUE NOT NULL,
            source TEXT,
            ip TEXT,
            ts REAL NOT NULL
        );
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            created_at REAL NOT NULL
        );
        CREATE TABLE IF NOT EXISTS devices (
            id TEXT PRIMARY KEY,
            user_id INTEGER NOT NULL,
            name TEXT NOT NULL,
            enroll_token TEXT UNIQUE NOT NULL,
            last_seen REAL,
            created_at REAL NOT NULL,
            FOREIGN KEY (user_id) REFERENCES users(id)
        );
        CREATE TABLE IF NOT EXISTS audit (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER,
            device_id TEXT,
            cmd_type TEXT NOT NULL,
            payload TEXT,
            status TEXT,
            result TEXT,
            ts REAL NOT NULL
        );
        """)

init_db()

# ---------------------------------------------------------------------------
# Auth helpers
# ---------------------------------------------------------------------------
def hash_pw(pw: str) -> str:
    return bcrypt.hashpw(pw.encode(), bcrypt.gensalt()).decode()

def check_pw(pw: str, hashed: str) -> bool:
    try:
        return bcrypt.checkpw(pw.encode(), hashed.encode())
    except Exception:
        return False

def make_jwt(claims: dict) -> str:
    payload = {**claims, "exp": int(time.time()) + JWT_TTL, "iat": int(time.time())}
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALG)

def decode_jwt(token: str) -> dict:
    return jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALG])

bearer = HTTPBearer(auto_error=False)

def require_user(creds: HTTPAuthorizationCredentials | None = Depends(bearer)) -> dict:
    if not creds:
        raise HTTPException(401, "missing bearer token")
    try:
        claims = decode_jwt(creds.credentials)
    except Exception:
        raise HTTPException(401, "invalid token")
    if claims.get("typ") != "user":
        raise HTTPException(401, "not a user token")
    return claims

# ---------------------------------------------------------------------------
# Agent registry (live WebSocket connections)
# ---------------------------------------------------------------------------
class Agent:
    __slots__ = ("device_id", "user_id", "ws", "pending", "lock")
    def __init__(self, device_id: str, user_id: int, ws: WebSocket):
        self.device_id = device_id
        self.user_id = user_id
        self.ws = ws
        self.pending: dict[str, asyncio.Future] = {}
        self.lock = asyncio.Lock()

    async def send_command(self, cmd: dict, timeout: float = 60.0) -> dict:
        req_id = str(uuid.uuid4())
        cmd = {"req_id": req_id, **cmd}
        fut: asyncio.Future = asyncio.get_event_loop().create_future()
        self.pending[req_id] = fut
        try:
            async with self.lock:
                await self.ws.send_text(json.dumps(cmd))
            return await asyncio.wait_for(fut, timeout=timeout)
        finally:
            self.pending.pop(req_id, None)

AGENTS: dict[str, Agent] = {}  # device_id -> Agent
USER_AGENTS: dict[int, set[str]] = {}  # user_id -> set of device_ids

def register_agent(agent: Agent):
    AGENTS[agent.device_id] = agent
    USER_AGENTS.setdefault(agent.user_id, set()).add(agent.device_id)

def unregister_agent(device_id: str):
    agent = AGENTS.pop(device_id, None)
    if agent:
        USER_AGENTS.get(agent.user_id, set()).discard(device_id)

def get_user_device(user_id: int, device_id: str | None) -> Agent:
    devices = USER_AGENTS.get(user_id, set())
    if not devices:
        raise HTTPException(503, "no device online for this user")
    if device_id:
        if device_id not in devices:
            raise HTTPException(404, f"device {device_id} not online")
        return AGENTS[device_id]
    # default: pick first
    return AGENTS[next(iter(devices))]

# ---------------------------------------------------------------------------
# Audit
# ---------------------------------------------------------------------------
def audit_log(user_id: int | None, device_id: str | None, cmd_type: str, payload: dict | None, status_: str, result: str | None = None):
    with db() as c:
        c.execute(
            "INSERT INTO audit (user_id, device_id, cmd_type, payload, status, result, ts) VALUES (?, ?, ?, ?, ?, ?, ?)",
            (user_id, device_id, cmd_type, json.dumps(payload) if payload else None, status_, result[:4096] if result else None, time.time()),
        )

# ---------------------------------------------------------------------------
# App
# ---------------------------------------------------------------------------
@asynccontextmanager
async def lifespan(app: FastAPI):
    print(f"[flowhook] starting on port {PORT}")
    yield
    print("[flowhook] shutting down")

app = FastAPI(title="Flowhook", lifespan=lifespan)

# ---- Auth ----------------------------------------------------------------
class AuthReq(BaseModel):
    username: str
    password: str

@app.post("/auth/register")
def register(req: AuthReq):
    with db() as c:
        try:
            cur = c.execute(
                "INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)",
                (req.username, hash_pw(req.password), time.time()),
            )
            uid = cur.lastrowid
        except sqlite3.IntegrityError:
            raise HTTPException(409, "username taken")
    return {"user_id": uid, "token": make_jwt({"uid": uid, "typ": "user"})}

@app.post("/auth/login")
def login(req: AuthReq):
    with db() as c:
        row = c.execute("SELECT id, password_hash FROM users WHERE username=?", (req.username,)).fetchone()
    if not row or not check_pw(req.password, row["password_hash"]):
        raise HTTPException(401, "bad credentials")
    return {"user_id": row["id"], "token": make_jwt({"uid": row["id"], "typ": "user"})}

# ---- Device enrollment ---------------------------------------------------
class DeviceReq(BaseModel):
    name: str

@app.post("/devices/enroll")
def enroll_device(req: DeviceReq, claims: dict = Depends(require_user)):
    """Create an enrollment token the phone uses to open its WebSocket."""
    device_id = secrets.token_hex(8)
    enroll_token = secrets.token_urlsafe(24)
    with db() as c:
        c.execute(
            "INSERT INTO devices (id, user_id, name, enroll_token, created_at) VALUES (?, ?, ?, ?, ?)",
            (device_id, claims["uid"], req.name, enroll_token, time.time()),
        )
    # agent JWT includes device_id + user_id + typ=agent
    agent_jwt = make_jwt({"uid": claims["uid"], "did": device_id, "typ": "agent"})
    return {"device_id": device_id, "agent_token": agent_jwt, "enroll_token": enroll_token}

@app.get("/devices")
def list_devices(claims: dict = Depends(require_user)):
    with db() as c:
        rows = c.execute(
            "SELECT id, name, last_seen FROM devices WHERE user_id=?",
            (claims["uid"],),
        ).fetchall()
    out = []
    for r in rows:
        out.append({
            "device_id": r["id"],
            "name": r["name"],
            "last_seen": r["last_seen"],
            "online": r["id"] in AGENTS,
        })
    return {"devices": out}

# ---- Agent WebSocket -----------------------------------------------------
@app.websocket("/agent")
async def agent_ws(ws: WebSocket):
    await ws.accept()
    # First frame: auth
    try:
        auth_raw = await asyncio.wait_for(ws.receive_text(), timeout=10)
        auth = json.loads(auth_raw)
        token = auth.get("token")
        claims = decode_jwt(token)
        if claims.get("typ") != "agent":
            await ws.close(code=4001, reason="not an agent token")
            return
        device_id = claims["did"]
        user_id = claims["uid"]
    except Exception as e:
        await ws.close(code=4001, reason=f"auth failed: {e}")
        return

    agent = Agent(device_id, user_id, ws)
    register_agent(agent)
    with db() as c:
        c.execute("UPDATE devices SET last_seen=? WHERE id=?", (time.time(), device_id))
    await ws.send_text(json.dumps({"type": "hello", "device_id": device_id}))
    print(f"[flowhook] agent online: device={device_id} user={user_id}")

    try:
        while True:
            msg = await ws.receive_text()
            data = json.loads(msg)
            # expected: {"req_id": "...", "ok": true/false, "stdout": "...", "stderr": "...", "exit": 0}
            req_id = data.get("req_id")
            if req_id and req_id in agent.pending:
                fut = agent.pending[req_id]
                if not fut.done():
                    fut.set_result(data)
            else:
                # unsolicited message (heartbeat, event)
                if data.get("type") == "ping":
                    await ws.send_text(json.dumps({"type": "pong"}))
    except WebSocketDisconnect:
        pass
    except Exception as e:
        print(f"[flowhook] ws error: {e}")
    finally:
        unregister_agent(device_id)
        with db() as c:
            c.execute("UPDATE devices SET last_seen=? WHERE id=?", (time.time(), device_id))
        print(f"[flowhook] agent offline: device={device_id}")

# ---- REST command endpoints ---------------------------------------------
class ExecReq(BaseModel):
    cmd: str
    device_id: str | None = None
    timeout: float = 30.0

@app.post("/exec")
async def exec_cmd(req: ExecReq, claims: dict = Depends(require_user)):
    agent = get_user_device(claims["uid"], req.device_id)
    try:
        res = await agent.send_command({"type": "exec", "cmd": req.cmd}, timeout=req.timeout)
        audit_log(claims["uid"], agent.device_id, "exec", {"cmd": req.cmd}, "ok" if res.get("ok") else "fail", res.get("stdout", "") + res.get("stderr", ""))
        return res
    except asyncio.TimeoutError:
        audit_log(claims["uid"], agent.device_id, "exec", {"cmd": req.cmd}, "timeout")
        raise HTTPException(504, "command timed out")

class InstallReq(BaseModel):
    apk_url: str
    device_id: str | None = None
    timeout: float = 300.0

@app.post("/install")
async def install_apk(req: InstallReq, claims: dict = Depends(require_user)):
    agent = get_user_device(claims["uid"], req.device_id)
    try:
        res = await agent.send_command({"type": "install", "apk_url": req.apk_url}, timeout=req.timeout)
        audit_log(claims["uid"], agent.device_id, "install", {"apk_url": req.apk_url}, "ok" if res.get("ok") else "fail", res.get("stderr"))
        return res
    except asyncio.TimeoutError:
        raise HTTPException(504, "install timed out")

class UninstallReq(BaseModel):
    package: str
    device_id: str | None = None

@app.post("/uninstall")
async def uninstall(req: UninstallReq, claims: dict = Depends(require_user)):
    agent = get_user_device(claims["uid"], req.device_id)
    res = await agent.send_command({"type": "uninstall", "package": req.package}, timeout=30)
    audit_log(claims["uid"], agent.device_id, "uninstall", {"package": req.package}, "ok" if res.get("ok") else "fail")
    return res

class TapReq(BaseModel):
    x: int
    y: int
    device_id: str | None = None

@app.post("/tap")
async def tap(req: TapReq, claims: dict = Depends(require_user)):
    agent = get_user_device(claims["uid"], req.device_id)
    return await agent.send_command({"type": "exec", "cmd": f"input tap {req.x} {req.y}"}, timeout=10)

class TextReq(BaseModel):
    text: str
    device_id: str | None = None

@app.post("/text")
async def input_text(req: TextReq, claims: dict = Depends(require_user)):
    agent = get_user_device(claims["uid"], req.device_id)
    # shell-escape
    escaped = req.text.replace("'", "'\\''")
    return await agent.send_command({"type": "exec", "cmd": f"input text '{escaped}'"}, timeout=10)

class KeyReq(BaseModel):
    keycode: str
    device_id: str | None = None

@app.post("/key")
async def input_key(req: KeyReq, claims: dict = Depends(require_user)):
    agent = get_user_device(claims["uid"], req.device_id)
    return await agent.send_command({"type": "exec", "cmd": f"input keyevent {req.keycode}"}, timeout=10)

@app.get("/logcat")
async def logcat(tag: str | None = None, lines: int = 200, device_id: str | None = None, claims: dict = Depends(require_user)):
    agent = get_user_device(claims["uid"], device_id)
    cmd = f"logcat -d -t {lines}" + (f" -s {tag}" if tag else "")
    res = await agent.send_command({"type": "exec", "cmd": cmd}, timeout=15)
    return res

@app.get("/screencap")
async def screencap(device_id: str | None = None, claims: dict = Depends(require_user)):
    agent = get_user_device(claims["uid"], device_id)
    res = await agent.send_command({"type": "screencap"}, timeout=20)
    if not res.get("ok"):
        raise HTTPException(500, res.get("error", "screencap failed"))
    # res contains base64-encoded PNG
    return res

# ---- Waitlist --------------------------------------------------------
SMTP_HOST = os.environ.get("FLOWHOOK_SMTP_HOST", "100.83.112.88")
SMTP_PORT = int(os.environ.get("FLOWHOOK_SMTP_PORT", "25"))
WAITLIST_FROM = os.environ.get("FLOWHOOK_WAITLIST_FROM", "waitlist@dustforge.com")
WAITLIST_TO = os.environ.get("FLOWHOOK_WAITLIST_TO", "ky@dustforge.com")
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")

class WaitlistReq(BaseModel):
    email: str
    source: str | None = None

def send_waitlist_email(email: str, source: str | None, ip: str | None):
    try:
        body = f"New waitlist signup:\n\n  email:  {email}\n  source: {source or '(none)'}\n  ip:     {ip or '(unknown)'}\n  ts:     {time.strftime('%Y-%m-%d %H:%M:%S %Z')}\n"
        msg = MIMEText(body)
        msg["Subject"] = f"[flowhook] waitlist: {email}"
        msg["From"] = WAITLIST_FROM
        msg["To"] = WAITLIST_TO
        with smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=10) as s:
            s.ehlo()
            s.send_message(msg)
        print(f"[flowhook] waitlist email sent for {email}")
    except Exception as e:
        print(f"[flowhook] waitlist email FAILED for {email}: {e}")

@app.post("/waitlist")
async def waitlist(req: WaitlistReq, request: Request):
    email = req.email.strip().lower()
    if not EMAIL_RE.match(email) or len(email) > 200:
        raise HTTPException(400, "invalid email")
    ip = request.client.host if request.client else None
    with db() as c:
        try:
            c.execute(
                "INSERT INTO waitlist (email, source, ip, ts) VALUES (?, ?, ?, ?)",
                (email, (req.source or "")[:200], ip, time.time()),
            )
            new = True
        except sqlite3.IntegrityError:
            new = False
    if new:
        # fire-and-forget email
        asyncio.get_event_loop().run_in_executor(None, send_waitlist_email, email, req.source, ip)
    return {"ok": True, "new": new, "message": "You're on the waitlist. We'll email you when a server slot opens."}

@app.get("/health")
def health():
    return {
        "status": "ok",
        "online_devices": len(AGENTS),
        "time": time.time(),
    }

@app.get("/")
def root():
    return {"service": "flowhook", "version": "0.1.0"}

# ---- audit query ---------------------------------------------------------
@app.get("/audit")
def audit(limit: int = 50, claims: dict = Depends(require_user)):
    with db() as c:
        rows = c.execute(
            "SELECT id, device_id, cmd_type, payload, status, result, ts FROM audit WHERE user_id=? ORDER BY id DESC LIMIT ?",
            (claims["uid"], limit),
        ).fetchall()
    return {"audit": [dict(r) for r in rows]}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=PORT)
