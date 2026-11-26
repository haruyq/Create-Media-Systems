package org.haruyq.createmediasystems.blocks.turntable.atlpa2.client;

import static org.haruyq.createmediasystems.blocks.turntable.atlpa2.TurntableLayout.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.turntable.atlpa2.TurntableBlock;
import org.haruyq.createmediasystems.blocks.turntable.atlpa2.TurntableFeature;
import org.haruyq.createmediasystems.client.IrisCompat;
import org.haruyq.createmediasystems.client.ItemRenderers;
import org.haruyq.createmediasystems.client.WorldEvents;

import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Draws the whole turntable, as a block and as an item: its deck, and on it the record turning, the arm swung and
 * lifted, the lift lever and the dust cover as they are now (see TurntableClient). Its shapes are built here, once,
 * in the turntable's own space (see TurntableLayout) facing south, from the materials of one texture
 * (tools/at_lpa2/gen_at_lpa2.py paints it). A record is black vinyl with the label of its disc's item: the colours of
 * the label in the middle of the disc's picture tint the record's label.
 *
 * Clear acrylic is drawn translucent, after everything solid: first its faces seen from inside it, which are the far
 * ones, then those seen from outside. The game does not sort what is translucent here and a face drawn hides what is
 * drawn behind it afterwards, so drawn the other way round the far edges of the plinth would not show through it.
 */
public final class TurntableRenderer implements BlockEntityRenderer<TurntableBlock.Entity> {
    private static final ResourceLocation TEXTURE = CreateMediaSystems.id("block/turntable/at_lpa2"),
            VINYL = CreateMediaSystems.id("block/turntable/record"), LABEL = CreateMediaSystems.id("block/turntable/record_label");
    private static final int SEGMENTS = 128, WHITE = 0xFFFFFFFF;
    private static final float SHEET = 512, CX = (float) CENTER_X, CZ = (float) CENTER_Z, TOP = (float) PLINTH_TOP;
    /** A 12-inch record's label is 100 mm across (sixteenths), with a ring of its second colour part way out. */
    private static final float LABEL_RADIUS = 1.82f, RING_IN = .55f, RING_OUT = .66f;
    /** The colours of the label of each disc picture (by its sprite's name): its own and its ring's. */
    private static final Map<ResourceLocation, int[]> LABELS = new HashMap<>();
    /** Round the record: the cosine and sine of each of its segments' corners. */
    private static final float[] COS = new float[SEGMENTS + 1], SIN = new float[SEGMENTS + 1];

    /**
     * A material's place in the texture (texels; gen_at_lpa2.py has the same places), and how wide the border is
     * that a face keeps unstretched, so that the bright line along a face's edge is as wide on a face of any size.
     */
    private record Tile(float x, float y, float w, float h, float border) {
    }

    private static final Tile GLOW = new Tile(0, 0, 24, 24, 8), EDGE = new Tile(32, 0, 24, 24, 8),
            SATIN = new Tile(64, 0, 24, 24, 8), BLACK = new Tile(96, 0, 24, 24, 8), HOUSING = new Tile(128, 0, 24, 24, 8),
            IVORY = new Tile(160, 0, 24, 24, 8), WIRES = new Tile(192, 0, 24, 24, 0), GLINT = new Tile(224, 0, 8, 8, 0),
            TURNED = new Tile(0, 32, 64, 32, 0), KNURL = new Tile(64, 32, 64, 32, 0), CARBON = new Tile(128, 32, 64, 32, 0),
            SHINE = new Tile(192, 32, 64, 8, 0), RUBBER = new Tile(192, 48, 64, 8, 0), CAP = new Tile(0, 64, 64, 64, 0),
            CAP_BLACK = new Tile(64, 64, 64, 64, 0), KEY = new Tile(128, 64, 64, 64, 0), PLATE = new Tile(192, 64, 64, 64, 0),
            LAMP = new Tile(256, 64, 16, 16, 0), PLATTER = new Tile(0, 128, 8, 128, 0), FRONT = new Tile(128, 128, 256, 56, 0),
            LOGO = new Tile(128, 192, 64, 8, 0);

    /**
     * What a round part is made of: the materials of its side, of its ends and of the bevels between them. A side's
     * material goes once round the part and an end's is a picture of the whole end, its rim {@link #RIM} from its
     * middle; or they are {@code profiles}: the side's the same all round, the end's running from its middle (the
     * material's top) out to its rim, so that a ring painted on it is as round as the part.
     */
    private record Stock(Tile side, Tile end, Tile bevel, boolean profiles) {
        float endU(float cos, float out) {
            return profiles ? end.x + end.w / 2 : end.x + end.w * (.5f + RIM * out * cos);
        }

        float endV(float sin, float out) {
            return profiles ? end.y + .02f + (end.h - .04f) * out : end.y + end.h * (.5f + RIM * out * sin);
        }
    }

    private static final float RIM = .455f;
    private static final Stock ALUMINIUM = new Stock(TURNED, CAP, SHINE, false), KNURLED = new Stock(KNURL, CAP, SHINE, false),
            RUBBERY = new Stock(RUBBER, CAP_BLACK, RUBBER, false), CARBON_TUBE = new Stock(CARBON, CAP_BLACK, RUBBER, false),
            KEYED = new Stock(TURNED, KEY, SHINE, false), ACRYLIC = new Stock(EDGE, PLATTER, GLINT, true);

    /** Quads, kept as their corners: x, y, z (sixteenths), u, v (fractions of the texture) and the normal. */
    private static final class Mesh {
        private float[] data = new float[2048];
        private int size;

        void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz) {
            if (size + 8 > data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            float[] d = data;
            d[size] = x;
            d[size + 1] = y;
            d[size + 2] = z;
            d[size + 3] = u / SHEET;
            d[size + 4] = v / SHEET;
            d[size + 5] = nx;
            d[size + 6] = ny;
            d[size + 7] = nz;
            size += 8;
        }

        void draw(PoseStack.Pose pose, VertexConsumer out, TextureAtlasSprite sprite, int light, int overlay) {
            draw(pose, out, sprite, WHITE, light, overlay);
        }

        void draw(PoseStack.Pose pose, VertexConsumer out, TextureAtlasSprite sprite, int color, int light, int overlay) {
            float[] d = data;
            for (int i = 0; i < size; i += 8) {
                out.addVertex(pose, d[i], d[i + 1], d[i + 2]).setColor(color).setUv(sprite.getU(d[i + 3]), sprite.getV(d[i + 4]))
                        .setOverlay(overlay).setLight(light).setNormal(pose, d[i + 5], d[i + 6], d[i + 7]);
            }
        }
    }

    /** A box's faces. */
    private static final int UP = 1, UNDER = 2, NORTH = 4, SOUTH = 8, WEST = 16, EAST = 32, ALL = 63;
    private static final float[] XP = {1, 0, 0}, XN = {-1, 0, 0}, YN = {0, -1, 0}, ZP = {0, 0, 1}, ZN = {0, 0, -1};
    /**
     * For a round part along x, y or z: its axis, and the two directions across it that its angles are measured
     * between, from the first towards the second. They are picked for the highlights painted round a part's side
     * (at 125 degrees, and a fainter one at 300) to fall on its front left if it stands, on its top if it lies.
     */
    private static final float[][][] AXES = {{XP, ZN, {0, 1, 0}}, {{0, 1, 0}, ZN, XN}, {ZP, XP, {0, 1, 0}}};
    /** How much of a bordered material's face is its border (sixteenths), where the face is big enough. */
    private static final float BORDER = 1;
    /** The belt's bottom round the platter's rim, and how wide it is. */
    private static final float BELT_Y = (float) PLATTER_BOTTOM + .28f, BELT_WIDTH = .14f;

    /**
     * The deck's shapes: what is solid of the deck itself, of the arm, of the lift lever and of the dust cover; the
     * clear plinth, platter and cover, each as seen from inside and from outside; and the three lamps, lit.
     */
    private static final Mesh BASE = new Mesh(), ARM = new Mesh(), CUE = new Mesh(), COVER = new Mesh(),
            BASE_IN = new Mesh(), BASE_OUT = new Mesh(), PLATTER_IN = new Mesh(), PLATTER_OUT = new Mesh(),
            COVER_IN = new Mesh(), COVER_OUT = new Mesh();
    private static final Mesh[] LAMPS = {new Mesh(), new Mesh(), new Mesh()};

    static {
        for (int i = 0; i <= SEGMENTS; i++) {
            COS[i] = (float) Math.cos(2 * Math.PI * i / SEGMENTS);
            SIN[i] = (float) Math.sin(2 * Math.PI * i / SEGMENTS);
        }
        base();
        arm();
        cover();
    }

    /**
     * The clear parts in the world: as Sheets.translucentCullBlockSheet, but with shaders/core/acrylic, which shows
     * the blocks and the sky about them mirrored in them as they are from where they are seen (see around), and what
     * is behind a thick sheet where its thickness bends the sight to. They are drawn once every block entity's solid
     * parts are, for these hide what is drawn behind them later: first their faces seen from inside (FAR), then, over
     * a copy of the picture so far (behind) for the shader to take what is behind them from, those seen from outside
     * (NEAR).
     */
    private static ShaderInstance acrylicShader;
    private static final RenderType ACRYLIC_SHEET = RenderType.create("create_media_systems_acrylic",
            DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536, true, true,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(() -> acrylicShader))
                    .setTextureState(RenderStateShard.BLOCK_SHEET)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setOverlayState(RenderStateShard.OVERLAY)
                    .createCompositeState(true));
    private static final MultiBufferSource.BufferSource FAR = MultiBufferSource.immediate(new ByteBufferBuilder(1536));
    private static final MultiBufferSource.BufferSource NEAR = MultiBufferSource.immediate(new ByteBufferBuilder(1536));
    private static TextureTarget behind;
    private static boolean clearPending;
    /**
     * The blocks about the camera, for the shader to mirror: SIDE cells each way, a cell of the world at its place
     * modulo SIDE, in a picture SIDE slices wide. A full opaque block's cell holds its map colour, and in its alpha
     * (over a half) how much it glows; any other holds the block light and the sky light there, and no alpha.
     */
    private static final int SIDE = 32;
    private static DynamicTexture around;
    private static long aroundTime;
    private static int aroundSlice;
    /** A vertex colour that tells the shader how thick (sixteenths) the sheet behind a face is; white is not at all. */
    private static final int PLINTH_THICK = thick(PLINTH_TOP - PLINTH_BOTTOM), PLATTER_THICK = thick(PLATTER_TOP - PLATTER_BOTTOM);

    static void register(IEventBus modBus) {
        modBus.addListener((RegisterShadersEvent event) -> {
            try {
                event.registerShader(new ShaderInstance(event.getResourceProvider(), CreateMediaSystems.id("acrylic"),
                        DefaultVertexFormat.NEW_ENTITY), loaded -> acrylicShader = loaded);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        // The last to draw at this stage, as the vanilla sheet is: after displays' pictures and stage lights' beams
        // (LightingRenderer listens at the lowest priority too, and LightingClient registers before TurntableClient).
        // With Iris, over the finished frame, as displays' pictures and those beams are under a shader pack
        // (ScreenLight), the world's depth being still there: a pack draws the world with its own shaders alone, and
        // even with none Iris holds back what block entities draw until translucent terrain, which the clear parts'
        // depth would then hide (see DisplayRenderer.PICTURES).
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (RenderLevelStageEvent event) -> {
            if (clearPending && event.getStage() == (IrisCompat.loaded()
                    ? RenderLevelStageEvent.Stage.AFTER_LEVEL : RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES)) {
                clearPending = false;
                // After the level, the level's matrices are no longer the ones set.
                RenderSystem.backupProjectionMatrix();
                RenderSystem.setProjectionMatrix(event.getProjectionMatrix(), VertexSorting.DISTANCE_TO_ORIGIN);
                Matrix4fStack view = RenderSystem.getModelViewStack();
                view.pushMatrix();
                view.set(event.getModelViewMatrix());
                RenderSystem.applyModelViewMatrix();
                Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
                // What other block entities drew last waits in the game's buffers still: a sign's text, a head.
                Minecraft.getInstance().renderBuffers().bufferSource().endLastBatch();
                ClientLevel level = Minecraft.getInstance().level;
                survey(level, event.getCamera().getBlockPosition());
                RenderSystem.setShaderTexture(5, around.getId());
                Vec3 eye = event.getCamera().getPosition();
                acrylicShader.safeGetUniform("Eye").set((float) Mth.positiveModulo(eye.x, SIDE),
                        (float) Mth.positiveModulo(eye.y, SIDE), (float) Mth.positiveModulo(eye.z, SIDE));
                // As LevelRenderer.renderSky turns the sun: overhead at noon, in the east (x) in the morning.
                float turn = level.getSunAngle(event.getPartialTick().getGameTimeDeltaPartialTick(false));
                acrylicShader.safeGetUniform("Sun").set(-Mth.sin(turn), Mth.cos(turn), 0f,
                        level.effects().skyType() != DimensionSpecialEffects.SkyType.NORMAL ? 0
                                : 1 - level.getRainLevel(1));
                FAR.endBatch();
                RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
                // A depth blit needs both targets' depth formats alike, and another mod can give the main one a stencil.
                if (behind == null || behind.isStencilEnabled() != main.isStencilEnabled()) {
                    if (behind != null) {
                        behind.destroyBuffers();
                    }
                    behind = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
                    if (main.isStencilEnabled()) {
                        behind.enableStencil();
                    }
                } else if (behind.width != main.width || behind.height != main.height) {
                    behind.resize(main.width, main.height, Minecraft.ON_OSX);
                }
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, behind.frameBufferId);
                GL30.glBlitFramebuffer(0, 0, main.width, main.height, 0, 0, main.width, main.height,
                        GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
                main.bindWrite(false);
                RenderSystem.setShaderTexture(3, behind.getColorTextureId());
                RenderSystem.setShaderTexture(4, behind.getDepthTextureId());
                NEAR.endBatch();
                view.popMatrix();
                RenderSystem.applyModelViewMatrix();
                RenderSystem.restoreProjectionMatrix();
            }
        });
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) -> event.registerBlockEntityRenderer(
                TurntableFeature.ENTITY.get(), context -> new TurntableRenderer()));
        modBus.addListener((RegisterClientExtensionsEvent event) -> ItemRenderers.register(event, Item::new,
                TurntableFeature.ITEM.get()));
    }

    /**
     * Brings {@link #around} up to date about the camera's block.
     * ponytail: four slices a tick while a turntable is in sight, so a block set, or one the camera comes within
     * reach of, is mirrored up to eight ticks late; and a block is one colour, its map's. Fill a slice when a block
     * in it changes, or keep each block's sprite, if either shows.
     */
    private static void survey(ClientLevel level, BlockPos eye) {
        if (around == null) {
            around = new DynamicTexture(SIDE * SIDE, SIDE, true);
        }
        long now = level.getGameTime();
        int slices = (int) Math.min(SIDE, Math.abs(now - aroundTime) * 4);
        if (slices == 0) {
            return;
        }
        aroundTime = now;
        NativeImage cells = around.getPixels();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int x0 = eye.getX() - SIDE / 2, y0 = eye.getY() - SIDE / 2, z0 = eye.getZ() - SIDE / 2;
        for (; slices > 0; slices--) {
            int sz = aroundSlice++ & SIDE - 1;
            for (int sy = 0; sy < SIDE; sy++) {
                for (int sx = 0; sx < SIDE; sx++) {
                    // The one cell within reach of the camera that is kept at this place.
                    at.set(x0 + (sx - x0 & SIDE - 1), y0 + (sy - y0 & SIDE - 1), z0 + (sz - z0 & SIDE - 1));
                    BlockState state = level.getBlockState(at);
                    int cell;
                    if (state.isSolidRender(level, at)) {
                        int color = state.getMapColor(level, at).col;
                        cell = 128 + 8 * state.getLightEmission(level, at) << 24 | (color & 0xFF) << 16 | color & 0xFF00
                                | color >> 16 & 0xFF;
                    } else {
                        cell = level.getBrightness(LightLayer.SKY, at) * 17 << 8 | level.getBrightness(LightLayer.BLOCK, at) * 17;
                    }
                    cells.setPixelRGBA(sx + SIDE * sz, sy, cell);
                }
            }
        }
        around.upload();
    }

    /** Its open cover stands up into the block above. */
    @Override
    public AABB getRenderBoundingBox(TurntableBlock.Entity turntable) {
        return new AABB(turntable.getBlockPos()).expandTowards(0, 1, 0).inflate(.2);
    }

    @Override
    public void render(TurntableBlock.Entity turntable, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        if (turntable.getLevel() == null) return;
        TurntableClient.Motion motion = TurntableClient.motion(turntable);
        Direction facing = turntable.getBlockState().getValue(TurntableBlock.FACING);
        pose.pushPose();
        pose.translate(.5, 0, .5);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.translate(-.5, 0, -.5);
        float cover = motion.cover(partialTick);
        // Only in the world, seen by the player, are a vertex's place and normal what the acrylic shader takes them for.
        boolean world = WorldEvents.isClientWorld(turntable.getLevel())
                && !(IrisCompat.loaded() && veiled(turntable));
        draw(pose, buffers, world, light, overlay, turntable.record(), motion.platter(partialTick), motion.arm(partialTick),
                TurntableClient.lift(turntable, partialTick), TurntableClient.lever(turntable, partialTick),
                cover * cover * (3 - 2 * cover),
                !turntable.power() ? 0 : turntable.speed() == TurntableBlock.Speed.RPM_45 ? 0b101 : 0b011);
        pose.popPose();
    }

    /**
     * Whether a translucent block or a fluid is between the camera and the turntable: stained glass, water. What is
     * drawn over the finished frame, as the clear parts are with Iris, is not seen behind those, whose depth is in
     * the frame; the turntable's clear parts are then drawn with the world, with their light painted on.
     */
    private static boolean veiled(TurntableBlock.Entity turntable) {
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        BlockPos pos = turntable.getBlockPos();
        for (int corner = 0; corner < 4; corner++) {
            Vec3 to = new Vec3(pos.getX() + .1 + .8 * (corner & 1), pos.getY() + .3, pos.getZ() + .1 + .8 * (corner >> 1));
            if (BlockGetter.traverseBlocks(eye, to, turntable.getLevel(), (level, at) -> {
                BlockState state = level.getBlockState(at);
                return !state.getFluidState().isEmpty()
                        || ItemBlockRenderTypes.getRenderLayers(state).contains(RenderType.translucent()) ? true : null;
            }, level -> null) != null) {
                return true;
            }
        }
        return false;
    }

    /** The turntable as an item: as it comes out of its box, its cover shut and its arm on its rest. */
    static final class Item extends BlockEntityWithoutLevelRenderer {
        Item() {
            super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        }

        @Override
        public void renderByItem(ItemStack item, ItemDisplayContext context, PoseStack pose, MultiBufferSource buffers,
                                 int light, int overlay) {
            draw(pose, buffers, false, light, overlay, ItemStack.EMPTY, 0, 0, 1, 1, 0, 0);
        }
    }

    /**
     * The turntable in the block at {@code pose}, facing south: the platter turned {@code platter} degrees clockwise
     * seen from above with {@code record} on it, the arm swung {@code arm} degrees in from its rest and lifted
     * {@code lift} of the way (0 to 1), the lift lever {@code lever} of the way up and the cover {@code cover} of the
     * way open; and of its lamps (power, 33, 45), those whose bit of {@code lamps} is set lit. Its clear parts are
     * drawn with the acrylic shader if it is in the {@code world}, or else as the light painted on them shows them.
     */
    private static void draw(PoseStack pose, MultiBufferSource buffers, boolean world, int light, int overlay,
                             ItemStack record, float platter, float arm, float lift, float lever, float cover,
                             int lamps) {
        pose.pushPose();
        pose.scale(1 / 16f, 1 / 16f, 1 / 16f);
        PoseStack.Pose deck = pose.last().copy();
        PoseStack.Pose turning = turned(pose, CENTER_X, 0, CENTER_Z, Axis.YP, -platter);
        // The arm swings about its pillar, and its head rises about the pivot's crossways axis.
        pose.pushPose();
        pose.translate(PIVOT_X, PIVOT_Y, PIVOT_Z);
        pose.mulPose(Axis.YP.rotationDegrees(-arm));
        pose.mulPose(Axis.XP.rotationDegrees(-LIFT_DEGREES * lift));
        pose.translate(-PIVOT_X, -PIVOT_Y, -PIVOT_Z);
        PoseStack.Pose swung = pose.last().copy();
        pose.popPose();
        PoseStack.Pose raised = turned(pose, 0, CUE_Y, CUE_Z, Axis.XP, -CUE_DEGREES * lever);
        PoseStack.Pose opened = turned(pose, 0, HINGE_Y, HINGE_Z, Axis.XP, -COVER_DEGREES * cover);
        pose.popPose();

        TextureAtlasSprite sprite = sprite(TEXTURE);
        VertexConsumer solid = buffers.getBuffer(Sheets.cutoutBlockSheet());
        BASE.draw(deck, solid, sprite, light, overlay);
        ARM.draw(swung, solid, sprite, light, overlay);
        CUE.draw(raised, solid, sprite, light, overlay);
        COVER.draw(opened, solid, sprite, light, overlay);
        for (int i = 0; i < LAMPS.length; i++) {
            if ((lamps >> i & 1) != 0) {
                LAMPS[i].draw(deck, solid, sprite, LightTexture.FULL_BRIGHT, overlay);
            }
        }
        if (!record.isEmpty()) {
            record(turning, solid, record, light, overlay);
        }

        if (world && IrisCompat.shadowPass()) {
            // A shader pack's shadow map, drawn from the sun: clear acrylic casts no shadow.
            return;
        }
        clearPending |= world;
        VertexConsumer far = world ? FAR.getBuffer(ACRYLIC_SHEET) : buffers.getBuffer(Sheets.translucentCullBlockSheet());
        VertexConsumer near = world ? NEAR.getBuffer(ACRYLIC_SHEET) : far;
        COVER_IN.draw(opened, far, sprite, WHITE, light, overlay);
        BASE_IN.draw(deck, far, sprite, WHITE, light, overlay);
        BASE_OUT.draw(deck, near, sprite, world ? PLINTH_THICK : WHITE, light, overlay);
        PLATTER_IN.draw(deck, far, sprite, WHITE, light, overlay);
        PLATTER_OUT.draw(deck, near, sprite, world ? PLATTER_THICK : WHITE, light, overlay);
        COVER_OUT.draw(opened, near, sprite, WHITE, light, overlay);
    }

    /** See PLINTH_THICK: the thickness is in what the colour's alpha lacks, a quarter of a block being all of it. */
    private static int thick(double sixteenths) {
        return 0xFFFFFF | 255 - (int) Math.round(sixteenths / 4 * 255) << 24;
    }

    /** {@code pose} turned {@code degrees} about {@code axis} through the point (sixteenths). */
    private static PoseStack.Pose turned(PoseStack pose, double x, double y, double z, Axis axis, float degrees) {
        pose.pushPose();
        pose.translate(x, y, z);
        pose.mulPose(axis.rotationDegrees(degrees));
        pose.translate(-x, -y, -z);
        PoseStack.Pose turned = pose.last().copy();
        pose.popPose();
        return turned;
    }

    /** The deck: everything that stays where it is, the clear platter too, which looks the same however it turns. */
    private static void base() {
        float bottom = (float) PLINTH_BOTTOM, px = (float) PIVOT_X, py = (float) PIVOT_Y, pz = (float) PIVOT_Z;
        // Each foot: a knurled disc, a neck and a washer under the plinth, the stud through it, and its cap on top.
        for (float[] at : new float[][] {{2, 2}, {14, 2}, {2, 11}, {14, 11}}) {
            round(BASE, KNURLED, 1, at[0], 0, at[1], 1, .75, 3);
            round(BASE, ALUMINIUM, 1, at[0], .75, at[1], .45, bottom - .84, 0);
            round(BASE, ALUMINIUM, 1, at[0], bottom - .09, at[1], .7, .08, 3);
            round(BASE, ALUMINIUM, 1, at[0], bottom, at[1], .14, TOP - .02 - bottom, 0);
            round(BASE, ALUMINIUM, 1, at[0], TOP, at[1], .35, .12, 2);
        }
        // The motor in the plinth at the back left, and its pulley above it.
        round(BASE, ALUMINIUM, 1, MOTOR_X, bottom + .05, MOTOR_Z, .7, TOP - .07 - bottom, 3);
        round(BASE, ALUMINIUM, 1, MOTOR_X, TOP, MOTOR_Z, .45, .95, 2);
        belt();
        // Under the spindle the optical sensor's white housing, let into the plinth, its board on top; the bearing
        // on it, up through the platter; and the spindle.
        box(BASE, HOUSING, HOUSING, 4.5f, bottom + .2f, 4.5f, 8.5f, TOP - .02f, 8.5f, 0, false, ALL & ~UP);
        panel(BASE, PLATE, new float[] {4.5f, TOP - .02f, 4.5f}, XP, ZP, 4, 4, 0);
        float under = (float) PLATTER_BOTTOM - .02f, upper = (float) PLATTER_TOP - .02f;
        round(BASE, ALUMINIUM, 1, CX, TOP - .02, CZ, .55, under - TOP + .02, 2);
        round(BASE, ALUMINIUM, 1, CX, under, CZ, .45, upper - under, 2);
        round(BASE, ALUMINIUM, 1, CX, upper, CZ, .13, .72, 2);

        // The tonearm's base, in two steps with its bolts, and its pillar up to the pivot's housing.
        round(BASE, ALUMINIUM, 1, px, TOP, pz, 1.35, .2, 2);
        round(BASE, ALUMINIUM, 1, px, TOP + .2, pz, .95, .3, 2);
        round(BASE, ALUMINIUM, 1, px, TOP + .5, pz, .42, py - .45 - TOP - .5, 0);
        for (int bolt = 0; bolt < 3; bolt++) {
            double a = Math.toRadians(30 + 120 * bolt);
            round(BASE, RUBBERY, 1, px + 1.15 * Math.cos(a), TOP + .2, pz + 1.15 * Math.sin(a), .09, .05, 2);
        }
        // The arm's rest: a post, and a cradle the tube lies in, open to the platter's side for the arm to swing out.
        round(BASE, ALUMINIUM, 1, px, TOP, 9.25, .15, py + .07 - TOP, 0);
        box(BASE, BLACK, px - .35f, py + .07f, 9.05f, px + .35f, py + .17f, 9.45f);
        box(BASE, BLACK, px + .23f, py + .17f, 9.05f, px + .35f, py + .45f, 9.45f);
        box(BASE, BLACK, px - .35f, py + .17f, 9.05f, px - .23f, py + .23f, 9.45f);
        // The lift lever's post.
        round(BASE, ALUMINIUM, 1, 15, TOP, CUE_Z, .22, CUE_Y + .1 - TOP, 2);
        // The dust cover's hinge seats, on the plinth's back edge.
        for (float x : new float[] {3.4f, 12.3f}) {
            box(BASE, BLACK, x, TOP, .5f, x + .9f, (float) HINGE_Y, 1);
        }

        // The control unit before the plinth: blasted aluminium, its keys and their lamps on its front.
        float x0 = (float) UNIT_X0, x1 = (float) UNIT_X1, top = (float) UNIT_TOP, front = (float) UNIT_Z1;
        box(BASE, SATIN, SATIN, x0, 0, (float) UNIT_Z0, x1, top, front, 0, false, UP | NORTH | WEST | EAST);
        panel(BASE, FRONT, new float[] {x0, top, front}, XP, YN, x1 - x0, top, 0);
        for (int i = 0; i < KEYS.length; i++) {
            double[] key = KEYS[i];
            round(BASE, KEYED, 2, key[0], key[1], front, key[2], .12, 2);
            if (i < LAMPS.length) {
                panel(LAMPS[i], LAMP, new float[] {(float) key[0] - .13f, (float) (key[1] + LAMP_RISE) + .13f, front + .01f},
                        XP, YN, .26f, .26f, 0);
            }
        }
        // Its cable: from a plug in its left side, along the ground and up into the plinth.
        round(BASE, RUBBERY, 0, x0 - .35, .17, 14.3, .17, .35, 1);
        round(BASE, RUBBERY, 0, 5.2, .1, 14.3, .1, x0 - .35 - 5.2, 0);
        round(BASE, RUBBERY, 2, 5.2, .1, 11.2, .1, 3.2, 2);
        round(BASE, RUBBERY, 1, 5.2, .1, 11.2, .1, bottom - .1, 0);

        // The clear plinth, its upright corners cut; and the maker's name printed on it, at the front right.
        box(BASE_IN, GLOW, EDGE, .5f, bottom, .5f, 15.5f, TOP, 12.5f, .3f, true, ALL);
        box(BASE_OUT, GLOW, EDGE, .5f, bottom, .5f, 15.5f, TOP, 12.5f, .3f, false, ALL);
        panel(BASE_OUT, LOGO, new float[] {8.6f, TOP + .02f, 11.5f}, XP, ZP, 3.6f, .45f, 0);
        // The clear platter, a thick disc.
        round(PLATTER_IN, ACRYLIC, 1, CX, PLATTER_BOTTOM, CZ, PLATTER_RADIUS, PLATTER_TOP - PLATTER_BOTTOM, 3, 0, true);
        round(PLATTER_OUT, ACRYLIC, 1, CX, PLATTER_BOTTOM, CZ, PLATTER_RADIUS, PLATTER_TOP - PLATTER_BOTTOM, 3, 0, false);
    }

    /**
     * The arm, on its rest (it points forwards from its pivot), and the lift lever, down. From the back: the
     * counterweight with its black scale, the pivot's housing with its bearings across it, the carbon tube, the
     * headshell with its finger lift, and under it the cartridge with its four leads and its stylus.
     */
    private static void arm() {
        float px = (float) PIVOT_X, py = (float) PIVOT_Y, pz = (float) PIVOT_Z;
        round(ARM, ALUMINIUM, 2, px, py, 1.1, .55, 1.1, 3);
        round(ARM, RUBBERY, 2, px, py, 2.2, .57, .18, 3);
        round(ARM, ALUMINIUM, 2, px, py, 2.3, .15, 1, 0);
        round(ARM, ALUMINIUM, 1, px, py - .45, pz, .5, .83, 3);
        round(ARM, ALUMINIUM, 0, px - .65, py, pz, .26, 1.3, 3);
        round(ARM, ALUMINIUM, 2, px, py, pz + .4, .24, .4, 3);
        round(ARM, CARBON_TUBE, 2, px, py, pz + .45, .18, 6.2, 0, 4, false);
        round(ARM, ALUMINIUM, 2, px, py, 10.3, .24, .4, 3);
        box(ARM, SATIN, px - .12f, py - .25f, 10.55f, px + .12f, py - .05f, 10.95f);
        box(ARM, SATIN, px - .35f, py - .4f, 10.6f, px + .35f, py - .25f, 12);
        box(ARM, SATIN, px + .35f, py - .36f, 11.25f, px + .9f, py - .3f, 11.4f);
        box(ARM, SATIN, px + .84f, py - .3f, 11.25f, px + .9f, py - .16f, 11.4f);
        box(ARM, IVORY, px - .25f, py - .63f, 11, px + .25f, py - .4f, 11.9f);
        box(ARM, WIRES, px - .2f, py - .56f, 10.8f, px + .2f, py - .46f, 11);
        box(ARM, SATIN, px - .04f, py - .69f, 11.6f, px + .04f, py - .63f, 11.8f);

        round(CUE, ALUMINIUM, 2, 15, CUE_Y, CUE_Z, .09, 1.6, 0);
        round(CUE, RUBBERY, 2, 15, CUE_Y, CUE_Z + 1.5, .17, .45, 3);
    }

    /**
     * The dust cover, shut: five thin clear walls, drawn as their outsides seen from both sides, with the cut edges
     * of its rim; and its hinges, which turn about their back bottom edge (TurntableLayout.HINGE_Y and HINGE_Z), so
     * they lift off their seats as the cover opens and never swing into them.
     */
    private static void cover() {
        float x0 = .7f, x1 = 15.3f, z0 = .7f, z1 = 12.3f, y = TOP + .02f, wall = .12f;
        box(COVER_IN, GLOW, GLOW, x0, y, z0, x1, (float) COVER_TOP, z1, 0, true, ALL & ~UNDER);
        box(COVER_OUT, GLOW, GLOW, x0, y, z0, x1, (float) COVER_TOP, z1, 0, false, ALL & ~UNDER);
        box(COVER_OUT, GLINT, GLINT, x0, y, z0, x1, y, z0 + wall, 0, false, UNDER);
        box(COVER_OUT, GLINT, GLINT, x0, y, z1 - wall, x1, y, z1, 0, false, UNDER);
        box(COVER_OUT, GLINT, GLINT, x0, y, z0 + wall, x0 + wall, y, z1 - wall, 0, false, UNDER);
        box(COVER_OUT, GLINT, GLINT, x1 - wall, y, z0 + wall, x1, y, z1 - wall, 0, false, UNDER);
        for (float x : new float[] {3.4f, 12.3f}) {
            box(COVER, BLACK, x, (float) HINGE_Y, .5f, x + .9f, TOP + .8f, 1);
        }
    }

    /**
     * The belt, black, seen from both sides: round the platter's rim from where it leaves for the pulley to where it
     * comes back, round the far side of the pulley, and the two straight runs between.
     */
    private static void belt() {
        double r = PLATTER_RADIUS + .02, pulley = .47, dx = MOTOR_X - CENTER_X, dz = MOTOR_Z - CENTER_Z;
        double toMotor = Math.atan2(dz, dx), spread = Math.acos((r - pulley) / Math.hypot(dx, dz));
        for (int i = 0; i < 80; i++) {
            double a = toMotor + spread + (2 * Math.PI - 2 * spread) * i / 80, b = a + (2 * Math.PI - 2 * spread) / 80;
            run(CENTER_X + r * Math.cos(a), CENTER_Z + r * Math.sin(a), CENTER_X + r * Math.cos(b), CENTER_Z + r * Math.sin(b));
        }
        for (int i = 0; i < 6; i++) {
            double a = toMotor - spread + 2 * spread * i / 6, b = a + 2 * spread / 6;
            run(MOTOR_X + pulley * Math.cos(a), MOTOR_Z + pulley * Math.sin(a), MOTOR_X + pulley * Math.cos(b), MOTOR_Z + pulley * Math.sin(b));
        }
        for (double a : new double[] {toMotor - spread, toMotor + spread}) {
            run(CENTER_X + r * Math.cos(a), CENTER_Z + r * Math.sin(a), MOTOR_X + pulley * Math.cos(a), MOTOR_Z + pulley * Math.sin(a));
        }
    }

    /** A length of the belt from one point to another (x, z). */
    private static void run(double xa, double za, double xb, double zb) {
        float length = (float) Math.hypot(xb - xa, zb - za), u = BLACK.x + 12, v = BLACK.y + 12;
        float nx = (float) (za - zb) / length, nz = (float) (xb - xa) / length;
        float[][] corners = {{(float) xa, BELT_Y + BELT_WIDTH, (float) za}, {(float) xa, BELT_Y, (float) za},
                {(float) xb, BELT_Y, (float) zb}, {(float) xb, BELT_Y + BELT_WIDTH, (float) zb}};
        for (int side = 1; side >= -1; side -= 2) {
            for (int i = 0; i < 4; i++) {
                float[] c = corners[side > 0 ? i : 3 - i];
                BASE.vertex(c[0], c[1], c[2], u, v, side * nx, 0, side * nz);
            }
        }
    }

    private static void box(Mesh mesh, Tile tile, float x0, float y0, float z0, float x1, float y1, float z1) {
        box(mesh, tile, tile, x0, y0, z0, x1, y1, z1, 0, false, ALL);
    }

    /**
     * Those of a box's {@code faces} seen from outside it, or ({@code inward}) from inside it: of {@code flat} above
     * and below, of {@code side} round it. Its four upright corners are cut off {@code cut} wide.
     */
    private static void box(Mesh mesh, Tile flat, Tile side, float x0, float y0, float z0, float x1, float y1, float z1,
                            float cut, boolean inward, int faces) {
        float w = x1 - x0, h = y1 - y0, d = z1 - z0, s = (float) Math.sqrt(.5), c = cut * (float) Math.sqrt(2);
        if ((faces & UP) != 0) face(mesh, flat, new float[] {x0, y1, z0}, XP, ZP, w, d, cut, inward);
        if ((faces & UNDER) != 0) face(mesh, flat, new float[] {x0, y0, z1}, XP, ZN, w, d, cut, inward);
        if ((faces & SOUTH) != 0) face(mesh, side, new float[] {x0 + cut, y1, z1}, XP, YN, w - 2 * cut, h, 0, inward);
        if ((faces & NORTH) != 0) face(mesh, side, new float[] {x1 - cut, y1, z0}, XN, YN, w - 2 * cut, h, 0, inward);
        if ((faces & EAST) != 0) face(mesh, side, new float[] {x1, y1, z1 - cut}, ZN, YN, d - 2 * cut, h, 0, inward);
        if ((faces & WEST) != 0) face(mesh, side, new float[] {x0, y1, z0 + cut}, ZP, YN, d - 2 * cut, h, 0, inward);
        if (cut > 0) {
            face(mesh, side, new float[] {x1 - cut, y1, z1}, new float[] {s, 0, -s}, YN, c, h, 0, inward);
            face(mesh, side, new float[] {x1, y1, z0 + cut}, new float[] {-s, 0, -s}, YN, c, h, 0, inward);
            face(mesh, side, new float[] {x0 + cut, y1, z0}, new float[] {-s, 0, s}, YN, c, h, 0, inward);
            face(mesh, side, new float[] {x0, y1, z1 - cut}, new float[] {s, 0, s}, YN, c, h, 0, inward);
        }
    }

    /** A panel seen from its front, or ({@code inward}) the same panel seen from its back. */
    private static void face(Mesh mesh, Tile tile, float[] o, float[] right, float[] down, float w, float h, float cut,
                             boolean inward) {
        if (inward) {
            panel(mesh, tile, new float[] {o[0] + right[0] * w, o[1] + right[1] * w, o[2] + right[2] * w},
                    new float[] {-right[0], -right[1], -right[2]}, down, w, h, cut);
        } else {
            panel(mesh, tile, o, right, down, w, h, cut);
        }
    }

    /**
     * A flat rectangle of {@code tile}, seen from its front: from its top left corner {@code o}, {@code w} along
     * {@code right} and {@code h} along {@code down}, its corners cut off {@code cut} wide. A material with a border
     * is drawn in nine parts, its border (as much as a third of a small face) unstretched.
     */
    private static void panel(Mesh mesh, Tile tile, float[] o, float[] right, float[] down, float w, float h, float cut) {
        float nx = down[1] * right[2] - down[2] * right[1], ny = down[2] * right[0] - down[0] * right[2],
                nz = down[0] * right[1] - down[1] * right[0];
        float bw = Math.min(BORDER, w / 3), bh = Math.min(BORDER, h / 3), b = tile.border, e = .02f;
        float[] xs = b > 0 ? new float[] {0, bw, w - bw, w} : new float[] {0, w};
        float[] ys = b > 0 ? new float[] {0, bh, h - bh, h} : new float[] {0, h};
        float[] us = b > 0 ? new float[] {e, b, tile.w - b, tile.w - e} : new float[] {e, tile.w - e};
        float[] vs = b > 0 ? new float[] {e, b, tile.h - b, tile.h - e} : new float[] {e, tile.h - e};
        for (int i = 0; i + 1 < xs.length; i++) {
            for (int j = 0; j + 1 < ys.length; j++) {
                float xa = xs[i], xb = xs[i + 1], ya = ys[j], yb = ys[j + 1];
                boolean left = cut > 0 && i == 0, far = cut > 0 && i == xs.length - 2, top = cut > 0 && j == 0,
                        low = cut > 0 && j == ys.length - 2;
                // Its corners from the top left, counter-clockwise: a cut corner is two.
                List<float[]> corners = new ArrayList<>();
                corners.add(left && top ? new float[] {xa, ya + cut} : new float[] {xa, ya});
                if (left && low) {
                    corners.add(new float[] {xa, yb - cut});
                    corners.add(new float[] {xa + cut, yb});
                } else {
                    corners.add(new float[] {xa, yb});
                }
                if (far && low) {
                    corners.add(new float[] {xb - cut, yb});
                    corners.add(new float[] {xb, yb - cut});
                } else {
                    corners.add(new float[] {xb, yb});
                }
                if (far && top) {
                    corners.add(new float[] {xb, ya + cut});
                    corners.add(new float[] {xb - cut, ya});
                } else {
                    corners.add(new float[] {xb, ya});
                }
                if (left && top) {
                    corners.add(new float[] {xa + cut, ya});
                }
                for (int k = 1; k + 1 < corners.size(); k += 2) {
                    for (int at : new int[] {0, k, k + 1, Math.min(k + 2, corners.size() - 1)}) {
                        float s = corners.get(at)[0], t = corners.get(at)[1];
                        mesh.vertex(o[0] + right[0] * s + down[0] * t, o[1] + right[1] * s + down[1] * t,
                                o[2] + right[2] * s + down[2] * t,
                                tile.x + us[i] + (us[i + 1] - us[i]) * (s - xa) / (xb - xa),
                                tile.y + vs[j] + (vs[j + 1] - vs[j]) * (t - ya) / (yb - ya), nx, ny, nz);
                    }
                }
            }
        }
    }

    private static void round(Mesh mesh, Stock stock, int axis, double x, double y, double z, double radius, double length,
                              int ends) {
        round(mesh, stock, axis, x, y, z, radius, length, ends, 0, false);
    }

    /**
     * A round part of {@code stock} along the x, y or z axis ({@code axis} 0, 1 or 2): {@code radius} round and
     * {@code length} long from the middle of its first end at x, y, z. Of its ends, those of {@code ends} (1 the
     * first, 2 the last) are closed and bevelled; the others are out of sight. Its side's material is stretched
     * along it, or laid again every {@code repeat} sixteenths. Seen from outside it, or ({@code inward}) from inside.
     */
    private static void round(Mesh mesh, Stock stock, int axis, double x, double y, double z, double radius, double length,
                              int ends, double repeat, boolean inward) {
        float r = (float) radius, len = (float) length, e = .02f;
        int n = r < .2 ? 12 : r < .6 ? 16 : r < 1.2 ? 24 : r < 3 ? 32 : 96;
        float bevel = Math.min(.07f, Math.min(r, len) * .3f);
        float b0 = (ends & 1) != 0 ? bevel : 0, b1 = (ends & 2) != 0 ? bevel : 0, slope = (float) Math.sqrt(.5);
        float[] centre = {(float) x, (float) y, (float) z};
        float[][] axes = AXES[axis];
        Tile side = stock.side, edge = stock.bevel;
        int lengths = repeat > 0 ? (int) Math.ceil((len - b0 - b1) / repeat) : 1;
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n, b = 2 * Math.PI * (i + 1) / n;
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a), cb = (float) Math.cos(b), sb = (float) Math.sin(b);
            float ua = !stock.profiles ? e + (side.w - 2 * e) * i / n : side.w / 2,
                    ub = !stock.profiles ? e + (side.w - 2 * e) * (i + 1) / n : side.w / 2;
            for (int k = 0; k < lengths; k++) {
                float span = len - b0 - b1, t0 = b0 + (repeat > 0 ? (float) repeat * k : 0),
                        t1 = repeat > 0 ? Math.min(len - b1, t0 + (float) repeat) : len - b1;
                // The material's top is at the part's last end.
                float v0 = side.y + (repeat > 0 ? e : side.h - e), v1 = side.y + (repeat > 0
                        ? e + (side.h - 2 * e) * (t1 - t0) / (float) repeat : e + (side.h - 2 * e) * (1 - (t1 - t0) / span));
                quad(mesh, inward, point(axes, centre, ca, sa, r, t0, side.x + ua, v0, 1, 0),
                        point(axes, centre, cb, sb, r, t0, side.x + ub, v0, 1, 0),
                        point(axes, centre, cb, sb, r, t1, side.x + ub, v1, 1, 0),
                        point(axes, centre, ca, sa, r, t1, side.x + ua, v1, 1, 0));
            }
            float ea = !stock.profiles ? e + (edge.w - 2 * e) * i / n : edge.w / 2,
                    eb = !stock.profiles ? e + (edge.w - 2 * e) * (i + 1) / n : edge.w / 2;
            if (b1 > 0) {
                float in = r - b1, rim = in / r;
                quad(mesh, inward, point(axes, centre, ca, sa, r, len - b1, edge.x + ea, edge.y + edge.h - e, slope, slope),
                        point(axes, centre, cb, sb, r, len - b1, edge.x + eb, edge.y + edge.h - e, slope, slope),
                        point(axes, centre, cb, sb, in, len, edge.x + eb, edge.y + e, slope, slope),
                        point(axes, centre, ca, sa, in, len, edge.x + ea, edge.y + e, slope, slope));
                float[] middle = point(axes, centre, 0, 0, 0, len, stock.endU(0, 0), stock.endV(0, 0), 0, 1);
                quad(mesh, inward, middle,
                        point(axes, centre, ca, sa, in, len, stock.endU(ca, rim), stock.endV(sa, rim), 0, 1),
                        point(axes, centre, cb, sb, in, len, stock.endU(cb, rim), stock.endV(sb, rim), 0, 1), middle);
            }
            if (b0 > 0) {
                float in = r - b0, rim = in / r;
                quad(mesh, inward, point(axes, centre, ca, sa, in, 0, edge.x + ea, edge.y + e, slope, -slope),
                        point(axes, centre, cb, sb, in, 0, edge.x + eb, edge.y + e, slope, -slope),
                        point(axes, centre, cb, sb, r, b0, edge.x + eb, edge.y + edge.h - e, slope, -slope),
                        point(axes, centre, ca, sa, r, b0, edge.x + ea, edge.y + edge.h - e, slope, -slope));
                float[] middle = point(axes, centre, 0, 0, 0, 0, stock.endU(0, 0), stock.endV(0, 0), 0, -1);
                quad(mesh, inward, middle,
                        point(axes, centre, cb, sb, in, 0, stock.endU(cb, rim), stock.endV(sb, rim), 0, -1),
                        point(axes, centre, ca, sa, in, 0, stock.endU(ca, rim), stock.endV(sa, rim), 0, -1), middle);
            }
        }
    }

    /**
     * A corner of a round part: {@code along} its axis from its first end's middle and {@code out} from the axis in
     * the direction of an angle's cosine and sine, with its place in the texture; its normal points {@code outward}
     * and {@code onward} that much.
     */
    private static float[] point(float[][] axes, float[] centre, float cos, float sin, float out, float along, float u,
                                 float v, float outward, float onward) {
        float[] p = new float[8];
        for (int i = 0; i < 3; i++) {
            float across = axes[1][i] * cos + axes[2][i] * sin;
            p[i] = centre[i] + axes[0][i] * along + across * out;
            p[5 + i] = across * outward + axes[0][i] * onward;
        }
        p[3] = u;
        p[4] = v;
        return p;
    }

    /** A quad of four corners, counter-clockwise seen from outside; or, {@code inward}, the same seen from inside. */
    private static void quad(Mesh mesh, boolean inward, float[]... corners) {
        for (int i = 0; i < 4; i++) {
            float[] c = corners[inward ? 3 - i : i];
            float n = inward ? -1 : 1;
            mesh.vertex(c[0], c[1], c[2], c[3], c[4], n * c[5], n * c[6], n * c[7]);
        }
    }

    /** The record, both its sides: the vinyl round its label, and the label in its disc's colours. */
    private static void record(PoseStack.Pose pose, VertexConsumer out, ItemStack disc, int light, int overlay) {
        Minecraft mc = Minecraft.getInstance();
        TextureAtlasSprite picture = mc.getItemRenderer().getModel(disc, mc.level, null, 0).getParticleIcon(ModelData.EMPTY);
        int[] label = LABELS.computeIfAbsent(picture.contents().name(), name -> labelColours(picture));
        TextureAtlasSprite vinyl = sprite(VINYL), paper = sprite(LABEL);
        float r = (float) RECORD_RADIUS, l = LABEL_RADIUS;
        for (int up : new int[] {1, -1}) {
            float y = (float) (up > 0 ? RECORD_TOP : PLATTER_TOP + .01);
            ring(pose, out, vinyl, .5f / r, l, r, y, up, WHITE, light, overlay);
            ring(pose, out, paper, .5f / l, 0, l * RING_IN, y, up, label[0], light, overlay);
            ring(pose, out, paper, .5f / l, l * RING_IN, l * RING_OUT, y, up, label[1], light, overlay);
            ring(pose, out, paper, .5f / l, l * RING_OUT, l, y, up, label[0], light, overlay);
        }
    }

    /**
     * The label's colour and its ring's, from the middle of the disc's picture: its most common and next most common
     * bright colours there, or a cream label with a darker ring if it has none.
     */
    private static int[] labelColours(TextureAtlasSprite picture) {
        var image = picture.contents().getOriginalImage();
        int w = picture.contents().width(), h = picture.contents().height();
        Map<Integer, Integer> counts = new HashMap<>();
        for (int y = h * 5 / 16; y < h * 10 / 16; y++) {
            for (int x = w * 4 / 16; x < w * 11 / 16; x++) {
                int abgr = image.getPixelRGBA(x, y);
                int red = abgr & 0xFF, green = abgr >> 8 & 0xFF, blue = abgr >> 16 & 0xFF;
                int max = Math.max(red, Math.max(green, blue)), min = Math.min(red, Math.min(green, blue));
                if ((abgr >>> 24) > 128 && max > 90 && (max - min > max / 3 || min > 200)) {
                    counts.merge(red << 16 | green << 8 | blue, 1, Integer::sum);
                }
            }
        }
        List<Integer> colours = new ArrayList<>(counts.keySet());
        colours.sort(Comparator.comparing(counts::get).reversed());
        int first = colours.isEmpty() ? 0xE8DCC0 : colours.getFirst();
        int second = colours.size() > 1 ? colours.get(1) : first >> 1 & 0x7F7F7F;
        return new int[] {first | 0xFF000000, second | 0xFF000000};
    }

    private static TextureAtlasSprite sprite(ResourceLocation texture) {
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texture);
    }

    /**
     * A flat band about the spindle at {@code y} from radius {@code r0} to {@code r1} (sixteenths), facing up
     * ({@code up} 1) or down. Its texture is the whole of {@code sprite} about its middle, {@code uvPerRadius} of it
     * per sixteenth out.
     */
    private static void ring(PoseStack.Pose pose, VertexConsumer out, TextureAtlasSprite sprite, float uvPerRadius,
                             float r0, float r1, float y, int up, int colour, int light, int overlay) {
        for (int i = 0; i < SEGMENTS; i++) {
            int a = up > 0 ? i + 1 : i, b = up > 0 ? i : i + 1;
            for (int corner = 0; corner < 4; corner++) {
                int at = corner < 2 ? a : b;
                float radius = corner == 0 || corner == 3 ? r0 : r1, x = COS[at] * radius, z = SIN[at] * radius;
                out.addVertex(pose, CX + x, y, CZ + z).setColor(colour)
                        .setUv(sprite.getU(.5f + x * uvPerRadius), sprite.getV(.5f + z * uvPerRadius))
                        .setOverlay(overlay).setLight(light).setNormal(pose, 0, up, 0);
            }
        }
    }
}
