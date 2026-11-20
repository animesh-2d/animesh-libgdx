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
 * Filtering is {@code NEAREST} (the shader interpolates) and the texture is immutable after
 * construction.
 */
public final class VatFrameColorTexture implements Disposable {

    private final int handle;
    private final int width;
    private final int height;

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
        this.width = animation.colorTextureWidth();
        this.height = animation.colorTextureHeight();

        int maxSize = VatBoneTexture.maxTextureSize();
        if (width > maxSize || height > maxSize) {
            throw new IllegalStateException(String.format(
                "VAT colour texture %dx%d exceeds GL_MAX_TEXTURE_SIZE (%d). "
                    + "Reduce the clip length or split the animation.",
                width, height, maxSize));
        }

        ByteBuffer buffer = BufferUtils.newByteBuffer(
            animation.frameColors().length * VatFormat.BYTES_PER_COLOR_TEXEL);
        for (int packed : animation.frameColors()) {
            buffer.put((byte) packed);        // r
            buffer.put((byte) (packed >> 8)); // g
            buffer.put((byte) (packed >> 16));// b
            buffer.put((byte) (packed >> 24));// a
        }
        buffer.flip();

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
