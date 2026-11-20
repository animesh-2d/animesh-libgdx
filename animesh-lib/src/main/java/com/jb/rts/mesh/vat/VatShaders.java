package com.jb.rts.mesh.vat;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;

/**
 * GLSL sources for the GPU skinning stage.
 *
 * <p>A single shader body is used for both dialects; only the {@code #version} header (and the
 * {@code highp} sampler qualifier required by GLSL ES) differ:
 * <ul>
 *   <li>Desktop OpenGL 3.2+ / core profile: {@code #version 150} + {@code texelFetch}</li>
 *   <li>OpenGL ES 3.0 / WebGL 2: {@code #version 300 es} + {@code texelFetch}</li>
 * </ul>
 * Both support vertex texture fetch and instancing, which is all this technique needs.
 *
 * <h3>What the vertex shader does</h3>
 * <ol>
 *   <li>Turns {@code a_instanceFrame} into frames {@code N} and {@code N+1} plus a blend factor
 *       {@code t} (modulo for looping clips, clamp otherwise).</li>
 *   <li>For each of the three bone influences it fetches the three meaningful <em>columns</em>
 *       ({@code 0}, {@code 1}, {@code 3}) of the baked 4x4 matrix for both frames and lerps them
 *       with {@code t} — 18 {@code texelFetch} calls, no dynamic loops, no matrix uniforms.</li>
 *   <li>Applies the resulting 2D affine matrices to the bind-pose position weighted by
 *       {@code a_weights} (linear blend skinning), then the per-instance scale / rotation /
 *       translation and finally the projection matrix.</li>
 *   <li>Mixes the <b>colours</b> of the same two frames when the animation carries a per-frame
 *       colour strip ({@code u_frameColors}, see {@link VatFrameColorTexture}) — this is the GPU
 *       equivalent of the CPU blend renderer's colour blending. Without a strip the static
 *       {@code a_color} attribute is used, exactly as before.</li>
 * </ol>
 *
 * <p>The z coordinate is <b>not</b> skinned: the mesheditor uses it purely for draw-order
 * (see {@code Mesh.Z_STEP}), so it is passed through unchanged.
 *
 * <h3>Optional model texture</h3>
 * A mesh painted with the editor's texture atlas carries per-vertex UVs
 * ({@link #ATTR_TEXCOORD}, uploaded as a second static VBO). The vertex shader forwards them as
 * {@code v_uv} and, when {@link #UNIFORM_HAS_TEXTURE} is {@code 1}, replaces the per-vertex
 * (Gouraud) colour with the per-instance tint. The fragment shader then samples
 * {@link #UNIFORM_TEXTURE} (texture unit 4) and multiplies it by that tint; fully transparent
 * texels are discarded. Untextured meshes are byte-identical to the previous shader behaviour.
 *
 * <h3>Texture layout reminder</h3>
 * {@code x = frame*4 + column}, {@code y = bone} — one {@code RGBA32F} texel per matrix column,
 * four columns per frame (see {@link VatFormat}). Column 2 of an affine 2D matrix is always
 * {@code (0,0,1,0)} and is therefore never fetched.
 *
 * <p>Colour strip: {@code texel = frame*vertexCount + vertex}, folded into
 * {@code (texel % rowLength, texel / rowLength)} (see {@link VatFormat#frameColorTexel}).
 */
public final class VatShaders {

    /** Vertex attribute aliases — must match the {@code VertexAttribute}s of {@link VatInstancedMesh}. */
    public static final String ATTR_POSITION       = "a_position";
    public static final String ATTR_COLOR          = "a_color";
    public static final String ATTR_BONE_IDS       = "a_boneIds";
    public static final String ATTR_WEIGHTS        = "a_weights";
    /** Per-vertex model texture coordinate (only present when the mesh has UVs). */
    public static final String ATTR_TEXCOORD       = "a_texcoord0";
    public static final String ATTR_INSTANCE_POS   = "a_instancePos";
    public static final String ATTR_INSTANCE_FRAME = "a_instanceFrame";
    public static final String ATTR_INSTANCE_ROT   = "a_instanceRot";
    public static final String ATTR_INSTANCE_SCALE = "a_instanceScale";
    public static final String ATTR_INSTANCE_TINT  = "a_instanceTint";

    /** Sampler of the bone matrix texture (texture unit 0). */
    public static final String UNIFORM_BONES = "u_bones";
    /** Sampler of the optional per-frame colour strip (texture unit 1). */
    public static final String UNIFORM_FRAME_COLORS = "u_frameColors";
    /** {@code 1.0} when {@link #UNIFORM_FRAME_COLORS} holds a per-frame colour strip. */
    public static final String UNIFORM_HAS_FRAME_COLORS = "u_hasFrameColors";
    /** Texels per row of the colour strip ({@link VatFormat#colorRowLength(int)}). */
    public static final String UNIFORM_COLOR_ROW_LENGTH = "u_colorRowLength";
    /** Vertex rows of the colour strip ({@link VatAnimationData#colorVertexCount()}). */
    public static final String UNIFORM_COLOR_VERTEX_COUNT = "u_colorVertexCount";
    /** Sampler of the optional per-vertex step mask (texture unit 2). */
    public static final String UNIFORM_STEP = "u_step";
    /** {@code 1.0} when {@link #UNIFORM_STEP} holds a step mask. */
    public static final String UNIFORM_HAS_STEP = "u_hasStep";
    /** Texels per row of the step mask ({@link VatFormat#colorRowLength(int)}). */
    public static final String UNIFORM_STEP_ROW_LENGTH = "u_stepRowLength";
    /** Sampler of the optional per-frame attachment step mask (texture unit 3). */
    public static final String UNIFORM_STEP_FRAMES = "u_stepFrames";
    /** {@code 1.0} when {@link #UNIFORM_STEP_FRAMES} holds per-frame step flags. */
    public static final String UNIFORM_HAS_STEP_FRAMES = "u_hasStepFrames";
    /** Texels per row of the per-frame step mask. */
    public static final String UNIFORM_STEP_FRAME_ROW_LENGTH = "u_stepFrameRowLength";
    /** Sampler of the optional model texture (texture unit 4). */
    public static final String UNIFORM_TEXTURE = "u_texture";
    /** {@code 1.0} when {@link #UNIFORM_TEXTURE} holds the mesh's model texture. */
    public static final String UNIFORM_HAS_TEXTURE = "u_hasTexture";

    private VatShaders() {}

    // -----------------------------------------------------------------------
    // Complete vertex shader body (shared by both dialects)
    // -----------------------------------------------------------------------

    /**
     * The complete, optimized vertex shader. {@code __BONES_UNIFORM__} and
     * {@code __FRAME_COLORS_UNIFORM__} are replaced with the dialect-specific sampler
     * declarations ({@code highp} is mandatory for vertex texture fetch on GLSL ES).
     */
    static final String VERTEX_BODY = """
            // ---- static (bind pose) vertex stream --------------------------------------
            in vec3  a_position;        // bind-pose position (x, y, z = draw order)
            in vec4  a_color;           // Usage.ColorPacked per-vertex RGBA
            in vec3  a_boneIds;         // up to 3 bone rows  (into the VAT texture)
            in vec3  a_weights;         // matching blend weights, sum == 1.0
            in vec2  a_texcoord0;       // model texture coordinate (only when the mesh has UVs)

            // ---- per-instance stream (updated every frame) ------------------------------
            in vec2  a_instancePos;     // world position of this entity
            in float a_instanceFrame;   // fractional frame index, e.g. 4.5
            in float a_instanceRot;     // rotation in radians
            in vec2  a_instanceScale;   // per-entity scale (1,1 = unchanged)
            in vec4  a_instanceTint;    // per-entity tint (1,1,1,1 = unchanged)

            uniform mat4  u_projTrans;  // camera.combined
            uniform float u_frameCount; // number of baked frames in u_bones
            uniform float u_loop;       // 1.0 = wrap the last frame back to frame 0

            // ---- optional per-frame colour strip (RGBA8, one texel per vertex and frame) ---
            uniform float u_hasFrameColors;   // 1.0 = sample u_frameColors instead of a_color
            uniform float u_colorRowLength;   // texels per row of the strip (wraps at this width)
            uniform float u_colorVertexCount; // vertex rows of the strip

            __BONES_UNIFORM__
            __FRAME_COLORS_UNIFORM__
            __STEP_UNIFORM__
            __STEP_FRAMES_UNIFORM__

            // ---- attachment step mask (one texel per vertex, RGBA8) --------------------
            uniform float u_hasStep;        // 1.0 = u_step holds the attachment mask
            uniform float u_stepRowLength;  // texels per row of the mask

            // ---- per-frame attachment step flags (one texel per frame, RGBA8) ---------
            uniform float u_hasStepFrames;      // 1.0 = u_stepFrames holds the flags
            uniform float u_stepFrameRowLength; // texels per row of the frame mask

            // ---- optional model texture (sampled in the fragment shader) ---------------
            uniform float u_hasTexture;         // 1.0 = the mesh is drawn with u_texture

            out vec4 v_color;
            out vec2 v_uv;

            // One column of the baked 4x4 matrix of `bone` at `frame`.
            // Texture layout: x = frame * 4 + column, y = bone.
            vec4 boneColumn(int bone, int frame, int column) {
                return texelFetch(u_bones, ivec2(frame * 4 + column, bone), 0);
            }

            /**
             * Colour of `vertex` at `frame` from the per-frame strip. The strip is stored
             * row-major with `u_colorRowLength` texels per row, so a mesh with more vertices
             * than GL_MAX_TEXTURE_SIZE still fits.
             */
            vec4 frameColor(int frame, int vertex) {
                int texel = frame * int(u_colorVertexCount) + vertex;
                int row   = int(u_colorRowLength);
                return texelFetch(u_frameColors, ivec2(texel % row, texel / row), 0);
            }

            /**
             * True when `vertex` is attachment geometry and must not be blended
             * between two frames (attachment placed ↔ collapsed to size 0).
             */
            bool isStepVertex(int vertex) {
                if (u_hasStep <= 0.5) return false;
                int row = int(u_stepRowLength);
                return texelFetch(u_step, ivec2(vertex % row, vertex / row), 0).r > 0.5;
            }

            /**
             * True when the attachment state changes at `frame` relative to the
             * previous frame. The interval that ends at such a frame is snapped.
             */
            bool frameIsStep(int frame) {
                if (u_hasStepFrames <= 0.5) return false;
                int row = int(u_stepFrameRowLength);
                return texelFetch(u_stepFrames, ivec2(frame % row, frame / row), 0).r > 0.5;
            }

            /**
             * Linear-blend skinning of a single influence for one frame.
             * Matrices are column-major:  c0 = (m00,m10,0,0)  c1 = (m01,m11,0,0)
             *                              c3 = (m03,m13,0,1)  c2 = (0,0,1,0)  -- skipped
             * so   x' = m00*x + m01*y + m03   and   y' = m10*x + m11*y + m13.
             */
            vec2 skinFrame(int bone, float weight, vec2 p, int frame) {
                if (weight <= 0.0) {
                    return vec2(0.0);
                }
                vec4 c0 = boneColumn(bone, frame, 0);
                vec4 c1 = boneColumn(bone, frame, 1);
                vec4 c3 = boneColumn(bone, frame, 3);
                return vec2(c0.x * p.x + c1.x * p.y + c3.x,
                            c0.y * p.x + c1.y * p.y + c3.y) * weight;
            }

            /**
             * Blends the <b>skinned positions</b> of the two neighbouring frames instead of the
             * matrix columns. Same number of texture fetches, but exactly the editor's frame
             * blending and no shrinkage when the bone rotation between frames is large.
             */
            vec2 skin(int bone, float weight, vec2 p, int f0, int f1, float t) {
                return mix(skinFrame(bone, weight, p, f0),
                           skinFrame(bone, weight, p, f1), t);
            }

            void main() {
                // ---- frame N / N+1 selection -------------------------------------------
                float frames = u_frameCount;
                float f = u_loop > 0.5
                        ? mod(a_instanceFrame, frames)
                        : clamp(a_instanceFrame, 0.0, frames - 1.0);

                int   f0 = int(floor(f));
                float t  = f - float(f0);
                int   f1 = f0 + 1;
                if (f1 >= int(frames)) {
                    f1 = (u_loop > 0.5) ? 0 : f0;
                }

                // Attachments must switch instantly: an interval that ends at a
                // state change holds the earlier frame instead of interpolating
                // placed ↔ collapsed. Other intervals interpolate normally, so an
                // attached object follows a moving slot smoothly.
                if (isStepVertex(gl_VertexID) && frameIsStep(f1)) {
                    t = 0.0;
                }

                // ---- 3-influence linear blend skinning of the bind-pose position -------
                vec2 p = a_position.xy;
                vec2 skinned =
                      skin(int(a_boneIds.x + 0.5), a_weights.x, p, f0, f1, t)
                    + skin(int(a_boneIds.y + 0.5), a_weights.y, p, f0, f1, t)
                    + skin(int(a_boneIds.z + 0.5), a_weights.z, p, f0, f1, t);

                // ---- per-instance transform: scale -> rotate -> translate --------------
                float s = sin(a_instanceRot);
                float c = cos(a_instanceRot);
                vec2 q = skinned * a_instanceScale;
                q = vec2(q.x * c - q.y * s, q.x * s + q.y * c) + a_instancePos;

                // ---- colour: per-frame strip (blended) or the static bind-pose colour --------
                vec4 baseColor = a_color;
                if (u_hasFrameColors > 0.5) {
                    // gl_VertexID is the expanded vertex index (no index buffer is bound).
                    baseColor = mix(frameColor(f0, gl_VertexID), frameColor(f1, gl_VertexID), t);
                }
                // A textured mesh is drawn with the texture instead of the Gouraud colours; the
                // per-instance tint stays, so tinting/alpha fading still works.
                v_color = u_hasTexture > 0.5 ? a_instanceTint : baseColor * a_instanceTint;
                v_uv = a_texcoord0;

                // z is draw order only: never skinned, never scaled.
                gl_Position = u_projTrans * vec4(q, a_position.z, 1.0);
            }
            """;

    // -----------------------------------------------------------------------
    // Complete fragment shader body
    // -----------------------------------------------------------------------

    static final String FRAGMENT_BODY = """
            in vec4 v_color;
            in vec2 v_uv;

            __TEXTURE_UNIFORM__
            uniform float u_hasTexture;  // 1.0 = sample u_texture instead of v_color

            uniform vec4  u_overrideColor;
            uniform float u_useOverride;

            out vec4 fragColor;

            void main() {
                vec4 base = v_color;
                if (u_hasTexture > 0.5) {
                    vec4 texel = texture(u_texture, v_uv);
                    // Fully transparent texels are the atlas background: drop them instead of
                    // blending (keeps the tint and the override colour untouched).
                    if (texel.a <= 0.0039) discard;
                    // v_color carries the per-instance tint (RGB = 1 unless tinted).
                    base = vec4(texel.rgb, texel.a) * v_color;
                }
                fragColor = mix(base, u_overrideColor, u_useOverride);
            }
            """;

    // -----------------------------------------------------------------------
    // Assembly
    // -----------------------------------------------------------------------

    /** True when the running backend compiles GLSL ES shaders (Android / iOS / WebGL). */
    public static boolean isEsProfile() {
        if (Gdx.app == null) return false;
        Application.ApplicationType type = Gdx.app.getType();
        return type == Application.ApplicationType.Android
            || type == Application.ApplicationType.iOS
            || type == Application.ApplicationType.WebGL;
    }

    /** Complete vertex shader source for the given dialect. */
    public static String vertexShaderSource(boolean esProfile) {
        String header = esProfile
            ? "#version 300 es\n#ifdef GL_ES\nprecision highp float;\n#endif\n"
            : "#version 150\n";
        String bonesUniform = esProfile
            ? "uniform highp sampler2D u_bones;  // baked bone matrices (RGBA32F)\n"
            : "uniform sampler2D u_bones;        // baked bone matrices (RGBA32F)\n";
        String colorsUniform = esProfile
            ? "uniform highp sampler2D u_frameColors;  // per-frame vertex colours (RGBA8)\n"
            : "uniform sampler2D u_frameColors;        // per-frame vertex colours (RGBA8)\n";
        String stepUniform = esProfile
            ? "uniform highp sampler2D u_step;  // per-vertex attachment step mask (RGBA8)\n"
            : "uniform sampler2D u_step;        // per-vertex attachment step mask (RGBA8)\n";
        String stepFramesUniform = esProfile
            ? "uniform highp sampler2D u_stepFrames;  // per-frame attachment step flags (RGBA8)\n"
            : "uniform sampler2D u_stepFrames;        // per-frame attachment step flags (RGBA8)\n";
        return header + VERTEX_BODY
            .replace("__BONES_UNIFORM__", bonesUniform)
            .replace("__FRAME_COLORS_UNIFORM__", colorsUniform)
            .replace("__STEP_UNIFORM__", stepUniform)
            .replace("__STEP_FRAMES_UNIFORM__", stepFramesUniform);
    }

    /** Complete fragment shader source for the given dialect. */
    public static String fragmentShaderSource(boolean esProfile) {
        String header = esProfile
            ? "#version 300 es\n#ifdef GL_ES\nprecision mediump float;\n#endif\n"
            : "#version 150\n";
        String textureUniform = esProfile
            ? "uniform highp sampler2D u_texture;  // model texture (RGBA8)\n"
            : "uniform sampler2D u_texture;        // model texture (RGBA8)\n";
        return header + FRAGMENT_BODY.replace("__TEXTURE_UNIFORM__", textureUniform);
    }

    /**
     * Compiles the shader pair for the current backend.
     *
     * @throws IllegalStateException when compilation or linking fails (the GL log is included)
     */
    public static ShaderProgram createProgram() {
        boolean es = isEsProfile();
        ShaderProgram program = new ShaderProgram(
            vertexShaderSource(es), fragmentShaderSource(es));
        if (!program.isCompiled()) {
            String log = program.getLog();
            program.dispose();
            throw new IllegalStateException(
                "VAT shader compilation failed (" + (es ? "GLSL ES" : "GLSL 150") + "):\n" + log);
        }
        return program;
    }
}