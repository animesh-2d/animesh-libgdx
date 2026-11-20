package com.jb.rts.mesh.blend;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GPU-side mesh blender that pre-builds a <em>single static VBO</em> for all <em>poses</em>
 * sharing the same {@link BlendableMesh#prefix() prefix}, then blends up to four of them per
 * frame using only uniform updates — no VBO upload at runtime.
 *
 * <h3>Position blending only; colour = palette-derived per-vertex RGBA</h3>
 * Every pose in the group is interleaved into one static VBO:
 * <pre>
 *   per vertex:  [base_x,base_y,base_z,  off₀_x,off₀_y,off₀_z,  …,
 *                 offₙ₋₁_x,offₙ₋₁_y,offₙ₋₁_z,  r,g,b,a]
 *                 ↑ base   ↑ offset 0            ↑ offset N-1   ↑ colour (from palette index)
 *                 |←───────────── (N+1)×3 floats ─────────────→|←4→|
 * </pre>
 * A generated vertex shader declares {@code a_basePos}, {@code a_off0…a_offN-1} plus one
 * {@code a_color}. The final position is computed as:
 * <pre>finalPos = a_basePos + weightedOffsets</pre>
 * Four uniform ints ({@code u_slot0…u_slot3}) select which pose each blend input reads from;
 * four uniform floats ({@code u_w0…u_w3}) are the weights.
 *
 * <p>Only <b>positions</b> are blended. The per-vertex colour is resolved from the shared
 * {@link ColorPalette} index at VBO build time and is identical for every pose, so colours
 * never change between frames – "frame 1 always uses the same colour as frame 2". Within a
 * triangle the three corner colours are still interpolated by the GPU (Gouraud is preserved
 * inside a triangle). Replacing a palette colour triggers a cheap VBO rebuild
 * ({@link #refreshPalette()}).
 *
 * <p>The shader deliberately uses the simple, widely compatible GLSL 1.20 style (attributes +
 * varying, no int-uniform if-chains) – the same pattern that rendered successfully before.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * MeshExport export = table.loadExport(Gdx.files.internal("meshes/tree_redwood-export.json"));
 * MeshBlenderGroup group = new MeshBlenderGroup(table, export.name, export.palette);
 * group.setBlend(new MeshBlendCommand("tree_redwood_idle_0", 0.6f, "tree_redwood_idle_1", 0.4f));
 * group.render(camera.combined);
 * group.dispose();
 * }</pre>
 *
 * <h3>Constraints</h3>
 * <ul>
 *   <li>All poses in the group must share an identical topology.</li>
 *   <li>Maximum {@value #MAX_MESHES} poses per group.</li>
 *   <li>Only meshes already registered in the table before construction are included.</li>
 * </ul>
 */
public class MeshBlenderGroup implements Disposable {

    /**
     * Hard cap on the number of poses in one group. OpenGL ES 2.0 guarantees ≥ 8 vertex
     * attribute slots; this group uses 1 per pose + 1 colour attribute (instancing adds 3 more).
     */
    public static final int MAX_MESHES = 12;

    private static final String FRAGMENT_SHADER = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec4 v_color;
            uniform vec4  u_overrideColor;
            uniform float u_useOverride;
            void main() {
                gl_FragColor = mix(v_color, u_overrideColor, u_useOverride);
            }
            """;

    // -----------------------------------------------------------------------

    private final String                 prefix;
    private final List<BlendableMesh>    orderedMeshes;
    private final Map<String, Integer>   nameToIndex;
    private final ColorPalette           palette;

    /**
     * Shader variants indexed by active slot count (1-based index). {@code shaderVariants[k]}
     * handles exactly k active slots, avoiding multiply-by-zero for inactive slots.
     */
    private final ShaderProgram[]        shaderVariants;
    /** Currently bound shader variant. */
    private ShaderProgram                activeShader;

    private Mesh                         gpuMesh;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Creates a group for all meshes in {@code table} that share {@code prefix}.
     *
     * @param table   the registry to pull meshes from
     * @param prefix  the common prefix (e.g. {@code "tree_redwood"})
     * @param palette the colour palette for this mesh family
     */
    public MeshBlenderGroup(MeshBlendTable table, String prefix, ColorPalette palette) {
        this(prefix, table.byPrefix(prefix), palette);
    }

    /**
     * Creates a group for an explicit list of poses (e.g. the frames of a single animation).
     * Useful when the group should contain only a sub-set of a mesh family.
     *
     * @param name    the group name (used in logs/errors)
     * @param meshes  the poses; all must share an identical topology
     * @param palette the colour palette for this mesh family
     */
    public MeshBlenderGroup(String name, List<BlendableMesh> meshes, ColorPalette palette) {
        this.prefix  = name;
        this.palette = palette;
        int palSize = palette.size();

        if (meshes.isEmpty()) {
            throw new IllegalArgumentException(
                "No meshes provided for group '" + name + "'.");
        }
        if (meshes.size() > MAX_MESHES) {
            throw new IllegalArgumentException(
                "Group '" + name + "' has " + meshes.size() + " meshes; maximum is "
                + MAX_MESHES + ".");
        }

        // Validate uniform topology
        BlendableMesh first = meshes.get(0);
        for (BlendableMesh m : meshes) {
            if (!m.sameTopology(first)) {
                throw new IllegalStateException(String.format(
                    "Topology mismatch: '%s' is not compatible with '%s' (different vertices/" +
                    "indices/colour indexes). Only poses of the same mesh may share a group.",
                    m.name, first.name));
            }
        }

        this.orderedMeshes = Collections.unmodifiableList(new ArrayList<>(meshes));

        // Build name → index lookup
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < orderedMeshes.size(); i++) idx.put(orderedMeshes.get(i).name, i);
        this.nameToIndex = Collections.unmodifiableMap(idx);

        // Compile one shader variant per possible active-slot count (1..SLOTS)
        ShaderProgram.pedantic = false;
        shaderVariants = new ShaderProgram[MeshBlendCommand.SLOTS + 1]; // [0] unused
        for (int activeSlots = 1; activeSlots <= MeshBlendCommand.SLOTS; activeSlots++) {
            ShaderProgram sp = new ShaderProgram(
                buildVertexShader(orderedMeshes.size(), activeSlots, palSize), FRAGMENT_SHADER);
            if (!sp.isCompiled()) {
                throw new IllegalStateException(
                    "MeshBlenderGroup shader variant (activeSlots=" + activeSlots
                    + ") failed to compile:\n" + sp.getLog());
            }
            shaderVariants[activeSlots] = sp;
        }
        activeShader = shaderVariants[1]; // default: 1 active slot
        // Initialise the shader-test override to "off" and log attribute locations for debugging.
        setOverrideColor(1f, 0f, 1f, 1f, false);
        ShaderProgram v1 = shaderVariants[1];
        Gdx.app.log("MeshBlenderGroup", "shader: a_pos0=" + v1.getAttributeLocation("a_pos0")
            + " a_color=" + v1.getAttributeLocation("a_color")
            + " u_projTrans=" + v1.getUniformLocation("u_projTrans")
            + " log='" + v1.getLog() + "'");

        // Build static GPU mesh (use a VAO-backed VBO on GL3+ – core profiles have no default VAO)
        gpuMesh = buildGpuMesh(
            orderedMeshes, first.vertexCount(), first.indices.length, renderType(), palette);

        Gdx.app.log("MeshBlenderGroup", String.format(
            "Group '%s': %d poses, %d vertices, %d triangles – %d shader variants compiled.",
            prefix, orderedMeshes.size(), first.vertexCount(), first.triangleCount(), MeshBlendCommand.SLOTS));
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Updates blend slot uniforms and selects the shader variant that matches the number of
     * active (non-null, non-zero-weight) slots. No VBO data is transferred.
     *
     * @param command the desired blend; only names registered in this group are valid
     * @throws IllegalArgumentException if a name is not part of this group
     */
    public void setBlend(MeshBlendCommand command) {
        // Count active slots and resolve name→index
        int     activeCount = 0;
        int[]   slots   = new int  [MeshBlendCommand.SLOTS];
        float[] weights = new float[MeshBlendCommand.SLOTS];

        for (int i = 0; i < MeshBlendCommand.SLOTS; i++) {
            String name = command.names[i];
            if (name != null && command.weights[i] != 0f) {
                Integer resolved = nameToIndex.get(name);
                if (resolved == null) {
                    throw new IllegalArgumentException(
                        "Mesh '" + name + "' is not part of group '" + prefix + "'.");
                }
                slots  [activeCount] = resolved;
                weights[activeCount] = command.weights[i];
                activeCount++;
            }
        }

        // At least one slot must be active
        if (activeCount == 0) {
            activeCount = 1;
            slots[0]   = 0;
            weights[0] = 1f;
        }

        ShaderProgram variant = shaderVariants[activeCount];
        if (activeShader != variant) {
            activeShader = variant;
        }

        activeShader.bind();
        for (int i = 0; i < activeCount; i++) {
            activeShader.setUniformi("u_slot" + i, slots[i]);
            activeShader.setUniformf("u_w"    + i, weights[i]);
        }
        uploadPaletteUniforms();
    }

    /**
     * Re-uploads the colour palette as shader uniforms (no VBO rebuild needed).
     * Call after editing palette colours to refresh rendering.
     */
    public void refreshPalette() {
        activeShader.bind();
        uploadPaletteUniforms();
    }

    /**
     * GPU-only shader test: forces the fragment colour to {@code (r,g,b,a)} when {@code on} is
     * true, bypassing the per-vertex colour attribute. Use this to verify that the geometry and
     * draw call work independently of the colour attribute (e.g. try a bright magenta).
     */
    public void setOverrideColor(float r, float g, float b, float a, boolean on) {
        for (int i = 1; i <= MeshBlendCommand.SLOTS; i++) {
            shaderVariants[i].bind();
            shaderVariants[i].setUniformf("u_overrideColor", r, g, b, a);
            shaderVariants[i].setUniformf("u_useOverride", on ? 1f : 0f);
        }
    }

    /**
     * Renders the blended mesh with the supplied projection matrix.
     * {@link #setBlend(MeshBlendCommand)} must have been called at least once beforehand.
     *
     * <p>Rendering is non-indexed ({@code glDrawArrays}): the expanded topology uses the trivial
     * sequential index buffer {@code [0,1,2,3,…]}, so drawing vertices in order produces the
     * identical triangles while avoiding the indexed-draw path completely.
     *
     * @param projTrans combined projection+view matrix (e.g. {@code camera.combined})
     */
    public void render(Matrix4 projTrans) {
        activeShader.bind();
        activeShader.setUniformMatrix("u_projTrans", projTrans);
        gpuMesh.bind(activeShader);
        Gdx.gl.glDrawArrays(GL20.GL_TRIANGLES, 0, gpuMesh.getNumVertices());
        gpuMesh.unbind(activeShader);
        int err = Gdx.gl.glGetError();
        if (err != GL20.GL_NO_ERROR) {
            Gdx.app.error("MeshBlenderGroup", "OpenGL error after draw: 0x"
                + Integer.toHexString(err));
        }
    }

    /** Returns the zero-based index of a mesh within this group's ordered list. */
    public int indexOf(String name) {
        Integer idx = nameToIndex.get(name);
        return idx != null ? idx : -1;
    }

    /** Returns the number of poses in this group. */
    public int meshCount() {
        return orderedMeshes.size();
    }

    /** Returns an unmodifiable ordered view of the poses in this group. */
    public List<BlendableMesh> meshes() {
        return orderedMeshes;
    }

    /** Returns the palette used for lookups. */
    public ColorPalette getPalette() {
        return palette;
    }

    /** Returns the shader variant currently active. */
    public ShaderProgram getShader() {
        return activeShader;
    }

    /** Returns the shader variant for a specific active-slot count (1–{@value MeshBlendCommand#SLOTS}). */
    public ShaderProgram getShaderVariant(int activeSlots) {
        if (activeSlots < 1 || activeSlots > MeshBlendCommand.SLOTS) {
            throw new IllegalArgumentException("activeSlots must be 1–" + MeshBlendCommand.SLOTS);
        }
        return shaderVariants[activeSlots];
    }

    @Override
    public void dispose() {
        gpuMesh.dispose();
        for (int i = 1; i <= MeshBlendCommand.SLOTS; i++) {
            shaderVariants[i].dispose();
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Chooses a vertex-data type that works on the current context: VAO-backed on GL3+ (core
     * profiles have no default VAO, so plain VBO drawing silently produces nothing there).
     */
    private static Mesh.VertexDataType renderType() {
        return Gdx.gl30 != null
            ? Mesh.VertexDataType.VertexBufferObjectWithVAO
            : Mesh.VertexDataType.VertexBufferObject;
    }

    /**
     * Uploads the current palette colours as {@code u_pal0..u_palN} uniforms
     * to the currently active shader variant.
     */
    private void uploadPaletteUniforms() {
        int palSize = palette.size();
        for (int i = 0; i < palSize; i++) {
            activeShader.setUniformf("u_pal" + i,
                palette.red(i), palette.green(i), palette.blue(i), palette.alpha(i));
        }
    }

    /**
     * Generates a GLSL vertex shader for {@code n} poses and exactly {@code activeSlots}
     * blend inputs. Positions are computed as {@code basePos + weightedOffsets}; the colour
     * is resolved per-pose from palette-index attributes and then blended at the slot weights.
     * Palette colours are uploaded as uniforms {@code u_pal0..u_palN}.
     */
    private static String buildVertexShader(int n, int activeSlots, int paletteSize) {
        StringBuilder sb = new StringBuilder();

        sb.append("attribute vec3 a_basePos;\n");
        for (int i = 0; i < n; i++) {
            sb.append("attribute vec3 a_off").append(i).append(";\n");
        }
        // Pack N colour indices into ceil(N/4) vec4 blocks
        int colBlocks = (n + 3) / 4;
        for (int b = 0; b < colBlocks; b++) {
            sb.append("attribute vec4 a_colBlock").append(b).append(";\n");
        }
        sb.append('\n');

        sb.append("uniform mat4 u_projTrans;\n");
        for (int i = 0; i < activeSlots; i++) {
            sb.append("uniform int   u_slot").append(i).append(";\n");
            sb.append("uniform float u_w"   ).append(i).append(";\n");
        }
        // Palette uniforms
        for (int i = 0; i < paletteSize; i++) {
            sb.append("uniform vec4 u_pal").append(i).append(";\n");
        }
        sb.append("\nvarying vec4 v_color;\n\n");

        // pickOffset : vec3
        sb.append("vec3 pickOffset(int slot) {\n");
        for (int i = 0; i < n; i++) {
            sb.append("    if (slot == ").append(i).append(") return a_off").append(i).append(";\n");
        }
        sb.append("    return a_off0;\n}\n\n");

        // pickColorIdx : float
        sb.append("float pickColorIdx(int slot) {\n");
        for (int i = 0; i < n; i++) {
            int block = i / 4;
            int comp  = i % 4;
            String compName = comp == 0 ? "x" : comp == 1 ? "y" : comp == 2 ? "z" : "w";
            sb.append("    if (slot == ").append(i).append(") return a_colBlock")
              .append(block).append(".").append(compName).append(";\n");
        }
        sb.append("    return a_colBlock0.x;\n}\n\n");

        // resolveColor : vec4 from palette index
        sb.append("vec4 resolveColor(int idx) {\n");
        if (paletteSize == 0) {
            sb.append("    return vec4(1.0, 0.0, 1.0, 1.0);\n");
        } else {
            for (int i = 0; i < paletteSize; i++) {
                sb.append("    if (idx == ").append(i).append(") return u_pal").append(i).append(";\n");
            }
            sb.append("    return u_pal0;\n");
        }
        sb.append("}\n\n");

        // main()
        sb.append("void main() {\n");
        if (activeSlots == 1) {
            sb.append("    vec3 blended_off = pickOffset(u_slot0);\n");
            sb.append("    vec4 c0 = resolveColor(int(pickColorIdx(u_slot0) + 0.5));\n");
            sb.append("    v_color = c0;\n");
        } else {
            sb.append("    float total = u_w0");
            for (int i = 1; i < activeSlots; i++) sb.append(" + u_w").append(i);
            sb.append(";\n");
            sb.append("    float inv = (total > 0.0001) ? 1.0 / total : 0.0;\n");
            sb.append("    vec3 blended_off = ( pickOffset(u_slot0) * u_w0");
            for (int i = 1; i < activeSlots; i++) {
                sb.append("\n                          + pickOffset(u_slot").append(i)
                  .append(") * u_w").append(i);
            }
            sb.append(" ) * inv;\n");

            // Resolve and blend colours
            sb.append("    vec4 blendCol = resolveColor(int(pickColorIdx(u_slot0) + 0.5)) * u_w0");
            for (int i = 1; i < activeSlots; i++) {
                sb.append("\n                + resolveColor(int(pickColorIdx(u_slot").append(i)
                  .append(") + 0.5)) * u_w").append(i);
            }
            sb.append(";\n");
            sb.append("    v_color = blendCol * inv;\n");
        }
        sb.append("    vec3 final_pos = a_basePos + blended_off;\n");
        sb.append("    gl_Position    = u_projTrans * vec4(final_pos, 1.0);\n");
        sb.append("}\n");

        return sb.toString();
    }

    /**
     * Builds a GPU {@link Mesh} by interleaving the <b>base positions</b> (once) and
     * <b>N offset arrays</b> plus <b>N colour indices</b> (packed into ceil(N/4) vec4 blocks).
     * Package-private so {@link InstancedMeshBlenderGroup} reuses the same VBO layout.
     *
     * <p>Per-vertex layout ({@code (N+1)*3 + 4*ceil(N/4)} floats):
     * <pre>[base_x,base_y,base_z, off₀_x,off₀_y,off₀_z, …, offₙ₋₁_x,offₙ₋₁_y,offₙ₋₁_z,
     *       colBlock₀_xyzw, …, colBlock_{K-1}_xyzw]</pre>
     * where {@code K = ceil(N/4)}.
     *
     * @param type Vertex data type; use {@code VertexDataType.VertexBufferObject} when VAO
     *             behaviour must be avoided (e.g. for instanced rendering)
     */
    static Mesh buildGpuMesh(List<BlendableMesh> meshes, int vertCount, int indexCount,
                              Mesh.VertexDataType type, ColorPalette palette) {
        int n   = meshes.size();
        int colBlocks = (n + 3) / 4;
        // Layout: 1 base position + N offsets (3 floats each) + N colour indices in K vec4 blocks
        int floatsPerVertex = (n + 1) * 3 + colBlocks * 4;

        float[] gpuVerts = new float[vertCount * floatsPerVertex];
        int colorBlockStart = (n + 1) * 3; // after basePos + N offsets
        BlendableMesh first = meshes.get(0);
        for (int v = 0; v < vertCount; v++) {
            int out = v * floatsPerVertex;
            int vi  = v * BlendableMesh.POSITION_FLOATS;

            // Base position (from defaultPositions of the first/rest mesh — shared by all poses)
            gpuVerts[out    ] = first.defaultPositions[vi    ];
            gpuVerts[out + 1] = first.defaultPositions[vi + 1];
            gpuVerts[out + 2] = first.defaultPositions[vi + 2];

            // Offsets for each pose
            for (int m = 0; m < n; m++) {
                float[] d = meshes.get(m).positions;
                int o = out + 3 + m * 3;
                gpuVerts[o    ] = d[vi    ];
                gpuVerts[o + 1] = d[vi + 1];
                gpuVerts[o + 2] = d[vi + 2];
            }

            // Colour indices per pose (packed into vec4 blocks)
            int dstBase = out + colorBlockStart;
            for (int b = 0; b < colBlocks; b++) {
                int blockOut = dstBase + b * 4;
                for (int sub = 0; sub < 4; sub++) {
                    int poseIdx = b * 4 + sub;
                    int ci = (poseIdx < n)
                        ? meshes.get(poseIdx).colorIndices[v]
                        : 0;
                    gpuVerts[blockOut + sub] = ci;
                }
            }
        }

        // Vertex attributes: a_basePos (vec3) + a_off0..a_offN-1 (vec3 each) + a_colBlock0..K-1 (vec4)
        VertexAttribute[] attrs = new VertexAttribute[n + 1 + colBlocks];
        attrs[0] = new VertexAttribute(Usage.Generic, 3, "a_basePos");
        for (int i = 0; i < n; i++) {
            attrs[i + 1] = new VertexAttribute(Usage.Generic, 3, "a_off" + i);
        }
        for (int b = 0; b < colBlocks; b++) {
            attrs[n + 1 + b] = new VertexAttribute(Usage.Generic, 4, "a_colBlock" + b);
        }

        Mesh gpuMesh = new Mesh(type, true, vertCount, indexCount, attrs);
        gpuMesh.setVertices(gpuVerts);
        gpuMesh.setIndices(meshes.get(0).indices);
        return gpuMesh;
    }
}
