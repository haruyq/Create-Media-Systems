package org.haruyq.createmediasystems.disc;

import static org.haruyq.createmediasystems.client.mesh.Mesh.box;
import static org.haruyq.createmediasystems.client.mesh.Mesh.quad;

import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.haruyq.createmediasystems.disc.DriveEmulatorBlock.Drive;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * A drive emulator's drive, in the middle of the screen: an external disc drive whose tray is open with no disc and
 * closes on the one in the real drive (see DriveClient), its lamp flickering as the disc is read. What it shows is
 * what this client has of the emulator, so everyone who opens it sees the drive of whoever uses it. Whose disc it is
 * also chooses there which of its sound and subtitle streams everyone gets, where it has any to choose from.
 */
public final class DriveScreen extends Screen {
    /** How long (seconds) the disc takes to be put on the tray and the tray to close on it, or the other way. */
    private static final float MOVE_SECONDS = 1.7f;
    /** The share of that the disc takes; the tray moves for the rest. */
    private static final float DISC_SHARE = .45f;
    /** How steeply (degrees) the disc is held as it comes to the tray, its front up. */
    private static final float DISC_TILT = 25;
    private static final int LAMP_OFF = 0x26302B, LAMP_ON = 0x4BE37A, LAMP_FAILED = 0xFF5A3C;
    /** Plain white, for the mesh's own colours: the drive it is drawn from is whiter than any shared material. */
    private static final RenderType SOLID = RenderType.entityCutoutNoCull(
            ResourceLocation.withDefaultNamespace("textures/misc/white.png"));

    private final BlockPos pos;
    /** How far the drive has closed on a disc, 0 (open, empty) to 1; NaN before it is first drawn. */
    private float closed = Float.NaN;
    private Button eject;
    /** The disc's streams its buttons choose from (see DriveClient#tracks): they are made again as those change. */
    @Nullable
    private DiscSource.Tracks tracks;

    public static void open(BlockPos pos) {
        Minecraft.getInstance().setScreen(new DriveScreen(pos));
    }

    private DriveScreen(BlockPos pos) {
        super(DiscFeature.DRIVE_EMULATOR.get().getName());
        this.pos = pos;
    }

    static boolean shows(BlockPos pos) {
        return Minecraft.getInstance().screen instanceof DriveScreen screen && screen.pos.equals(pos);
    }

    @Nullable
    private DriveEmulatorBlock.Entity emulator() {
        return minecraft.level != null && minecraft.level.getBlockEntity(pos) instanceof DriveEmulatorBlock.Entity found
                ? found : null;
    }

    /** The drive's size on screen (pixels a side), and the height of its middle: its text goes below. */
    private float size() {
        return Math.min(width * .4f, height * .52f);
    }

    private float middle() {
        return height / 2f - size() * .27f;
    }

    @Override
    protected void init() {
        DriveClient.opened(pos);
        int y = (int) (middle() + size() * .6f) + 44;
        eject = addRenderableWidget(Button.builder(Component.literal("⏏"), b -> DriveClient.eject())
                .bounds(width / 2 - 10, y, 20, 20).build());
        DiscSource.Tracks tracks = this.tracks = DriveClient.tracks(pos);
        if (tracks == null) {
            return;
        }
        int wide = Math.min(150, width / 2 - 18);
        Locale locale = minecraft.getLocale();
        // Sound to choose between from two streams on; subtitles from one, as they can be off.
        if (tracks.audio.size() > 1) {
            addRenderableWidget(CycleButton.<Integer>builder(i -> name(tracks.audio, i, locale))
                    .withValues(IntStream.range(0, tracks.audio.size()).boxed().toList())
                    .withInitialValue(tracks.audio())
                    .create(width / 2 - 14 - wide, y, wide, 20, Component.translatable("create_media_systems.drive.audio"),
                            (button, value) -> DriveClient.choose(value, tracks.subtitle())));
        }
        if (!tracks.subtitles.isEmpty()) {
            addRenderableWidget(CycleButton.<Integer>builder(i -> i < 0
                            ? Component.translatable("create_media_systems.drive.subtitles.off")
                            : name(tracks.subtitles, i, locale))
                    .withValues(IntStream.range(-1, tracks.subtitles.size()).boxed().toList())
                    .withInitialValue(tracks.subtitle())
                    .create(width / 2 + 14, y, wide, 20, Component.translatable("create_media_systems.drive.subtitles"),
                            (button, value) -> DriveClient.choose(tracks.audio(), value)));
        }
    }

    /**
     * Stream {@code index} of {@code tracks} as its button names it: which of them it is, its language in the game's
     * if the disc says it, and a sound stream's codec and channels, which tell two of one language apart.
     */
    static Component name(List<DiscSource.Track> tracks, int index, Locale locale) {
        DiscSource.Track track = tracks.get(index);
        String language = track.languageName(locale);
        return Component.literal(index + 1 + "/" + tracks.size() + (language.isEmpty() ? "" : " " + language)
                + (track.channels() == 0 ? "" : " " + track.codec().toUpperCase(Locale.ROOT) + " " + track.channels() + "ch"));
    }

    @Override
    public void removed() {
        DriveClient.closed();
    }

    @Override
    public void tick() {
        if (emulator() == null) {
            onClose();
        } else if (DriveClient.tracks(pos) != tracks) {
            rebuildWidgets();
        }
    }

    /**
     * The access lamp: off with no disc and while one lies still, blinking evenly as it is read in, and flickering
     * as a real one does while it is played, which reads it in bursts.
     */
    private static int lamp(DriveEmulatorBlock.Entity emulator) {
        long now = System.currentTimeMillis();
        return switch (emulator.drive()) {
            case OPEN -> LAMP_OFF;
            case FAILED -> LAMP_FAILED;
            case LOADING, READING, DOWNLOADING -> now / 90 % 2 == 0 ? LAMP_ON : LAMP_OFF;
            case READY -> emulator.spinning() && (Long.hashCode(now / 70 * 0x9E3779B97F4A7C15L) & 7) < 5 ? LAMP_ON : LAMP_OFF;
        };
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        eject.visible = DriveClient.ejectable(pos);
        super.render(graphics, mouseX, mouseY, partialTick);
        DriveEmulatorBlock.Entity emulator = emulator();
        if (emulator == null) {
            return;
        }
        Drive drive = emulator.drive();
        float target = drive == Drive.OPEN ? 0 : 1;
        closed = Float.isNaN(closed) ? target
                : Mth.approach(closed, target, minecraft.getTimer().getRealtimeDeltaTicks() / 20 / MOVE_SECONDS);
        float size = size();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(width / 2f, middle(), 300);
        pose.scale(size, -size, size);
        // Seen from above its front left, like the drive it is drawn from.
        pose.mulPose(Axis.XP.rotationDegrees(28));
        pose.mulPose(Axis.YP.rotationDegrees(-32));
        drive(pose, graphics.bufferSource().getBuffer(SOLID), closed, drive == Drive.LOADING ? null : emulator.disc(), lamp(emulator));
        graphics.flush();
        pose.popPose();

        int y = (int) (middle() + size * .6f) + 6;
        graphics.drawCenteredString(font, title, width / 2, (int) (middle() - size * .43f) - 20, 0xFFFFFF);
        graphics.drawCenteredString(font, emulator.status(), width / 2, y, 0xFFFFFF);
        if (drive == Drive.OPEN) {
            graphics.drawCenteredString(font, Component.translatable("create_media_systems.drive.hint."
                    + DriveClient.hint(emulator)).withStyle(ChatFormatting.GRAY), width / 2, y + 12, 0xFFFFFF);
            return;
        }
        if (drive != Drive.LOADING) {
            // Known once the real drive has made the disc out.
            graphics.drawCenteredString(font, Component.translatable("create_media_systems.drive.disc", emulator.discName())
                    .withStyle(ChatFormatting.GOLD), width / 2, y += 12, 0xFFFFFF);
        }
        graphics.drawCenteredString(font, drive == Drive.FAILED
                ? Component.literal(font.plainSubstrByWidth(emulator.error(), width - 40)).withStyle(ChatFormatting.RED)
                : Component.translatable("create_media_systems.drive.host", emulator.hostName())
                        .withStyle(ChatFormatting.GRAY), width / 2, y + 12, 0xFFFFFF);
    }

    /**
     * The drive, a unit wide and deep, its front towards +z: a white shell with waves pressed into all of its top, a
     * black tray out of its front by how far it is from {@code closed}, a disc ({@code disc}; null for one of a kind
     * not known yet) put on that as it starts to close, the lamp and button on the tray's front, and its cable out of
     * the back.
     */
    private static void drive(PoseStack p, VertexConsumer v, float closed, @Nullable DriveEmulatorBlock.Disc disc, int lamp) {
        int light = LightTexture.FULL_BRIGHT;
        float travel = Mth.clamp((closed - DISC_SHARE) / (1 - DISC_SHARE), 0, 1);
        float out = .62f * (1 - travel * travel * (3 - 2 * travel));
        box(p, v, -.5f, 0, -.5f, .5f, .12f, .5f, 0xF2F2EF, light);
        for (int wave = 0; wave < 11; wave++) {
            for (int i = 0; i < 21; i++) {
                float x = -.4725f + i * .045f, xx = x + .045f;
                float z = -.425f + wave * .085f + .03f * Mth.sin(x * 12 + wave * .9f);
                float zz = -.425f + wave * .085f + .03f * Mth.sin(xx * 12 + wave * .9f);
                quad(p, v, x, .1205f, z - .008f, xx, .1205f, zz - .008f, xx, .1205f, zz + .008f, x, .1205f, z + .008f,
                        0, 1, 0, 0xD6D6D0, light);
            }
        }
        // The slot the tray leaves, then the tray: a plate with a well and a hub for the disc, and its front.
        box(p, v, -.48f, .018f, .499f, .48f, .078f, .502f, 0x0C0D10, light);
        box(p, v, -.45f, .03f, -.42f + out, .45f, .05f, .5f + out, 0x1B1D21, light);
        box(p, v, -.48f, .018f, .5f + out, .48f, .078f, .52f + out, 0x24262B, light);
        box(p, v, .2f, .04f, .52f + out, .23f, .056f, .523f + out, lamp, light);
        box(p, v, .3f, .036f, .52f + out, .38f, .06f, .524f + out, 0x3A3D44, light);
        p.pushPose();
        p.translate(0, .0505f, out);
        p.mulPose(Axis.XP.rotationDegrees(-90));
        Mesh.ring(p, v, 0, 0, 0, .44f, .075f, 48, 1, 0x2A2D33, light);
        Mesh.ring(p, v, 0, 0, .006f, .075f, 0, 48, 1, 0x4A4E57, light);
        p.popPose();
        if (closed > 0) {
            // Put in as a hand does, before the tray moves (and taken out so after): from the front, its front edge
            // held up, its back edge low enough to slide into the slot, under the shell, as it is laid flat on the hub.
            float left = 1 - Math.min(closed / DISC_SHARE, 1);
            float held = Math.max(0, 1 - (1 - left) / .3f);
            p.pushPose();
            p.translate(0, .0625f + .15f * held * held, out + .5f * left - .43f);
            p.mulPose(Axis.XP.rotationDegrees(-DISC_TILT * left * Mth.sqrt(left)));
            p.translate(0, 0, .43f);
            p.mulPose(Axis.XP.rotationDegrees(-90));
            disc(p, v, disc, light);
            p.popPose();
        }
        // The cable: a plug in the back, and a lead curving away over the table.
        box(p, v, .27f, .03f, -.56f, .33f, .07f, -.5f, 0xC9C9C4, light);
        for (int i = 0; i < 12; i++) {
            float t = i / 12f, tt = (i + 1) / 12f;
            float x = .3f + .35f * t * t, xx = .3f + .35f * tt * tt, z = -.56f - .5f * t, zz = -.56f - .5f * tt;
            quad(p, v, x - .018f, .03f, z, xx - .018f, .03f, zz, xx + .018f, .03f, zz, x + .018f, .03f, z,
                    0, 1, 0, 0xE9E9E6, light);
        }
    }

    /**
     * A disc, label up, around its clear hub: a Blu-ray's deep blue, a DVD's violet-tinted silver, a CD's plain
     * silver, which shows the colours of its sheen in rings, or the bare grey of one whose kind is not known yet.
     */
    private static void disc(PoseStack p, VertexConsumer v, @Nullable DriveEmulatorBlock.Disc disc, int light) {
        switch (disc) {
            case null -> {
                Mesh.ring(p, v, 0, 0, 0, .43f, .15f, 48, 1, 0xC9CED4, light);
                Mesh.ring(p, v, 0, 0, 0, .15f, .11f, 48, 1, 0xE3E6EA, light);
            }
            case BLU_RAY -> {
                Mesh.ring(p, v, 0, 0, 0, .43f, .15f, 48, 1, 0x2B63C9, light);
                Mesh.ring(p, v, 0, 0, 0, .15f, .11f, 48, 1, 0x9CC7F2, light);
            }
            case DVD -> {
                Mesh.ring(p, v, 0, 0, 0, .43f, .40f, 48, 1, 0xD9CFEA, light);
                Mesh.ring(p, v, 0, 0, 0, .40f, .15f, 48, 1, 0xB49BD8, light);
                Mesh.ring(p, v, 0, 0, 0, .15f, .11f, 48, 1, 0xE6DEF3, light);
            }
            case CD -> {
                Mesh.ring(p, v, 0, 0, 0, .43f, .36f, 48, 1, 0xC6E3EC, light);
                Mesh.ring(p, v, 0, 0, 0, .36f, .29f, 48, 1, 0xD6D2EE, light);
                Mesh.ring(p, v, 0, 0, 0, .29f, .22f, 48, 1, 0xF0D4DF, light);
                Mesh.ring(p, v, 0, 0, 0, .22f, .15f, 48, 1, 0xF1EBC8, light);
                Mesh.ring(p, v, 0, 0, 0, .15f, .11f, 48, 1, 0xECEEF0, light);
            }
        }
        Mesh.ring(p, v, 0, 0, 0, .11f, .055f, 48, 1, 0xDDE6EE, light);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
