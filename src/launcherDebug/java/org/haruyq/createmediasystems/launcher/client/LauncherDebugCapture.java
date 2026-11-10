package org.haruyq.createmediasystems.launcher.client;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Opt-in recording of this game's framebuffer only. Never captures the desktop, other apps or their audio. */
@EventBusSubscriber(modid="create_media_systems", value=Dist.CLIENT)
public final class LauncherDebugCapture {
    private static final int FPS = 30;
    private static final long INTERVAL = 1_000_000_000L / FPS;
    private static volatile Session active;
    private record Frame(long at, NativeImage image) {}

    private static final class Session {
        final long start = System.nanoTime();
        long next = start;
        final Path output;
        final Process encoder;
        final int width, height, oldFps;
        final boolean oldPause;
        final ArrayBlockingQueue<Frame> frames = new ArrayBlockingQueue<>(3);
        final List<Map<String,Object>> sounds = new CopyOnWriteArrayList<>();
        volatile long stop;
        int captured, dropped;
        Session(Path output, Process encoder, int width, int height, int oldFps, boolean oldPause) {
            this.output=output; this.encoder=encoder; this.width=width; this.height=height;
            this.oldFps=oldFps; this.oldPause=oldPause;
        }
    }

    public static void start(String name) throws Exception {
        if (active != null) throw new IllegalStateException("Already recording");
        if (!name.matches("[a-zA-Z0-9_-]+\\.mp4")) throw new IllegalArgumentException("Invalid recording name");
        var mc = Minecraft.getInstance();
        Path directory = mc.gameDirectory.toPath().resolve("recordings"); Files.createDirectories(directory);
        Path output = directory.resolve(name);
        Files.deleteIfExists(Path.of(output+".done"));
        Path executableFile = mc.gameDirectory.toPath().resolve("recording-encoder");
        String executable = Files.readString(executableFile).strip();
        int width=mc.getMainRenderTarget().width, height=mc.getMainRenderTarget().height;
        Process encoder = new ProcessBuilder(executable, "-hide_banner", "-loglevel", "warning", "-y",
                "-f", "rawvideo", "-pixel_format", "rgba", "-video_size", width+"x"+height,
                "-framerate", Integer.toString(FPS), "-i", "pipe:0", "-an", "-c:v", "libx264",
                "-preset", "veryfast", "-crf", "18", "-threads", "2", "-pix_fmt", "yuv420p",
                "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", "-movflags", "+faststart", output.toString())
                .redirectError(directory.resolve(name+".encoder.log").toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        var session = new Session(output, encoder, width, height, mc.options.framerateLimit().get(), mc.options.pauseOnLostFocus);
        mc.options.framerateLimit().set(60); mc.options.pauseOnLostFocus=false;
        active=session;
        Thread.ofPlatform().daemon().name("CMS debug recording").start(() -> encode(session));
        LogUtils.getLogger().info("LAUNCHER_DEBUG RECORDING_START {} {}x{}",name,width,height);
    }

    public static void stop() {
        Session session=active;
        if (session==null) return;
        active=null; session.stop=System.nanoTime();
        var mc=Minecraft.getInstance();
        mc.options.framerateLimit().set(session.oldFps); mc.options.pauseOnLostFocus=session.oldPause;
        LogUtils.getLogger().info("LAUNCHER_DEBUG RECORDING_STOP {}",session.output.getFileName());
    }

    @SubscribeEvent public static void frame(RenderFrameEvent.Post event) {
        Session session=active;
        if(session==null) return;
        long now=System.nanoTime();
        if(now<session.next) return;
        while(session.next<=now) session.next+=INTERVAL;
        NativeImage pixels=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget());
        if(pixels.getWidth()!=session.width || pixels.getHeight()!=session.height) {pixels.close();stop();return;}
        if(!session.frames.offer(new Frame(now,pixels))) {pixels.close();session.dropped++;}
        else session.captured++;
    }

    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { stop(); }

    @SubscribeEvent public static void sound(PlaySoundSourceEvent event) {
        Session session=active;
        if(session==null) return;
        var sound=event.getSound();
        if(sound==null || !sound.getLocation().getNamespace().equals("create_media_systems")
                || !sound.getLocation().getPath().startsWith("disc_") || sound.getSound()==null) return;
        // These real sound events can be rendered into a separate effects track without recording other apps.
        session.sounds.add(Map.of("seconds",(System.nanoTime()-session.start)/1e9,
                "sound",sound.getLocation().getPath(),"volume",sound.getVolume(),"pitch",sound.getPitch()));
    }

    private static void encode(Session session) {
        int written=0;
        byte[] last=null;
        try(OutputStream stdin=session.encoder.getOutputStream()) {
            while(session.stop==0 || !session.frames.isEmpty()) {
                Frame frame=session.frames.poll(100,TimeUnit.MILLISECONDS);
                if(frame==null) continue;
                byte[] rgba;
                try(NativeImage pixels=frame.image) {
                    int[] values=pixels.getPixelsRGBA(); rgba=new byte[values.length*4];
                    for(int i=0,j=0;i<values.length;i++) {
                        int pixel=values[i];
                        rgba[j++]=(byte)pixel; rgba[j++]=(byte)(pixel>>>8);
                        rgba[j++]=(byte)(pixel>>>16); rgba[j++]=(byte)(pixel>>>24);
                    }
                }
                int target=Math.max(written,(int)((frame.at-session.start)/INTERVAL));
                if(last==null) last=rgba;
                while(written<target) {stdin.write(last);written++;}
                stdin.write(rgba);written++;last=rgba;
            }
            int total=(int)Math.ceil((session.stop-session.start)/(double)INTERVAL);
            while(last!=null && written<total) {stdin.write(last);written++;}
        } catch(Exception failure) {
            LogUtils.getLogger().error("LAUNCHER_DEBUG recording failed",failure);
            session.encoder.destroy();
        } finally {
            Frame extra; while((extra=session.frames.poll())!=null) extra.image.close();
        }
        try {
            if(!session.encoder.waitFor(30,TimeUnit.SECONDS) || session.encoder.exitValue()!=0)
                throw new IllegalStateException("Encoder did not finish successfully");
            Files.writeString(Path.of(session.output+".json"),new Gson().toJson(Map.of(
                    "fps",FPS,"capturedFrames",session.captured,"droppedFrames",session.dropped,"encodedFrames",written,
                    "width",session.width,"height",session.height,"durationSeconds",(session.stop-session.start)/1e9,
                    "sounds",new ArrayList<>(session.sounds))));
            Files.writeString(Path.of(session.output+".done"),"complete");
            LogUtils.getLogger().info("LAUNCHER_DEBUG RECORDING_SAVED {} frames={}",session.output,written);
        } catch(Exception failure) {LogUtils.getLogger().error("LAUNCHER_DEBUG could not finish video",failure);}
    }
}
