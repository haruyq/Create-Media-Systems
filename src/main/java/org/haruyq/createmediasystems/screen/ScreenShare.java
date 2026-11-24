package org.haruyq.createmediasystems.screen;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.client.ClientFiles;
import org.haruyq.createmediasystems.disc.DiscTools;
import org.haruyq.createmediasystems.p2p.P2pClient;
import org.haruyq.createmediasystems.p2p.Transport;
import org.haruyq.createmediasystems.playback.ScreenPlayer;
import org.haruyq.createmediasystems.stream.DiscUrl;

import org.jetbrains.annotations.Nullable;

import com.mojang.logging.LogUtils;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDegradationPreference;
import dev.onvoid.webrtc.RTCEncodedVideoFrame;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCRtpCodecCapability;
import dev.onvoid.webrtc.RTCRtpSendParameters;
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.RTCRtpTransceiverDirection;
import dev.onvoid.webrtc.RTCRtpTransceiverInit;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

/**
 * This player's screen share, while it runs: FFmpeg captures a window or a monitor (Windows.Graphics.Capture, at the
 * rate the host chose) into raw pictures, which one H.264 encoder here encodes once for every viewer; each encoded
 * frame is handed to their P2P connections as it is (see Transport#video), not encoded again for each. The encoder
 * only runs while someone watches, and a still screen gives FFmpeg no new pictures, so it encodes next to nothing.
 * Its sound goes to each viewer from cms-screen-audio.exe (see ScreenSound).
 */
public final class ScreenShare implements ScreenPlayer.Feed {
    /** What is shared: a window (its HWND) or a monitor (its index, as FFmpeg's gfxcapture counts them). */
    public record Source(boolean window, long id, String title) {
    }

    public enum Sound {
        NONE, APP, PC
    }

    /** As the host set it: frames a second (any), picture height, the most kbps it may send, its sound. */
    public record Settings(Source source, int fps, int height, int kbps, Sound sound, @Nullable String mic) {
        public int width() {
            return (height * 16 / 9 + 1) & ~1;
        }
    }

    /** Encoded video waiting to go out, held back as long as the sound is (see ScreenPlayer#SOUND_DELAY_MICROS). */
    private record Encoded(long due, byte[] data, long rtp, boolean key, int width, int height) {
    }

    @Nullable
    private static volatile ScreenShare current;

    final DiscUrl url;
    private volatile Settings settings;
    private final PeerConnectionFactory factory;
    private final HeadlessAudioDeviceModule audioModule;
    private final RTCPeerConnection send;
    private final RTCPeerConnection receive;
    private final RTCRtpSender sender;
    private final CustomVideoSource source = new CustomVideoSource();
    private final VideoTrack track;
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("CMS screen share").factory());
    private final ArrayDeque<Encoded> delayed = new ArrayDeque<>();
    private final Set<VideoTrackSink> local = ConcurrentHashMap.newKeySet();
    /** Guarded by this. */
    @Nullable
    private Process capture;
    @Nullable
    private ScreenSound sound;
    @Nullable
    private NativeI420Buffer last;
    private long lastPushed;
    /** Whether pictures go to the encoder: only while someone watches. */
    private volatile boolean attached;
    /** Counts captures started, so one superseded while it started gives way. Guarded by this. */
    private int captures;
    private int appliedKbps, appliedFps;
    private volatile boolean stopped;
    /** What the preview tells the host: a problem, or what it waits for; empty when all is well. */
    volatile String status = "";
    volatile String soundStatus = "";
    private final AtomicLong captured = new AtomicLong(), encoded = new AtomicLong(), sent = new AtomicLong();
    /** Per second, as of the last second: pictures captured and encoded, kbps sent to each viewer, viewers. */
    volatile float capturedFps, encodedFps;
    volatile int kbps, viewers;

    private ScreenShare(UUID owner, Settings settings) throws Exception {
        this.url = new DiscUrl(owner, UUID.randomUUID(), true);
        this.settings = settings;
        audioModule = new HeadlessAudioDeviceModule();
        factory = new PeerConnectionFactory(audioModule);
        // A loopback pair, there only to drive the encoder: its frames are taken before they leave and handed on.
        RTCConfiguration config = new RTCConfiguration();
        RTCPeerConnection[] pair = new RTCPeerConnection[2];
        pair[0] = factory.createPeerConnection(config, candidate -> pair[1].addIceCandidate(candidate));
        pair[1] = factory.createPeerConnection(config, new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                pair[0].addIceCandidate(candidate);
            }
        });
        send = pair[0];
        receive = pair[1];
        RTCRtpTransceiverInit init = new RTCRtpTransceiverInit();
        init.direction = RTCRtpTransceiverDirection.SEND_ONLY;
        init.streamIds = List.of("screen");
        var transceiver = send.addTransceiver(MediaType.VIDEO, init);
        List<RTCRtpCodecCapability> h264 = factory.getRtpSenderCapabilities(MediaType.VIDEO).getCodecs().stream()
                .filter(c -> c.getMimeType().equalsIgnoreCase("video/H264") || c.getMimeType().endsWith("rtx")).toList();
        transceiver.setCodecPreferences(h264);
        RTCSessionDescription offer = described(o -> send.createOffer(new RTCOfferOptions(), o));
        set(o -> send.setLocalDescription(offer, o));
        set(o -> receive.setRemoteDescription(offer, o));
        RTCSessionDescription answer = described(o -> receive.createAnswer(new RTCAnswerOptions(), o));
        set(o -> receive.setLocalDescription(answer, o));
        set(o -> send.setRemoteDescription(answer, o));
        sender = transceiver.getSender();
        sender.setTransform(frame -> {
            if (frame instanceof RTCEncodedVideoFrame video) {
                encoded.incrementAndGet();
                ByteBuffer data = video.getData();
                byte[] copy = new byte[data.remaining()];
                data.get(copy);
                long delay = this.settings.sound() == Sound.NONE && this.settings.mic() == null ? 0
                        : ScreenPlayer.SOUND_DELAY_MICROS * 1000;
                synchronized (delayed) {
                    delayed.addLast(new Encoded(System.nanoTime() + delay, copy, video.getCaptureTimeUs() * 90 / 1000 & 0xFFFFFFFFL,
                            video.isKeyFrame(), video.getWidth(), video.getHeight()));
                }
            }
            frame.drop();
        });
        track = factory.createVideoTrack("screen", source);
        sender.replaceTrack(track);
        clock.scheduleAtFixedRate(this::tick, 5, 5, TimeUnit.MILLISECONDS);
        clock.scheduleAtFixedRate(this::second, 1, 1, TimeUnit.SECONDS);
    }

    @Nullable
    public static ScreenShare current() {
        return current;
    }

    /**
     * Shares {@code settings}' source as {@code owner}, in place of any share before; off the game thread, as FFmpeg
     * may be downloaded first. The P2P session it goes over is the owner's own (see P2pClient#connect).
     */
    static ScreenShare start(UUID owner, Settings settings) throws IOException {
        stopCurrent();
        SoundShare.stopCurrent();
        ScreenShare share;
        try {
            share = new ScreenShare(owner, settings);
        } catch (Exception | LinkageError failed) {
            throw new IOException("Could not start the screen share: " + failed.getMessage(), failed);
        }
        current = share;
        share.apply(settings, null);
        return share;
    }

    static void stopCurrent() {
        ScreenShare share = current;
        current = null;
        if (share != null) {
            share.stop();
        }
    }

    public Settings settings() {
        return settings;
    }

    /**
     * How loud this client's share sends its sound, in percent (0 to 200), as the host set it on the preview (see
     * ShareScreen); kept for its next share, live for the current one.
     */
    static volatile int volume = 100;

    public float soundLevel() {
        ScreenSound playing = sound;
        return playing == null ? 0 : playing.level;
    }

    /**
     * Goes on with {@code next}: a new source, rate or size restarts the capture, other sound restarts that; the same
     * link and encoder throughout, so viewers keep watching. Off the game thread.
     */
    void change(Settings next) {
        Settings before = settings;
        settings = next;
        apply(next, before);
    }

    private void apply(Settings next, @Nullable Settings before) {
        if (before == null || !before.source().equals(next.source()) || before.fps() != next.fps()
                || before.height() != next.height()) {
            capture(next);
        }
        if (before == null || before.sound() != next.sound() || !java.util.Objects.equals(before.mic(), next.mic())
                || !before.source().equals(next.source())) {
            sound(next);
        }
    }

    private void capture(Settings settings) {
        Process process;
        ServerSocket pictures;
        int started;
        synchronized (this) {
            started = ++captures;
            if (capture != null) {
                capture.destroy();
                capture = null;
            }
        }
        try {
            status = "starting";
            DiscTools.Installed ffmpeg = DiscTools.ensure(ClientFiles.DIR.resolve("ffmpeg"), ClientConfig.DISC_FFMPEG_PATH.get(),
                    ClientConfig.DISC_FFPROBE_PATH.get(), () -> status = "downloading");
            int width = settings.width(), height = settings.height();
            Source what = settings.source();
            // gfxcapture's own max_framerate drops any picture sooner than 1/fps after the last, so a picture due right at
            // the limit was dropped half the time: on a 120 Hz monitor 120 gave 88-105 a second, 60 gave 56-61 and 50
            // gave 40. Instead every picture comes and select keeps the first at most a quarter step early of each 1/fps
            // step: 120-121, 60-61 and 50-51 there.
            String step = "1/" + settings.fps(), early = "1/" + 4 * settings.fps();
            String grab = (what.window() ? "gfxcapture=hwnd=" + Long.toUnsignedString(what.id()) : "gfxcapture=monitor_idx=" + what.id())
                    + ":max_framerate=1000,select='if(gte(t,ld(0)-" + early + "),1+0*st(0,max(ld(0),t-" + early + ")+" + step
                    + "),0)',hwdownload,format=bgra"
                    + ",scale=" + width + ":" + height + ":force_original_aspect_ratio=decrease:force_divisible_by=2"
                    + ",pad=" + width + ":" + height + ":(ow-iw)/2:(oh-ih)/2,format=yuv420p";
            // Over loopback TCP: Java reads FFmpeg's stdout at about 130 MB/s here, 43 fps of 1080p, and TCP at 60.
            pictures = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            // vfr: only pictures that changed, so a still screen is hardly encoded at all. passthrough also let through two
            // pictures gfxcapture gave the same timestamp, which the rawvideo muxer refuses with an error line each.
            try {
                process = new ProcessBuilder(ffmpeg.ffmpeg(), "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", grab,
                        "-fps_mode", "vfr", "-f", "rawvideo", "tcp://127.0.0.1:" + pictures.getLocalPort()).start();
            } catch (IOException failed) {
                pictures.close();
                throw failed;
            }
            process.getOutputStream().close();
        } catch (IOException failed) {
            status = "failed:" + failed.getMessage();
            return;
        }
        synchronized (this) {
            if (stopped || started != captures) {
                process.destroy();
                closeQuietly(pictures);
                return;
            }
            capture = process;
        }
        status = "";
        Thread.ofPlatform().daemon().name("CMS screen capture").start(() -> read(process, pictures, settings.width(), settings.height()));
    }

    private void read(Process process, ServerSocket pictures, int width, int height) {
        // Read as FFmpeg writes it: once its 4 KB pipe was full (about 30 error lines), FFmpeg waited on it and sent no
        // more pictures, and every viewer's screen stopped on the last one. The start is kept for the log.
        CompletableFuture<String> errors = CompletableFuture.supplyAsync(() -> {
            try (InputStream err = process.getErrorStream()) {
                String first = new String(err.readNBytes(1024), StandardCharsets.UTF_8).trim();
                err.transferTo(OutputStream.nullOutputStream());
                return first;
            } catch (IOException unreadable) {
                return "";
            }
        }, runnable -> Thread.ofPlatform().daemon().name("CMS screen capture errors").start(runnable));
        int size = width * height * 3 / 2;
        byte[] picture = new byte[size];
        Socket from = null;
        try (pictures) {
            pictures.setSoTimeout(500);
            for (long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15); from == null && process.isAlive()
                    && System.nanoTime() < until; ) {
                try {
                    from = pictures.accept();
                } catch (SocketTimeoutException waiting) {
                    // FFmpeg is still opening the capture.
                }
            }
        } catch (IOException failed) {
            // closed below
        }
        if (from == null) {
            process.destroy();
        } else {
            try (Socket socket = from; InputStream in = socket.getInputStream()) {
                while (in.readNBytes(picture, 0, size) == size) {
                    NativeI420Buffer buffer = NativeI420Buffer.allocate(width, height);
                    plane(picture, 0, width, height, buffer.getDataY(), buffer.getStrideY());
                    plane(picture, width * height, width / 2, height / 2, buffer.getDataU(), buffer.getStrideU());
                    plane(picture, width * height * 5 / 4, width / 2, height / 2, buffer.getDataV(), buffer.getStrideV());
                    push(buffer);
                    captured.incrementAndGet();
                }
            } catch (IOException closed) {
                // restarted or stopped, or FFmpeg failed: told below
            }
        }
        String why = errors.completeOnTimeout("", 2, TimeUnit.SECONDS).join();
        synchronized (this) {
            if (capture == process && !stopped) {
                LogUtils.getLogger().warn("Screen capture stopped: {}", why);
                status = "lost";
            }
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void plane(byte[] from, int offset, int width, int height, ByteBuffer to, int stride) {
        for (int row = 0; row < height; row++) {
            to.put(row * stride, from, offset + row * width, width);
        }
    }

    /**
     * Hands {@code buffer} to the encoder (while someone watches) and this computer's own pictures of it, and keeps it
     * as the last one; takes one reference to it.
     */
    private synchronized void push(NativeI420Buffer buffer) {
        if (stopped) {
            buffer.release();
            return;
        }
        VideoFrame frame = new VideoFrame(buffer, System.nanoTime());
        if (attached) {
            source.pushFrame(frame);
        }
        for (VideoTrackSink sink : local) {
            sink.onVideoFrame(frame);
        }
        if (last != buffer) {
            buffer.retain();
            if (last != null) {
                last.release();
            }
            last = buffer;
        }
        frame.release();
        lastPushed = System.nanoTime();
    }

    private void sound(Settings settings) {
        ScreenSound before;
        synchronized (this) {
            before = sound;
            sound = null;
        }
        if (before != null) {
            before.close();
        }
        List<List<String>> sources = new ArrayList<>();
        if (settings.sound() == Sound.APP && settings.source().window()) {
            IntByReference pid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(new WinDef.HWND(new Pointer(settings.source().id())), pid);
            if (pid.getValue() != 0) {
                sources.add(List.of("include", Integer.toString(pid.getValue())));
            }
        } else if (settings.sound() == Sound.PC) {
            // All but this game, which would otherwise play back into itself.
            sources.add(List.of("exclude", Long.toString(ProcessHandle.current().pid())));
        }
        if (settings.mic() != null) {
            sources.add(List.of("device", settings.mic()));
        }
        soundStatus = "";
        if (sources.isEmpty()) {
            return;
        }
        try {
            ScreenSound started = new ScreenSound(ScreenSound.exe(), sources, () -> volume, this::heard);
            synchronized (this) {
                if (stopped || this.settings != settings) {
                    started.close();
                    return;
                }
                sound = started;
            }
        } catch (IOException failed) {
            soundStatus = failed.getMessage();
        }
    }

    private void heard(byte[] chunk) {
        Transport session = session();
        if (session != null) {
            session.sound(chunk);
        }
    }

    @Nullable
    private Transport session() {
        Transport session = P2pClient.session(url.owner());
        return session != null && session.host() && session.state() != Transport.State.CLOSED ? session : null;
    }

    /** Every 5 ms: encoded video due goes out, and a viewer's ask for a key frame reaches the encoder. */
    private void tick() {
        try {
            Transport session = session();
            long now = System.nanoTime();
            while (true) {
                Encoded next;
                synchronized (delayed) {
                    next = delayed.peekFirst();
                    if (next == null || next.due - now > 0) {
                        break;
                    }
                    delayed.removeFirst();
                }
                if (session != null) {
                    session.video(ByteBuffer.wrap(next.data), next.rtp, next.key, next.width, next.height);
                    sent.addAndGet(next.data.length);
                }
            }
            if (session != null && session.keyFrameWanted()) {
                sender.generateKeyFrame();
                repeat();
            }
        } catch (RuntimeException | LinkageError e) {
            LogUtils.getLogger().warn("Screen share failed", e);
        }
    }

    /** The last picture again: a still screen gives no new one for a key frame, or for a viewer who just came. */
    private synchronized void repeat() {
        if (last != null && !stopped) {
            last.retain();
            push(last);
        }
    }

    /** Every second: the encoder runs only while someone watches, at the rate and bitrate the host set. */
    private void second() {
        try {
            Transport session = session();
            int watching = session == null ? 0 : session.connected().size();
            boolean want = watching > 0;
            if (want != attached) {
                attached = want;
                if (want) {
                    sender.generateKeyFrame();
                    repeat();
                }
            }
            Settings now = settings;
            int target = now.kbps();
            if (session != null && session.budgetBps() > 0) {
                // The slowest viewer's measured pace, with room for the sound and the disc share's own sends.
                target = (int) Math.max(300, Math.min(target, session.budgetBps() * 8 / 10 / 1000));
            }
            if (target != appliedKbps || now.fps() != appliedFps) {
                RTCRtpSendParameters parameters = sender.getParameters();
                if (!parameters.encodings.isEmpty()) {
                    parameters.encodings.get(0).minBitrate = target * 1000;
                    parameters.encodings.get(0).maxBitrate = target * 1000;
                    parameters.encodings.get(0).maxFramerate = (double) now.fps();
                    parameters.degradationPreference = RTCDegradationPreference.MAINTAIN_RESOLUTION;
                    sender.setParameters(parameters);
                    appliedKbps = target;
                    appliedFps = now.fps();
                }
            }
            if (want && System.nanoTime() - lastPushed > 1_000_000_000L) {
                repeat();
            }
            viewers = watching;
            capturedFps = captured.getAndSet(0);
            encodedFps = encoded.getAndSet(0);
            kbps = (int) (sent.getAndSet(0) * 8 / 1000 / Math.max(1, watching));
        } catch (RuntimeException | LinkageError e) {
            LogUtils.getLogger().warn("Screen share failed", e);
        }
    }

    /** Ends the share: capture, sound and encoder stop, and its pictures here end. */
    void stop() {
        synchronized (this) {
            if (stopped) {
                return;
            }
            stopped = true;
            if (capture != null) {
                capture.destroy();
            }
            if (sound != null) {
                sound.close();
            }
            if (last != null) {
                last.release();
                last = null;
            }
        }
        local.clear();
        clock.shutdownNow();
        // Native teardown may wait on WebRTC's threads: never on the game's.
        CompletableFuture.runAsync(() -> {
            try {
                clock.awaitTermination(2, TimeUnit.SECONDS);
                send.close();
                receive.close();
                track.dispose();
                source.dispose();
                factory.dispose();
                audioModule.dispose();
            } catch (InterruptedException | RuntimeException e) {
                LogUtils.getLogger().warn("Could not release the screen share's encoder", e);
            }
        });
    }

    @Override
    public void watch(@Nullable VideoTrackSink pictures, @Nullable AudioTrackSink sound) {
        if (pictures != null) {
            local.add(pictures);
        }
        repeat();
    }

    @Override
    public void unwatch(@Nullable VideoTrackSink pictures, @Nullable AudioTrackSink sound) {
        local.remove(pictures);
    }

    @Override
    public boolean ended() {
        return stopped;
    }

    /** The pictures the preview shows, as captured here (see ShareScreen). */
    void preview(@Nullable VideoTrackSink pictures, boolean add) {
        if (add) {
            watch(pictures, null);
        } else {
            local.remove(pictures);
        }
    }

    private interface Call<T> {
        void call(T observer);
    }

    private static RTCSessionDescription described(Call<CreateSessionDescriptionObserver> call) throws Exception {
        CompletableFuture<RTCSessionDescription> done = new CompletableFuture<>();
        call.call(new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                done.complete(description);
            }

            @Override
            public void onFailure(String error) {
                done.completeExceptionally(new IOException(error));
            }
        });
        return done.get(5, TimeUnit.SECONDS);
    }

    private static void set(Call<SetSessionDescriptionObserver> call) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        call.call(new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                done.complete(null);
            }

            @Override
            public void onFailure(String error) {
                done.completeExceptionally(new IOException(error));
            }
        });
        done.get(5, TimeUnit.SECONDS);
    }
}
