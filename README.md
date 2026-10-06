# BatiDoorLink

An Android doorbell viewer: it shows an RTSP camera's live video and sound,
and lets you talk back through the camera's speaker. It's built for a Reolink
doorbell, and works with other ONVIF cameras that use H.264 and AAC.

- Low-latency playback: about as fast as the camera itself (~0.1 s), over TCP
  or UDP.
- Hold to talk, or leave the mic open for full duplex.
- Doesn't interrupt your music until you talk.
- Open it with an `rtsp://` link: video only, with talk and hang-up buttons,
  closing by itself after 30 s untouched.
- Open it by hand for the settings panel and a list of recent cameras.
- Other apps can control it with an intent: `ACTION_VIEW` with the stream's
  `rtsp://` URL opens that camera, so a doorbell ring can bring it up.
