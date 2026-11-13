package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** The shaders say what LightingGpu says they do. */
class LightingGpuTest {
    private static String shader(String name) throws IOException {
        try (InputStream in = LightingGpuTest.class.getResourceAsStream("/assets/create_media_systems/shaders/core/" + name)) {
            assertTrue(in != null, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void says(String source, String expected) {
        assertTrue(source.contains(expected), "expected \"" + expected + "\"");
    }

    @Test
    void theBeamShaderReadsRowsAsLightingBeamsWritesThem() throws IOException {
        String beam = shader("stage_beam.vsh");
        says(beam, "row * " + LightingGpu.ROW_TEXELS + " + column");
        says(beam, "gl_VertexID / " + LightingGpu.ROW_VERTICES);
        says(beam, "(gl_VertexID % " + LightingGpu.ROW_VERTICES + ") / " + LightingGpu.STEP_VERTICES);
        says(beam, "float(step) / " + LightingGpu.STEPS + ".0");
        for (int corner = 0; corner < 4; corner++) {
            says(beam, "data(" + (LightingGpu.TEXEL_CORNERS + corner) + ", row).xyz");
        }
        for (int plane = 0; plane < 3; plane++) {
            says(beam, "data(" + (LightingGpu.TEXEL_PLANES + plane) + ", row)");
        }
        says(beam, "colorA = data(" + LightingGpu.TEXEL_COLORS + ", row)");
        says(beam, "colorB = data(" + (LightingGpu.TEXEL_COLORS + 1) + ", row)");
        says(beam, "settings = data(" + LightingGpu.TEXEL_SETTINGS + ", row)");
        says(beam, "mat4(data(" + LightingGpu.TEXEL_MATRIX + ", row), data(" + (LightingGpu.TEXEL_MATRIX + 1) + ", row), data("
                + (LightingGpu.TEXEL_MATRIX + 2) + ", row), data(" + (LightingGpu.TEXEL_MATRIX + 3) + ", row))");
    }

    @Test
    void theBeamShaderFadesAsFadeDoes() throws IOException {
        String beam = shader("stage_beam.vsh");
        says(beam, "mode == " + LightingBeams.Fade.CONE.ordinal() + ")");
        says(beam, "mode == " + LightingBeams.Fade.SHEET.ordinal() + ")");
        says(beam, "mode == " + LightingBeams.Fade.SOLID.ordinal() + " ");
        says(beam, "t0 * " + LightingGpu.CONE_FADE + ")");
        says(beam, "float(step) / " + LightingGpu.SHEET_FADE_STEPS + ".0");
        says(beam, "alpha0 - " + LightingGpu.SHEET_FAR_DROP + ".0");
        // As the shader: a cone to a quarter over its steps, a sheet's far edge 3 under its near one, a ray whole.
        assertTrue(LightingBeams.Fade.CONE.near(100, 0) == 100 && LightingBeams.Fade.CONE.far(100, 7) == 25);
        assertTrue(LightingBeams.Fade.SHEET.far(26, 0) == 23 && LightingBeams.Fade.SOLID.steps() == 1);
    }

    @Test
    void theSpotShadersLightAsLightingIlluminationDoes() throws IOException {
        String fragment = shader("spot_light.fsh");
        says(fragment, "max(" + LightingGpu.MIN_RADIUS + ", along * Tangent)");
        says(fragment, "(1.0 - radial) / Softness");
        says(shader("spot_light.json"), "\"Softness\", \"type\": \"float\", \"count\": 1, \"values\": [ "
                + LightingGpu.EDGE_SOFTNESS + " ]");
        says(fragment, "Range * " + LightingGpu.END_FADE);
        String vertex = shader("spot_light.vsh");
        says(vertex, "gl_VertexID / " + LightingGpu.CELL_VERTICES);
        says(vertex, "corners[gl_VertexID % " + LightingGpu.CELL_VERTICES + "]");
    }
}
