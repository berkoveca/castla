# Stability investigation: phone reboots and cloudflared drops

This document ranks what in Castla could **reboot the phone** or **kill or drop the
cloudflared tunnel**. It then explains how to read the diagnostic report that
**Settings → Logs → Copy Recent** now produces. None of the suspects below is fixed
in the logging change. They are hypotheses, and the new logs are built to confirm or
rule out each one.

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
(`MirrorForegroundService.kt:409`). `onPhoneScreenOff()` asks `ScreenOffPolicy` what
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

**Suggested fix (not done):** only turn the panel off from the explicit UI button.
On `ACTION_SCREEN_OFF`, use the keep-alive path, or do nothing.

### R2: thermal shutdown

Since the switch to tunnel-only mode, every video frame is encrypted by cloudflared
(userspace QUIC/TLS in Go) and sent over the phone's uplink. That is on top of the
hardware encoder, the virtual display and optional audio capture. Add a car
windshield and charging, and the framework will eventually call `shutdown()` at
`THERMAL_STATUS_SHUTDOWN`. The existing mitigation (`ThermalMitigationPolicy`) only
reacts to the SoC thermal status and headroom. It does not see battery temperature or
radio heat, and until now none of this was persisted.

**What the new logs show:** a 60 s `Health` heartbeat line with
`thermal=… headroom10s=… batteryTemp=…C charging=…`. Every thermal status change and
mitigation step is logged. After a reboot, the post-mortem boot reason reads
`shutdown,thermal` and classifies it as `THERMAL`, and the "Reboot check" section
shows the last heartbeat from before the reboot.

### R3: virtual-display churn from tunnel blips

`DisconnectPolicy.DEFAULT_GRACE_MS` is 3 s (`DisconnectPolicy.kt:14`). If the browser
is gone for longer, `onBrowserDisconnected()` releases the encoders and the virtual
display, and the next reconnect recreates them and relaunches the app on the VD. A
cloudflared reconnect (process restart, then edge registration, then browser
reconnect) takes longer than 3 s. With T2 making drops frequent, a flapping tunnel
becomes a VD create/destroy loop. The code's own watchdog comments already name "VD
thrash" as a reboot risk for surfaceflinger and system_server.

**What the new logs show:** `Browser gone — grace window …`, `Tearing down VD
session`, `→ createVirtualDisplay`, `→ release primary VD`, plus the existing
`WS_CONNECTED`, `SOCKET_CLOSED` and `VD_CREATED` events. Count them per minute before
a reboot.

**Suggested fix (not done):** a much longer grace period (for example 20–30 s) in
tunnel mode.

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

**Suggested fix (not done):** keep the token in a field, and synchronize the maps.

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

The tunnel runs with `--ha-connections 1` (`CloudflareTunnelManager.kt:567`) and the
default protocol, which is QUIC over UDP. Mobile carriers and phone hotspots time out
idle UDP NAT mappings aggressively. With only one connection, any QUIC hiccup takes
the site down until cloudflared reconnects
([example of QUIC drops fixed with http2](https://github.com/Themis128/cloudless.gr/pull/1873),
[Cloudflare tunnel troubleshooting](https://developers.cloudflare.com/tunnel/troubleshooting/)).

**What the new logs show:** cloudflared's own WRN/ERR lines are now persisted (for
example `Failed to dial a quic connection`, `timeout: no recent network activity`,
`Unregistered tunnel connection`), rate-limited and with the token redacted.

**Suggested fix (not done):** add `--protocol http2`, and use `--ha-connections 2`.

### T3: which binary actually ships

- `android.yml` (debug artifact) bundles the **Termux bionic** build, which resolves
  DNS through netd.
- `release.yml` (signed release) bundles the **static upstream**
  `cloudflared-linux-arm64` (`release.yml:134`). That build reads `/etc/resolv.conf`,
  which Android does not have. This is the exact problem commit `79d4eb3` fixed, but
  only in `android.yml`. A binary not built for Android can also hit Android's seccomp
  filter (SIGSYS, exit 159).

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
