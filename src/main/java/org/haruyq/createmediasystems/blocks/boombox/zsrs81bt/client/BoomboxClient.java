package org.haruyq.createmediasystems.blocks.boombox.zsrs81bt.client;

import static org.haruyq.createmediasystems.client.PonderScenes.notes;
import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import org.haruyq.createmediasystems.blocks.boombox.zsrs81bt.BoomboxBlock;
import org.haruyq.createmediasystems.blocks.boombox.zsrs81bt.BoomboxBlock.Band;
import org.haruyq.createmediasystems.blocks.boombox.zsrs81bt.BoomboxFeature;
import org.haruyq.createmediasystems.blocks.boombox.zsrs81bt.BoomboxKey;
import org.haruyq.createmediasystems.blocks.radio.RadioFeature;
import org.haruyq.createmediasystems.client.ClientFeature;
import org.haruyq.createmediasystems.client.ControlDrag;
import org.haruyq.createmediasystems.client.ItemRenderers;
import org.haruyq.createmediasystems.disc.DiscFeature;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.Create;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The CD radio on a client: its keys are pressed by pointing at them and right-clicking, as a hi-fi stack's are, and
 * the one pointed at is named above the hotbar. Its volume keys, and on FM its tuning keys, repeat while held. On FM
 * it hisses as much as its signal is short of full.
 */
public final class BoomboxClient implements ClientFeature {
    private static final long PRESSED_MILLIS = 180;
    /** A key held repeats after this many ticks, then every so many. */
    private static final int REPEAT_AFTER = 8, REPEAT_EVERY = 3;

    @Nullable
    private static BlockPos hoveredAt;
    @Nullable
    private static BoomboxKey hovered;
    @Nullable
    private static BlockPos pressedAt;
    @Nullable
    private static BoomboxKey pressed;
    private static long pressedTime;

    @Override
    public void register(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        ControlDrag.onUse(BoomboxClient::press);
        BoomboxBlock.Entity.clientTick = BoomboxClient::hiss;
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) -> event.registerBlockEntityRenderer(
                BoomboxFeature.ENTITY.get(), context -> new BoomboxRenderer()));
        modBus.addListener((RegisterClientExtensionsEvent event) -> ItemRenderers.register(event, BoomboxRenderer.Item::new,
                BoomboxFeature.ITEM.get()));
    }

    @Nullable
    static BoomboxKey hovered(BoomboxBlock.Entity set) {
        return set.getBlockPos().equals(hoveredAt) ? hovered : null;
    }

    @Nullable
    static BoomboxKey pressed(BoomboxBlock.Entity set) {
        return set.getBlockPos().equals(pressedAt) && Util.getMillis() - pressedTime < PRESSED_MILLIS ? pressed : null;
    }

    @Nullable
    private static BoomboxBlock.Entity set(@Nullable BlockPos pos) {
        var level = Minecraft.getInstance().level;
        return pos != null && level != null && level.getBlockEntity(pos) instanceof BoomboxBlock.Entity set ? set : null;
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        BoomboxKey was = hovered;
        BlockPos wasAt = hoveredAt;
        hovered = null;
        hoveredAt = null;
        if (mc.level != null && mc.player != null && mc.screen == null && mc.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK && ControlDrag.works(mc.player)) {
            BoomboxBlock.Entity set = set(hit.getBlockPos());
            if (set != null) {
                ControlDrag.Look look = ControlDrag.look(hit.getBlockPos());
                hovered = BoomboxKey.at(set.getBlockState().getValue(BoomboxBlock.FACING),
                        look.eye().subtract(Vec3.atLowerCornerOf(hit.getBlockPos())), look.along());
                hoveredAt = hovered == null ? null : hit.getBlockPos().immutable();
            }
        }
        if (ControlDrag.held() == null && hovered != null && (hovered != was || !hoveredAt.equals(wasAt))) {
            mc.gui.setOverlayMessage(hovered.label(), false);
        }
    }

    private static boolean press() {
        BoomboxBlock.Entity set = set(hoveredAt);
        if (hovered == null || set == null) {
            return false;
        }
        send(hoveredAt, hovered);
        boolean tunes = (hovered == BoomboxKey.PREVIOUS || hovered == BoomboxKey.NEXT) && set.band() == Band.FM;
        if (set.power() && (tunes || hovered == BoomboxKey.VOLUME_DOWN || hovered == BoomboxKey.VOLUME_UP)) {
            ControlDrag.hold(new Repeat(hoveredAt, hovered));
        }
        return true;
    }

    private static void send(BlockPos pos, BoomboxKey key) {
        pressed = key;
        pressedAt = pos;
        pressedTime = Util.getMillis();
        PacketDistributor.sendToServer(new BoomboxBlock.Press(pos, key.ordinal()));
    }

    /** A key held down: pressed again and again while it is still pointed at. */
    private static final class Repeat implements ControlDrag.Held {
        private final BlockPos pos;
        private final BoomboxKey key;
        private int ticks;

        Repeat(BlockPos pos, BoomboxKey key) {
            this.pos = pos;
            this.key = key;
        }

        @Override
        public BlockPos pos() {
            return pos;
        }

        @Override
        public void drag(ControlDrag.Look look) {
            if (++ticks >= REPEAT_AFTER && (ticks - REPEAT_AFTER) % REPEAT_EVERY == 0 && key == hovered && pos.equals(hoveredAt)) {
                send(pos, key);
            }
        }

        @Override
        public void release(boolean gone) {
        }
    }

    /** Starts the hiss of a set that has one and plays none. */
    private static void hiss(BoomboxBlock.Entity set) {
        var sounds = Minecraft.getInstance().getSoundManager();
        if (set.hiss() > 0 && !(set.noise instanceof Noise noise && sounds.isActive(noise))) {
            Noise noise = new Noise(set);
            set.noise = noise;
            sounds.play(noise);
        }
    }

    /** A set's hiss, from where it stands: one loop, as loud as the set says, which ends once that is nothing. */
    private static final class Noise extends AbstractTickableSoundInstance {
        private final BoomboxBlock.Entity set;

        Noise(BoomboxBlock.Entity set) {
            super(RadioFeature.STATIC.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            this.set = set;
            Vec3 at = Vec3.atCenterOf(set.getBlockPos());
            x = at.x;
            y = at.y;
            z = at.z;
            looping = true;
            delay = 0;
            volume = 0;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            float hiss = set.isRemoved() ? 0 : set.hiss();
            // Its signal comes every relay; eased, so the hiss does not step with it.
            volume = Mth.lerp(.2f, volume, hiss);
            if (hiss <= 0 && volume < .005f) {
                stop();
            }
        }
    }

    @Override
    public void registerPonder(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(BoomboxFeature.ITEM.getId()).addStoryBoard(Create.asResource("redstone_link"), BoomboxClient::scene);
    }

    private static void scene(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "boombox", "The Sunny ZS-RS81BT CD Radio");
        // Its keys are small: seen from nearer than a scene usually is.
        scene.scaleSceneView(1.25f);
        BlockPos pos = util.grid().at(2, 1, 2);
        Vec3 top = util.vector().topOf(pos).subtract(0, .55, 0);
        Vec3 front = util.vector().blockSurface(pos, Direction.NORTH).add(0, -.2, .2);
        ItemStack cd = new ItemStack(DiscFeature.CD.get());
        scene.world().setBlock(pos, BoomboxFeature.BLOCK.get().defaultBlockState().setValue(BoomboxBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(pos), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showControls(top, Pointing.DOWN, 60).rightClick();
        say(scene, 120, top, PonderPalette.GREEN, "Point at a key and right-click to press it. What you point at is named above the hotbar. Power, the lid's key and the volume are on top; the rest are on the front.");

        scene.world().modifyBlockEntity(pos, BoomboxBlock.Entity.class, set -> set.arrange(true, Band.FM, false, ItemStack.EMPTY));
        scene.overlay().showControls(front, Pointing.RIGHT, 60).rightClick();
        notes(scene, top, 130);
        say(scene, 130, front, "On FM it plays the radio station of its frequency to everyone near it. The << and >> keys tune it, and keep tuning while held. Where it receives no station it plays static.");

        scene.world().modifyBlockEntity(pos, BoomboxBlock.Entity.class, set -> set.arrange(true, Band.CD, true, ItemStack.EMPTY));
        scene.idle(10);
        scene.overlay().showControls(top, Pointing.DOWN, 60).rightClick().withItem(cd);
        scene.idle(20);
        scene.world().modifyBlockEntity(pos, BoomboxBlock.Entity.class, set -> set.arrange(true, Band.CD, true, cd));
        say(scene, 130, top, "Open the lid on its top with its key, then right-click the open lid with a CD or a Custom Disc to put it in. An empty hand takes the disc out again.");

        scene.world().modifyBlockEntity(pos, BoomboxBlock.Entity.class, set -> set.arrange(true, Band.CD, false, cd));
        scene.overlay().showControls(front, Pointing.RIGHT, 60).rightClick();
        notes(scene, top, 120);
        say(scene, 120, front, "Press the CD key, and shut the lid: the disc plays from its first track. Play, stop, << and >> work it from there.");
    }
}
