package com.jb.rts.mesh.vat;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Draws an unbounded number of copies of one GPU-skinned mesh in a <b>single instanced draw
 * call</b> — 100k+ instances per call on any GL 3.x / GL ES 3.0 device.
 *
 * <h3>What lives on the GPU</h3>
 * <ul>
 *   <li><b>Static bind-pose VBO</b> — uploaded exactly once by the constructor
 *       ({@link VatMeshData#toInterleavedVertices()}):
 *       {@code a_position} (vec3), {@code a_color} (ColorPacked),
 *       {@code a_boneIds} (vec3, Usage.Generic unit 0),
 *       {@code a_weights} (vec3, Usage.Generic unit 1).</li>
 *   <li><b>Optional UV VBO</b> — {@code a_texcoord0} (vec2) per expanded vertex, uploaded only
 *       when the mesh has texture coordinates ({@link VatMeshData#hasUvs()}).</li>
 *   <li><b>Model texture</b> — optional {@link Texture} on texture unit 4, passed to the
 *       constructor and <b>owned by the caller</b> (one picture serves all animations of an
 *       export). The fragment shader samples it instead of the per-vertex Gouraud colours and
 *       discards transparent texels.</li>
 *   <li><b>Bone matrix texture</b> — one {@code RGBA32F} texture per animation
 *       ({@link VatBoneTexture}), never updated at runtime.</li>
 *   <li><b>Colour strip</b> — optional {@code RGBA8} texture with one texel per expanded vertex
 *       and frame ({@link VatFrameColorTexture}, texture unit 1). Present only when the export
 *       overrides vertex colours per frame; the shader then mixes the two neighbouring frames so
 *       colour blending matches the CPU blend renderer.</li>
 *   <li><b>Step mask</b> — optional {@code RGBA8} texture with one texel per expanded vertex
 *       ({@link VatStepTexture}, texture unit 2). Present only when the mesh has attachment
 *       geometry; it identifies the vertices whose state must switch instantly.</li>
 *   <li><b>Per-frame step flags</b> — optional {@code RGBA8} texture with one texel per baked frame
 *       ({@link VatFrameStepTexture}, texture unit 3). Present only when the clip changes the
 *       attachment state; the shader snaps the interval that ends at such a frame, so a placement
 *       or removal never grows/shrinks while every other interval interpolates normally.</li>
 *   <li><b>Instance VBO</b> — {@value #FLOATS_PER_INSTANCE} floats per instance
 *       ({@value #BYTES_PER_INSTANCE} bytes), streamed with {@code glBufferSubData} once per
 *       frame:
 *       <pre>
 *  offset  field               attribute
 *  ------  ------------------  ------------------
 *     0    x, y                a_instancePos   (vec2)
 *     8    frame               a_instanceFrame (float, fractional, e.g. 4.5)
 *    12    rotation (radians)  a_instanceRot   (float)
 *    16    scaleX, scaleY      a_instanceScale (vec2)
 *    24    r, g, b, a          a_instanceTint  (vec4)
 *       </pre></li>
 * </ul>
 *
 * <h3>Per-object CPU cost</h3>
 * Writing 10 floats into a preallocated buffer. No matrix maths, no per-object upload, no
 * material changes — the entire crowd is one {@code glDrawArraysInstanced} call.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * VatFile.Content content = VatFile.load(Gdx.files.internal("meshes/unit_idle.vat"));
 * VatInstancedMesh batch = new VatInstancedMesh(content.mesh(), content.animation(), 200_000);
 *
 * public void render() {
 *     float baseFrame = content.animation().frameAt(stateTime);
 *
 *     batch.begin();
 *     for (Unit unit : units) {
 *         batch.add(unit.x, unit.y, content.animation().wrapFrame(baseFrame + unit.phase),
 *                   unit.facing, unit.scale);          // rotation + uniform scale
 *     }
 *     batch.render(camera.combined);                   // 1 draw call for all units
 * }
 * }</pre>
 *
 * <h3>Requirements</h3>
 * OpenGL ES 3.0 / OpenGL 3.2 core ({@code Gdx.gl30 != null}) for instancing, vertex texture fetch
 * and {@code RGBA32F} textures.
 *
 * <p>{@link VatMeshData} / {@link VatAnimationData} are immutable and may be shared, but every
 * {@code VatInstancedMesh} owns its own static VBO, instance buffer and bone texture (one renderer
 * per mesh + animation pair).
 */
public final class VatInstancedMesh implements Disposable {

    /** Floats per instance (pos 2 + frame 1 + rot 1 + scale 2 + tint 4). */
    public static final int FLOATS_PER_INSTANCE = 10;

    /** Bytes per instance. */
    public static final int BYTES_PER_INSTANCE = FLOATS_PER_INSTANCE * Float.BYTES;

    // Instance attribute byte offsets inside one instance record.
    private static final int OFFSET_POS   = 0;
    private static final int OFFSET_FRAME = 8;
    private static final int OFFSET_ROT   = 12;
    private static final int OFFSET_SCALE = 16;
    private static final int OFFSET_TINT  = 24;

    // -----------------------------------------------------------------------

    private final VatMeshData meshData;
    private final VatAnimationData animation;
    private final int maxInstances;

    private final Mesh gpuMesh;
    private final ShaderProgram shader;
    private final VatBoneTexture boneTexture;
    /** Per-frame colour strip, or {@code null} when the animation uses one colour per vertex. */
    private final VatFrameColorTexture frameColorTexture;
    /** Per-vertex attachment step mask, or {@code null} when the mesh has no attachments. */
    private final VatStepTexture stepTexture;
    /** Per-frame attachment step flags, or {@code null} when the clip has no switches. */
    private final VatFrameStepTexture stepFrameTexture;

    private final int vaoHandle;
    private final int instanceVboHandle;
    /** Static per-vertex UV buffer ({@code 2} floats each); {@code 0} when the mesh has no UVs. */
    private final int uvVboHandle;
    private final FloatBuffer instanceBuffer;

    /** Model texture (texture unit 4), or {@code null}. Owned by the caller — never disposed here. */
    private final Texture texture;

    private int currentInstanceCount;

    private final int locPos;
    private final int locFrame;
    private final int locRot;
    private final int locScale;
    private final int locTint;
    private final int locUv;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /**
     * Uploads the static mesh and the animation and prepares the instanced pipeline.
     *
     * @param meshData     static bind-pose geometry + bone influences
     * @param animation    baked bone matrices (one texture)
     * @param maxInstances capacity of the per-frame instance stream
     */
    public VatInstancedMesh(VatMeshData meshData, VatAnimationData animation, int maxInstances) {
        this(meshData, animation, maxInstances, null);
    }

    /**
     * Same as {@link #VatInstancedMesh(VatMeshData, VatAnimationData, int)}, plus an optional
     * <b>model texture</b>.
     *
     * <p>When {@code texture} is not {@code null}, the mesh's UVs
     * ({@link VatMeshData#uvs()}) are uploaded as a second static VBO and the fragment shader
     * samples the texture instead of the per-vertex (Gouraud) colours; the per-instance tint
     * still multiplies the texel and transparent texels are discarded. This is the runtime
     * counterpart of the mesheditor's texture-mapped preview, so a textured mesh exported with
     * {@code JsonMeshExporter} (version 6) looks the same in the player.
     *
     * <p>The texture is <b>not</b> owned by this batch: every animation of one export shares a
     * single instance, so the caller keeps and disposes it ({@link #texture()}).
     *
     * @throws IllegalArgumentException when a texture is given for a mesh without UVs
     */
    public VatInstancedMesh(VatMeshData meshData, VatAnimationData animation, int maxInstances,
                            Texture texture) {
        if (meshData == null || animation == null) {
            throw new IllegalArgumentException("meshData and animation must not be null.");
        }
        if (Gdx.gl30 == null) {
            throw new IllegalStateException(
                "VatInstancedMesh requires OpenGL ES 3.0 / OpenGL 3.x (Gdx.gl30 is null).");
        }
        if (maxInstances <= 0) {
            throw new IllegalArgumentException("maxInstances must be > 0, was " + maxInstances);
        }
        if ((long) maxInstances * BYTES_PER_INSTANCE > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "maxInstances " + maxInstances + " exceeds the 2 GiB instance-buffer limit.");
        }
        if (texture != null && !meshData.hasUvs()) {
            throw new IllegalArgumentException(
                "A model texture needs per-vertex UVs, but the mesh has none.");
        }
        this.meshData = meshData;
        this.animation = animation;
        this.maxInstances = maxInstances;
        this.texture = texture;

        // ---- static geometry: ONE upload, never touched again --------------------
        VertexAttribute[] attributes = {
            new VertexAttribute(Usage.Position, 3, VatShaders.ATTR_POSITION),
            new VertexAttribute(Usage.ColorPacked, 4, VatShaders.ATTR_COLOR),
            new VertexAttribute(Usage.Generic, VatFormat.INFLUENCES, VatShaders.ATTR_BONE_IDS, 0),
            new VertexAttribute(Usage.Generic, VatFormat.INFLUENCES, VatShaders.ATTR_WEIGHTS, 1)
        };
        // VertexBufferObject (not the default VAO-backed buffer on GL3): this class owns its own
        // VAO so that the static attributes and the per-instance attributes share one state block.
        gpuMesh = new Mesh(Mesh.VertexDataType.VertexBufferObject, true,
            meshData.vertexCount(), 0, attributes);
        gpuMesh.setVertices(meshData.toInterleavedVertices());

        shader = VatShaders.createProgram();

        locPos   = requiredAttribute(VatShaders.ATTR_INSTANCE_POS);
        locFrame = requiredAttribute(VatShaders.ATTR_INSTANCE_FRAME);
        locRot   = requiredAttribute(VatShaders.ATTR_INSTANCE_ROT);
        locScale = requiredAttribute(VatShaders.ATTR_INSTANCE_SCALE);
        locTint  = requiredAttribute(VatShaders.ATTR_INSTANCE_TINT);
        // The UV attribute is part of the shared shader body; a texture without it cannot work.
        locUv    = shader.getAttributeLocation(VatShaders.ATTR_TEXCOORD);
        if (texture != null && locUv < 0) {
            throw new IllegalStateException("VAT shader does not declare attribute '"
                + VatShaders.ATTR_TEXCOORD + "'. Log:\n" + shader.getLog());
        }

        boneTexture = new VatBoneTexture(animation);

        // ---- optional per-frame colour strip (restores frame-to-frame colour blending) ----
        if (animation.hasFrameColors() && animation.colorVertexCount() != meshData.vertexCount()) {
            throw new IllegalArgumentException("Animation colour strip describes "
                + animation.colorVertexCount() + " vertices but the mesh has "
                + meshData.vertexCount() + ".");
        }
        frameColorTexture = animation.hasFrameColors() ? new VatFrameColorTexture(animation) : null;
        stepTexture = meshData.hasStepVertices() ? new VatStepTexture(meshData) : null;
        stepFrameTexture = animation.hasStepFrames()
            ? new VatFrameStepTexture(animation) : null;

        // ---- streaming instance buffer ------------------------------------------
        IntBuffer handles = BufferUtils.newIntBuffer(1);
        Gdx.gl.glGenBuffers(1, handles);
        instanceVboHandle = handles.get(0);
        Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, instanceVboHandle);
        Gdx.gl.glBufferData(GL20.GL_ARRAY_BUFFER,
            maxInstances * BYTES_PER_INSTANCE, null, GL20.GL_DYNAMIC_DRAW);
        Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);

        instanceBuffer = BufferUtils.newFloatBuffer(maxInstances * FLOATS_PER_INSTANCE);

        // ---- optional static UV buffer (only for textured meshes) ----------------
        uvVboHandle = locUv >= 0 && meshData.hasUvs() ? createUvBuffer(meshData.uvs()) : 0;

        // ---- VAO: static attributes + per-instance attributes -------------------
        IntBuffer vaoHandleBuffer = BufferUtils.newIntBuffer(1);
        Gdx.gl30.glGenVertexArrays(1, vaoHandleBuffer);
        vaoHandle = vaoHandleBuffer.get(0);
        setupVao();

        // ---- immutable uniforms --------------------------------------------------
        shader.bind();
        shader.setUniformi(VatShaders.UNIFORM_BONES, 0);
        shader.setUniformf("u_frameCount", animation.frameCount());
        shader.setUniformf("u_loop", animation.looping() ? 1f : 0f);
        shader.setUniformf("u_overrideColor", 1f, 0f, 1f, 1f);
        shader.setUniformf("u_useOverride", 0f);
        // Colour strip lives on texture unit 1; the geometry uniforms are valid either way.
        shader.setUniformi(VatShaders.UNIFORM_FRAME_COLORS, 1);
        shader.setUniformf(VatShaders.UNIFORM_HAS_FRAME_COLORS, frameColorTexture != null ? 1f : 0f);
        shader.setUniformf(VatShaders.UNIFORM_COLOR_ROW_LENGTH,
            VatFormat.colorRowLength(meshData.vertexCount()));
        shader.setUniformf(VatShaders.UNIFORM_COLOR_VERTEX_COUNT, meshData.vertexCount());
        shader.setUniformf(VatShaders.UNIFORM_HAS_STEP, stepTexture != null ? 1f : 0f);
        shader.setUniformi(VatShaders.UNIFORM_STEP, 2);
        shader.setUniformf(VatShaders.UNIFORM_STEP_ROW_LENGTH,
            VatFormat.colorRowLength(meshData.vertexCount()));
        shader.setUniformf(VatShaders.UNIFORM_HAS_STEP_FRAMES,
            stepFrameTexture != null ? 1f : 0f);
        shader.setUniformi(VatShaders.UNIFORM_STEP_FRAMES, 3);
        shader.setUniformf(VatShaders.UNIFORM_STEP_FRAME_ROW_LENGTH,
            stepFrameTexture != null ? stepFrameTexture.rowLength() : 1f);
        // Model texture lives on texture unit 4; the switch disables the sampler when absent.
        shader.setUniformi(VatShaders.UNIFORM_TEXTURE, 4);
        shader.setUniformf(VatShaders.UNIFORM_HAS_TEXTURE, texture != null ? 1f : 0f);

        if (Gdx.app != null) {
            Gdx.app.log("VatInstancedMesh", String.format(
                "VAT '%s': %d vertices, %d triangles, %d bones x %d frames (%dx%d RGBA32F), "
                    + "max %d instances%s%s.",
                animation.name(), meshData.vertexCount(), meshData.triangleCount(),
                animation.boneCount(), animation.frameCount(),
                animation.textureWidth(), animation.textureHeight(), maxInstances,
                frameColorTexture != null
                    ? String.format(", per-frame colours %dx%d RGBA8",
                        frameColorTexture.width(), frameColorTexture.height())
                    : "",
                texture != null
                    ? String.format(", model texture %dx%d", texture.getWidth(), texture.getHeight())
                    : ""));
        }
    }

    /** Looks up a required vertex attribute and fails loudly when the shader dropped it. */
    private int requiredAttribute(String alias) {
        int location = shader.getAttributeLocation(alias);
        if (location < 0) {
            throw new IllegalStateException("VAT shader does not declare attribute '" + alias
                + "'. Log:\n" + shader.getLog());
        }
        return location;
    }

    /**
     * Uploads the per-vertex UVs into a dedicated static VBO. A separate buffer (instead of
     * widening the interleaved layout) keeps untextured meshes at exactly 40 bytes per vertex:
     * the attribute only exists when the mesh actually has texture coordinates.
     */
    private static int createUvBuffer(float[] uvs) {
        FloatBuffer data = BufferUtils.newFloatBuffer(uvs.length);
        data.put(uvs).flip();

        IntBuffer handle = BufferUtils.newIntBuffer(1);
        Gdx.gl.glGenBuffers(1, handle);
        int vbo = handle.get(0);
        Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
        Gdx.gl.glBufferData(GL20.GL_ARRAY_BUFFER, uvs.length * 4, data, GL20.GL_STATIC_DRAW);
        Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        return vbo;
    }

    /**
     * Builds the VAO. The static attributes come from {@link Mesh#bind(ShaderProgram)}; the five
     * instance attributes are bound to the instance VBO with a divisor of 1 (one value per
     * instance instead of per vertex).
     */
    private void setupVao() {
        Gdx.gl30.glBindVertexArray(vaoHandle);

        gpuMesh.bind(shader);

        Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, instanceVboHandle);
        int stride = BYTES_PER_INSTANCE;
        bindInstanceAttribute(locPos,   2, OFFSET_POS,   stride);
        bindInstanceAttribute(locFrame, 1, OFFSET_FRAME, stride);
        bindInstanceAttribute(locRot,   1, OFFSET_ROT,   stride);
        bindInstanceAttribute(locScale, 2, OFFSET_SCALE, stride);
        bindInstanceAttribute(locTint,  4, OFFSET_TINT,  stride);

        // Per-vertex UV attribute (divisor 0 = one value per vertex, not per instance).
        if (uvVboHandle != 0) {
            Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, uvVboHandle);
            Gdx.gl.glEnableVertexAttribArray(locUv);
            Gdx.gl.glVertexAttribPointer(locUv, 2, GL20.GL_FLOAT, false, 0, 0);
            Gdx.gl30.glVertexAttribDivisor(locUv, 0);
        }

        Gdx.gl30.glBindVertexArray(0);
        gpuMesh.unbind(shader);
        Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
    }

    private static void bindInstanceAttribute(int location, int size, int byteOffset, int stride) {
        Gdx.gl.glEnableVertexAttribArray(location);
        Gdx.gl.glVertexAttribPointer(location, size, GL20.GL_FLOAT, false, stride, byteOffset);
        Gdx.gl30.glVertexAttribDivisor(location, 1);
    }

    // -----------------------------------------------------------------------
    // Per-frame instance stream
    // -----------------------------------------------------------------------

    /** Clears the instance list. Call once per frame before any {@link #add} call. */
    public void begin() {
        instanceBuffer.clear();
        currentInstanceCount = 0;
    }

    /**
     * Adds an instance at {@code (x, y)} playing {@code frame} at default rotation, scale and tint.
     *
     * @param frame fractional frame index, e.g. {@code 4.5} (see {@link VatAnimationData#frameAt})
     */
    public void add(float x, float y, float frame) {
        add(x, y, frame, 0f, 1f, 1f, 1f, 1f, 1f, 1f);
    }

    /** Adds an instance with a rotation (radians) and a uniform scale. */
    public void add(float x, float y, float frame, float rotationRad, float scale) {
        add(x, y, frame, rotationRad, scale, scale, 1f, 1f, 1f, 1f);
    }

    /**
     * Adds a fully specified instance. All values are written straight into the streaming buffer;
     * no matrix is built on the CPU.
     *
     * @param x           world X
     * @param y           world Y
     * @param frame       fractional frame index (frame N + fraction)
     * @param rotationRad rotation around the mesh origin, in radians
     * @param scaleX      horizontal scale
     * @param scaleY      vertical scale (negative flips the sprite)
     * @param r           tint red   (0..1)
     * @param g           tint green (0..1)
     * @param b           tint blue  (0..1)
     * @param a           tint alpha (0..1)
     */
    public void add(float x, float y, float frame, float rotationRad,
                    float scaleX, float scaleY, float r, float g, float b, float a) {
        if (currentInstanceCount >= maxInstances) {
            throw new IllegalStateException(
                "VAT instance capacity (" + maxInstances + ") exceeded for '" + animation.name()
                    + "'. Increase maxInstances when constructing VatInstancedMesh.");
        }
        instanceBuffer.put(x).put(y);
        instanceBuffer.put(frame);
        instanceBuffer.put(rotationRad);
        instanceBuffer.put(scaleX).put(scaleY);
        instanceBuffer.put(r).put(g).put(b).put(a);
        currentInstanceCount++;
    }

    /**
     * Uploads the instance stream and issues the single instanced draw call.
     *
     * @param projTrans combined projection + view matrix (e.g. {@code camera.combined})
     */
    public void render(Matrix4 projTrans) {
        if (currentInstanceCount == 0) return;

        instanceBuffer.flip();

        shader.bind();
        shader.setUniformMatrix("u_projTrans", projTrans);
        boneTexture.bind(0);
        // Unit 1 holds the per-frame colour strip; without one bind the bone texture so the
        // sampler is never left pointing at a deleted/unknown texture.
        if (frameColorTexture != null) {
            frameColorTexture.bind(1);
        } else {
            boneTexture.bind(1);
        }
        // Unit 2 holds the attachment step mask; without one bind the bone texture so
        // the sampler is never left pointing at a deleted/unknown texture.
        if (stepTexture != null) {
            stepTexture.bind(2);
        } else {
            boneTexture.bind(2);
        }
        // Unit 3 holds the per-frame attachment step flags.
        if (stepFrameTexture != null) {
            stepFrameTexture.bind(3);
        } else {
            boneTexture.bind(3);
        }
        // Unit 4 holds the optional model texture; without one bind the bone texture so the
        // sampler never points at a deleted/unknown texture (the switch keeps it unused).
        if (texture != null) {
            texture.bind(4);
        } else {
            boneTexture.bind(4);
        }

        Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, instanceVboHandle);
        Gdx.gl.glBufferSubData(GL20.GL_ARRAY_BUFFER, 0,
            currentInstanceCount * BYTES_PER_INSTANCE, instanceBuffer);
        Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);

        // Texture atlases carry an alpha channel: blend so the transparent background between
        // the triangles disappears instead of painting the clear colour.
        boolean textured = texture != null;
        if (textured) {
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        }
        Gdx.gl30.glBindVertexArray(vaoHandle);
        Gdx.gl30.glDrawArraysInstanced(GL20.GL_TRIANGLES, 0,
            meshData.vertexCount(), currentInstanceCount);
        Gdx.gl30.glBindVertexArray(0);
        if (textured) {
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }

        // Restore the conventional active texture unit 0. Texture.bind(int) leaves the given unit
        // active, and SpriteBatch.flush() later calls Texture.bind() (no unit), which binds to the
        // *currently active* unit. Leaving unit 1 active here made the overlay font and the Scene2D
        // skin sample the bone matrix / colour strip instead of their own atlas — the navigation
        // buttons then rendered black/invisible.
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
    }

    // -----------------------------------------------------------------------
    // Debug helpers / accessors
    // -----------------------------------------------------------------------

    /**
     * Forces the fragment colour (useful to verify the draw call independently of the vertex
     * colours — e.g. a bright magenta).
     */
    public void setOverrideColor(float r, float g, float b, float a, boolean on) {
        shader.bind();
        shader.setUniformf("u_overrideColor", r, g, b, a);
        shader.setUniformf("u_useOverride", on ? 1f : 0f);
    }

    /** Number of instances added since the last {@link #begin()}. */
    public int instanceCount() {
        return currentInstanceCount;
    }

    /** Capacity of the instance stream. */
    public int maxInstances() {
        return maxInstances;
    }

    /** Static bind-pose geometry uploaded by this batch. */
    public VatMeshData meshData() {
        return meshData;
    }

    /** Baked animation sampled by this batch. */
    public VatAnimationData animation() {
        return animation;
    }

    /** The compiled shader (for custom uniforms, e.g. a global tint). */
    public ShaderProgram shader() {
        return shader;
    }

    /** The bone matrix texture (for custom binding). */
    public VatBoneTexture boneTexture() {
        return boneTexture;
    }

    /**
     * The model texture this batch draws with (texture unit 4), or {@code null} when the mesh
     * is drawn with its per-vertex palette colours. The texture is owned by the caller.
     */
    public Texture texture() {
        return texture;
    }

    /** True when this batch samples a model texture instead of the per-vertex colours. */
    public boolean hasTexture() {
        return texture != null;
    }

    /**
     * The per-frame colour strip (texture unit 1), or {@code null} when the animation carries no
     * per-frame colour overrides and the static {@code a_color} attribute is used.
     */
    public VatFrameColorTexture frameColorTexture() {
        return frameColorTexture;
    }

    /**
     * The per-vertex attachment step mask (texture unit 2), or {@code null} when
     * the mesh has no attachment geometry.
     */
    public VatStepTexture stepTexture() {
        return stepTexture;
    }

    /**
     * The per-frame attachment step flags (texture unit 3), or {@code null} when
     * the clip has no attachment state changes.
     */
    public VatFrameStepTexture stepFrameTexture() {
        return stepFrameTexture;
    }

    /**
     * Logs the GL state of every attribute (location, enabled flag, component count, stride and
     * the VBO it reads from). Useful when a pipeline change silently stops drawing: a missing
     * {@code enabled=1} or a {@code vbo=0} immediately shows which attribute is not wired up.
     */
    public void logPipelineState() {
        if (Gdx.app == null) return;
        IntBuffer out = BufferUtils.newIntBuffer(1);
        String[] names = {
            VatShaders.ATTR_POSITION, VatShaders.ATTR_COLOR,
            VatShaders.ATTR_BONE_IDS, VatShaders.ATTR_WEIGHTS,
            VatShaders.ATTR_TEXCOORD,
            VatShaders.ATTR_INSTANCE_POS, VatShaders.ATTR_INSTANCE_FRAME,
            VatShaders.ATTR_INSTANCE_ROT, VatShaders.ATTR_INSTANCE_SCALE,
            VatShaders.ATTR_INSTANCE_TINT
        };
        Gdx.gl30.glBindVertexArray(vaoHandle);
        Gdx.app.log("VatInstancedMesh", String.format(
            "VAO=%d instanceVbo=%d vertices=%d stride=%d",
            vaoHandle, instanceVboHandle, meshData.vertexCount(), VatFormat.FLOATS_PER_VERTEX * 4));
        for (String name : names) {
            int loc = shader.getAttributeLocation(name);
            if (loc < 0) {
                Gdx.app.log("VatInstancedMesh", "  " + name + ": <not declared / optimised out>");
                continue;
            }
            int enabled = queryAttrib(loc, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED, out);
            int size = queryAttrib(loc, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE, out);
            int stride = queryAttrib(loc, GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE, out);
            int vbo = queryAttrib(loc, GL20.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING, out);
            Gdx.app.log("VatInstancedMesh", String.format(
                "  %-16s loc=%d enabled=%d size=%d stride=%d vbo=%d",
                name, loc, enabled, size, stride, vbo));
        }
        Gdx.gl30.glBindVertexArray(0);
    }

    private static int queryAttrib(int location, int pname, IntBuffer out) {
        out.put(0, 0);
        Gdx.gl.glGetVertexAttribiv(location, pname, out);
        return out.get(0);
    }

    @Override
    public void dispose() {
        gpuMesh.dispose();
        shader.dispose();
        boneTexture.dispose();
        if (frameColorTexture != null) frameColorTexture.dispose();
        if (stepTexture != null) stepTexture.dispose();
        if (stepFrameTexture != null) stepFrameTexture.dispose();

        IntBuffer handle = BufferUtils.newIntBuffer(1);
        handle.put(0, instanceVboHandle);
        Gdx.gl.glDeleteBuffers(1, handle);

        // The model texture is caller-owned (shared by every animation batch of one export).
        if (uvVboHandle != 0) {
            IntBuffer uv = BufferUtils.newIntBuffer(1);
            uv.put(0, uvVboHandle);
            Gdx.gl.glDeleteBuffers(1, uv);
        }

        IntBuffer vao = BufferUtils.newIntBuffer(1);
        vao.put(0, vaoHandle);
        Gdx.gl30.glDeleteVertexArrays(1, vao);
    }
}