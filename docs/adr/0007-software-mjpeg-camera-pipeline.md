# ADR 0007: Software MJPEG Camera Pipeline for Playback Protection

## Context & Decision
Camera streaming on Android TV overlays presents a unique hardware contention challenge: TV system-on-chips (MediaTek Cortex-A55, Amlogic S905X4) provide limited hardware video decoder instances (`MediaCodec`). If an overlay initializes an ExoPlayer, HLS, or WebRTC hardware video decoder while a foreground streaming app (such as Netflix, YouTube, or Plex) is playing 4K/HDR content, hardware decoder capacity is exhausted. This leads to dropped frames, audio-video desynchronization, or fatal decoder eviction crashes in the background playback app.

We decided to strictly use pure software-downsampled MJPEG over HTTP (`RGB_565` bitmaps via `cameraHttpClient`) for all camera views, including the interactive `CameraDialog` and transient `CameraPipAlert`:
1. **Zero Hardware Decoder Contention**: Pure software bitmap decoding bypasses Android `MediaCodec` entirely, guaranteeing active 4K/HDR video decoders in foreground TV apps are never interrupted.
2. **Sub-Sampled Memory Footprint**: Frame decodes target downsampled dimensions (`RGB_565` color config, 2 bytes per pixel) and recycle buffers immediately on disposal, avoiding GC thrashing.
3. **Transient Alert Safety**: Camera PiP Alerts can appear over active video playback with zero dropped frames.

## Considered Options
- **ExoPlayer HLS / RTSP Pipeline**: Rejected because initializing ExoPlayer allocates hardware video decoders, crashing or causing severe stuttering in active video streaming applications on low-power TV chipsets.
- **WebRTC (go2rtc / libwebrtc)**: Rejected because native WebRTC dependencies add 15MB+ binary bloat and require hardware acceleration that competes for video decoders.
- **Native Android SurfaceView Overlay**: Rejected because system alert windows using `TYPE_APPLICATION_OVERLAY` with hardware video surfaces cause compositing flicker over protected DRM video layers.
