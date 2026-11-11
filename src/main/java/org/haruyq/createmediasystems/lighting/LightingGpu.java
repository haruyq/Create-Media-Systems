package org.haruyq.createmediasystems.lighting;

/**
 * What the stage lights' shaders and the Java feeding them agree on, kept in one place: LightingGpuTest checks that
 * stage_beam.vsh, spot_light.vsh and spot_light.fsh still say the same.
 */
final class LightingGpu {
    // stage_beam.vsh: each strip is a row of ROW_TEXELS RGBA32F texels in Sampler0 (LightingBeams.strip).
    static final int ROW_TEXELS = 16;
    static final int ROW_FLOATS = ROW_TEXELS * 4;
    /** A row's texels: its corners a, b, c and d, then its three clipping planes. */
    static final int TEXEL_CORNERS = 0;
    static final int TEXEL_PLANES = 4;
    /** Its colour along a to d, then along b to c. */
    static final int TEXEL_COLORS = 7;
    /** Its Fade's ordinal and its alpha at its near edge. */
    static final int TEXEL_SETTINGS = 9;
    /** The four columns of its matrix into the camera's space. */
    static final int TEXEL_MATRIX = 10;
    /** The steps a fading strip is drawn in, from its near edge to its far one. */
    static final int STEPS = 8;
    /** Vertices a step expands to: three clipping planes leave at most seven corners, or five triangles. */
    static final int STEP_VERTICES = 5 * 3;
    static final int ROW_VERTICES = STEPS * STEP_VERTICES;
    /** A cone fades over its length to this much less (a quarter of its alpha is left at the end). */
    static final float CONE_FADE = 0.75f;
    /** A sheet fades by its alpha over this many steps (its last is never reached), and its far edge this lower. */
    static final int SHEET_FADE_STEPS = 11;
    static final int SHEET_FAR_DROP = 3;

    // spot_light.fsh: how brightly a cone lights a point (LightingIllumination.exposure).
    /** The cone's least radius, so its tip still lights the block it is in. */
    static final double MIN_RADIUS = 0.08;
    /** How far in from its edge (a share of its radius) the light is at full strength. */
    static final double EDGE_SOFTNESS = 0.35;
    /** How much of its range (a share, at least a block) it fades out over at its far end. */
    static final double END_FADE = 0.2;

    // spot_light.vsh: the light a face's cells get, a texel a cell, a byte a corner (red first), four vertices a cell.
    static final int VISIBILITY_WIDTH = 256;
    static final int CELL_VERTICES = 4;

    private LightingGpu() {
    }
}
