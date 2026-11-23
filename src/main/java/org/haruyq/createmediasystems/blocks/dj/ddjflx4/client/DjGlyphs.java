package org.haruyq.createmediasystems.blocks.dj.ddjflx4.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import org.haruyq.createmediasystems.CreateMediaSystems;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FreeType;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.providers.FreeTypeUtil;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * The letters {@link DjDisplay}'s own pictures have not, Japanese above all: Noto Sans JP Medium
 * (font/noto_sans_jp_medium.otf, SIL Open Font License), each letter drawn into one picture when it is first asked
 * for, and the picture smoothed as it is drawn from. A font definition of the game's would not do: it takes no
 * OpenType file with outlines of this kind, its pictures are not smoothed, and every one is read as the game starts,
 * whoever plays; this typeface is read when a display first draws a letter its own pictures have not.
 */
final class DjGlyphs extends AbstractTexture {
    private static final ResourceLocation PICTURE = CreateMediaSystems.id("dj_display_glyphs"),
            TYPEFACE = CreateMediaSystems.id("font/noto_sans_jp_medium.otf");
    /**
     * The picture's side, an em of a letter in it and what is left clear between letters, in pixels; how many times
     * the picture is halved for drawing it small, which the clear between letters is wide enough for.
     */
    private static final int SIDE = 1024, EM = 32, CLEAR = 8, HALVINGS = 3;
    /**
     * How much finer a picture it is drawn small from than the one nearest in size, in halvings: sharper for it, as
     * DjDisplay's own letters are from a picture never smaller than they are seen, and rougher if it is made more.
     */
    private static final float FINER = .7f;
    /** What its letters are drawn with, by whether in the world, where lettering is kept off the screen behind it. */
    static final RenderType[] TYPES = new RenderType[2];
    private static DjGlyphs made;

    static {
        for (int world = 0; world < 2; world++) {
            TYPES[world] = RenderType.create("create_media_systems_dj_glyphs_" + world, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                    VertexFormat.Mode.QUADS, 1536, false, false, RenderType.CompositeState.builder()
                            .setShaderState(RenderStateShard.RENDERTYPE_TEXT_SHADER)
                            .setTextureState(new RenderStateShard.TextureStateShard(PICTURE, true, true))
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setLightmapState(RenderStateShard.LIGHTMAP)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setLayeringState(world == 1 ? RenderStateShard.POLYGON_OFFSET_LAYERING : RenderStateShard.NO_LAYERING)
                            .createCompositeState(false));
        }
    }

    /**
     * A letter: how far it moves the pen; its picture's left from the pen, its top above the baseline, its width and
     * height, all in ems, the width 0 if there is nothing to draw of it; and where in the picture it is.
     */
    record Glyph(float advance, float left, float top, float wide, float tall, float u0, float v0, float u1, float v1) {
    }

    private final Int2ObjectMap<Glyph> glyphs = new Int2ObjectOpenHashMap<>();
    /** Null if the typeface could not be read. */
    private TrueTypeGlyphProvider typeface;
    /** Where the next letter goes in the picture, and how tall the row it goes in is so far. */
    private int x, y, row;

    private DjGlyphs() {
    }

    /** The one there is, made when it is first asked for. On the render thread. */
    static DjGlyphs get() {
        if (made == null) {
            made = new DjGlyphs();
            // The game calls load now, and again whenever its resources are read again.
            Minecraft.getInstance().getTextureManager().register(PICTURE, made);
        }
        return made;
    }

    @Override
    public void load(ResourceManager resources) throws IOException {
        if (typeface != null) {
            typeface.close();
            typeface = null;
        }
        if (!GL.getCapabilities().OpenGL33) {
            throw new IOException("No texture swizzle: the display's Japanese letters come in the game's font");
        }
        TextureUtil.prepareImage(NativeImage.InternalGlFormat.RED, getId(), HALVINGS, SIDE, SIDE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, -FINER);
        // The picture holds how much of each pixel a letter covers and nothing else: read as white, that much opaque.
        // The game's own shader for such a picture darkens a half covered pixel twice, in colour and in opacity.
        GL11.glTexParameteriv(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_RGBA, new int[] {GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_RED});
        blank();
        ByteBuffer file = null;
        try (InputStream in = resources.open(TYPEFACE)) {
            file = TextureUtil.readResource(in);
            file.flip();
            // As the game opens a font for a definition, but for its refusing all outlines but TrueType's.
            synchronized (FreeTypeUtil.LIBRARY_LOCK) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    PointerBuffer face = stack.mallocPointer(1);
                    FreeTypeUtil.assertError(FreeType.FT_New_Memory_Face(FreeTypeUtil.getLibrary(), file, 0, face), "Initializing font face");
                    FT_Face opened = FT_Face.create(face.get());
                    FreeTypeUtil.assertError(FreeType.FT_Select_Charmap(opened, FreeType.FT_ENCODING_UNICODE), "Find unicode charmap");
                    typeface = new TrueTypeGlyphProvider(file, opened, EM, 1, 0, 0, "");
                }
            }
        } catch (IllegalStateException e) {
            MemoryUtil.memFree(file);
            // The game goes on without these letters; anything but this exception would stop it.
            throw new IOException(e);
        }
    }

    @Override
    public void close() {
        if (typeface != null) {
            typeface.close();
            typeface = null;
        }
        releaseId();
    }

    /** {@code letter}, drawn into the picture now if it is new; null if the typeface has it not. */
    Glyph glyph(int letter) {
        if (typeface == null) {
            return null;
        }
        if (glyphs.containsKey(letter)) {
            return glyphs.get(letter);
        }
        GlyphInfo info = typeface.getGlyph(letter);
        Glyph[] glyph = {info == null ? null : new Glyph(info.getAdvance() / EM, 0, 0, 0, 0, 0, 0, 0, 0)};
        if (info != null) {
            // A letter with nothing to draw, a space, never comes to this.
            info.bake(sheet -> {
                int wide = sheet.getPixelWidth(), tall = sheet.getPixelHeight();
                if (x + wide + CLEAR > SIDE) {
                    x = CLEAR;
                    y += row + CLEAR;
                    row = 0;
                }
                if (y + tall + CLEAR > SIDE) {
                    // ponytail: full after some 600 different letters: all are forgotten and drawn again as they come,
                    // and letters on screen are wrong for a frame. A second picture if titles ever come to that.
                    blank();
                }
                bind();
                sheet.upload(x, y);
                GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
                // With a pixel of the clear around it, for its edge to be smoothed too.
                glyph[0] = new Glyph(info.getAdvance() / EM, (sheet.getBearingLeft() - 1) / EM, (sheet.getBearingTop() + 1) / EM,
                        (wide + 2f) / EM, (tall + 2f) / EM, (x - 1f) / SIDE, (y - 1f) / SIDE, (x + wide + 1f) / SIDE, (y + tall + 1f) / SIDE);
                x += wide + CLEAR;
                row = Math.max(row, tall);
                return null;
            });
        }
        glyphs.put(letter, glyph[0]);
        return glyph[0];
    }

    /** No letter in the picture. */
    private void blank() {
        glyphs.clear();
        x = y = CLEAR;
        row = 0;
        bind();
        try (NativeImage none = new NativeImage(NativeImage.Format.LUMINANCE, SIDE, SIDE, true)) {
            none.upload(0, 0, 0, 0, 0, SIDE, SIDE, false, false);
        }
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
    }
}
