# Flowhook

**Full phone control for Claude Code.**

Flowhook gives any authorized caller — your CLI, a Claude Code session, a shell script — full `adb shell`-level control over an Android phone over the open internet. Install APKs, run shell commands, simulate taps, grab screenshots, read logcat. No USB cable. No Wi-Fi. Works over cell data from anywhere.

**Live site & APK download:** https://flowhook.dustforge.com

---

## What it is

A small FastAPI server + an Android companion app. The phone dials out to your server and keeps a WebSocket open. The server exposes a REST API. When you (or an agent) hit the API, the command travels down the socket, executes on the phone via [Shizuku](https://shizuku.rikka.app/)'s `shell`-user Binder IPC, and the result comes back. Everything a wired `adb shell` can do, Flowhook can do — from any network.

## Why it exists

Because letting your agent drive your phone while you're on a run is a reasonable thing to want, and USB isn't going to reach you.

## Repository layout

```
flowhook/
├── app/                  # Android companion app (Kotlin)
├── server/               # FastAPI backend (server.py + requirements.txt)
├── cli/                  # Python CLI (flowhook)
├── build.gradle.kts      # Top-level Gradle config
├── settings.gradle.kts
└── README.md (you are here)
```

## Quick start (users)

1. **Install Shizuku** on the phone. Pair it via Wireless Debugging. Confirm "Shizuku is running."
2. **Download the APK:** https://flowhook.dustforge.com/app.apk
3. **Register and enroll** at the server:
   ```bash
   export FLOW=https://flowhook.dustforge.com

   USER_TOKEN=$(curl -s -X POST $FLOW/auth/register \
     -H 'Content-Type: application/json' \
     -d '{"username":"you","password":"pw"}' \
     | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")

   curl -s -X POST $FLOW/devices/enroll \
     -H "Authorization: Bearer $USER_TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{"name":"my phone"}'
   # → returns {device_id, agent_token, enroll_token}
   ```
4. **Open Flowhook on the phone.** Paste server URL `wss://flowhook.dustforge.com/agent` and the `agent_token` from step 3. Save. Grant Shizuku permission. Hit all three "KEEP ALIVE" buttons.
5. **Run a command:**
   ```bash
   curl -s -X POST $FLOW/exec \
     -H "Authorization: Bearer $USER_TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{"cmd":"id; getprop ro.product.model"}'
   ```

## Quick start (self-host server)

```bash
cd server
python3 -m venv venv
venv/bin/pip install -r requirements.txt
FLOWHOOK_JWT_SECRET=$(openssl rand -hex 32) venv/bin/python server.py
# Listens on :8700. Put nginx in front for TLS.
```

See `server/server.py` top comment for endpoints.

## Quick start (CLI)

```bash
cp cli/flowhook ~/bin/flowhook
chmod +x ~/bin/flowhook
mkdir -p ~/.flowhook
echo '<your user_token>' > ~/.flowhook/user_token

flowhook health
flowhook devices
flowhook exec 'uptime'
flowhook install https://example.com/yourapp.apk
flowhook screencap phone.png
flowhook logcat MyTag
```

## Build the Android app

Requires Android SDK 35 + Gradle 8.7+ + JDK 17+.

```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## Endpoints

See [the landing page](https://flowhook.dustforge.com/#api) for the full API reference.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/auth/register` | — | create user |
| `POST` | `/auth/login` | — | reissue user token |
| `POST` | `/devices/enroll` | user | create device + agent token |
| `GET` | `/devices` | user | list enrolled devices |
| `WS` | `/agent` | (first frame) | phone dials in here |
| `POST` | `/exec` | user | run shell command on phone |
| `POST` | `/install` | user | silent APK install via Shizuku |
| `POST` | `/uninstall` | user | `pm uninstall` |
| `POST` | `/tap` \| `/text` \| `/key` | user | synthetic input |
| `GET` | `/logcat` | user | recent logcat |
| `GET` | `/screencap` | user | base64 PNG |
| `GET` | `/audit` | user | command history |
| `GET` | `/health` | — | server + online-device count |

## How it works

1. Server issues a JWT on registration.
2. User enrolls a phone → server mints a long-lived agent token bound to that phone.
3. Phone opens a WebSocket to `/agent`, sends the agent token as the first frame.
4. Server holds the socket. Outbound-only from phone — no inbound ports, carrier NAT irrelevant.
5. Caller hits `/exec`. Server marshals command → phone → Shizuku → uid 2000 (shell) exec → response.
6. Every command logged to audit.

## Limitations

- **Shizuku dies on reboot.** Re-pair via Wireless Debugging (any Wi-Fi, including a temporary hotspot). 30-second ritual. If your phone rarely reboots, this is a non-issue.
- **Shell user, not root.** Writing system partitions or modifying other apps' private data still requires root. Flowhook won't help with that.
- **OEM battery aggression.** Samsung (OneUI) and some Chinese OEMs will kill background services even with Android's standard exemptions. Flowhook's app prompts for all three: battery-optimization ignore, Samsung "Never sleeping apps," and App-info → Unrestricted. Grant all three.

## Security

- Transport is `wss://` (WebSocket over TLS) — same encryption as HTTPS, same Let's Encrypt cert.
- Tokens are bearer JWTs. Treat them like SSH keys. Rotate via uninstall/re-enroll or by changing `FLOWHOOK_JWT_SECRET` (invalidates all).
- Shizuku privileges = `shell` user. Not root.
- **Dual-use.** Don't install Flowhook on a phone you don't own. Don't hand tokens to anyone you wouldn't hand your unlocked phone to.

## Related project

**[Sightless](https://sightless.dustforge.com)** — voice-driven remote control for your agent army. Separate product, separate APK, no Shizuku dependency (commercial subscription).

## License

MIT. See [LICENSE](LICENSE).

---

Built the morning of 2026-04-14 from blank slate to running-over-cell-data in one session.
