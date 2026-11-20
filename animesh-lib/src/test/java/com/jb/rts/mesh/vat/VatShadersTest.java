package com.jb.rts.mesh.vat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless checks on the generated GLSL. Compilation itself needs a GL context (see
 * {@code :desktop:runVatDemo}), but the dialect headers and the attribute/uniform contract can be
 * verified here so a template edit cannot silently break the shader.
 */
class VatShadersTest {

    @Test
    void desktopSourceUsesGlsl150WithAllAttributes() {
        String vertex = VatShaders.vertexShaderSource(false);
        assertTrue(vertex.startsWith("#version 150"), "desktop must compile as GLSL 1.50");
        String[] required = {
            VatShaders.ATTR_POSITION, VatShaders.ATTR_COLOR, VatShaders.ATTR_BONE_IDS,
            VatShaders.ATTR_WEIGHTS, VatShaders.ATTR_INSTANCE_POS, VatShaders.ATTR_INSTANCE_FRAME,
            VatShaders.ATTR_INSTANCE_ROT, VatShaders.ATTR_INSTANCE_SCALE,
            VatShaders.ATTR_INSTANCE_TINT,
            "u_bones", "u_projTrans", "u_frameCount", "u_loop", "texelFetch", "gl_Position"
        };
        for (String token : required) {
            assertTrue(vertex.contains(token), "vertex shader is missing '" + token + "'");
        }
        assertFalse(vertex.contains("__BONES_UNIFORM__"), "template placeholder must be replaced");
        assertFalse(vertex.contains("attribute "), "GLSL 1.50 uses in/out, not attribute");
        assertFalse(vertex.contains("varying "), "GLSL 1.50 uses in/out, not varying");
    }

    @Test
    void esSourceUsesGlsl300EsWithHighpSampler() {
        String vertex = VatShaders.vertexShaderSource(true);
        assertTrue(vertex.startsWith("#version 300 es"), "mobile must compile as GLSL ES 3.00");
        assertTrue(vertex.contains("precision highp float"), "ES needs a float precision");
        assertTrue(vertex.contains("uniform highp sampler2D u_bones"),
            "ES requires highp for the float bone texture");
        assertTrue(vertex.contains("texelFetch"), "ES 3.0 supports texelFetch");
        assertFalse(vertex.contains("__BONES_UNIFORM__"), "template placeholder must be replaced");
    }

    @Test
    void fragmentSourceDeclaresItsOwnOutput() {
        for (boolean es : new boolean[]{false, true}) {
            String fragment = VatShaders.fragmentShaderSource(es);
            assertTrue(fragment.startsWith(es ? "#version 300 es" : "#version 150"));
            assertTrue(fragment.contains("out vec4 fragColor"),
                "core/ES 3 shaders cannot write gl_FragColor");
            assertFalse(fragment.contains("gl_FragColor"));
            assertTrue(fragment.contains("u_overrideColor"));
        }
    }

    @Test
    void shaderSamplesOneTexelPerMatrixColumn() {
        String vertex = VatShaders.vertexShaderSource(false);
        // x = frame * 4 + column, y = bone  -> the layout documented in VatFormat.
        assertTrue(vertex.contains("ivec2(frame * 4 + column, bone)"),
            "texel lookup must follow the frame*4 + column layout");
        // exactly three columns per influence and frame (column 2 is the constant affine column)
        assertTrue(vertex.contains("boneColumn(bone, frame, 0)"));
        assertTrue(vertex.contains("boneColumn(bone, frame, 1)"));
        assertTrue(vertex.contains("boneColumn(bone, frame, 3)"));
        assertFalse(vertex.contains("boneColumn(bone, frame, 2)"));
        // frames are blended by mixing the skinned positions, not the matrix columns
        assertTrue(vertex.contains("skinFrame(bone, weight, p, f0)"));
        assertTrue(vertex.contains("skinFrame(bone, weight, p, f1)"));
        assertTrue(vertex.contains("mix(skinFrame(bone, weight, p, f0),"));
    }

    @Test
    void shaderBlendsPerFrameColoursWhenTheStripIsPresent() {
        for (boolean es : new boolean[]{false, true}) {
            String vertex = VatShaders.vertexShaderSource(es);
            String label = es ? "ES" : "desktop";
            assertTrue(vertex.contains(VatShaders.UNIFORM_FRAME_COLORS),
                label + ": colour strip sampler missing");
            assertTrue(vertex.contains(VatShaders.UNIFORM_HAS_FRAME_COLORS),
                label + ": colour strip switch missing");
            assertTrue(vertex.contains(VatShaders.UNIFORM_COLOR_ROW_LENGTH),
                label + ": colour strip row length missing");
            assertTrue(vertex.contains(VatShaders.UNIFORM_COLOR_VERTEX_COUNT),
                label + ": colour strip vertex count missing");
            assertTrue(vertex.contains("texelFetch(u_frameColors"),
                label + ": colour strip must be fetched with texelFetch");
            assertTrue(vertex.contains("gl_VertexID"),
                label + ": the colour row is the expanded vertex index (gl_VertexID)");
            assertFalse(vertex.contains("__FRAME_COLORS_UNIFORM__"),
                label + ": template placeholder must be replaced");
            if (es) {
                assertTrue(vertex.contains("uniform highp sampler2D u_frameColors"),
                    "ES requires highp for vertex texture fetch");
            }
            // Without a strip the static per-vertex colour must still be used.
            assertTrue(vertex.contains("vec4 baseColor = a_color;"),
                label + ": a_color must stay the fallback");
        }
    }

    @Test
    void shaderSamplesTheModelTextureInsteadOfTheVertexColours() {
        for (boolean es : new boolean[]{false, true}) {
            String label = es ? "ES" : "desktop";
            String vertex = VatShaders.vertexShaderSource(es);
            String fragment = VatShaders.fragmentShaderSource(es);

            assertTrue(vertex.contains(VatShaders.ATTR_TEXCOORD),
                label + ": the UV attribute is missing");
            assertTrue(vertex.contains("out vec2 v_uv;"),
                label + ": the UV varying must be forwarded to the fragment stage");
            assertTrue(vertex.contains("v_uv = a_texcoord0;"),
                label + ": the UV must come from the vertex stream");
            assertTrue(vertex.contains(VatShaders.UNIFORM_HAS_TEXTURE),
                label + ": the texture switch is missing");

            assertTrue(fragment.contains(VatShaders.UNIFORM_TEXTURE),
                label + ": the model texture sampler is missing");
            assertTrue(fragment.contains("texture(" + VatShaders.UNIFORM_TEXTURE),
                label + ": the model texture must be sampled with texture()");
            assertTrue(fragment.contains("in vec2 v_uv;"),
                label + ": the fragment stage needs the UV varying");
            assertFalse(fragment.contains("__TEXTURE_UNIFORM__"),
                label + ": template placeholder must be replaced");
            if (es) {
                assertTrue(fragment.contains("uniform highp sampler2D u_texture"),
                    "ES needs an explicit sampler precision");
            }
        }
    }

    @Test
    void shaderSnapsAttachmentVerticesInsteadOfBlending() {
        for (boolean es : new boolean[]{false, true}) {
            String vertex = VatShaders.vertexShaderSource(es);
            String label = es ? "ES" : "desktop";
            assertTrue(vertex.contains(VatShaders.UNIFORM_STEP),
                label + ": step mask sampler missing");
            assertTrue(vertex.contains(VatShaders.UNIFORM_HAS_STEP),
                label + ": step mask switch missing");
            assertTrue(vertex.contains(VatShaders.UNIFORM_STEP_ROW_LENGTH),
                label + ": step mask row length missing");
            assertTrue(vertex.contains("texelFetch(u_step"),
                label + ": step mask must be fetched with texelFetch");
            assertTrue(vertex.contains("isStepVertex(gl_VertexID)"),
                label + ": the step row is the expanded vertex index (gl_VertexID)");
            assertTrue(vertex.contains(VatShaders.UNIFORM_STEP_FRAMES),
                label + ": per-frame step sampler missing");
            assertTrue(vertex.contains(VatShaders.UNIFORM_HAS_STEP_FRAMES),
                label + ": per-frame step switch missing");
            assertTrue(vertex.contains("texelFetch(u_stepFrames"),
                label + ": per-frame step flags must be fetched with texelFetch");
            assertTrue(vertex.contains("frameIsStep(f1)"),
                label + ": only intervals ending at a state change may snap");
            assertTrue(vertex.contains("t = 0.0;"),
                label + ": attachment vertices must hold the previous frame at a switch");
            assertFalse(vertex.contains("__STEP_UNIFORM__"),
                label + ": template placeholder must be replaced");
            assertFalse(vertex.contains("__STEP_FRAMES_UNIFORM__"),
                label + ": template placeholder must be replaced");
            if (es) {
                assertTrue(vertex.contains("uniform highp sampler2D u_step"),
                    "ES requires highp for vertex texture fetch");
                assertTrue(vertex.contains("uniform highp sampler2D u_stepFrames"),
                    "ES requires highp for vertex texture fetch");
            }
        }
    }
}