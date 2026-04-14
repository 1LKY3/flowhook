# Flowhook — How and Why
### A detailed build report, 2026-04-14

> This is a contemporaneous, narrative account of the session that produced Flowhook v0.1.0
> through v0.3.1. It's written as a report, not a changelog. The goal is that a future
> person — human or AI — can read this and understand *why* Flowhook is the way it is before
> touching it. Decisions that didn't happen are included alongside the ones that did.

---

## 0. Origin and framing

The user, Kyle, opened this session after an earlier one in which we'd already stood up
**Sightless** — a voice-activated agent remote control product. Sightless is commercial-grade,
targeted at a $100/month subscription, and has a strict constraint: **no dependencies that
aren't monetizable**. Its server side was already live at sightless.dustforge.com (TLS, FastAPI,
full pipeline: wake word → STT → speaker verification → LLM parse → route → TTS → audio out).
Its Android app hadn't been built yet.

Kyle also mentioned wanting a "Claude Cowork–like" agentic workspace for Linux, but we ruled
that out for this session — there's a real risk it'd trip Anthropic's prohibition against
third-party frameworks running on Claude subscription OAuth. We left that for later.

Then Kyle asked a question that reshaped the day: "Can you write and install apps to my phone
*remotely*?" That's a different problem than Sightless. Sightless is about *input*: getting
voice into an agent. The remote-control question is about *output*: getting commands *out* to
the phone.

We split these into two products with distinct positioning:

- **Sightless** — commercial, closed, subscription. Must stay self-contained.
- **Flowhook** — open source, dev-tool, can have any dependency it wants.

The decision was unambiguous: they must be two separate APKs, two separate repos, two separate
products. Sightless must never require Flowhook's capabilities. Flowhook can be Kyle's "hacker
tool," distributed on GitHub for anyone willing to accept its tradeoffs. This session built
Flowhook from blank slate to a working, self-contained, cell-data-working product. Sightless
stayed where it was.

---

## 1. The core problem: controlling a phone from anywhere

Kyle wanted remote shell access to his Samsung Galaxy S22 Ultra (SM-S908U1, Android 16) from
his Linux workstation. Most specifically: while kayaking, while running, while at the park —
anywhere he had cell service but no Wi-Fi. He'd been frustrated with WhisperFlow silently
failing on low signal, and wanted a control plane that was robust over terrible connections.

### Options surveyed

**USB ADB.** Obvious; works perfectly when tethered. Useless at the park. Cable not portable.

**ADB over Wi-Fi.** `adb tcpip 5555` lets a laptop connect to a phone over LAN. Fine for home
network only. Carrier NAT prevents external connections to the phone; even if you could get
packets there, the phone's cellular IP changes on every handoff.

**ADB over Tailscale.** Kyle already ran Tailscale on his phone for Minecraft and dustforge.com
mail traffic. With `adb tcpip 5555` active, a laptop also on Tailscale can reach the phone via
its Tailscale IP (100.123.253.44). This works over cell data because Tailscale's DERP relays
punch through NAT. We *did* use this during development — it's how we installed early APK
versions. But it's not a product:
- Phone has to keep Tailscale up (battery cost, user responsibility)
- adbd has to keep listening on network (resets every reboot)
- Any observer on the Tailscale network can reach it
- Depends on Tailscale the company existing

**Cloud MDM (Samsung Knox / Android Management API).** Enterprise licensing, device enrollment
rituals, factory reset to enroll. Not a fit for a personal/open product.

**Root + Magisk + persistent daemon.** This would give us everything — boot-time daemon start,
full control, zero friction. But unlocking the Samsung S22 bootloader blows the Knox e-fuse
permanently. That breaks Samsung Pay (Kyle uses tap-to-pay), Secure Folder, Samsung Health, and
most banking/DRM apps that check Knox status. It's a one-way door. Ruled out.

**Our own WebSocket-based control plane.** The phone dials *outbound* to a server we control
on the public internet. The server holds the socket open. Commands flow down the socket,
responses come back up. No inbound port needed on the phone, no NAT to traverse, no
carrier-level shenanigans.

That outbound-only property is the single most important architectural insight in this project.
Every constraint — cell NAT, Wi-Fi vs LTE switching, hotel/café firewalls — evaporates when
the phone is the one opening the connection. We chose this as the spine of Flowhook.

---

## 2. First architecture — the scaffold

We had existing infrastructure we could reuse:

- **RackNerd VPS** at 192.3.84.103 (already hosting `sightless.dustforge.com`, `mail.dustforge.com`,
  `mc.dustforge.com`), running Debian, nginx, Let's Encrypt, and bridging to the internal LAN
  via Tailscale.
- **K1**, Kyle's home server (Ryzen 5800X, 32GB RAM, Ubuntu, Tailscale 100.69.1.78). Sightless's
  FastAPI was already listening here on port 8600.
- **dustforge.com** Hover registrar, with SPF/DKIM/DMARC set up for Stalwart mail on phasewhip.
- **phasewhip**, Kyle's mail server box (Tailscale 100.83.112.88), running Stalwart Mail.

So Flowhook didn't need to build any of the foundational infra — just add to it.

### What we built

**DNS.** `flowhook.dustforge.com` A record → 192.3.84.103, added through Hover. DNS propagation
took about a minute (verified via `dig @8.8.8.8`). The first `certbot` run failed with
"No such authorization" because the A record hadn't propagated yet to Let's Encrypt's resolvers;
a retry 30 seconds later succeeded.

**TLS.** Certbot-nginx issued a Let's Encrypt cert valid through 2026-07-13. Standard flow,
integrated with the nginx vhost Certbot edits in place.

**nginx vhost.** New file at `/etc/nginx/sites-available/flowhook.dustforge.com`. Key pieces:
- `upstream flowhook_backend { server 100.69.1.78:8700; }` — proxies to K1 over Tailscale
- `limit_req_zone ... zone=flowhook_api:10m rate=20r/s` — rate limiting per IP
- WebSocket upgrade headers (`proxy_set_header Upgrade $http_upgrade; proxy_set_header
  Connection $connection_upgrade`)
- `proxy_read_timeout 3600s` for WebSocket (long-lived connections)
- `client_max_body_size 50m` for APK uploads

We also defined a reusable `$connection_upgrade` map in `/etc/nginx/conf.d/websocket_upgrade.conf`
since it's needed for both flowhook and potentially other WebSocket services.

**FastAPI server on K1.** New directory `/home/claude/flowhook/`. A virtualenv with `fastapi`,
`uvicorn[standard]`, `bcrypt`, `pyjwt`, `python-multipart`. Systemd unit at
`/etc/systemd/system/flowhook.service` with `Type=simple`, `EnvironmentFile` for secrets,
`Restart=always`, `RestartSec=3`.

The server listens on `0.0.0.0:8700`. Core file is `server.py`, initially ~400 lines, now ~550
after waitlist was added. Schema:

```
users(id, username unique, password_hash, created_at)
devices(id, user_id, name, enroll_token, last_seen, created_at)
audit(id, user_id, device_id, cmd_type, payload, status, result, ts)
waitlist(id, email unique, source, ip, ts)          -- added for /signup
user_settings(...)                                    -- from Sightless, unused here
```

**Endpoints built in day 1:**

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/auth/register` | — | create user, return JWT |
| `POST` | `/auth/login` | — | reissue JWT |
| `POST` | `/devices/enroll` | user | create device + agent token |
| `GET` | `/devices` | user | list enrolled devices + online state |
| `WS` | `/agent` | (first frame) | agent dials in here |
| `POST` | `/exec` | user | run shell command on a target device |
| `POST` | `/install` | user | pull APK from a URL, install silently |
| `POST` | `/uninstall` | user | pm uninstall |
| `POST` | `/tap`, `/text`, `/key` | user | synthetic input |
| `GET` | `/logcat` | user | recent logcat |
| `GET` | `/screencap` | user | base64 PNG |
| `GET` | `/audit` | user | command history |
| `GET` | `/health` | — | server status + online device count |

Added later:
| `POST` | `/waitlist` | — | email collection + SMTP relay |

**Auth model.** Two kinds of JWT: **user tokens** (lifetime 30 days, bearer token for the
CLI/agent to call REST endpoints) and **agent tokens** (long-lived, issued per enrolled
device, sent as the first WebSocket frame). Password storage: bcrypt direct (not passlib —
version-compat issues with bcrypt's 72-byte truncation rule). JWT secret lives in an
`EnvironmentFile` that systemd reads, generated via `openssl rand -hex 32` at install time.

**Agent connection lifecycle.** In-memory `AGENTS: dict[device_id, Agent]`, `USER_AGENTS:
dict[user_id, set[device_id]]`. Each `Agent` holds its WebSocket and a `pending: dict[req_id,
Future]` for correlating responses. On `/exec`, we generate a UUID req_id, send the command
to the agent, await the future, time out at the server-side timeout, return the result.

**CLI wrapper.** `/home/ky/bin/flowhook` — one Python file, 120 lines, uses `urllib.request`
only (no external deps). Reads `~/.flowhook/user_token`. Commands: `health`, `devices`, `exec`,
`sh` (batch mode), `install`, `uninstall`, `screencap`, `logcat`, `audit`. Matches what you'd
expect from `adb` with familiar ergonomics.

---

## 3. v0.1 — the Shizuku-based Flowhook

The hard constraint of Android: a normal app cannot create a child process running as a
different UID. Every process an Android app forks inherits that app's UID (e.g., `u0_a524`).
For Flowhook to `pm install`, `input tap`, write to `/data/local/tmp`, or do anything a USB
ADB session does, something must run at **uid 2000 (shell)** or higher.

Only two ways to get that:
1. **Root** — ruled out (Knox)
2. **adbd** — `adbd` already runs as shell. Its forked children inherit.

Shizuku exploits path 2. When Shizuku's manager app is first paired via Wireless Debugging,
it runs `adb shell <binary>` once. That binary — shipped inside the Shizuku APK as
`libshizuku.so` (it's an ELF executable masquerading as a library) — daemonizes itself. It
listens on Binder and exposes privileged operations to any app that holds Shizuku's permission.

### Our v0.1 implementation

**Kotlin project scaffolded from scratch** at `/home/ky/Projects/Flowhook/`. Gradle 8.7
(installed into `/tmp/gradle-8.7/` because Ubuntu's apt gradle is 4.4, way too old for
AGP 8.3). Android SDK at `~/Android/Sdk`, JDK 21.

```
app/src/main/kotlin/com/dustforge/flowhook/
  MainActivity.kt              ← entry UI
  FlowhookService.kt           ← foreground service, holds WebSocket
  CommandHandler.kt            ← dispatches WS messages → executor
  Config.kt                    ← SharedPreferences wrapper
  BootReceiver.kt              ← auto-start on boot
  ShizukuExecutor.kt           ← wraps Shizuku.newProcess via reflection
```

**Dependencies:**
- `com.squareup.okhttp3:okhttp:4.12.0` — WebSocket client
- `dev.rikka.shizuku:api:13.1.5` + `dev.rikka.shizuku:provider:13.1.5`
- Jetpack core, lifecycle, coroutines, Material components

**Build gotcha: kotlinx / Android 14+ targetSdk.** targetSdk 35 requires JDK 17+ source
compatibility. We set both `sourceCompatibility` and `targetCompatibility` to
`JavaVersion.VERSION_17` and `kotlinOptions.jvmTarget = "17"`.

### Deployment of v0.1

Installed via the laptop's USB ADB. Phone pre-paired (USB debugging already enabled from
Shizuku setup). Version 0.1.0 → 0.1.1 → 0.1.2 → 0.1.3 landed over about 90 minutes of iteration.

Shizuku pairing: Kyle enabled Wireless Debugging on the phone. I couldn't do the pair code
exchange from a shell (Android 11+ uses Spake2 which dadb doesn't implement), but we
bypassed by directly editing Shizuku's config file at
`/data/user_de/0/com.android.shell/shizuku.json`. Initial state had:
```json
{"version":2,"packages":[{"uid":10523,"flags":2,"packages":["com.dustforge.flowhook"]}]}
```
`flags=2` means DENIED (Shizuku's internal bitmask: 1=ALLOWED, 2=DENIED, 4=HIDDEN). We wrote
`flags=1` and restarted Shizuku's server (`libshizuku.so`). That pre-grant trick worked —
`Shizuku.checkSelfPermission()` returned GRANTED on the next connection.

### The Samsung battery three-ingredient recipe

On our S22 Ultra running OneUI Android 16, we discovered that **none** of the standard battery
exemptions alone was sufficient to keep Flowhook alive through screen-off and Doze. All three
had to be simultaneously true:

1. **deviceidle whitelist entry** — `user,com.dustforge.flowhook,<uid>`, granted via
   `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent
2. **Standby bucket = 5 (EXEMPTED)** — the 50/45/40/30/20/10 scale plus this special
   "exempted" bucket. Set by navigating Settings → Apps → Flowhook → Battery → Unrestricted.
   This one was the hardest to discover because the standard "Ignore battery optimization"
   dialog does NOT set this.
3. **PARTIAL_WAKE_LOCK held by the service** — `pm.newWakeLock(PARTIAL_WAKE_LOCK,
   "flowhook:ws").acquire()`, released on service destroy

Missing #2 made the service die after ~5 minutes of Doze. Missing #3 made it die after the
first Doze window. Missing #1 made it die immediately.

We added three sequential buttons to the UI labeled "KEEP ALIVE 1/2/3", each opening the
relevant settings screen. Samsung's "Never sleeping apps" deep link
(`com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity`) has variants
across OneUI versions, so we attempt several component names before falling back to generic
battery settings.

### v0.1 smoke test results (confirmed working)

- Screen-off 10+ minutes on OneUI 16 in light Doze (`mLightState=IDLE`) — **survived**
- Network switch Mint SIM → Saily eSIM over LTE — **survived**
- Chrome foregrounded, Flowhook backgrounded for 5+ minutes — **survived**
- Screencap transfer over LTE, ~34KB PNG — **worked**
- WebSocket stayed alive during screen lock + unlock cycles

On all these tests we were **running Flowhook via Shizuku**. The tests passed. We declared v0.1
a win.

---

## 4. The failed final smoke test — and the realization

Kyle went for a run while the phone sat paired and working. When he got to the park, we tested
again — this time truly untethered (USB unplugged, Wi-Fi off, Tailscale off, screen locked
during the run).

The WebSocket was alive. But every `exec` command came back with `"Shizuku not running"`.

The Shizuku server process had died during the run. Samsung's OS had reaped it. Our three-part
battery exemption was applied to **Flowhook's** UID, but the Shizuku server runs as `shell`
user under a separate process tree — it does NOT inherit Flowhook's exemptions. OS-level
protections attach to app UIDs, not to arbitrary shell processes.

This was a hard truth: **we'd built a control plane that depended on something we couldn't
protect.** The WebSocket was alive and useless. Commands went in, nothing came out.

Kyle's reaction was correct: "This was a failed smoke test."

### Kyle's insight

He then asked the question that reshaped v0.2: *"If Shizuku can do its job without root, why
can't we do Shizuku's job without root?"*

The honest analysis I gave him:

- Yes, we can replicate Shizuku's mechanism inside Flowhook. Shizuku is Apache 2.0 — we're
  free to.
- No, we cannot escape the fundamental constraint: the shell-user process must be forked by
  adbd. No app can fork a shell-user child from within its own UID.
- **But** — here's the leverage — if we own both sides, we can auto-revive the shell-user
  server the moment it dies, using a persistent ADB key we stored on first pair. Shizuku
  doesn't do this because it's permission-neutral and treats pairing as a one-time user-driven
  ritual.

Three paths forward:
1. **Root** — already ruled out
2. **Bundle Shizuku's server in our APK + supervise its lifecycle** — reduces to our own code
   surveillance of Shizuku code
3. **Skip the separate server entirely** — each command opens a fresh adb shell via a local
   loopback connection to the phone's own adbd. Each command gets its own shell-user process,
   lives just long enough to run, then exits.

Chose **path 3**. Simpler. Fewer moving parts. Same capabilities. The per-command shell startup
cost is measured in milliseconds — invisible for interactive use.

---

## 5. v0.2.0 — the self-managed ADB bridge

### Library selection

Needed a Kotlin-friendly ADB client that talks **directly to adbd** (not through the `adb`
host server). Evaluated:

- **dadb** (`dev.mobile:dadb:1.2.8`) — Mobile.dev's library. Connects directly to adbd,
  supports `shell:`, `exec:`, `sync:` streams. Has `AdbKeyPair` for RSA key generation.
  Active, small, Apache 2.0. **Winner.**
- **adam** (`com.malinskiy.adam:0.5.4`) — has `PairDeviceRequest` (Spake2 pairing support)
  but is oriented toward talking to the adb host server, not directly to adbd. More work to
  repurpose.
- **cgutman/AdbLib** — Java ADB client, widely used. No Spake2 pairing.
- **Google's adblib** — same.

Dadb's limitation: **no Spake2 Wireless Debugging pairing**. That meant we couldn't do a
fully-in-app first-time setup for Android 11+ Wireless Debugging. Acceptable tradeoff: user
runs `adb tcpip 5555` once via USB (or Tailscale), and from then on dadb handles everything.
The Wireless Debugging Spake2 pairing client is planned for v0.5.

### RSA key management

New class `AdbKeyStore.kt`:
```kotlin
fun keyPair(ctx: Context): AdbKeyPair {
    val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
    val privFile = File(dir, "adbkey")
    val pubFile  = File(dir, "adbkey.pub")
    if (!privFile.exists() || !pubFile.exists()) {
        AdbKeyPair.generate(privFile, pubFile)
    }
    return AdbKeyPair.read(privFile, pubFile)
}
```

Keys live in app-private storage at `/data/data/com.dustforge.flowhook/files/adb/`. Survives
reboot. Survives app force-stop. Wiped only by uninstall.

### Authentication UX

The first time Flowhook connects to `127.0.0.1:5555` with a brand-new key, adbd displays
Android's standard **"Allow debugging?"** dialog with the key's fingerprint. User taps Allow
(and "Always allow from this computer" checkbox). adbd persists our public key to
`/data/misc/adb/adb_keys`. From then on, Flowhook can reconnect silently.

Note: that `adb_keys` file is `system:shell 0640`. Shell can read but not write — it's
populated only by the system's auth flow. This is why we can't plant our key programmatically
and must go through the user-tap dance on first use.

### Connection + executor

`AdbExecutor.kt`:
```kotlin
private val dadbRef = AtomicReference<Dadb?>(null)

fun connect(ctx: Context, port: Int = 5555): Result {
    val keyPair = AdbKeyStore.keyPair(ctx)
    val d = Dadb.create("127.0.0.1", port, keyPair)
    val probe = d.shell("echo flowhook_ok")
    if (probe.exitCode == 0 && probe.output.contains("flowhook_ok")) {
        dadbRef.set(d)
        return Result(probe.output, probe.errorOutput, probe.exitCode)
    }
    ...
}

fun exec(cmd: String): Result {
    val d = dadbRef.get() ?: return Result("", "adb bridge not connected", -2)
    val r = d.shell(cmd)
    return Result(r.output, r.errorOutput, r.exitCode)
}
```

Each `exec` opens a new shell stream through the already-authenticated dadb connection. dadb
keeps the underlying TCP connection alive; only the per-command shell stream is short-lived.
Zero ongoing cost when idle.

### Unified executor façade

`Executor.kt` — routes through whichever bridge is available:
```kotlin
fun exec(cmd: String): Result {
    if (AdbExecutor.isReady())  return AdbExecutor.exec(cmd).copy(source = "adb")
    if (ShizukuExecutor.isReady() && ShizukuExecutor.hasPermission())
        return ShizukuExecutor.exec(cmd).copy(source = "shizuku")
    return Result("", "no shell bridge available", -99, "none")
}
```

`CommandHandler.kt` was updated to route through `Executor` instead of `ShizukuExecutor`
directly. This preserves Shizuku as a fallback path for edge cases — it hasn't been needed in
v0.2+ but it's still there.

Server responses now include a `"source": "adb"` field in the JSON so we can verify which
path executed each command. First successful test:

```json
{"req_id": "...", "ok": true, "source": "adb",
 "stdout": "uid=2000(shell) gid=2000(shell) groups=2000(shell),1004(input),..."}
```

`"source": "adb"` was the confirmation. Flowhook was running its own bridge. Shizuku remained
dead throughout. We no longer cared.

### Supervisor loop

In `FlowhookService`:
```kotlin
scope.launch {
    while (isActive) {
        if (!AdbExecutor.isReady()) {
            val r = AdbExecutor.connect(applicationContext)
            if (r.exit == 0) {
                AdbExecutor.exec("pm grant com.dustforge.flowhook android.permission.WRITE_SECURE_SETTINGS")
                AdbExecutor.exec("pm grant com.dustforge.flowhook android.permission.READ_LOGS")
            }
        }
        delay(10_000)
    }
}
```

Every 10 seconds, checks if the bridge is up. If not, reconnects. On the first successful
reconnect, pre-grants ourselves two privileged permissions. `pm grant` works because we're
running *as shell user* at that moment.

**Why WRITE_SECURE_SETTINGS matters for the future:** with it, we can programmatically change
settings like `adb_wifi_enabled` at boot time. That's the foundation for full auto-recovery
after reboot (work planned for v0.4).

### Manifest changes

```xml
<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS"
    tools:ignore="ProtectedPermissions" />
<uses-permission android:name="android.permission.READ_LOGS"
    tools:ignore="ProtectedPermissions" />
```

These are permissions that a normal app can *declare* but can't be granted by the user.
Our own ADB bridge grants them via `pm grant` once connected. `tools:ignore` stops the
Android Studio lint from complaining.

### Build-time issues

- **BouncyCastle conflicting JARs.** dadb pulls in `bcprov-jdk18on` and `bcpkix-jdk18on`; both
  contain `META-INF/versions/9/OSGI-INF/MANIFEST.MF`. Merge collision. Fix: add to
  `android.packaging.resources.excludes`.
- **ShizukuExecutor.exec "process hasn't exited" bug.** On Shizuku path, `Process.waitFor(long,
  TimeUnit)` returned incorrectly on the remote Process implementation. Rewrote to poll
  `proc.exitValue()` and catch `Throwable` (not just `IllegalThreadStateException` — some
  variants wrap it). Still a fallback path, still works.

### v0.2.0 proof

Installed over Tailscale-adb while Kyle was still on his run. Verified with a raw
`curl POST /exec`. Response showed `"source": "adb"`. Shizuku was confirmed dead via
`ps -A | grep shizuku_server` (returned nothing). The new bridge was entirely self-managed.

When Kyle returned home, we ran the same end-to-end smoke test as v0.1, but this time:
- Tailscale **off on the phone**
- USB **unplugged**
- Wi-Fi **off**
- Screen **locked** during the entire test
- Only LTE

All green. `"source": "adb"` every call. Screen-off for 10+ minutes, WebSocket stayed up,
commands executed, a base64 PNG screencap round-tripped successfully. Kyle's verdict:
"Green light enough for me."

---

## 6. The chicken-and-egg deployment problem

How do you ship a new Flowhook APK to a phone that only has the old Flowhook talking to
your server?

Flowhook's `/install` endpoint was already in the server: you hit it with an `apk_url`, and
the phone downloads + installs via its current bridge. So in theory, Flowhook can update
itself. But:

1. `pm install -r` **kills the running process** as part of the replace.
2. Android does NOT auto-restart services after package replacement.
3. So after install, the new APK is on disk but nothing is running.

That's what happened each time we shipped a new version: user must tap the icon once to
bring the new version online.

Workaround that almost works: `nohup sh -c "curl + pm install" &` inside a single exec. The
nohup-ed shell survives Flowhook's death. The install completes. Phone goes offline until
user taps the app. Then new version starts and we're back.

Proper fix (planned for v0.3.2): add a `PACKAGE_REPLACED` broadcast receiver that auto-starts
the service when our own APK is updated:

```xml
<receiver android:name=".PackageReplacedReceiver">
    <intent-filter>
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
    </intent-filter>
</receiver>
```

Not yet shipped. v0.3.2 work.

---

## 7. Dead ends and alternatives we considered

### "Just ship Shizuku's server binary inside Flowhook's APK"

Rejected. Would have given us the exact same lifecycle problem Shizuku has (separate process
at a different UID that our exemptions don't protect) while also adding complexity (we'd have
to embed, extract, and invoke `libshizuku.so`). Dadb's per-command shell gave us the same
capability with a single TCP connection and no separate long-running process.

### "Implement Spake2 Wireless Debugging pairing in Kotlin"

Considered seriously for v0.2. Android's reference implementation is in
`packages/modules/adb/client/pairing_connection.cpp`. No Kotlin port existed when we searched.
Estimated effort ~1 day to port (Spake2 over TLS with a one-time pairing code is a well-defined
protocol). Deferred. The current friction of "run `adb tcpip 5555` once via USB" is
acceptable. Spake2 support is v0.5+ work.

### "Plant our app's RSA public key directly in /data/misc/adb/adb_keys"

Considered. That file's permissions on modern Android:

```
drwxrwx--x system shell     /data/misc/adb/
-rw-r----- system system    /data/misc/adb/adb_keys
```

Directory is world-x (shell can traverse), file is readable by shell but only writable by
`system`. Writing requires root. Cannot bypass — this is the explicit design of adbd's trust
model.

### "Unix domain socket IPC between Flowhook and a shell-user helper process"

Considered when we were still thinking in terms of "ship Shizuku-like server bundled with
Flowhook." The shell-user server would bind `android.net.LocalServerSocket` in the abstract
namespace (e.g., `"flowhook"`), Flowhook's app would connect as the client. Works, but adds a
second process to manage. Dropped in favor of dadb's per-command streams. If we ever need a
persistent shell-user helper (for, e.g., logcat streaming), we might revisit this.

### "Use WRITE_SECURE_SETTINGS to re-enable Wireless Debugging on boot"

Partially attainable. `WRITE_SECURE_SETTINGS` lets us write to `Settings.Global`, including
`adb_wifi_enabled=1` (toggles Wireless Debugging). But Wireless Debugging uses TLS + Spake2
paired keys — our stored key isn't paired via Spake2, only via the legacy ADB auth prompt
(which `adb tcpip` uses). So enabling Wireless Debugging post-reboot wouldn't let our existing
key work. The real target is the legacy `service.adb.tcp.port` system property, which adbd
reads on start to decide whether to listen on TCP. Setting that requires shell's SELinux
context — which our app doesn't have until the bridge is up, which requires tcpip to be on.
Chicken-egg.

Partial workaround path (v0.4): use our existing bridge to set `persist.adb.tcp.port=5555`
while connected. On next boot, adbd should pick that up. This is unverified — needs testing
on the actual device. If it works, reboot recovery becomes zero-touch.

### "Use Accessibility Service instead of ADB"

Considered early. Accessibility Service gives input simulation and UI scraping. But it cannot
`pm install`, cannot `logcat`, cannot write `/data/local/tmp/`, cannot execute arbitrary
shell. Insufficient for Flowhook's mandate. Also, Accessibility abuse is a major security
concern and Google Play aggressively screens for it.

### "Use Samsung Knox SDK under enterprise license"

Commercial license, MDM enrollment, factory reset to enroll. Enterprise-scale only.
Incompatible with a self-hosted open-source product.

### "Containerize Flowhook on K1 with Incus for easier migration"

Kyle asked about this early, weighing whether to Incus-contain Flowhook + Sightless to make
future migration to a commercial VPS easier. Honest assessment: Flowhook's server is ~500
lines of Python + SQLite; migration is `rsync + pip install + systemctl enable`, maybe
15 minutes total. Incus adds 30-45 minutes of setup (bridge networking, nginx upstream
changes, exposing ports) that doesn't pay back. Skipped — runs directly on K1 host alongside
Sightless. If we ever need isolation (dependency conflicts, multi-tenant concerns), we revisit.

---

## 8. Website iteration

The landing page at flowhook.dustforge.com went through several revisions during the session.

### Initial design

Single-page HTML, ~12KB. Dark theme (`#0B0D10` background, `#7ED957` accent green,
`#E6E8EB` foreground, `#14181D` card, `#242A32` border). Monospace for code. Hero headline
with a glowing green drop-shadow around the H1. CTA buttons (Download APK, Quickstart, GitHub).
Full API reference table. Paste-ready Claude Code walkthrough for anyone handing the setup to
their own agent.

### Sticky header

Kyle asked for a sticky header at the top of the page with the Flowhook icon on the far left,
acting as a "back to top" link. Built with `position: sticky` + `backdrop-filter: blur(8px)`
for a glass effect + green bottom border matching the accent. Icon is an inline SVG (crosshair
+ X pattern in green) on a dark tile with a subtle border.

Kyle clarified: remove the "FLOWHOOK" wordmark from the sticky header entirely. Keep just the
icon. Revised. Then "far left" — the icon was centered in a max-width container; moved it to
the actual viewport edge via `.site-header-inner { padding: 10px 16px; }` without the
`max-width`.

### Yellow highlights on requirements

Kyle: "Make the bullet points yellow." First pass: I made the whole bullet lines yellow and
bold. He corrected: only specific lead phrases should be yellow — "An Android phone", "Shizuku
installed and running on the phone.", "A Flowhook server." The rest of each line stays white.
Also: don't bold them, use a bright yellow instead. Final: `.hl { color: #FFEE00; }`, applied
only to `<span class="hl">` around the highlighted fragments.

### Glowing GitHub button

Kyle wanted the GitHub CTA button in the hero row to have a glowing green border, similar to
the H1 but "slightly duller." First pass had layered shadows (`0 0 12px + 0 0 28px + hover
expands`). Kyle: "less drop shadow." Dialed it back to a single tight `0 0 6px` halo. Kept
the green border. Clean.

### Marketing copy simplification (v0.2.1)

After v0.2 shipped, the page still had lots of "No Shizuku. No root. No Knox trip." framing —
describing Flowhook by what it *doesn't* require. Kyle's direction was sharp:

> "Leave out the Shizuku if you don't need the Shizuku. Don't need to say you don't need it.
> Leave out anything saying you don't need. If you don't need it, we don't need to mention
> it. Simple as possible."

Stripped all the "no X" negative framing. Now the page describes only what Flowhook *is* and
*does*. "Open source. Self-hosted. One APK." Much tighter. This is good marketing instinct.

### APK distribution via GitHub Releases

Kyle wanted new releases on GitHub to automatically update the public download, not require a
manual re-upload to the VPS. Switched `/app.apk` from static file serving to:

```nginx
location = /app.apk {
    return 302 https://github.com/1LKY3/flowhook/releases/latest/download/flowhook.apk;
}
```

GitHub's `/releases/latest/download/<name>` URL redirects to whatever asset is attached to
the release tagged as "Latest." Now the cut-a-release workflow is:

```
gh release create v0.X.Y /tmp/flowhook.apk --title "..." --notes "..."
```

That single command makes the new APK immediately downloadable from flowhook.dustforge.com.
No website change needed. The site now behaves like a stable CDN endpoint pointing at the
moving "latest" target in GitHub.

### Waitlist page at /signup

Kyle wanted the "use this server" mention on the landing to be a waitlist signup (since our
public server isn't actually open-enrollment yet). Built `/signup.html`:

- Same visual style as the landing (sticky header with icon, glowing title card, dark theme,
  yellow highlights absent)
- Single form with email field + submit button
- POSTs to `/waitlist` on the FastAPI backend with `{email, source}`
- Server validates email with a simple regex, deduplicates by email (unique constraint on the
  table), logs to `waitlist` table, fires SMTP to `ky@dustforge.com`

**SMTP relay.** K1 reaches phasewhip (Stalwart mail server) on `100.83.112.88:25` over
Tailscale. Stalwart accepts unauthenticated local submission for its own domain. Python's
`smtplib.SMTP(host, 25, timeout=10)` + `send_message()` works cleanly. We confirmed
end-to-end: Kyle got test emails at ky@dustforge.com within seconds of each signup.

---

## 9. v0.3.0 — battery toggles

### The battery problem

v0.2.1 by design was a "battery scorcher" — PARTIAL_WAKE_LOCK held 24/7, WebSocket pings every
15 seconds (cell radio keeps waking up), ADB supervisor loop every 10 seconds. Kyle observed
the drain and was correct that this was the service's doing, not the tcpip listener itself.
adbd idle costs nearly nothing.

### Kyle's design: two toggles, different blast radii

He proposed two toggles at the top of the app:
1. **Left: Flowhook services** (safe) — just pauses the battery-intensive parts
2. **Right: ADB Bridge** (dangerous) — kills the underlying tcpip, requires USB to re-enable

I scrutinized and agreed. The distinction matters because:
- Turning off Flowhook services (left) stops ~99% of the battery drain. Flipping back on
  reconnects in ~2 seconds. Safe, easy.
- Turning off ADB bridge (right) nukes the whole thing at OS level. Re-enabling requires
  plugging into a computer and running `adb tcpip 5555` again. Dangerous, one-way-ish.

The separation of concerns is legitimate: left toggle is for daily battery management; right
toggle is for "panic button" security (I don't trust the attack surface of having adbd listen
on the network right now).

### Confirmation dialog

Tapping the right toggle off opens a custom dialog (not `AlertDialog` — doesn't support
vertically stacked buttons out of the box):

```
Turn off ADB Bridge?

If you turn this off, you won't be able to turn it back on
until you connect your phone to a computer and run
`adb tcpip 5555`.

  [  Leave ADB Bridge on   (white) ]
  [  Turn ADB Bridge off    (red)  ]
```

Two buttons stacked vertically. White "cancel" on top (the safer action is always more
prominent). Red "confirm destructive" on bottom. Built with `android.app.Dialog` + a custom
layout in `res/layout/dialog_adb_confirm.xml`.

Tapping outside the dialog = cancel. When user confirms, we run the shell command via the
current bridge (which will self-destruct after):

```kotlin
val killCmd = "setprop service.adb.tcp.port -1; stop adbd; start adbd"
val r = AdbExecutor.exec(killCmd, timeoutMs = 5_000)
AdbExecutor.disconnect()
```

adbd restarts without tcpip mode. The bridge dies. UI updates to show "ADB Bridge: off."

### Service state machine

`FlowhookService.Mode` enum:

| Left toggle | Right toggle | Mode | WS | WakeLock | ADB supervisor | Notification |
|---|---|---|---|---|---|---|
| ON | ON | `FULL` | ✓ | ✓ | ✓ | LOW importance ("Remote admin bridge active") |
| OFF | ON | `IDLE` | — | — | ✓ | MIN importance ("ADB bridge ready (idle)") |
| ON | OFF | `FULL` | ✓ | ✓ | tries but can't connect | LOW importance |
| OFF | OFF | `STOP` | — | — | — | none, service stopped |

`onStartCommand` receives `EXTRA_MODE`, calls `applyMode(Mode)`, which is idempotent and
handles transitions between any two states.

**The MIN-importance notification trick.** Android requires every foreground service to have
a notification. An `IMPORTANCE_MIN` notification channel doesn't show in the status bar at
all — it lives in the collapsed "Silent notifications" section of the notification shade.
Practically invisible to the user. Satisfies Android's requirement without cluttering the UI.
This is how we achieve "no visible notification when Flowhook services are paused" without
actually removing the notification.

### SwitchCompat color workaround

First attempt: define two XML color selectors (`switch_track_green.xml`,
`switch_track_red.xml`) and apply via `android:trackTint="@color/switch_track_red"` in the
layout XML. Didn't render — SwitchCompat in a Material Components theme has its tint
overridden by the theme's `colorPrimary` (which is our green accent).

Fix: apply tint programmatically in `onCreate`:

```kotlin
private fun applySwitchColors(sw: SwitchCompat, checkedTrackColor: Int) {
    val states = arrayOf(
        intArrayOf(android.R.attr.state_checked),
        intArrayOf(-android.R.attr.state_checked)
    )
    sw.trackTintList = ColorStateList(states, intArrayOf(checkedTrackColor, 0xFF3A3F46.toInt()))
    sw.thumbTintList = ColorStateList(states, intArrayOf(0xFFE6E8EB.toInt(), 0xFFB0B4BA.toInt()))
}

// then:
applySwitchColors(toggleServices, checkedTrackColor = 0xFF7ED957.toInt())  // green
applySwitchColors(toggleAdb, checkedTrackColor = 0xFFC0392B.toInt())       // red
```

Programmatic wins. Lesson for future Material themes.

### UI iteration (v0.3.0 → v0.3.1)

Kyle reviewed the first v0.3.0 build and requested:
- **Logo moved out of the header center** into the brand row, to the left of "Flowhook" title
- **Toggle subtext** added under each switch:
  - Left: "Tap off to save battery. ADB stays warm."
  - Right: "Tap off to cut remote access. Requires USB to re-enable."
- **Red toggle actually red** (fix described above)

All shipped in v0.3.1.

---

## 10. A tangent — the "wss vs https" security question

Mid-session Kyle noticed the server URL was `wss://flowhook.dustforge.com/agent` and asked
about the security implications. We clarified:

- `ws://` is unencrypted WebSocket (like `http://`)
- `wss://` is WebSocket over TLS (like `https://`) — **same** encryption
- The WebSocket handshake is an HTTP upgrade request. Before upgrade, everything is plain
  HTTPS. After upgrade, the TLS tunnel continues to carry WebSocket frames.
- Same Let's Encrypt certificate, same nginx TLS termination, same cipher suites. Bytes on
  the wire are indistinguishable from HTTPS to anyone observing.

`wss://` is the secure choice. `ws://` would have been a red flag.

Real vulnerabilities are not in the transport:
1. **JWT secret** on K1 — if stolen, attacker can mint tokens. Rotate by changing
   `FLOWHOOK_JWT_SECRET` (invalidates all existing tokens).
2. **Agent token on phone** — treat like SSH key. Stored in SharedPreferences.
3. **Server compromise** — blast radius is the same as SSH keys on K1.
4. **No rate limiting on `/exec`** — if token leaks, attacker can spam shell commands at
   HTTP speed. Audit log captures but doesn't prevent.

---

## 11. Version history (as of session end)

| Version | Date | Commit note |
|---|---|---|
| 0.1.0 | 2026-04-14 | Initial release — Shizuku-based, working end-to-end |
| 0.1.1 | 2026-04-14 | WakeLock fix, faster reconnect backoff |
| 0.1.2 | 2026-04-14 | Install path: write APK via externalCacheDir, copy to /data/local/tmp |
| 0.1.3 | 2026-04-14 | Samsung survival: deviceidle + exempted bucket + wakelock confirmed |
| 0.2.0 | 2026-04-14 | **Self-managed ADB bridge** via dadb. Shizuku no longer required. |
| 0.2.1 | 2026-04-14 | /app.apk → GitHub Releases 302 redirect. Marketing simplification. |
| 0.3.0 | 2026-04-14 | **Battery toggles** (left: Flowhook services, right: ADB Bridge) + confirm dialog |
| 0.3.1 | 2026-04-14 | Red toggle tint via ColorStateList, toggle subtext, logo to brand row |

Everything from blank repo to v0.3.1 shipped in a single day (2026-04-14), with
end-user validation via LTE after each major version.

---

## 12. Current deployed state (v0.3.1, session end)

### Infrastructure

- **GitHub:** github.com/1LKY3/flowhook (public, MIT, all code + README)
- **Landing:** https://flowhook.dustforge.com — HTML + /signup waitlist + /app.apk 302 redirect
- **API:** same domain — `/auth/*`, `/devices/*`, `/exec`, `/install`, `/screencap`,
  `/logcat`, `/audit`, `/health`, `/waitlist`
- **Agent WebSocket:** `wss://flowhook.dustforge.com/agent`
- **Server process:** FastAPI on K1 (100.69.1.78:8700), systemd unit `flowhook.service`,
  JWT secret in `EnvironmentFile`
- **Nginx:** VPS 192.3.84.103, proxies over Tailscale to K1, Let's Encrypt cert valid through
  2026-07-13
- **CLI:** `/home/ky/bin/flowhook` (Python, depends only on stdlib)
- **Android app:** v0.3.1 installed on Kyle's SM-S908U1 (Android 16)

### Capabilities (full USB-ADB parity, over any network)

- `pm install -r -t <apk>`, `pm uninstall <pkg>`, `pm grant <perm>`
- `am start`, `am start-foreground-service`, `am force-stop`
- Arbitrary shell as `uid=2000(shell) gid=2000(shell) groups=...,1011(adb),...`
- `input tap X Y`, `input text "..."`, `input keyevent KEYCODE`
- `screencap -p /sdcard/x.png` + base64 return
- `logcat -d -t N -s TAG`
- Arbitrary `exec` via the CLI or REST

### Verified resilience

- 10+ minutes screen-off in OneUI light Doze (`mLightState=IDLE`)
- Network switch Mint SIM → Saily eSIM over LTE
- Backgrounded while Chrome foregrounded 5+ min
- Full cell-only smoke test: no Tailscale, no Wi-Fi, no USB, screen locked
- Self-update via `flowhook install https://...` successfully replaced the APK (with the
  caveat that user must tap the icon to restart — v0.3.2 fix pending)

---

## 13. Known limitations and planned work

### Short-term (v0.3.2)

- Add `android.intent.action.MY_PACKAGE_REPLACED` broadcast receiver, so self-update restarts
  the service without requiring a user tap.

### Medium-term (v0.4)

- **Reboot recovery.** Currently, phone reboot kills `adb tcpip 5555`. User must plug into
  a computer to re-enable. Plan:
  1. On boot, `BootReceiver` fires with `RECEIVE_BOOT_COMPLETED`
  2. We already hold `WRITE_SECURE_SETTINGS` (pre-granted via our own bridge)
  3. Attempt to set `persist.adb.tcp.port=5555` — this is a system property, writable by
     shell. Our app isn't shell at boot, but we might have cached enough state to try.
  4. Fall back: fire a notification prompting the user to plug in once.
  
  If we can get path 3 working, reboot recovery becomes zero-touch. Testing required on the
  actual device; this may turn out to be infeasible on Samsung specifically.

### Longer-term (v0.5+)

- **Spake2 Wireless Debugging pairing** implemented inside Flowhook. Eliminates the "plug
  into a computer once" first-time setup step. Estimated ~1 day to port from Android's C++
  reference. Makes Flowhook fully self-contained.
- **Token rotation UI** for security hygiene.
- **Device revocation** (`DELETE /devices/{id}`).
- **Audit log rotation + export.**
- **Real release signing.** Currently we're debug-signed. For distribution to third parties
  beyond Kyle's trusted circle, we need a release keystore and proper signing.

---

## 14. Things a future agent must know before touching this

### Sightless is off-limits to Flowhook's dependencies

Sightless is the commercial product. It must never gain a Shizuku dependency, never require
ADB-over-network, never need anything that isn't monetizable. If a new feature needs
shell-user privileges, it goes into Flowhook. Sightless and Flowhook are peers that can be
*used together* by the same person, not a single stack.

### The on-phone RSA key is sacred

`ctx.filesDir/adb/adbkey{,.pub}` is what authorizes Flowhook to the phone's own adbd. If
wiped (app uninstall, or developer-mode clear-data), the user has to re-run the "Allow
debugging?" dialog with the new key's fingerprint. Do not regenerate it casually. Rotating
it invalidates the adbd trust — re-auth is required from the user.

### SwitchCompat in Material themes fights XML track tints

Any time you set a SwitchCompat's track color, do it programmatically via
`setTrackTintList(ColorStateList)` in `onCreate`. XML `android:trackTint` or AppCompat
`app:trackTint` silently loses to `Theme.MaterialComponents.*` `colorPrimary`. Don't trust
it. Verify visually after every theme change.

### Samsung OneUI is aggressive. The three-ingredient exemption is non-optional.

1. `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (deviceidle whitelist)
2. `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` → Battery → Unrestricted (bucket 5)
3. `PARTIAL_WAKE_LOCK` held by the foreground service

Missing any one = dead service within minutes. Test after every UI change that touches these.

### Shell user cannot write `/data/misc/adb/adb_keys`

That file is `system:system`, mode `640`. Adbd's trust store is populated only by the system
(through user tap on the "Allow?" dialog). You cannot bypass this from the app, even with
WRITE_SECURE_SETTINGS. Design around it; don't fight it.

### `pm install -r` kills the running process

When Flowhook updates itself via `/install`, the old process dies. Until v0.3.2 ships a
`PACKAGE_REPLACED` receiver, users must tap the icon to restart. Build around this when
testing self-update flows.

### `wss://` is correct, not a concern

Tell anyone who asks: `wss://` is WebSocket Secure. Same TLS as HTTPS. Same cert. The
difference is purely the application layer protocol after the TLS handshake completes.

### Build environment on Kyle's box

- Gradle 8.7 at `/tmp/gradle-8.7/bin/gradle` (system gradle is 4.4, too old)
- Android SDK at `~/Android/Sdk` (platform 35, build-tools 35.0.0)
- JDK 21 from Ubuntu
- `gh` CLI authenticated as `1LKY3`
- Tailscale ADB to phone at `100.123.253.44:5555` (dev-time deploy channel only)
- `sudo` on K1/phasewhip/VPS via `su - claude` with password `subwayeatfresh`
- RackNerd VPS root via sshpass with password from memory `reference_racknerd_vps.md`

### Deploy workflow

```bash
cd /home/ky/Projects/Flowhook
# bump versionCode + versionName in app/build.gradle.kts
/tmp/gradle-8.7/bin/gradle --no-daemon assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk /tmp/flowhook.apk
git add -A && git commit -m "v0.X.Y: ..."
git push
gh release create v0.X.Y /tmp/flowhook.apk --title "..." --notes "..."
# For a live phone with an older Flowhook:
curl -X POST https://flowhook.dustforge.com/exec \
    -H "Authorization: Bearer $(cat ~/.flowhook/user_token)" \
    -d '{"cmd":"nohup sh -c \"cd /data/local/tmp && curl -sLo fh.apk https://flowhook.dustforge.com/app.apk && pm install -r -t fh.apk\" >/dev/null 2>&1 &"}'
# User taps Flowhook icon once to activate new version (until v0.3.2)
```

---

## 15. Why this design ages well

A few architectural choices that should outlast specific Android versions, carriers, or
libraries:

**Outbound-only phone connectivity.** Whatever cell tech, NAT, or firewall comes next — IPv6
rollout, QUIC, satellite links, whatever — an outbound TLS connection will still work. The
phone is always the one initiating. The server is always a stable address. This is a
fundamental win.

**Shell-user privileges are load-bearing.** Since Android 1.0, `adb shell` has had uid 2000.
Whatever sandboxing Google tightens for regular apps, they can't remove the shell user without
breaking every developer's workflow. We piggyback on that permanence.

**GitHub Releases as distribution CDN.** `/app.apk` → 302 redirect → latest release asset.
Every future push to releases auto-updates the public download. GitHub maintains the
infrastructure. We don't.

**End-to-end user ownership of keys.** JWT secret on K1 (you can rotate), RSA key on phone
(you own it), adbd trust store on phone (you authorize new keys via the Allow dialog). No
component trusts any other implicitly. Each can be rotated without cascading.

**Strict product separation.** Sightless and Flowhook are two products with no shared
dependencies. Kyle can monetize Sightless without worrying about Flowhook's dev-grade
tradeoffs affecting it.

---

## 16. Session metadata

- **Date:** 2026-04-14
- **Duration:** ~8 hours of concentrated work
- **Device:** Samsung Galaxy S22 Ultra (SM-S908U1), OneUI Android 16
- **Network tested:** Mint Mobile 4G/LTE, Saily eSIM 5G/LTE, home Wi-Fi
- **Server:** K1 (Ryzen 5800X, 32GB RAM, Tailscale 100.69.1.78)
- **VPS:** RackNerd 192.3.84.103 (Debian, nginx, certbot, Tailscale 100.73.251.12)
- **Languages:** Kotlin (Android), Python (server + CLI), HTML/CSS (web), Bash (shell
  scripts), Gradle Kotlin DSL (build)
- **Developers:** Kyle (architecture, product decisions, testing) + Claude (implementation,
  research, execution)

---

*This document was written during the session that produced v0.3.1. Future agents: append to
this log, don't rewrite it. The historical record is the point — you need to see what was
tried, what was dropped, and why, not just what currently exists.*
