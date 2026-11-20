package com.jb.rts.mesh.blend;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;

/**
 * GPU instanced mesh blender: draws many copies of the same mesh group in a <em>single draw
 * call</em>, each with its own pose-blend parameters and world position.
 *
 * <h3>Position blending only; colour = palette-derived per-vertex RGBA</h3>
 * Like {@link MeshBlenderGroup}, the geometry VBO interleaves the <b>base position</b> (once)
 * and <b>N offset arrays</b> plus one <b>palette-derived RGBA colour</b> per vertex (resolved
 * from the shared colour index at VBO build time; identical for every pose). Each instance
 * chooses up to four poses (by slot index), blends their <b>offsets</b> at its own weights,
 * then adds the result to the base position. Colour is never blended across slots or across
 * instances, and never changes over time. Gouraud shading inside a triangle is preserved.
 *
 * <h3>Requirements</h3>
 * OpenGL ES 3.0 / OpenGL 3.3 – {@code Gdx.gl30} must be non-null.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * MeshExport export = table.loadExport(Gdx.files.internal("meshes/tree_redwood-export.json"));
 * InstancedMeshBlenderGroup instances =
 *     new InstancedMeshBlenderGroup(table, export.name, export.palette, 1000);
 *
 * instances.begin();
 * for (TreeRedwood t : trees) {
 *     instances.add(new MeshBlendCommand("tree_redwood_idle_0", t.t,
 *                                        "tree_redwood_idle_1", 1f - t.t), t.x, t.y);
 * }
 * instances.render(camera.combined);
 * instances.dispose();
 * }</pre>
 */
public class InstancedMeshBlenderGroup implements Disposable {

    // -----------------------------------------------------------------------
    // Instance buffer layout (10 floats per instance)
    // -----------------------------------------------------------------------
    // offset  0: slot0, slot1, slot2, slot3  (4× float, cast to int in shader)
    // offset 16: w0, w1, w2, w3             (4× float)
    // offset 32: pos_x, pos_y               (2× float)

    private static final int FLOATS_PER_INSTANCE = MeshBlendInstance.FLOATS; // 10
    private static final int BYTES_PER_INSTANCE = FLOATS_PER_INSTANCE * Float.BYTES;

    // -----------------------------------------------------------------------
    // Shader
    // -----------------------------------------------------------------------

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

    private final String prefix;
    private final Map<String, Integer> nameToIndex;
    private final List<BlendableMesh> orderedMeshes;
    private final ShaderProgram shader;
    private final ColorPalette palette;
    private Mesh gpuMesh;
    private final int instanceVboHandle;
    private final FloatBuffer instanceBuffer;
    private final int maxInstances;
    private int currentInstanceCount;
    /**
     * VAO storing geometry + instance attribute state.
     */
    private final int vaoHandle;

    // Cached instance attribute locations
    private final int locInstSlots;
    private final int locInstWeights;
    private final int locInstPos;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Creates the instanced blender for all meshes sharing {@code prefix}.
     *
     * @param table        the mesh registry
     * @param prefix       common prefix (e.g. {@code "tree_redwood"})
     * @param palette      the colour palette for this mesh family
     * @param maxInstances maximum number of instances per frame
     */
    public InstancedMeshBlenderGroup(MeshBlendTable table, String prefix, ColorPalette palette,
                                     int maxInstances) {
        this(prefix, table.byPrefix(prefix), palette, maxInstances);
    }

    /**
     * Creates an instanced blender for an explicit list of poses (e.g. the frames of a single
     * animation), useful when the group should contain only a sub-set of a mesh family.
     *
     * @param name         the group name (used in logs/errors)
     * @param meshes       the poses; all must share an identical topology
     * @param palette      the colour palette for this mesh family
     * @param maxInstances maximum number of instances per frame
     */
    public InstancedMeshBlenderGroup(String name, List<BlendableMesh> meshes, ColorPalette palette,
                                     int maxInstances) {
        if (Gdx.gl30 == null) {
            throw new IllegalStateException(
                    "InstancedMeshBlenderGroup requires OpenGL ES 3.0 (Gdx.gl30 is null).");
        }
        this.prefix = name;
        this.maxInstances = maxInstances;
        this.palette = palette;
        this.orderedMeshes = Collections.unmodifiableList(new ArrayList<>(meshes));

        if (meshes.isEmpty()) {
            throw new IllegalArgumentException(
                    "No meshes provided for group '" + name + "'.");
        }
        if (meshes.size() > MeshBlenderGroup.MAX_MESHES) {
            throw new IllegalArgumentException(
                    "Group '" + name + "' has " + meshes.size()
                            + " meshes; maximum is " + MeshBlenderGroup.MAX_MESHES + ".");
        }

        // Validate uniform topology
        BlendableMesh first = meshes.get(0);
        for (BlendableMesh m : meshes) {
            if (!m.sameTopology(first)) {
                throw new IllegalStateException(String.format(
                        "Topology mismatch: '%s' vs '%s'. Only poses of the same mesh may be blended.",
                        m.name, first.name));
            }
        }

        // Name → index map
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < meshes.size(); i++) idx.put(meshes.get(i).name, i);
        nameToIndex = Collections.unmodifiableMap(idx);

        // Geometry VBO: use VertexBufferObject (no VAO) so instance attrib setup works cleanly
        gpuMesh = MeshBlenderGroup.buildGpuMesh(
                meshes, first.vertexCount(), first.indices.length,
                Mesh.VertexDataType.VertexBufferObject, palette);

        // Compile shader
        ShaderProgram.pedantic = false;
        int palSize = palette.size();
        shader = new ShaderProgram(buildVertexShader(meshes.size(), palSize), FRAGMENT_SHADER);
        if (!shader.isCompiled()) {
            throw new IllegalStateException(
                    "InstancedMeshBlenderGroup shader failed:\n" + shader.getLog());
        }

        // Cache instance attribute locations
        locInstSlots = shader.getAttributeLocation("a_inst_slots");
        locInstWeights = shader.getAttributeLocation("a_inst_weights");
        locInstPos = shader.getAttributeLocation("a_inst_pos");

        // Instance VBO (dynamic, CPU-writable every frame)
        IntBuffer handleBuf = BufferUtils.newIntBuffer(1);
        Gdx.gl.glGenBuffers(1, handleBuf);
        instanceVboHandle = handleBuf.get(0);

        Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, instanceVboHandle);
        Gdx.gl.glBufferData(GL20.GL_ARRAY_BUFFER,
                maxInstances * BYTES_PER_INSTANCE, null, GL20.GL_DYNAMIC_DRAW);
        Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);

        instanceBuffer = BufferUtils.newFloatBuffer(maxInstances * FLOATS_PER_INSTANCE);

        // Build VAO
        IntBuffer vaoBuffer = BufferUtils.newIntBuffer(1);
        Gdx.gl30.glGenVertexArrays(1, vaoBuffer);
        vaoHandle = vaoBuffer.get(0);
        setupVao();

        Gdx.app.log("InstancedMeshBlenderGroup", String.format(
                "Group '%s': %d poses, %d vertices, %d triangles, max %d instances.",
                prefix, meshes.size(), first.vertexCount(), first.triangleCount(), maxInstances));

        // Initialise the shader-test override to "off".
        setOverrideColor(1f, 0f, 1f, 1f, false);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Clears the instance list. Must be called once per frame before any {@link #add}.
     */
    public void begin() {
        instanceBuffer.clear();
        currentInstanceCount = 0;
    }

    /**
     * Adds a pre-built {@link MeshBlendInstance}.
     */
    public void add(MeshBlendInstance instance) {
        if (currentInstanceCount >= maxInstances) {
            throw new IllegalStateException(
                    "Maximum instance count (" + maxInstances + ") exceeded.");
        }
        instance.write(instanceBuffer);
        currentInstanceCount++;
    }

    /**
     * Convenience overload: resolves mesh names and adds an instance in one step.
     */
    public void add(MeshBlendCommand command, float x, float y) {
        if (currentInstanceCount >= maxInstances) {
            throw new IllegalStateException(
                    "Maximum instance count (" + maxInstances + ") exceeded.");
        }
        MeshBlendInstance inst = new MeshBlendInstance();
        inst.x = x;
        inst.y = y;
        for (int i = 0; i < MeshBlendCommand.SLOTS; i++) {
            String name = command.names[i];
            int slot = 0;
            float w = 0f;
            if (name != null) {
                Integer resolved = nameToIndex.get(name);
                if (resolved == null) {
                    throw new IllegalArgumentException(
                            "Mesh '" + name + "' is not part of group '" + prefix + "'.");
                }
                slot = resolved;
                w = command.weights[i];
            }
            switch (i) {
                case 0 -> {
                    inst.slot0 = slot;
                    inst.w0 = w;
                }
                case 1 -> {
                    inst.slot1 = slot;
                    inst.w1 = w;
                }
                case 2 -> {
                    inst.slot2 = slot;
                    inst.w2 = w;
                }
                case 3 -> {
                    inst.slot3 = slot;
                    inst.w3 = w;
                }
            }
        }
        inst.write(instanceBuffer);
        currentInstanceCount++;
    }

    /**
     * Uploads instance data and issues a single {@code glDrawElementsInstanced} call.
     */
    public void render(Matrix4 projTrans) {
        if (currentInstanceCount == 0) return;

        instanceBuffer.flip();

        shader.bind();
        shader.setUniformMatrix("u_projTrans", projTrans);
        uploadPaletteUniforms();

        Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, instanceVboHandle);
        Gdx.gl.glBufferSubData(GL20.GL_ARRAY_BUFFER, 0,
                currentInstanceCount * BYTES_PER_INSTANCE, instanceBuffer);
        Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);

        Gdx.gl30.glBindVertexArray(vaoHandle);
        Gdx.gl30.glDrawElementsInstanced(
                GL20.GL_TRIANGLES,
                gpuMesh.getNumIndices(),
                GL20.GL_UNSIGNED_SHORT,
                0,
                currentInstanceCount);
        Gdx.gl30.glBindVertexArray(0);
    }

    /**
     * Sets (or disables) a shader-level colour override — useful for debugging to make every
     * draw call work independently of the colour attribute (e.g. try a bright magenta).
     */
    public void setOverrideColor(float r, float g, float b, float a, boolean on) {
        shader.bind();
        shader.setUniformf("u_overrideColor", r, g, b, a);
        shader.setUniformf("u_useOverride", on ? 1f : 0f);
    }

    /**
     * Re-uploads the colour palette as shader uniforms (no VBO rebuild needed).
     * Call after editing palette colours to refresh rendering.
     */
    public void refreshPalette() {
        shader.bind();
        uploadPaletteUniforms();
    }

    /**
     * Returns the zero-based index of a mesh within this group.
     */
    public int indexOf(String name) {
        Integer idx = nameToIndex.get(name);
        return idx != null ? idx : -1;
    }

    /**
     * Returns the maximum number of instances supported by this group.
     */
    public int maxInstances() {
        return maxInstances;
    }

    /**
     * Returns the number of instances added since the last {@link #begin()}.
     */
    public int currentInstanceCount() {
        return currentInstanceCount;
    }

    /**
     * Returns the palette used for lookups.
     */
    public ColorPalette getPalette() {
        return palette;
    }

    @Override
    public void dispose() {
        gpuMesh.dispose();
        shader.dispose();
        IntBuffer handleBuf = BufferUtils.newIntBuffer(1);
        handleBuf.put(0, instanceVboHandle);
        Gdx.gl.glDeleteBuffers(1, handleBuf);
        IntBuffer vaoBuffer = BufferUtils.newIntBuffer(1);
        vaoBuffer.put(0, vaoHandle);
        Gdx.gl30.glDeleteVertexArrays(1, vaoBuffer);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the ordered poses this group was built from.
     */
    public List<BlendableMesh> meshes() {
        return orderedMeshes;
    }

    private void setupVao() {
        Gdx.gl30.glBindVertexArray(vaoHandle);

        gpuMesh.bind(shader);

        Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, instanceVboHandle);
        int stride = BYTES_PER_INSTANCE;
        bindInstanceAttrib(locInstSlots, 4, 0 * Float.BYTES, stride);
        bindInstanceAttrib(locInstWeights, 4, 4 * Float.BYTES, stride);
        bindInstanceAttrib(locInstPos, 2, 8 * Float.BYTES, stride);

        Gdx.gl30.glBindVertexArray(0);

        gpuMesh.unbind(shader);
        Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
    }

    private void bindInstanceAttrib(int loc, int size, int byteOffset, int stride) {
        if (loc < 0) return;
        Gdx.gl.glEnableVertexAttribArray(loc);
        Gdx.gl.glVertexAttribPointer(loc, size, GL20.GL_FLOAT, false, stride, byteOffset);
        Gdx.gl30.glVertexAttribDivisor(loc, 1);
    }

    /**
     * Generates the GLSL vertex shader. Positions are blended per instance via slot/weight
     * attributes; colour indices are resolved from per-pose vertex attributes and blended
     * at the slot weights using palette uniforms.
     */
    private static String buildVertexShader(int n, int palSize) {
        StringBuilder sb = new StringBuilder();

        sb.append("attribute vec3 a_basePos;\n");
        for (int i = 0; i < n; i++) sb.append("attribute vec3 a_off").append(i).append(";\n");
        // Pack N colour indices into ceil(N/4) vec4 blocks
        int colBlocks = (n + 3) / 4;
        for (int b = 0; b < colBlocks; b++) {
            sb.append("attribute vec4 a_colBlock").append(b).append(";\n");
        }
        sb.append('\n');

        sb.append("attribute vec4 a_inst_slots;    // slot indices (as float, cast to int)\n");
        sb.append("attribute vec4 a_inst_weights;  // blend weights\n");
        sb.append("attribute vec2 a_inst_pos;      // world-space position offset\n\n");

        sb.append("uniform mat4 u_projTrans;\n");
        // Palette uniforms
        for (int i = 0; i < palSize; i++) {
            sb.append("uniform vec4 u_pal").append(i).append(";\n");
        }
        sb.append("varying vec4 v_color;\n\n");

        sb.append("vec3 pickOffset(int s) {\n");
        for (int i = 0; i < n; i++)
            sb.append("    if (s == ").append(i).append(") return a_off").append(i).append(";\n");
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
        if (palSize == 0) {
            sb.append("    return vec4(1.0, 0.0, 1.0, 1.0);\n");
        } else {
            for (int i = 0; i < palSize; i++) {
                sb.append("    if (idx == ").append(i).append(") return u_pal").append(i).append(";\n");
            }
            sb.append("    return u_pal0;\n");
        }
        sb.append("}\n\n");

        sb.append("""
                void main() {
                    int s0 = int(a_inst_slots.x);
                    int s1 = int(a_inst_slots.y);
                    int s2 = int(a_inst_slots.z);
                    int s3 = int(a_inst_slots.w);
                
                    float total = a_inst_weights.x + a_inst_weights.y
                                + a_inst_weights.z + a_inst_weights.w;
                    float inv = (total > 0.0001) ? 1.0 / total : 0.0;
                
                    vec3 blended_off = ( pickOffset(s0) * a_inst_weights.x
                                       + pickOffset(s1) * a_inst_weights.y
                                       + pickOffset(s2) * a_inst_weights.z
                                       + pickOffset(s3) * a_inst_weights.w ) * inv;
                
                    vec3 final_pos = a_basePos + blended_off;
                    final_pos.xy += a_inst_pos;  // world-space position offset
                
                    // Resolve and blend colours per slot
                    vec4 c0 = resolveColor(int(pickColorIdx(s0) + 0.5)) * a_inst_weights.x;
                    vec4 c1 = resolveColor(int(pickColorIdx(s1) + 0.5)) * a_inst_weights.y;
                    vec4 c2 = resolveColor(int(pickColorIdx(s2) + 0.5)) * a_inst_weights.z;
                    vec4 c3 = resolveColor(int(pickColorIdx(s3) + 0.5)) * a_inst_weights.w;
                    v_color = (c0 + c1 + c2 + c3) * inv;
                
                    gl_Position = u_projTrans * vec4(final_pos, 1.0);
                }
                """);

        return sb.toString();
    }

    /**
     * Uploads the current palette colours as {@code u_pal0..u_palN} uniforms.
     */
    private void uploadPaletteUniforms() {
        int palSize = palette.size();
        for (int i = 0; i < palSize; i++) {
            shader.setUniformf("u_pal" + i,
                palette.red(i), palette.green(i), palette.blue(i), palette.alpha(i));
        }
    }
}
