# Stability investigation: phone reboots and cloudflared drops

This document ranks what in Castla could **reboot the phone** or **kill or drop the
cloudflared tunnel**. It then explains how to read the diagnostic report that
**Settings → Logs → Copy Recent** now produces. The suspects are hypotheses, and the
new logs are built to confirm or rule out each one. R1, R3, R4, T2 and T3 have since
been mitigated in code (see the **Status** lines). R2 and T1 are reduced but not
eliminated.

## TL;DR: most likely causes

| # | Symptom | Suspect | Confidence |
|---|---------|---------|------------|
| R1 | Phone reboots | Panel power is forced off with `SurfaceControl.setDisplayPowerMode` on **every power-button screen-off**, behind system_server's back | High |
| R2 | Phone reboots / powers off | Thermal shutdown: H.264 encode, userspace QUIC/TLS in cloudflared and the cellular/hotspot radio all run hot, often in a hot car while charging | High |
| R3 | Phone reboots | Virtual-display churn: every tunnel blip longer than 3 s tears down and rebuilds the VD and relaunches apps on it | Medium |
| R4 | Phone reboots after an app crash | Orphaned-VD protection is weaker than it looks: the death token is not held, and the VD maps are not thread-safe | Low–medium |
| T1 | Tunnel dies | cloudflared is a **child process of the app**, so it is frozen or killed whenever the app is cached, frozen, low-memory-killed or phantom-killed | High |
| T2 | Tunnel drops / flaps | QUIC over mobile/hotspot NAT combined with `--ha-connections 1` (a single edge connection) | High |
| T3 | Tunnel never connects (release builds) | `release.yml` ships the **static upstream** `cloudflared-linux-arm64` binary, not the bionic Termux build that `android.yml` uses | Medium (depends on which APK you run) |

## Phone reboots

### R1: panel-off on every power-button press (most likely)

`MirrorForegroundService` registers for `ACTION_SCREEN_OFF`
(`MirrorForegroundService.kt`). `onPhoneScreenOff()` asks `ScreenOffPolicy` what
to do (`ScreenOffPolicy.kt:57`). Because `isPanelOffSupported` defaults to `true`,
the answer is `TURN_PANEL_OFF`. The privileged service then calls
`SurfaceControl.setDisplayPowerMode(token, OFF)` (`PrivilegedService.kt:915`). On
`ACTION_SCREEN_ON` it calls the same method with `NORMAL`.

So on every screen-off during a session, the app writes the panel power mode straight
to SurfaceFlinger. At that moment system_server's DisplayPowerController is itself
moving the display to OFF, DOZE or DOZE_SUSPEND (AOD on Samsung). Two writers now
disagree about the panel state. SurfaceFlinger or the vendor HWC can crash on the
unexpected transition, which restarts the framework (a "soft reboot" with the boot
animation). A stuck display pipeline can also trip the system_server watchdog, which
does a full reboot. On API 34+ the same path loads `libandroid_servers.so` into the
shell process (`PrivilegedService.kt:970`). The comment on `turnPanelOffForMirroring()`
says this should only run from the UI button, but the screen-off receiver runs it too.

scrcpy uses the same API, and its issue tracker has device crash, freeze and reboot
reports when the display is turned off
([#5379](https://github.com/Genymobile/scrcpy/issues/5379),
[#875](https://github.com/Genymobile/scrcpy/issues/875),
[#909](https://github.com/Genymobile/scrcpy/issues/909)). scrcpy only does this from
an explicit user action, never in reaction to the system's own screen-off.

**What the new logs show:** a durable `→ SurfaceControl panel power OFF` line is
fsync'ed before the call and a `← panel power OFF call returned` line after it. If the
last line before a reboot is the `→` line, R1 is confirmed. The post-mortem section
then shows `SYSTEM_TOMBSTONE` for `surfaceflinger`, or a `system_server_watchdog`
record.

**Status: fixed.** `ScreenOffPolicy.onSystemScreenOff()` always takes the keep-alive
path. Panel-off only happens from the explicit Screen Off button.

### R2: thermal shutdown

Since the switch to tunnel-only mode, every video frame is encrypted by cloudflared
(userspace QUIC/TLS in Go) and sent over the phone's uplink. That is on top of the
hardware encoder, the virtual display and optional audio capture. Add a car
windshield and charging, and the framework will eventually call `shutdown()` at
`THERMAL_STATUS_SHUTDOWN`. The existing mitigation (`ThermalMitigationPolicy`) only
reacts to the SoC thermal status and headroom. It does not see battery temperature or
radio heat, and until now none of this was persisted.

**Status: reduced.** Auto quality now stays at 30 fps and at most 960p. The stream
bitrate is capped at 4.5 Mbps. Keyframes come every 2 s instead of every 1 s.

**What the new logs show:** a 60 s `Health` heartbeat line with
`thermal=… headroom10s=… batteryTemp=…C charging=…`. Every thermal status change and
mitigation step is logged. After a reboot, the post-mortem boot reason reads
`shutdown,thermal` and classifies it as `THERMAL`, and the "Reboot check" section
shows the last heartbeat from before the reboot.

### R3: virtual-display churn from tunnel blips

`DisconnectPolicy.DEFAULT_GRACE_MS` was 3 s. If the browser
was gone for longer, `onBrowserDisconnected()` releases the encoders and the virtual
display, and the next reconnect recreates them and relaunches the app on the VD. A
cloudflared reconnect (process restart, then edge registration, then browser
reconnect) takes longer than 3 s. With T2 making drops frequent, a flapping tunnel
becomes a VD create/destroy loop. The code's own watchdog comments already name "VD
thrash" as a reboot risk for surfaceflinger and system_server.

**What the new logs show:** `Browser gone — grace window …`, `Tearing down VD
session`, `→ createVirtualDisplay`, `→ release primary VD`, plus the existing
`WS_CONNECTED`, `SOCKET_CLOSED` and `VD_CREATED` events. Count them per minute before
a reboot.

**Status: fixed.** The grace period is now 20 s (30 s with the screen off), and the
browser's stall watchdog is 8 s instead of 4 s.

### R4: orphaned-VD safety net

- `registerDeathToken()` calls `linkToDeath` on the passed binder but does not keep a
  reference to it (`PrivilegedService.kt:870`). If that proxy is garbage-collected,
  the death recipient silently goes away with it
  ([background](https://www.androiddesignpatterns.com/2013/08/binders-death-recipients.html)).
  Shizuku's `daemon(false)` user-service teardown is the remaining backstop.
- `virtualDisplays` and `virtualDisplayNames` are plain `mutableMapOf`
  (`PrivilegedService.kt:46`), but binder calls arrive on a thread pool. A concurrent
  create and release (primary plus split) can lose track of a display.
- `destroy()` always calls `setPhysicalDisplayPower(true)`, even when the panel was
  never turned off. That runs the risky token-resolution path during teardown.

**Status: fixed.** The token is kept in a field, the VD map operations are
`@Synchronized`, and `destroy()` only restores the panel if the app turned it off.

## cloudflared tunnel drops

### T1: cloudflared lives and dies with the app process

cloudflared is spawned with `ProcessBuilder`, so it sits in the app's process group
and cgroup. That has three consequences:

- **App killed → tunnel killed.** When Android kills the app process (LMK, "force
  stop", crash), it kills the whole process group. No exit line is logged, because the
  logger dies too.
- **App cached → tunnel frozen.** After a session ends, the tunnel is kept for 75 s
  (`TUNNEL_ORPHAN_TIMEOUT_MS`) and for up to 180 s idle **without a foreground
  service**. A cached app is frozen by the cached-apps freezer, and the freezer works
  on the cgroup, so cloudflared is frozen with it
  ([AOSP: cached apps freezer](https://source.android.com/docs/core/perf/cached-apps-freezer)).
  The edge then times the connection out.
- **Phantom-process killer (Android 12+).** Child processes are killed when there are
  more than 32 across the device, or for excessive CPU use while the parent app is in
  the background
  ([AOSP issue 205156966](https://issuetracker.google.com/issues/205156966),
  [detailed write-up](https://github.com/agnostic-apollo/Android-Docs/blob/master/en/docs/apps/processes/phantom-cached-and-empty-processes.md),
  [Termux #2366](https://github.com/termux/termux-app/issues/2366)).

**What the new logs show:** each cloudflared exit is logged as
`cloudflared exited (named): exit=137 (SIGKILL: killed by Android …) after 812s
appImportance=CACHED fgService=false`. `CACHED` together with SIGKILL means the app
was in the background when it died. Android's own exit records (API 30+) appear under
"Previous app exits" (`LOW_MEMORY`, `FREEZER`, `EXCESSIVE_RESOURCE_USAGE`, …). The
post-mortem shows `settings_enable_monitor_phantom_procs` and `max_phantom_processes`.
Gaps between `Health` heartbeats mean the process was frozen.

### T2: QUIC plus a single edge connection

The tunnel runs with `--ha-connections 1` (now built by `CloudflaredArgs.kt`) and the
default protocol, which is QUIC over UDP. Mobile carriers and phone hotspots time out
idle UDP NAT mappings aggressively. With only one connection, any QUIC hiccup takes
the site down until cloudflared reconnects
([example of QUIC drops fixed with http2](https://github.com/Themis128/cloudless.gr/pull/1873),
[Cloudflare tunnel troubleshooting](https://developers.cloudflare.com/tunnel/troubleshooting/)).

**What the new logs show:** cloudflared's own WRN/ERR lines are now persisted (for
example `Failed to dial a quic connection`, `timeout: no recent network activity`,
`Unregistered tunnel connection`), rate-limited and with the token redacted.

**Status: fixed.** cloudflared now runs with `--protocol http2 --ha-connections 2`
(`CloudflaredArgs`).

### T3: which binary actually ships

- `android.yml` (debug artifact) bundles the **Termux bionic** build, which resolves
  DNS through netd.
- `release.yml` (signed release) bundles the **static upstream**
  `cloudflared-linux-arm64` (`release.yml:134`). That build reads `/etc/resolv.conf`,
  which Android does not have. This is the exact problem commit `79d4eb3` fixed, but
  only in `android.yml`. A binary not built for Android can also hit Android's seccomp
  filter (SIGSYS, exit 159).

**Status: fixed.** `release.yml` now bundles the same Termux build as `android.yml`.

**What the new logs show:** on the first start of each app process:
`cloudflared binary: size=…KB build=bionic (interp=/system/bin/linker64 …) version="…"`
or `build=static (…)`.

## Reading the "Copy Recent" report

The clipboard payload has sections, then up to about 60,000 characters of the newest
log, drawn from both log files.

| Section | What it answers |
|---------|-----------------|
| App / device | Build, device, Android version, uptime, boot count |
| Health now | Thermal status and headroom, battery temperature and charging, memory, app RSS, tunnel state and cloudflared RSS |
| Mirroring session | Whether the service is running, panel state, tunnel error |
| Reboot check | `REBOOTED_DURING_SESSION`, `PROCESS_DIED_DURING_SESSION`, `REBOOTED`, `SAME_BOOT`, plus the previous run's **last heartbeat** |
| Previous app exits | Android's `ApplicationExitInfo` records (why the process died) |
| Post-mortem | `sys.boot.reason` classified (THERMAL, KERNEL_PANIC, WATCHDOG, …), DropBox system_server crash, watchdog and tombstone records, filtered last kernel log, phantom-killer settings. Needs Shizuku to have connected once in this app run |
| Cloudflare tunnel | State, restarts, drops in the last 60 s, cloudflared PIDs (warns if there are two), last exit decoded, stderr tail |

### Decision table after a reboot

| Evidence | Likely cause |
|----------|--------------|
| Boot reason `THERMAL`, heartbeats show rising `batteryTemp` or `SEVERE`/`CRITICAL` | R2 thermal |
| Last log line is `→ SurfaceControl panel power …` with no `←` line; tombstone for `surfaceflinger` or `system_server_watchdog` | R1 panel power |
| `system_server_crash` detail mentioning display/window/VirtualDisplay, many VD create/release lines before it | R3 or R4 VD churn / orphan |
| Boot reason `KERNEL_PANIC` / `HARDWARE_RESET`, kmsg shows a GPU, encoder or display driver | Vendor driver bug hit by the encoder/VD load |
| `PROCESS_DIED_DURING_SESSION` with `LOW_MEMORY` or `FREEZER` | App killed, not a phone reboot; the tunnel went with it (T1) |

Log lines that must survive a hard reboot (W/E level, heartbeats, and breadcrumbs
before VD and panel calls) are fsync'ed. Everything else can lose its last few
seconds when the phone reboots.

## Phone soft-reboot: HOME key on a released virtual display (confirmed)

Field report (Galaxy S20 Ultra, Android 13): `system_server_crash` followed by `SYSTEM_RESTART`:

```
NullPointerException: ... WindowContainer.reduceOnAllTaskDisplayAreas(...) on a null object reference
  at RootWindowContainer.startHomeOnDisplay(RootWindowContainer.java:1735)
  at PhoneWindowManager.handleShortPressOnHome(...)
  at PhoneWindowManager$DisplayHomeButtonHandler...
```

AOSP 13 source confirms both halves of the bug:

- `PhoneWindowManager` creates a `DisplayHomeButtonHandler` for **any** display id a HOME
  key carries and runs the home action later on its handler (`post`, or `postDelayed` by the
  double-tap timeout). It never checks that the display still exists.
- `RootWindowContainer.startHomeOnDisplay()` calls `getDisplayContent(displayId)` and
  dereferences it with no null check.

So a HOME keyevent (`input -d N keyevent 3`) crashes system_server if display N is gone
either before the key is sent (stale id) or before the posted handler runs (release race).
Castla sends HOME from go-home and from display cleanup on disconnect, while viewport
rebuilds release/recreate displays on other threads — the field crash was HOME to VD 25
followed a few ms later by VD 25's release for a resize.

**Status: fixed.** `PrivilegedService` checks and sends every HOME under the same lock as
display release; HOME to a display that is not live is dropped, and a release of a display
that just got HOME waits 1 s (`HomeKeyGuard`, unit-tested). Post-mortem now also captures
`system_server_wtf` and `SYSTEM_RESTART` bodies.

## Reading a diagnostic report after a soft reboot

| Line prefix | Source | What to look for |
|---|---|---|
| `SysCall: → #n …` / `← #n … Xms` | every call into system_server via Shizuku | the last `→` with no `←` is the call in flight when system_server died; `SLOW` = system_server busy; `✗` = the call threw |
| `SysCall: touch DOWN/UP/MOVE` | injected input (system_server input dispatcher) | gesture in progress at the crash |
| `SysEvent: display added/removed/changed` | DisplayManager as system_server reports it | our VD removed without a Castla release; state flips |
| `SysEvent: main thread blocked` | app watchdog | overload, or a binder call stuck in system_server |
| `Health: … stream[…] … load=… appCpu=…` | 15 s heartbeat while mirroring | trends: load avg vs ~8 cores, CPU, threads/fds growth, fps/kbps, `maxSend` (network backpressure) |
| `Page: car→ …` | control messages from the car page | the driver's last actions |
| `client: …` (W) | page-side failures | decoder errors, stalls, reconnects, launch timeouts, JS errors |
| `VideoEncoder:` | MediaCodec | codec name, negotiated profile/level, codec errors |
| Post-mortem: `latest native crash`, `Android crash log` | DropBox + `logcat -b crash` | thread name, abort message and backtrace of a system_server SIGABRT |

Overload vs. bug: an overloaded/hung system_server is killed by its Watchdog (`system_server_watchdog`
in DropBox, `Watchdog` lines in the crash/system log). A `SIGABRT` with an abort message is a
deliberate abort on a failed check — a bug path, usually triggered by the last system call.

## Phone soft-reboot: InputDispatcher abort on injected touch (confirmed)

Crash buffer after two field soft-reboots (10:17 and 10:52, 2026-09-26):

```
Fatal signal 6 (SIGABRT) in tid (InputDispatcher), pid (system_server)
#01 libinputflinger.so InputTarget::addPointers(BitSet32, Transform)
#02 InputDispatcher::addWindowTargetLocked
#03 InputDispatcher::findTouchedWindowTargetsLocked
#04 InputDispatcher::dispatchMotionLocked
```

Both times the abort came milliseconds after Castla injected an `ACTION_DOWN` (SysCall trace:
`touch DOWN d=11 id=17` at 10:17:29.592, fatal signal at 10:17:29.595); the next injection then
blocked ~2 s while system_server died. Load, memory and temperature were normal.

The injected stream differed from a real touchscreen in everything that code path checks:
pointer ids climbed per tap (0…31) instead of restarting at 0, `downTime` was "now" on every
event, additional fingers arrived as unrelated single-pointer `ACTION_DOWN`s, and lost UPs were
covered with synthetic downs.

**Status: fixed.** `TouchStream` (unit-tested) produces hardware/scrcpy-shaped events — lowest
free pointer id (single tap = id 0), all active pointers per event with `ACTION_POINTER_DOWN/UP`
+ index, one `downTime` per gesture, unknown moves/ups dropped, stale gestures cancelled — and
`PrivilegedService.injectMotionEvent` injects them with finger tool type and pressure 0 on lift.
