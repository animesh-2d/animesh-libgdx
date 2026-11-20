package com.jb.rts.mesh.blend;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;

import java.util.Arrays;

/**
 * GPU-side mesh blender that linearly combines up to four {@link BlendableMesh} poses in a
 * single draw call.
 *
 * <h3>Position <em>and</em> colour blending across blend slots</h3>
 * The four blend slots are four <em>poses</em> of the same mesh (same topology, different
 * offsets and possibly different per-vertex colour indices – e.g. animation frames).
 * The final vertex position is the weighted sum of the four offset inputs added to the base
 * position. <b>Colour is also blended across slots</b>: each slot carries its own per-vertex
 * colour index, and the indices are resolved through the shared {@link ColorPalette} in the
 * shader before being linearly mixed at the blend weights.
 *
 * <p>Colour indices are stored per-slot as vertex attributes. The palette is uploaded as shader
 * uniforms. The vertex shader resolves each slot's index to RGBA via a generated if-chain, then
 * blends the four resolved colours at the slot weights. Palette replacement via
 * {@link #refreshPalette()} is a cheap uniform re-upload &ndash; no VBO rebuild required.
 * Within a triangle the three corner colours are still interpolated by the GPU (Gouraud shading
 * is preserved <em>inside</em> a triangle).
 *
 * <h3>GPU layout</h3>
 * <pre>
 *   per vertex: [base_x,base_y,base_z, off0_x,off0_y,off0_z, off1_x,off1_y,off1_z,
 *                off2_x,off2_y,off2_z, off3_x,off3_y,off3_z, colIdx0,colIdx1,colIdx2,colIdx3]   (19 floats)
 * </pre>
 * The final 4 floats carry the colour <em>index</em> for each of the four blend slots
 * (cast from float to int in the shader). The palette colours themselves are uploaded as
 * uniforms {@code u_pal0..u_palN}. The VBO is rebuilt only when the <em>set of mesh names</em>
 * changes; palette edits only re-upload uniforms.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * MeshExport export = table.loadExport(Gdx.files.internal("meshes/tree_redwood-export.json"));
 * MeshBlender blender = new MeshBlender(table, export.palette);
 * blender.setBlend(new MeshBlendCommand("tree_redwood_idle_0", 0.6f, "tree_redwood_idle_1", 0.4f));
 * blender.render(camera.combined);
 * blender.dispose();
 * }</pre>
 */
public class MeshBlender implements Disposable {

    // -----------------------------------------------------------------------
    // Vertex layout constants
    // -----------------------------------------------------------------------

    /** Floats per vertex in the GPU mesh: basePos(3) + 4×offsets(3) + 4 colour-indices = 19. */
    private static final int GPU_FLOATS_PER_VERTEX = (MeshBlendCommand.SLOTS + 1) * 3 + MeshBlendCommand.SLOTS;

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final MeshBlendTable table;
    private final ShaderProgram  shader;
    private final String         vertexShaderSource;

    private static final String  FRAGMENT_SHADER = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec4 v_color;
            void main() {
                gl_FragColor = v_color;
            }
            """;

    private final ColorPalette   palette;

    /** Current GPU mesh; rebuilt when the set of active mesh names changes. */
    private Mesh    gpuMesh;
    /** Last names used to build the GPU mesh (length = SLOTS). */
    private final String[] lastNames = new String[MeshBlendCommand.SLOTS];

    private final int paletteSize;

    // -----------------------------------------------------------------------
    // Constructor / disposal
    // -----------------------------------------------------------------------

    /**
     * Creates a MeshBlender backed by the given table and palette.
     *
     * @param table   the registry of blendable meshes
     * @param palette the colour palette for this mesh family
     */
    public MeshBlender(MeshBlendTable table, ColorPalette palette) {
        this.table   = table;
        this.palette = palette;
        this.paletteSize = palette.size();
        this.vertexShaderSource = buildVertexShader(paletteSize);

        ShaderProgram.pedantic = false;
        shader = new ShaderProgram(vertexShaderSource, FRAGMENT_SHADER);
        if (!shader.isCompiled()) {
            throw new IllegalStateException(
                "MeshBlender: shader compilation failed:\n" + shader.getLog());
        }
    }

    @Override
    public void dispose() {
        if (gpuMesh != null) gpuMesh.dispose();
        shader.dispose();
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Tells the GPU which mesh poses to blend and at what weights.
     *
     * @param command blend description (pose names + weights); must not be {@code null}
     * @throws IllegalArgumentException if a named pose is not found in the table
     * @throws IllegalStateException    if named meshes have mismatched topology
     */
    public void setBlend(MeshBlendCommand command) {
        boolean namesChanged = !Arrays.equals(lastNames, command.names);

        if (namesChanged) {
            rebuildGpuMesh(command.names);
            System.arraycopy(command.names, 0, lastNames, 0, MeshBlendCommand.SLOTS);
        }

        // Always rebind shader and upload blend weights + palette uniforms
        shader.bind();
        shader.setUniformf("u_w0", command.weights[0]);
        shader.setUniformf("u_w1", command.weights[1]);
        shader.setUniformf("u_w2", command.weights[2]);
        shader.setUniformf("u_w3", command.weights[3]);
        uploadPaletteUniforms();
    }

    /**
     * Uploads the colour palette as uniforms (no VBO rebuild needed).
     * Call after editing palette colours to refresh rendering.
     */
    public void refreshPalette() {
        if (lastNames[0] != null) {
            shader.bind();
            uploadPaletteUniforms();
        }
    }

    /**
     * Renders the blended mesh using the provided projection matrix.
     * {@link #setBlend(MeshBlendCommand)} must have been called at least once before rendering.
     *
     * @param projTrans combined projection+view matrix (e.g. {@code camera.combined})
     */
    public void render(Matrix4 projTrans) {
        if (gpuMesh == null) {
            Gdx.app.error("MeshBlender", "render() called before setBlend() – nothing to draw.");
            return;
        }
        shader.bind();
        shader.setUniformMatrix("u_projTrans", projTrans);
        // Non-indexed draw; the expanded topology uses the trivial sequential index buffer.
        gpuMesh.bind(shader);
        Gdx.gl.glDrawArrays(GL20.GL_TRIANGLES, 0, gpuMesh.getNumVertices());
        gpuMesh.unbind(shader);
        int err = Gdx.gl.glGetError();
        if (err != GL20.GL_NO_ERROR) {
            Gdx.app.error("MeshBlender", "OpenGL error after draw: 0x"
                + Integer.toHexString(err));
        }
    }

    /** Returns the underlying built-in {@link ShaderProgram}. */
    public ShaderProgram getShader() {
        return shader;
    }

    /** Returns the current GPU {@link Mesh}, or {@code null} before the first setBlend(). */
    public Mesh getGpuMesh() {
        return gpuMesh;
    }

    /** Returns the palette currently in use. */
    public ColorPalette getPalette() {
        return palette;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Builds (or rebuilds) the GPU mesh by interleaving the <b>base position</b> (from
     * slot 0's {@code defaultPositions}), up to four <b>offset arrays</b>, plus one
     * <b>palette-derived RGBA colour</b> per vertex.
     *
     * <p>Missing slots are filled with the offset data of slot 0 (they receive weight 0).
     */
    private void rebuildGpuMesh(String[] names) {
        // Resolve primary mesh (slot 0 must always be present)
        if (names[0] == null) {
            throw new IllegalArgumentException(
                "Slot 0 must always contain a valid mesh name.");
        }
        BlendableMesh primary = table.getRequired(names[0]);

        // Resolve remaining slots (fall back to primary for missing slots)
        BlendableMesh[] sources = new BlendableMesh[MeshBlendCommand.SLOTS];
        sources[0] = primary;
        for (int i = 1; i < MeshBlendCommand.SLOTS; i++) {
            sources[i] = (names[i] != null) ? table.getRequired(names[i]) : primary;
        }

        // Validate topology
        int vertCount  = primary.vertexCount();
        int indexCount = primary.indices.length;
        for (int i = 1; i < MeshBlendCommand.SLOTS; i++) {
            if (!sources[i].sameTopology(primary)) {
                throw new IllegalStateException(String.format(
                    "Mesh '%s' has a different topology than primary '%s'. " +
                    "Only poses of the same mesh may be blended.",
                    sources[i].name, primary.name));
            }
        }

        // Build interleaved GPU vertex buffer
        // Layout per vertex: [base_x,base_y,base_z, off0_x,off0_y,off0_z, off1_x,...,
        //                     off3_z, colIdx0, colIdx1, colIdx2, colIdx3]
        float[] gpuVerts = new float[vertCount * GPU_FLOATS_PER_VERTEX];
        int colorIdxOffset = 3 + MeshBlendCommand.SLOTS * 3; // after basePos + 4 offsets
        for (int v = 0; v < vertCount; v++) {
            int out = v * GPU_FLOATS_PER_VERTEX;
            int vi  = v * BlendableMesh.POSITION_FLOATS;

            // Base position (shared by all poses, from primary/default mesh)
            gpuVerts[out    ] = primary.defaultPositions[vi    ];
            gpuVerts[out + 1] = primary.defaultPositions[vi + 1];
            gpuVerts[out + 2] = primary.defaultPositions[vi + 2];

            // Offsets for each of the four slots
            for (int slot = 0; slot < MeshBlendCommand.SLOTS; slot++) {
                float[] d = sources[slot].positions;
                int o = out + 3 + slot * 3;
                gpuVerts[o    ] = d[vi    ]; // dx
                gpuVerts[o + 1] = d[vi + 1]; // dy
                gpuVerts[o + 2] = d[vi + 2]; // dz
            }

            // Colour indices — one per slot (stored as float, cast to int in shader)
            gpuVerts[out + colorIdxOffset    ] = primary.colorIndices[v];
            gpuVerts[out + colorIdxOffset + 1] = sources[1].colorIndices[v];
            gpuVerts[out + colorIdxOffset + 2] = sources[2].colorIndices[v];
            gpuVerts[out + colorIdxOffset + 3] = sources[3].colorIndices[v];
        }

        // (Re)create GPU mesh. VAO-backed VBO on GL3+ (core profiles have no default VAO).
        Mesh.VertexDataType type = Gdx.gl30 != null
            ? Mesh.VertexDataType.VertexBufferObjectWithVAO
            : Mesh.VertexDataType.VertexBufferObject;
        if (gpuMesh != null) gpuMesh.dispose();
        gpuMesh = new Mesh(type, true, vertCount, indexCount,
            new VertexAttribute(Usage.Generic, 3, "a_basePos"),
            new VertexAttribute(Usage.Generic, 3, "a_off0"),
            new VertexAttribute(Usage.Generic, 3, "a_off1"),
            new VertexAttribute(Usage.Generic, 3, "a_off2"),
            new VertexAttribute(Usage.Generic, 3, "a_off3"),
            new VertexAttribute(Usage.Generic, 4, "a_colorIdx")
        );
        gpuMesh.setVertices(gpuVerts);
        gpuMesh.setIndices(primary.indices);

        // Upload palette uniforms for the first time
        shader.bind();
        uploadPaletteUniforms();

        Gdx.app.log("MeshBlender", String.format(
            "GPU mesh built: %d vertices, %d triangles | slots: %s, %s, %s, %s",
            vertCount, indexCount / 3,
            names[0],
            names[1] != null ? names[1] : "(primary)",
            names[2] != null ? names[2] : "(primary)",
            names[3] != null ? names[3] : "(primary)"));
    }

    /**
     * Uploads the current palette colours as {@code u_pal0..u_palN} uniforms.
     * Called from both {@link #setBlend} and {@link #refreshPalette}.
     */
    private void uploadPaletteUniforms() {
        for (int i = 0; i < paletteSize; i++) {
            String name = "u_pal" + i;
            shader.setUniformf(name,
                palette.red(i), palette.green(i), palette.blue(i), palette.alpha(i));
        }
    }

    // -----------------------------------------------------------------------
    // Shader generator
    // -----------------------------------------------------------------------

    /**
     * Generates a GLSL vertex shader for a palette of {@code paletteSize} colours.
     *
     * <p>The shader declares four uniform colour-slot attributes ({@code a_colorIdx}
     * as a {@code vec4}) and resolves each slot's index to an RGBA colour via a
     * generated if-chain over {@code u_pal0..u_palN}. The four resolved colours
     * are then linearly blended at the slot weights.
     */
    private static String buildVertexShader(int paletteSize) {
        StringBuilder sb = new StringBuilder();

        sb.append("attribute vec3 a_basePos;\n");
        sb.append("attribute vec3 a_off0;\n");
        sb.append("attribute vec3 a_off1;\n");
        sb.append("attribute vec3 a_off2;\n");
        sb.append("attribute vec3 a_off3;\n");
        sb.append("attribute vec4 a_colorIdx;\n\n");

        sb.append("uniform mat4  u_projTrans;\n");
        sb.append("uniform float u_w0;\n");
        sb.append("uniform float u_w1;\n");
        sb.append("uniform float u_w2;\n");
        sb.append("uniform float u_w3;\n\n");

        // Palette uniforms
        for (int i = 0; i < paletteSize; i++) {
            sb.append("uniform vec4 u_pal").append(i).append(";\n");
        }

        sb.append("\nvarying vec4 v_color;\n\n");

        // resolveColor function: if-chain over palette entries
        sb.append("vec4 resolveColor(int idx) {\n");
        if (paletteSize == 0) {
            sb.append("    return vec4(1.0, 0.0, 1.0, 1.0); // magenta = error\n");
        } else {
            for (int i = 0; i < paletteSize; i++) {
                sb.append("    if (idx == ").append(i).append(") return u_pal").append(i).append(";\n");
            }
            sb.append("    return u_pal0; // fallback (out-of-range index)\n");
        }
        sb.append("}\n\n");

        // main()
        sb.append("void main() {\n");
        sb.append("    float total = u_w0 + u_w1 + u_w2 + u_w3;\n");
        sb.append("    float inv   = (total > 0.0001) ? 1.0 / total : 0.0;\n\n");

        sb.append("    vec3 blended_off = (a_off0 * u_w0\n");
        sb.append("                      + a_off1 * u_w1\n");
        sb.append("                      + a_off2 * u_w2\n");
        sb.append("                      + a_off3 * u_w3) * inv;\n\n");

        sb.append("    vec3 final_pos = a_basePos + blended_off;\n\n");

        // Resolve and blend colours from indices
        sb.append("    vec4 c0 = resolveColor(int(a_colorIdx.x + 0.5));\n");
        sb.append("    vec4 c1 = resolveColor(int(a_colorIdx.y + 0.5));\n");
        sb.append("    vec4 c2 = resolveColor(int(a_colorIdx.z + 0.5));\n");
        sb.append("    vec4 c3 = resolveColor(int(a_colorIdx.w + 0.5));\n");
        sb.append("    v_color = (c0 * u_w0 + c1 * u_w1 + c2 * u_w2 + c3 * u_w3) * inv;\n\n");

        sb.append("    gl_Position = u_projTrans * vec4(final_pos, 1.0);\n");
        sb.append("}\n");

        return sb.toString();
    }
}