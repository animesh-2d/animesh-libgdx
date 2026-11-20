package com.jb.rts.mesh.vat;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;

import java.nio.ByteBuffer;

/**
 * The <b>per-frame colour strip</b> of one animation living in GPU memory as a single
 * {@code GL_RGBA8} texture.
 *
 * <p>The static vertex buffer ({@link VatMeshData}) stores one colour per vertex. Because the
 * mesheditor can override the colours of each animation frame, those overrides are uploaded
 * here: one texel per (frame, expanded vertex). The vertex shader fetches the two neighbouring
 * frames and {@code mix()}es them with the same weight it uses for the pose, which reproduces
 * the CPU blend renderer's colour blending.
 *
 * <h3>Layout</h3>
 * <pre>
 *   texel index   = frame * vertexCount + vertex        ({@link VatFormat#frameColorTexel})
 *   x (width)     = texel % rowLength                   (rowLength = min(vertexCount, 2048))
 *   y (height)    = texel / rowLength
 * </pre>
 * The strip wraps every {@link VatFormat#COLOR_TEXTURE_MAX_WIDTH} texels, so a mesh with more
 * vertices than {@code GL_MAX_TEXTURE_SIZE} still fits beside the (much smaller) bone texture.
 *
 * <p>Colours are {@code int}s packed as {@code 0xAABBGGRR}, i.e. the bytes land in memory as
 * R, G, B, A — no endianness assumptions, the upload writes the four bytes explicitly.
 * Filtering is {@code NEAREST} (the shader interpolates); the dimensions are fixed, but the
 * content can be replaced in place with {@link #update(VatAnimationData)} — or, after a palette
 * edit, with the re-resolved strip through {@link #update(int[])}.
 */
public final class VatFrameColorTexture implements Disposable {

    private final int handle;
    private final int width;
    private final int height;
    /** Meaningful texels of the strip ({@code vertexCount * frameCount}). */
    private final int texelCount;

    /** Reused by {@link #update(VatAnimationData)} so a weight drag does not allocate each time. */
    private ByteBuffer updateBuffer;

    /**
     * Uploads {@code animation}'s colour strip.
     *
     * @throws IllegalStateException    when the context lacks GL 3.0 or the texture exceeds
     *                                  {@code GL_MAX_TEXTURE_SIZE}
     * @throws IllegalArgumentException when the animation has no per-frame colours
     */
    public VatFrameColorTexture(VatAnimationData animation) {
        if (Gdx.gl30 == null) {
            throw new IllegalStateException(
                "VAT colour textures require OpenGL ES 3.0 / OpenGL 3.x (Gdx.gl30 is null).");
        }
        if (animation == null || !animation.hasFrameColors()) {
            throw new IllegalArgumentException(
                "Animation carries no per-frame colours, nothing to upload.");
        }
        this.texelCount = animation.frameColors().length;
        this.width = animation.colorTextureWidth();
        this.height = animation.colorTextureHeight();

        int maxSize = VatBoneTexture.maxTextureSize();
        if (width > maxSize || height > maxSize) {
            throw new IllegalStateException(String.format(
                "VAT colour texture %dx%d exceeds GL_MAX_TEXTURE_SIZE (%d). "
                    + "Reduce the clip length or split the animation.",
                width, height, maxSize));
        }

        ByteBuffer buffer = packedBytes(animation.frameColors());

        handle = Gdx.gl.glGenTexture();
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, handle);
        Gdx.gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL30.GL_RGBA8,
            width, height, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, buffer);

        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MIN_FILTER, GL20.GL_NEAREST);
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MAG_FILTER, GL20.GL_NEAREST);
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_S, GL20.GL_CLAMP_TO_EDGE);
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_T, GL20.GL_CLAMP_TO_EDGE);
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0);
    }

    /** Texture width in texels ({@code min(vertexCount, 2048)}). */
    public int width() {
        return width;
    }

    /** Texture height in texels ({@code ceil(vertexCount * frameCount / width)}). */
    public int height() {
        return height;
    }

    /** Raw GL texture handle (for custom binding). */
    public int handle() {
        return handle;
    }

    /**
     * Re-uploads the colour strip of {@code animation} (<b>same</b> dimensions).
     *
     * <p>Used together with {@link VatBoneTexture#update(VatAnimationData)} when an
     * {@link VatInstancedMesh} swaps its clip in place, e.g. while an animation blend weight is
     * dragged: only {@code vertexCount × frameCount} RGBA bytes are rewritten.</p>
     *
     * @param animation the new clip; must carry per-frame colours for the same vertex count and
     *                  frame count as this strip
     * @throws IllegalArgumentException when the texture dimensions would change
     */
    public void update(VatAnimationData animation) {
        if (animation == null || !animation.hasFrameColors()) {
            throw new IllegalArgumentException("Animation carries no per-frame colours.");
        }
        if (animation.colorTextureWidth() != width || animation.colorTextureHeight() != height) {
            throw new IllegalArgumentException("Colour texture is " + width + "x" + height
                + " but the animation needs " + animation.colorTextureWidth() + "x"
                + animation.colorTextureHeight() + "; create a new VatInstancedMesh instead.");
        }
        update(animation.frameColors());
    }

    /**
     * Re-uploads raw packed strip texels ({@code 0xAABBGGRR}, frame-major) into this texture.
     *
     * <p>This is the <b>palette refresh</b> path: an export that recolours triangle corners per
     * frame keeps the palette <em>indexes</em> of every texel
     * ({@link VatAnimationData#frameColorIndices()}), so an edited palette can be re-resolved into
     * the strip and uploaded here — no re-bake, and the authored colour ramps are reproduced
     * because the same resolution runs as at bake time.
     *
     * @param frameColors the new strip, in the {@link VatAnimationData#frameColors()} layout
     * @throws IllegalArgumentException when the texel count differs from this texture's
     */
    public void update(int[] frameColors) {
        if (frameColors == null || frameColors.length != texelCount) {
            throw new IllegalArgumentException("Colour strip must hold " + texelCount
                + " texels, was " + (frameColors == null ? "null" : frameColors.length));
        }
        if (Gdx.gl30 == null) {
            throw new IllegalStateException(
                "VAT colour textures require OpenGL ES 3.0 / OpenGL 3.x (Gdx.gl30 is null).");
        }
        ByteBuffer buffer = packedBytes(frameColors);

        // Unit 0 is what SpriteBatch/VatInstancedMesh expect to be active afterwards.
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, handle);
        Gdx.gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 0, 0, width, height,
            GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, buffer);
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0);
    }

    /**
     * Packs strip texels into the reused upload buffer (R, G, B, A bytes per texel).
     *
     * <p>The buffer is padded to {@code width * height} texels: a strip whose texel count is not a
     * multiple of the row length leaves the last texture row partially unused, and OpenGL reads
     * <b>complete</b> rows from the pointer — without the padding the driver reads past the end of
     * the buffer.
     */
    private ByteBuffer packedBytes(int[] frameColors) {
        int uploadTexels = width * height;   // whole rows, see the javadoc above
        int bytes = uploadTexels * VatFormat.BYTES_PER_COLOR_TEXEL;
        if (updateBuffer == null || updateBuffer.capacity() < bytes) {
            updateBuffer = BufferUtils.newByteBuffer(bytes);
        }
        updateBuffer.clear();
        for (int packed : frameColors) {
            updateBuffer.put((byte) packed);        // r
            updateBuffer.put((byte) (packed >> 8)); // g
            updateBuffer.put((byte) (packed >> 16));// b
            updateBuffer.put((byte) (packed >> 24));// a
        }
        for (int texel = frameColors.length; texel < uploadTexels; texel++) {
            updateBuffer.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
        }
        updateBuffer.flip();
        return updateBuffer;
    }

    /**
     * Binds this texture to the given texture unit and makes that unit active.
     *
     * @param unit zero-based texture unit ({@code 1} is used by {@link VatInstancedMesh},
     *             unit {@code 0} holds the bone matrix texture)
     */
    public void bind(int unit) {
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0 + unit);
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, handle);
    }

    @Override
    public void dispose() {
        if (Gdx.gl == null || handle == 0) return;
        Gdx.gl.glDeleteTexture(handle);
    }
}
