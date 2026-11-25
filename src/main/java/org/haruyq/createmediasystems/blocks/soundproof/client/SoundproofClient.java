package org.haruyq.createmediasystems.blocks.soundproof.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.soundproof.SoundproofFeature;
import org.haruyq.createmediasystems.blocks.soundproof.Soundproofing;
import org.haruyq.createmediasystems.client.ClientFeature;
import org.haruyq.createmediasystems.client.Quill;
import org.haruyq.createmediasystems.playback.StreamSound;

import net.createmod.catnip.outliner.Outliner;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Soundproofing as this client hears it: every sound of the mod that plays from somewhere in the world is as loud as
 * what is between it and the camera lets through (see SoundproofMixin). Holding an insulation sheet outlines the
 * blocks near that have one in them.
 */
public final class SoundproofClient implements ClientFeature {
    private static final String PACKAGE = CreateMediaSystems.class.getPackageName() + ".";
    /** How often what is in a sound's way is looked up again (ticks), and how far its loudness then moves a tick. */
    private static final int TRACE_TICKS = 5;
    private static final float STEP = .1f;
    private static final int OUTLINE_RANGE = 16;
    private static final Object OUTLINE = new Object();
    private static final Map<SoundInstance, Heard> HEARD = new WeakHashMap<>();

    private static final class Heard {
        float now;
        float target;
        long traced;
    }

    @Override
    public void register(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(SoundproofClient::outlineSheets);
        SoundproofDoorRenderer.register(modBus);
    }

    @Override
    public void registerPonder(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        SoundproofPonder.register(helper);
    }

    /** The part (0..1) of {@code sound}'s volume that is heard now; 1 for a sound that is not the mod's. */
    public static float gain(SoundInstance sound) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || sound.isRelative() || !(sound.getClass().getName().startsWith(PACKAGE)
                || sound.getLocation().getNamespace().equals(CreateMediaSystems.MOD_ID))) {
            return 1;
        }
        Heard heard = HEARD.get(sound);
        boolean first = heard == null;
        if (first) HEARD.put(sound, heard = new Heard());
        long time = level.getGameTime();
        if (first || time - heard.traced >= TRACE_TICKS) {
            heard.traced = time;
            Vec3 from = sound instanceof StreamSound stream ? stream.tracedFrom()
                    : new Vec3(sound.getX(), sound.getY(), sound.getZ());
            heard.target = (float) Soundproofing.transmission(level, pos -> Soundproofing.sheeted(level, pos), from,
                    mc.gameRenderer.getMainCamera().getPosition());
            // A stream that starts again is a new sound: it starts as loud as the last was heard, not at full.
            if (first) heard.now = heard.target;
        }
        heard.now += Mth.clamp(heard.target - heard.now, -STEP, STEP);
        return heard.now;
    }

    private static void outlineSheets(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !mc.player.isHolding(SoundproofFeature.SHEET.get())) return;
        BlockPos at = mc.player.blockPosition();
        List<BlockPos> sheets = new ArrayList<>();
        for (int x = at.getX() - OUTLINE_RANGE >> 4; x <= at.getX() + OUTLINE_RANGE >> 4; x++) {
            for (int z = at.getZ() - OUTLINE_RANGE >> 4; z <= at.getZ() + OUTLINE_RANGE >> 4; z++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (BlockPos pos : chunk.getExistingData(SoundproofFeature.SHEETS).orElse(Set.of())) {
                    if (pos.closerThan(at, OUTLINE_RANGE)) sheets.add(pos);
                }
            }
        }
        if (!sheets.isEmpty()) {
            Outliner.getInstance().showCluster(OUTLINE, sheets).colored(Quill.PICKED).lineWidth(Quill.LINE_WIDTH);
        }
    }
}
