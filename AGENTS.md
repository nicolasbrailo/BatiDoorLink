# BatiDoorLink

Android app for talking to a doorbell camera over RTSP: it plays the camera's
stream and sends the microphone back over the ONVIF audio backchannel. It is a test
bench for two-way (duplex) audio, with the camera typed in by hand, and the
viewer that [AstroDock](../AstroDock) starts when the doorbell rings, through
an `ACTION_VIEW` intent with the stream's `rtsp://` URL (AstroDock's
`doorbell/DoorbellViewer.kt`). AstroDock owns the MQTT ring event
(`cmd/doorbell/ring`, sent by zmw_homeboard, which gets the camera's streams
from zmw_doorman) and waking the screen; this app owns nothing but the call.

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
- `tools/build-apks.sh [OUT_DIR]`: runs the unit tests, builds both APKs and
  leaves them in `~/Downloads` as `BatiDoorLink-debug.apk` and
  `BatiDoorLink-release.apk`, printing each one's version, size and sha256 and
  the key that signed them (the Android debug keystore, as AstroDock's; it has
  to stay the same for updates to install). It finds a JDK with `javac` by
  itself (Android Studio's, failing `JAVA_HOME`), so it runs from a plain
  shell. Prefer the debug APK: `run-as` needs it.
- Connect from adb, filling the fields:
  `adb shell am start -a android.intent.action.VIEW -d 'rtsp://admin:@10.10.30.11/h264Preview_01_sub'`
- `adb shell pm grant com.nicobrailo.batidoorlink android.permission.RECORD_AUDIO`
  saves tapping the permission dialog.
- Logs: `adb logcat -s BatiDoorLink StreamSession VideoDecoder AudioPlayer Backchannel RtspConnection MicStreamer`.
  Every 2s the activity logs its status overlay as one line (transport,
  frame rate, decode time, losses, audio queue, talk channel, mic level),
  which is how to follow a test from adb. `StreamSession` logs each UDP
  track's Transport reply and how long the first packet took.
- The settings are in `shared_prefs/settings.xml`, which `run-as` can edit
  with the app stopped; `send_mic` false makes the talk controls open the
  mic without sending anything, for testing without a sound at the door.
- On a Portal the screensaver covers an activity started from adb, and
  stops it; a second `input keyevent KEYCODE_WAKEUP` ends it, and the app
  then connects (see `wantConnected`). The screen going to sleep stops it
  too (`mWakefulness=Dozing` in `dumpsys power`).
- Latency is measured from the clock the camera burns into the picture:
  screenshots (`adb exec-out screencap`, timed on the PC), each read as "the
  clock said S at time t", so the delay is between t-S-1 and t-S; eight of
  them narrow it to a fraction of a second. The camera's clock agrees with
  the PC's (NTP) to well under a second, and is an hour behind it (UTC).

Toolchain as AstroDock: AGP 9 with built-in Kotlin, version catalog in
`gradle/libs.versions.toml`, minSdk 28, Java 11, Views and XML layouts. No
media library: the stream is played by a pipeline of our own, see below.

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
- What it sends, from a capture (`src/test/resources/reolink-sub-6s.rtp`):
  SPS and PPS as single NAL packets ahead of each keyframe, **with the
  marker bit set on the SPS** (against RFC 6184, so the depacketizer only
  lets a marker end a picture that has a slice), the keyframe in FU-A
  fragments, P frames whole or in FU-A. No STAP-A, no B frames. Audio is
  one AAC frame per packet, but its timestamps step by 950 to 961 rather
  than the frame's 1024 samples, so they aren't sample counts.
- Over TCP it starts with a keyframe straight after PLAY. Over UDP the first
  packet came 0.3 to 3.2s after PLAY (sub stream, 5 tries), probably
  waiting for the next keyframe (not checked).
- Latency, measured 2026-10-05: frames reached a PC running ffmpeg with no
  buffering about 0.1s past each second the clock turned, so the camera and
  the network add about 0.1s. On a Portal+, Media3 played 2 to 2.7s behind;
  this app's own pipeline, at most about 0.15s over UDP and 0.06s over TCP
  (plus the moment screencap takes to grab the screen), so the camera's own
  delay is about all there is.
- The doorbell press is not an ONVIF event (only motion is, on both
  firmwares). It comes over port 9000.
- Its HTTP API stopped answering for about a minute after a burst of
  concurrent queries, so don't poll it.

## Code map

All sources are in `app/src/main/java/com/nicobrailo/batidoorlink/`.

- `MainActivity.kt` + `res/layout/activity_main.xml`: the video, with the
  call's buttons over it as on AstroDock's call screen (its
  `hang_up_background` and `ic_call_end`): Talk (hold; green while it
  sends, `res/drawable/talk_background.xml`) and Hang up at the bottom,
  shown while connected, and a settings button at the top right that
  shows and hides the config panel beside the video. The debug overlay
  (format and decoder, frame rate, decode time, losses, audio queue, talk
  channel, packets sent, mic level, focus, countdown) shows with the panel
  only. The panel: a dropdown of recent streams, the fields (host, stream
  path or full URL, user, password), Connect (Reconnect while connected)
  and the switches.
  **Opened by a ring** (a VIEW intent): video and buttons only, the panel
  hidden; hanging up closes the app, like ending a call. **Opened by hand**
  (the launcher): the panel shows; hanging up disconnects, blanks the video
  (the last picture would otherwise stay, looking live: hiding the
  SurfaceView takes its surface, and `connect()` brings it back) and shows
  the panel. The launcher over a ring switches to the second.
  `StreamHistory.kt`: the recent streams, most recent first, 10 at most,
  each the full URL with its credentials (it is what reconnecting needs; the
  dropdown shows it without them), in the `history` preference, one per
  line. A stream goes in once it plays, so a typo doesn't; picking one
  fills the fields and connects. Pure and unit tested.
  Leaving the screen (`onStop`) disconnects everything.
  Opened by an intent (as a ring will open it), it closes itself
  (`finish()`, back to AstroDock) after 30s nobody touched it, counted from
  the first picture on screen (the count from the activity starting, which
  left only 25 to 29s of picture behind the camera's keyframe wait, is the
  fallback for a stream that never shows one). Any touch restarts the 30s, talking (held or open
  mic) holds it off, and letting go restarts it. Connecting or
  disconnecting by hand turns it off: that is somebody using the app. Opened
  from the launcher it never closes itself. The status line shows the
  countdown. Measured on the Portal+: closed 30.0s after the talk button
  was let go.
  **Other apps' audio:** while it only shows the door it asks for no audio
  focus and leaves the audio mode alone, so music carries on and the
  doorbell plays over it. Talking (held or open mic) takes transient focus,
  which pauses music, and with "Call audio mode" switches to
  `MODE_IN_COMMUNICATION`; both are given back when the talking stops, and
  music resumes. Measured with Spotify holding focus: untouched while
  watching, `LOSS_TRANSIENT` while the button was held, focus back after.
  Earlier versions switched the mode on connect, which also cost startup
  time.
  **Connecting**, measured on the Portal+ (2026-10-06, `timing:` lines in
  the log): a cold start takes 1.2s to get to connecting (process start
  and layout), an already open app 0.04s; DESCRIBE, SETUP and PLAY take 0.3
  to 0.4s; and then the camera sends nothing until its next keyframe, 0 to
  4s on the sub stream, 0 to 2s on the main. That wait is most of it, and
  only the camera's keyframe interval can shorten it. Stopping the old
  decoders happens on `streamIo` and closing the old session on a thread of
  its own, since on the main thread they held a reconnect up by 0.7s. The
  talk channel opens once the stream plays, so its requests don't queue
  ahead of the stream's at the camera.
  Talking is either held (the Talk button) or left on (Open mic, full duplex).
  "Mute the doorbell while talking" makes it half duplex.
  The talk channel opens on connect, so pressing the button is instant (a
  switch makes it wait for the first press). "Call audio mode" plays the
  stream as `USAGE_VOICE_COMMUNICATION` in `MODE_IN_COMMUNICATION`, which is
  what a platform echo canceller needs to see the far end; the mic switches
  pick the `VOICE_COMMUNICATION` source and attach the `AcousticEchoCanceler`
  and `NoiseSuppressor` effects where the device has them. The Portals have
  no hardware echo canceller (AstroDock's WebRTC logs "HW AEC not
  supported"), so these are there to measure, not to rely on.
  The stream plays through `StreamSession`, `VideoDecoder` and
  `AudioPlayer` (below), which hold nothing back; that took the delay from
  Media3's 2 to 2.7s to the camera's own ~0.1s. Transport is TCP by default,
  for the reasons under `StreamSession`; with the switch off it tries UDP and
  falls back to TCP by itself (`StreamSession.play`).
- `rtsp/StreamSession.kt`: the stream. `describe` first (the SDP gives the
  SPS and PPS, the picture size and the AAC config, which the decoders need
  before they start), then `play`, so the decoders exist before the first
  packet: the camera opens with a keyframe, and missing it costs a keyframe
  interval. Over UDP: an even/odd port pair per track, a punch packet out of
  each (an empty RTP header and an empty receiver report, as ffmpeg sends)
  for a stateful firewall in between, an empty receiver report every 5s, and
  a fall back to a new session over TCP if nothing arrives in 5s. Over TCP:
  interleaved channels 0 (video) and 2 (audio) on the RTSP connection.
  **UDP isn't faster than TCP here, and costs more:** the same delay
  (above), plus up to a keyframe interval at startup, and on the Portal+'s
  Wi-Fi the main stream (4 Mbps) lost about 4 packets a second over UDP
  (170 in 40s, 118 pictures dropped: a loss freezes the picture until the
  next keyframe), where TCP retransmits instead. The sockets themselves
  dropped nothing (`/proc/net/udp6`); the sub stream lost nothing in a
  minute. Measured 2026-10-05.
- `media/H264.kt`: `H264Depacketizer` (RFC 6184 mode 1: single NAL, STAP-A,
  FU-A) into Annex B access units. After any loss nothing is passed on until
  the next keyframe, since a frozen picture beats a smeared one; the same
  at the start. `SpsSize` reads the picture size from the SPS.
- `media/Aac.kt`: `AacConfig` (the fmtp `config`, AudioSpecificConfig) and
  `AacDepacketizer` (RFC 3640 AAC-hbr; AUs split over packets aren't
  handled, and the camera doesn't split them).
- `media/VideoDecoder.kt`: `MediaCodec` onto the `SurfaceView`, each picture
  released to the screen as soon as it is decoded, with no pacing. Asks
  Qualcomm decoders not to reorder (`vendor.qti-ext-dec-*` keys). A backlog
  of 8 pictures is thrown away and decoding resumes at the next keyframe.
  Measured on the Portal+: about 10ms from a picture's last packet to the
  screen at 640x480, 20 to 30ms at 2560x1920, on `OMX.qcom.video.decoder.avc`.
- `media/AudioPlayer.kt`: `MediaCodec` AAC into a low latency `AudioTrack`
  of the minimum size, dropping decoded audio whenever more than 200ms waits
  to play. Measured: 23 to 40ms queued, nothing dropped in a minute, so the
  camera's audio doesn't run fast despite its odd timestamps.
- `VideoFrame.kt`: keeps the picture's shape, centred.
- The Portal+ has no `AcousticEchoCanceler` ("AEC unavailable" in the
  status), so open-mic duplex there has no echo cancellation at all.
- `rtsp/Backchannel.kt`: the talk session. DESCRIBE with the `Require`
  header, SETUP of the `sendonly` track resolved against Content-Base, PLAY,
  then RTP interleaved on channel 0. `RtspUrl` splits the credentials off the
  URL, which requests must not carry.
- `rtsp/RtspConnection.kt`: one RTSP connection. A single reader thread takes
  everything off the socket, since responses and interleaved RTCP share it,
  and requests wait for their response on a queue. Digest or Basic, answered
  after the first 401. Its keepalive (GET_PARAMETER at half the session
  timeout, OPTIONS if the server doesn't know it) serves both sessions.
- `rtsp/RtspMessage.kt`: reads one response, interleaved frame or server
  request off a stream.
- `rtsp/Sdp.kt`: the media sections (with each payload type's rtpmap and
  fmtp), the backchannel track and its codec, and control URL resolution
  (RFC 2326 C.1.1).
- `rtsp/RtpPacket.kt`: received RTP packets, and `SequenceTracker`, which
  counts losses across the 16 bit wrap without counting a late packet.
- `rtsp/Auth.kt`: digest (RFC 2617, MD5, qop auth or none) and basic.
- `rtsp/Rtp.kt`: outgoing RTP packets; the marker starts each talkspurt.
- `rtsp/G711.kt`: A-law and mu-law, encode and decode, as the reference
  g711.c does (ffmpeg rounds where it truncates, so they differ by a step on a
  few values).
- `audio/MicStreamer.kt`: `AudioRecord` at 8 kHz mono, in 20ms frames of
  G.711, with the chosen source and effects; reports what it actually got
  (an effect can be asked for and missing) and the level.

Unit tests are in `app/src/test/`: everything in `rtsp/` and the
depacketizers in `media/`, run on 6s of RTP captured from the camera
(`Capture.kt` reads it). `BackchannelTest` and `StreamSessionTest` run
whole sessions against a fake camera that answers like the Reolink did;
the latter replays the capture over TCP, over UDP, and over a UDP that
never arrives, to check the fallback. The SDPs in `src/test/resources` are
the camera's own, from both firmwares and both streams. The decoders need a
device.

## Conventions

- No Google Play Services or Firebase: the Portals don't have them.
- Comments explain why, not what, in full sentences, as in AstroDock.
- Don't play audio through the doorbell without asking: it sounds at the
  front door.
- Keep `AGENTS.md` in sync with any change to behaviour or structure.
