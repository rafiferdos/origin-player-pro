# Origin Player Pro — Plan: Chinese ROM, OriginOS 7, lower battery use

Date: 2026-10-30. Status: Phase 1 implemented 2026-10-30 (uncommitted, not
yet device-tested — see below). Phases 2–5 are still plan only.

## Phase 1 — implemented 2026-10-30 (needs device testing)

- Adaptive poll in `MediaProbeListener`: 5 s while playing, 30 s while
  paused, stopped entirely with no tracked session, and stopped when the
  screen is off and nothing is playing. Callbacks and notification events
  restart the poll via `ensurePolling()`.
- MainActivity preview is now event-driven: `MediaProbeListener.onMirrorChanged`
  (fired from `syncSession`/`clearSession`) plus `onResume`, instead of a
  1 s timer. Preview art is cached by key and downscaled to 512 px for the
  small preview only; the mirrored session's art for OriginPlayer is untouched.
- `SuperXGrant` is cached: one attempt per process, and a successful grant
  remembered for the boot. Informational logs moved from `Log.e` to `Log.d`.
- Mirrored metadata is only pushed when its key (title/artist/album/art
  identity) changes, instead of on every state-only sync.
- Delayed second silent blip is now cancellable in `clearSession`, and the
  blip debounce is reset on clear, so a dead session cannot be re-announced.
- `RebindHelper` throttle: 10 s → 60 s, wall clock → `elapsedRealtime`,
  persisted across process deaths (with a reboot-stale guard).
- `MediaHistory`: capped at 50 apps / 30 days, uninstalled apps pruned on
  the ignored-apps page load, and a "Clear history" action added (English).
- SuperX scene spellings left unchanged (see §6 Phase 1 item 4).

Device tests still required before Phase 1 can be called done (see §7):
play/pause/skip latency, tap-through, call/car suppression, ignored-app
immediacy, Firefox-like source, and a 24 h idle + 1 h playback battery
comparison against the pre-Phase-1 build. No Android SDK was available in
the implementation environment, so a Gradle build has not been run yet —
run `./gradlew :app:assembleDebug` on a machine with the Android SDK
before installing.

## 1. Goal, in one line

Make Origin Player Pro reliable on a **Chinese ROM running OriginOS 7**, with
**lower battery consumption and no loss of mirroring functionality**, while
keeping the UI in **English**.

## 2. Fixed constraints (do not trade these away)

- **ROM focus: Chinese ROM.** Global-ROM behaviour is secondary. A fix that
  only helps the global ROM does not count as done.
- **UI language: English, always.** The user runs a Chinese phone but is
  Bangladeshi and does not read Chinese. Do NOT add a Chinese UI, and do NOT
  replace English strings with Chinese. If a vivo system screen is in Chinese,
  the app's own guide must explain that screen in English, with screenshots /
  step numbers, not by switching the app to Chinese.
- **OS priority: OriginOS 7.** OriginOS 6 behaviour is a fallback, not the
  target. OriginOS 7 is Android 17 based (see §4).
- **Functionality that must not regress** (the guardrail list in §7). Battery
  savings that break any of these are a failed change, even if the battery
  number looks better.
- **Spoofed `applicationId` stays.** JOOX / Luna identities — and any future
  Chinese identity — are load-bearing for OriginPlayer. Display-name renames
  never touch `applicationId`. See `docs/DEV.md`.
- **Personal sideload, not Play Store.** The spoofed identity cannot ship as
  if it were the real app. No change may assume Play Store distribution,
  Play policy approval, or a Play Console foreground-service declaration.

## 3. Where the battery goes today (measured from the code, 2026-10-30)

These are the current, verified constants — not estimates:

| Hotspot | Today | Why it costs battery |
|---|---|---|
| Session poll, `MediaProbeListener` | every 1,000 ms, forever, on the main looper | Wakes the process, queries `MediaSessionManager`, reads ignored-apps prefs, and re-evaluates the tracked session even when nothing is playing and the screen is off. |
| Live preview, `MainActivity` | every 1,000 ms while the activity is resumed | A second 1 s loop, duplicating the listener's work, plus metadata/bitmap reads on the UI thread. |
| Keep-alive health check, `KeepAliveService` | `AlarmManager.setAndAllowWhileIdle`, `RTC_WAKEUP`, every 5 min | Wakes the device out of idle every 5 minutes even on days with no music at all. In Doze this interval is not even honoured as written, so the cost is paid without the promised reliability. |
| Foreground service, `KeepAliveService` | always on, `specialUse`, `stopWithTask=false`, `START_STICKY`, plus a 1 s restart alarm on task removal | Three overlapping keep-alive mechanisms. The service has no idle-stop: it runs even when notification access is off or nothing has played for days. |
| Silent blip, `MediaProbeListener.syncSession` | 2 × 800 ms silent `AudioTrack` bursts on every transition into playing, second burst posted 1,200 ms later with no cancellation | Creates a new `AudioTrack` per burst, registers as active audio output, and the delayed second burst still fires after the session is cleared, a call starts, or the app changes. |
| SuperX grant, `SuperXGrant` | reflection call on every `MainActivity.onCreate`, `KeepAliveService.onCreate`, and listener connect | Repeated hidden-API reflection with no success cache and no result check. |
| Rebind toggle, `RebindHelper` | component DISABLE → ENABLE on every app open and every health-check tick when the listener instance is null, throttled only 10 s per process | Unnecessary unbind/rebind churn while the system is still binding, and the throttle resets on process death. |
| Album art / metadata | full-resolution bitmap copied on every `setMetadata`, and re-set on every 1 s preview refresh with no change check | Memory churn and binder work for art that has not changed. |
| Media history | throttled to 60 s, but never pruned | Unbounded `SharedPreferences` growth over months; also records listening history indefinitely with no expiry or clear option. |

What is already good, and should be kept: no explicit wake lock is held;
the blip deliberately takes no audio focus; call and car-mode suppression
exists; the ignored-apps filter is applied before tracking.

## 4. OriginOS 7 facts that shape the plan

- OriginOS 7 is Android 17 based, launched in China on 16 September 2026,
  with China stable rollout from 9 October 2026. The current project is
  `compileSdk = 36`, `targetSdk = 36`, `minSdk = 34`, AGP 8.9.2,
  Kotlin 2.0.21. It therefore runs on OriginOS 7 only in compatibility mode,
  and none of its OriginOS 7 behaviour (redirect packages, widget surfaces,
  background rules) has been verified on a device.
- The redirect allow-list is hard-coded from OriginOS 6 observations:
  `com.vivo.musicwidgetmix`, `com.vivo.systemuiplugin`,
  `com.android.systemui`, plus the `vivomusicmix` launch extra. Any rename
  in OriginOS 7 breaks tap-through silently — there is no fallback or
  diagnostic today.
- Leaks have suggested the OriginOS 7 OriginPlayer is no longer limited to
  one app as a full-screen lockscreen player. That is unverified. It must be
  tested on a real Chinese OriginOS 7 device before the blip or the spoof is
  assumed unnecessary. Do not remove the bridge on the basis of a leak.
- Android 17 hardens background audio and foreground services. A service
  that relies on `specialUse` plus a 5-minute wakeup alarm is exactly the
  pattern most likely to be throttled by vivo's Chinese-ROM power manager
  (iManager / background power management), so "run harder" is not a viable
  strategy on this ROM. The plan below moves the other way: run less, and
  only when music is actually involved.

## 5. Battery principles (in priority order)

1. **Event-driven first, polling last.** `MediaController.Callback`,
   `OnActiveSessionsChangedListener`, `onNotificationPosted/Removed`, and
   car/call broadcasts already exist. Polling is a safety net, not the
   engine. If a callback already tells us the state changed, do not poll
   to learn the same thing again.
2. **No work when nothing is playing.** Idle cost must tend to zero:
   no 1 s loop, no 5-minute wakeup, no blip, no repeated grant, when there
   is no active session and no recent playback.
3. **Only wake the device for a real transition.** A health check that
   fires while the phone is idle and nothing is playing is pure cost.
   Wakeups must be tied to playback, a listener disconnect, or a user
   opening the app — not to a wall-clock timer.
4. **Cache, don't recompute.** Grant result, album art, labels, and
   session state are cached and only refreshed when the underlying value
   changes.
5. **Fail visibly, not busily.** When the bridge is dead, show why in the
   app (in English) instead of retrying harder in the background. A user
   who can fix one setting once saves more battery than a service that
   retries every 5 minutes for a week.
6. **Measure before claiming.** No percentage saving is promised in this
   plan. Every phase ends with the measurement in §8, before/after, on the
   same device and ROM.

## 6. Phases

### Phase 0 — Baseline and device truth (do first, blocks nothing else)

- Record the test device: model, Chinese ROM build, OriginOS version,
  Android API level, and the Chinese music apps actually used (QQ Music,
  Kugou, Kuwo, NetEase, etc. — the choice decides §6 Phase 3).
- Record the baseline with the current build: 24 h idle battery use,
  1 h continuous playback battery use, time from pressing play to
  OriginPlayer showing the track, and time from pause to the widget
  clearing. Use `adb dumpsys batterystats` / Battery Historian, not the
  Settings estimate alone.
- On the device, verify the OriginOS 7 facts that the code assumes:
  redirect referrer packages and launch extra, whether the spoofed
  identity still triggers OriginPlayer, and whether the blip is still
  needed (test with blip disabled behind a temporary flag — see Phase 2).
- Deliverable: a one-page baseline note added to this file. No code
  change is merged in Phase 0.

### Phase 1 — Low-risk battery wins (no behaviour change expected)

These are safe to do in any order, each independently revertible:

1. **Adaptive poll, not 1 s always.**
   - Playing: keep a heartbeat, but lengthen it (e.g. 5 s) — callbacks
     already carry state/metadata changes instantly.
   - Paused / no session: back off to 30 s, then stop polling entirely
     after a quiet period and rely on session-changed + notification
     callbacks to wake up again.
   - Screen off and no session: no poll at all.
   - Acceptance: play/pause/skip latency unchanged in the §7 checklist.
2. **Preview follows the listener, not a timer.** Remove the 1 s
   `MainActivity` loop. Refresh the preview on listener callback /
   `onResume` only, plus a slow refresh (e.g. 5 s) only while the
   activity is visible and something is playing.
3. **Cache the SuperX grant.** Call it once per process and once per
   boot at most; remember success/failure in memory, and do not retry
   on every activity/service start. Keep the existing silent-failure
   behaviour for the call itself, but log the outcome once for the
   diagnostics screen in Phase 4.
4. **SuperX scene "typos" — verified, do NOT fix.** `DELIEVERY` and
   `METTING` in `SuperXGrant.kt` were checked 2026-10-30 against
   origin-island-pro's `OriginIslandConstants.kt`, where the identical
   list is ported verbatim. Vivo's system most likely expects these
   exact tokens, so changing them would be a behaviour change, not a
   typo fix. Left unchanged in Phase 1, with a comment in
   `SuperXGrant.kt` explaining why.
5. **Album-art cache and downscale.** Downscale art to a fixed maximum
   (e.g. 512 px) once, cache it by title/artist/album key, and skip
   `setMetadata` / `setImageBitmap` when the key has not changed.
6. **Prune media history.** Cap entries (e.g. 50 apps / 30 days), delete
   entries for uninstalled apps on load, and add a "Clear history" action
   in English. This is also a privacy fix, not just a battery fix.
7. **Rebind throttle done properly.** Use `elapsedRealtime`, not wall
   clock; lengthen the throttle (e.g. 60 s); only toggle when the
   component is actually enabled-but-unbound, not on every resume/tick;
   persist the last-toggle time so a process death does not reset it.

### Phase 2 — Keep-alive redesign (the big battery win, the big risk)

Goal: the bridge is alive when music plays, and costs almost nothing
when it does not.

1. **Foreground service only while it earns its keep.** Start/keep the
   foreground service while a session is tracked or recently played.
   After a quiet period with no session (e.g. 10–15 min, tunable), stop
   the foreground service and cancel its health alarm. The
   `NotificationListenerService` itself is system-managed and does not
   depend on this service to receive the next notification — verify this
   claim on the Chinese OriginOS 7 device in Phase 0 before relying on it.
2. **Adaptive health check, replacing the fixed 5-minute alarm.**
   - While playing: no health alarm needed; callbacks are live.
   - While a session is tracked but paused: a slow check (e.g. 15 min).
   - While idle: no periodic alarm at all; restart is triggered by
     app open, boot, or the next media notification.
   - Replace `RTC_WAKEUP` + `setAndAllowWhileIdle` with an inexact,
     non-wakeup alarm where the check can wait for the next natural
     wakeup. Never use an exact alarm for a health check.
3. **Fix the stop-then-reschedule bug.** If `startForeground` fails,
   `stopSelf()` must also cancel the health alarm. A dead service must
   not keep scheduling itself.
4. **Fix the blip lifecycle.** Cancel the delayed second burst in
   `clearSession()` and on call/car suppression. Keep the 2,500 ms
   burst debounce, and reset it when the tracked controller is cleared
   so a genuine resume is not swallowed by the old controller's window.
   Test a single, shorter blip (e.g. 300–500 ms) and, separately, a
   no-blip build on OriginOS 7 — keep whichever passes §7, and gate the
   choice behind a preference so it can be flipped without a rebuild
   during testing.
5. **Correct the foreground-service type question.** Evaluate
   `mediaPlayback` vs the current `specialUse` against what the service
   actually does (bridging a media session, not playing audio itself)
   and against Android 17 / OriginOS 7 behaviour on the test device.
   Do not change the type on theory alone; the wrong type is worse than
   the current one if OriginPlayer keys off it.
6. **Add a boot receiver — but a quiet one.** A `BOOT_COMPLETED`
   receiver that only re-registers the listener state and does NOT
   start a permanent foreground service or a 5-minute alarm. Today a
   reboot leaves the bridge dead until the user opens the app; the fix
   is a boot-time check, not a boot-time permanent service.

### Phase 3 — Chinese ROM and OriginOS 7 correctness

Battery work is wasted if the app is running the wrong identity on the
wrong ROM, so this phase runs in parallel with Phase 2:

1. **Restore a Chinese identity.** Re-add the Kugou identity
   (`com.kugou.android.lite`, the originally proven one) as a flavour,
   and evaluate a QQ Music flavour (`com.tencent.qqmusic`) only after
   testing which identities OriginOS 7 China actually whitelists.
   Keep JOOX/Luna as global flavours; do not delete them for a Chinese
   build. Each flavour must be tested on the device — whitelist status
   is not deducible from the global ROM or from documentation.
2. **Wrong-flavour warning, in English.** At startup, detect the ROM
   type as far as Android allows (system properties / locale / installed
   system apps are hints, not proof) and, if the installed flavour is
   the global one on a Chinese ROM, show a plain-English warning:
   "This build is for the global ROM. Install the Chinese build."
   Never auto-switch flavours — the `applicationId` differs, so a
   switch is a reinstall, and the user must choose it knowingly,
   including that the real app of the same package cannot coexist.
3. **OriginOS 7 redirect verification.** Log (locally, on-device only)
   the referrer and launch extra for widget, lockscreen, status-bar,
   and control-panel taps on OriginOS 7, and update
   `REDIRECT_SOURCE_PACKAGES` / `REDIRECT_LAUNCH_FROM_VALUES` from
   observed values — not from guesses. Add a fallback: if no redirect
   target can be found, show the bridge UI with the real app's name and
   a tap target, instead of silently staying put.
4. **SDK upgrade, in its own step.** Move `compileSdk`/`targetSdk` from
   36 toward the Android 17 SDK, with AGP/Kotlin updates as required,
   and handle the Android 17 foreground-service timeout
   (`onTimeout`), edge-to-edge, and background-audio changes. This is
   deliberately after Phases 1–2: upgrading the SDK and redesigning
   keep-alive in the same change makes it impossible to tell which one
   broke something.
5. **vivo Chinese-ROM setup guide, in English.** In the app, replace
   the generic battery-optimisation row with a vivo-specific checklist
   in English: notification access, unrestricted background, auto-start
   (where vivo exposes it), lock the app in recents, and "do not put
   this app to sleep". Where vivo does not expose a deep link, the
   guide gives the English path through iManager / Settings with step
   numbers. This guide is documentation, not a permission hack — the
   app must not try to bypass vivo's power manager.

### Phase 4 — Diagnostics, safety, and the remaining fixes

1. **Diagnostics screen (English, on-device only).** Show: listener
   connected or not, last session seen, last blip, last grant result,
   last rebind, health-check state, battery optimisation state, and
   installed flavour vs detected ROM hint. No data leaves the device.
   This replaces blind background retrying with a fixable state.
2. **Thread-safety and session-state fixes.** Make the tracked
   controller state safe across main/binder/notification threads;
   stop mirroring a merely paused session as the fallback pick (paused
   must not become the tracked session when nothing is playing);
   shorten or rework the 3-minute unknown-state assumption so a stopped
   Firefox-like source clears promptly; do not copy source playback
   actions the mirrored session cannot honour, and add the missing
   media-button keys or drop them explicitly with a comment.
3. **Media-button de-duplication.** Resolve whether the session
   callback and `MediaButtonReceiver` double-deliver play/pause, and
   keep exactly one path. Test with a headset button and with
   OriginPlayer controls, not by reading the code alone.
4. **Media-type filter.** Do not mirror short-video / video sessions as
   music when a music session is also available. Start with a
   conservative rule (prefer a session whose app is a known music app
   or whose metadata looks like a track, and never let a video session
   steal a playing music session) and test against the user's actual
   apps, including Douyin/Bilibili-style apps if the user uses them.
5. **A stop switch.** Add an English on/off for the bridge in the app.
   Off must stop the foreground service, cancel alarms, and clear the
   mirrored session. A user who can turn the bridge off will not need
   to force-stop it, which is what currently breaks the listener until
   the next manual rebind.
6. **Release hygiene.** Re-enable minification/R8 evaluation (it is
   currently off), remove `Log.e` diagnostics from release builds,
   validate `keystore.properties` values before building so a missing
   value fails with a clear message instead of an obscure one, and add
   at least a build CI job — the current repo has no tests and no build
   CI, only a release-checksum workflow.

### Phase 5 — Release and follow-up

- Bump `versionCode`/`versionName` for the Chinese OriginOS 7 build only
  after Phases 1–4 pass §7 on the real device. Do not bundle an
  untested SDK upgrade and an untested flavour into the same release.
- Produce the signed APK + `.sha256` through `scripts/release.sh`
  (now producing `origin-player-pro-*` names), verify the certificate
  fingerprint against the published one, and upload both files to the
  same GitHub Release.
- After release, watch for exactly one week of real use: idle drain,
  play-to-widget latency, and any vivo "high power consumption" warning.
  If vivo flags the app, the next change is a battery change, not a
  feature — see §5 principle 5.

## 7. Functionality guardrails — every phase must pass all of these

Test on the Chinese OriginOS 7 device, in English, with the user's
actual music apps:

- [ ] Pressing play in a music app shows the track in OriginPlayer
      within a few seconds, with correct title, artist, and art.
- [ ] Pause clears or pauses the widget promptly; it does not stay
      "playing" for minutes after the music stopped.
- [ ] Play/pause, next, and previous from OriginPlayer reach the real
      app, exactly once each.
- [ ] Tapping the widget/lockscreen/status-bar player opens the real
      app, not the bridge UI, on OriginOS 7 surfaces.
- [ ] Switching from one music app to another does not flicker between
      the two, and does not stick on the old app.
- [ ] A phone/VoIP/conference call suppresses mirroring, and mirroring
      resumes correctly after the call ends.
- [ ] Android Auto / car mode still suppresses the duplicate player.
- [ ] Ignoring an app takes effect immediately; un-ignoring restores it.
- [ ] A Firefox-like source with no `PlaybackState` still appears, and
      clears when it is actually finished.
- [ ] After a reboot, the bridge recovers without the user having to
      remember a manual step (Phase 2 boot receiver), and without a
      permanent service running on a music-free day.
- [ ] The app UI, setup guide, diagnostics, and warnings are all in
      English, even on a Chinese ROM.

## 8. How battery will be measured (so "better" means something)

- Same device, same ROM build, same music app, same volume/network
  conditions for before/after runs. Do not compare across devices.
- 24 h idle: phone left with the bridge installed, no music played.
  Record `batterystats` attributed to the app's UID and the number of
  wakeups/alarms.
- 1 h playback: same playlist, screen off for most of the hour.
  Record app UID consumption and play-to-widget latency at the start,
  middle, and end.
- Report three numbers per phase: idle consumption, playback
  consumption, and worst-case latency. If idle consumption falls but
  latency rises beyond the §7 checklist, the phase is not accepted.

## 9. Risks and what to do about them

- **vivo kills the bridge anyway.** Mitigation: the English vivo setup
  guide, a diagnostics screen, and a boot receiver — not a faster
  retry loop, which vivo's power manager treats as misbehaviour.
- **OriginOS 7 changed the whitelist or redirect packages.**
  Mitigation: Phase 0 device verification before any code is merged
  on that assumption; a wrong-flavour warning so the failure is
  visible instead of silent.
- **Removing the blip breaks OriginPlayer on OriginOS 7.**
  Mitigation: test blip on/off as a flag on the real device; never
  remove it on the basis of a leak or a global-ROM result.
- **SDK 37 upgrade breaks the build or the hidden SuperX call.**
  Mitigation: SDK upgrade is its own phase, after the battery phases,
  with a rollback to the SDK 36 build.
- **A Chinese flavour conflicts with the user's real music app.**
  Mitigation: warn before install that the real Kugou/QQ app of the
  same package cannot coexist, and never install over it silently.

## 10. Open questions for the user (not blockers for this plan)

1. Phone model and OriginOS 7 build number?
2. Which Chinese music apps are actually used day to day?
3. Is the current phone already on OriginOS 7, or still on OriginOS 6
   waiting for the update? The Phase 0 baseline must be taken on the
   version the user actually runs.
4. Is a full Kotlin-package rename (`com.originghostplayer.android` →
   something with "player pro") wanted later, knowing it forces a
   notification-access re-grant? It is deliberately out of this plan.
