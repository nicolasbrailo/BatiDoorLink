# BatiDoorLink

Android app for talking to a doorbell camera over RTSP: it plays the camera's
stream and sends the microphone back over the ONVIF audio backchannel. For now
it is a test bench for two-way (duplex) audio, with the camera typed in by
hand. It is meant to become the viewer that
[AstroDock](../AstroDock) starts when the doorbell rings, through an
`ACTION_VIEW` intent with the stream's `rtsp://` URL; AstroDock will own the
camera settings, the MQTT ring event and waking the screen, and this app will
own nothing but the call.

It runs on Facebook Portals (Android 9 and 10, arm64, no Google Play
Services), like AstroDock; `../AstroDock/AGENTS.md` has the platform notes.

This file is the shared context for AI assistants (`CLAUDE.md` imports it).
Keep it up to date when the design changes.

## Build, test, run

- `./gradlew assembleDebug`, `./gradlew testDebugUnitTest` (JVM, no device),
  `./gradlew lintDebug`. On the development machine the JDKs in
  `/usr/lib/jvm` have no `javac`, so Gradle needs Android Studio's:
  `J=~/src/android-studio/jbr; JAVA_HOME=$J ./gradlew -Porg.gradle.java.installations.paths=$J ...`
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Connect from adb, filling the fields:
  `adb shell am start -a android.intent.action.VIEW -d 'rtsp://admin:@10.10.30.11/h264Preview_01_sub'`
- `adb shell pm grant com.nicobrailo.batidoorlink android.permission.RECORD_AUDIO`
  saves tapping the permission dialog.
- Logs: `adb logcat -s BatiDoorLink Backchannel RtspConnection MicStreamer`.
  Every 2s the activity logs the playback position against
  `elapsedRealtime`, the buffer, the talk channel and the mic level, which
  is how to follow a test from adb.
- The settings are in `shared_prefs/settings.xml`, which `run-as` can edit
  with the app stopped; `send_mic` false makes the talk controls open the
  mic without sending anything, for testing without a sound at the door.
- On a Portal the screensaver covers an activity started from adb, and
  stops it; a second `input keyevent KEYCODE_WAKEUP` ends it, and the app
  then connects (see `wantConnected`).

Toolchain as AstroDock: AGP 9 with built-in Kotlin, version catalog in
`gradle/libs.versions.toml`, minSdk 28, Java 11, Views and XML layouts.
Media3 (ExoPlayer) plays the stream.

## The camera

A Reolink Video Doorbell PoE (hardware `DB_566128M5MP_P`), measured
2026-10-05:
- `rtsp://admin:@IP/h264Preview_01_main` (2560x1920) and `_sub` (640x480),
  H.264 High (both declare level 5.1), with AAC-LC 16 kHz mono audio.
- The backchannel is a third, `sendonly` audio track that DESCRIBE lists only
  with `Require: www.onvif.org/ver20/backchannel`. Firmware v3.0.0.2033
  offered PCMA and played nothing; v3.0.0.6460 offers PCMU and plays it.
  Public reports put the change at v3.0.0.3215 (PCMU) and working talk from
  3308/4110. So the codec is read from the SDP, never assumed.
- A session holding only the talk track plays fine; the video needn't be
  set up in it.
- The camera reports its talk as full duplex (`FDX`, over Reolink's own
  protocol on port 9000, read with neolink). An RTSP session sending audio
  measured the mic going silent for exactly as long as the audio lasted on
  the old firmware; not measured on the new one yet.
- Keyframes: every 2s on the main stream (gop 2 at 20 fps) and every 4s on
  the sub stream (gop 4 at 10 fps), per `GetEnc`.
- Latency, measured 2026-10-05 from the clock the camera burns into the
  picture: frames reached a PC running ffmpeg with no buffering about 0.1s
  past each second the clock turned, so the camera and the network add at
  most about half a second (the camera's clock agreed with the PC's to
  within that). On a Portal+ with Media3 the sub stream showed 2 to 2.7s
  behind. See Media3 below.
- The doorbell press is not an ONVIF event (only motion is, on both
  firmwares). It comes over port 9000.
- Its HTTP API stopped answering for about a minute after a burst of
  concurrent queries, so don't poll it.

## Code map

All sources are in `app/src/main/java/com/nicobrailo/batidoorlink/`.

- `MainActivity.kt` + `res/layout/activity_main.xml`: the fields (host,
  stream path or full URL, user, password), Connect, the talk controls and
  the switches, with a status overlay on the video refreshed every 500ms
  (video format and decoder, buffer, dropped frames, talk channel, packets
  sent, mic level, audio mode). A VIEW intent fills the fields and connects.
  Leaving the screen (`onStop`) disconnects everything.
  Talking is either held (Hold to talk) or left on (Open mic, full duplex).
  "Mute the doorbell while talking" makes it half duplex.
  The talk channel opens on connect, so pressing the button is instant (a
  switch makes it wait for the first press). "Call audio mode" plays the
  stream as `USAGE_VOICE_COMMUNICATION` in `MODE_IN_COMMUNICATION`, which is
  what a platform echo canceller needs to see the far end; the mic switches
  pick the `VOICE_COMMUNICATION` source and attach the `AcousticEchoCanceler`
  and `NoiseSuppressor` effects where the device has them. The Portals have
  no hardware echo canceller (AstroDock's WebRTC logs "HW AEC not
  supported"), so these are there to measure, not to rely on.
  The player starts on 100ms of buffer, since a doorbell wants the picture
  now rather than smooth.
  **Media3 adds about 2s of delay here, and nothing in this app reduces it
  yet.** Measured on a Portal+ (2026-10-05): playback starts about 4s after
  connecting on the sub stream, then holds a steady ~1.6s buffer (~1.0s on
  the main stream), and plays 2 to 2.7s behind the door. Playing faster to
  catch up was tried and doesn't work: at `setPlaybackSpeed(1.15f)` the
  position still advanced at exactly 1.0x, with or without the audio track
  and in either audio mode, so it was removed. Seeking ahead isn't possible
  either: Media3's `RtspMediaPeriod.seekToUs` sends the camera PAUSE and
  PLAY. That the buffer follows the keyframe interval (4s and 2s) is a
  lead, not a finding. The way out, if it matters, is a pipeline of our
  own: `RtspConnection` already speaks RTSP, so it would take H.264 and AAC
  depacketising into `MediaCodec`, rendering each frame as it arrives.
- The video decodes on the Portal+'s hardware decoder
  (`OMX.qcom.video.decoder.avc`) at both sizes, 640x480 and 2560x1920,
  without dropped frames; the AAC audio is reported as `audio/mp4a-latm`.
- The Portal+ has no `AcousticEchoCanceler` ("AEC unavailable" in the
  status), so open-mic duplex there has no echo cancellation at all.
- `rtsp/Backchannel.kt`: the talk session. DESCRIBE with the `Require`
  header, SETUP of the `sendonly` track resolved against Content-Base, PLAY,
  then RTP interleaved on channel 0, and a GET_PARAMETER keepalive at half the
  session timeout (OPTIONS if the server doesn't know it). `RtspUrl` splits the
  credentials off the URL, which requests must not carry.
- `rtsp/RtspConnection.kt`: one RTSP connection. A single reader thread takes
  everything off the socket, since responses and interleaved RTCP share it,
  and requests wait for their response on a queue. Digest or Basic, answered
  after the first 401.
- `rtsp/RtspMessage.kt`: reads one response, interleaved frame or server
  request off a stream.
- `rtsp/Sdp.kt`: the media sections, the backchannel track and its codec, and
  control URL resolution (RFC 2326 C.1.1).
- `rtsp/Auth.kt`: digest (RFC 2617, MD5, qop auth or none) and basic.
- `rtsp/Rtp.kt`: RTP packets; the marker starts each talkspurt.
- `rtsp/G711.kt`: A-law and mu-law, encode and decode, as the reference
  g711.c does (ffmpeg rounds where it truncates, so they differ by a step on a
  few values).
- `audio/MicStreamer.kt`: `AudioRecord` at 8 kHz mono, in 20ms frames of
  G.711, with the chosen source and effects; reports what it actually got
  (an effect can be asked for and missing) and the level.

Unit tests are in `app/src/test/`: everything in `rtsp/`, including
`BackchannelTest`, which runs a whole session against a fake camera that
answers like the Reolink did. The SDPs in `src/test/resources` are the
camera's own, from both firmwares.

## Conventions

- No Google Play Services or Firebase: the Portals don't have them.
- Comments explain why, not what, in full sentences, as in AstroDock.
- Don't play audio through the doorbell without asking: it sounds at the
  front door.
- Keep `AGENTS.md` in sync with any change to behaviour or structure.
