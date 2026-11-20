package com.jb.rts.mesh.vat;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * The baked bone matrices of one animation living in GPU memory as a single {@code RGBA32F}
 * texture.
 *
 * <p>The upload is a single {@code glTexImage2D} of the flat float array produced by the
 * mesheditor — no conversion, no repacking. Filtering is {@code NEAREST} because frame
 * interpolation happens explicitly in the shader (and linear filtering of 32-bit float textures
 * is not guaranteed on GL ES 3.0 anyway).
 *
 * <p><b>Layout:</b> {@code x = frame*4 + column} (4 texels per matrix), {@code y = bone}.
 *
 * <p>The texture is created once per {@link VatInstancedMesh}. Its dimensions are fixed, but the
 * content may be replaced in place with {@link #update(VatAnimationData)} — the cheap path an
 * interactive animation blend uses.
 */
public final class VatBoneTexture implements Disposable {

    private final int handle;
    private final int width;
    private final int height;

    /** Reused by {@link #update(VatAnimationData)} so a weight drag does not allocate each time. */
    private FloatBuffer updateBuffer;

    /**
     * Uploads {@code animation}'s matrices.
     *
     * @throws IllegalStateException when the context lacks GL 3.0 (instancing/float textures) or
     *                               the texture exceeds {@code GL_MAX_TEXTURE_SIZE}
     */
    public VatBoneTexture(VatAnimationData animation) {
        if (Gdx.gl30 == null) {
            throw new IllegalStateException(
                "VAT bone textures require OpenGL ES 3.0 / OpenGL 3.x (Gdx.gl30 is null).");
        }
        this.width = animation.textureWidth();
        this.height = animation.textureHeight();

        int maxSize = maxTextureSize();
        if (width > maxSize || height > maxSize) {
            throw new IllegalStateException(String.format(
                "VAT texture %dx%d exceeds GL_MAX_TEXTURE_SIZE (%d). "
                    + "Bake at most %d frames per animation or split the clip.",
                width, height, maxSize, VatFormat.maxFrames(maxSize)));
        }

        float[] data = animation.boneMatrices();
        FloatBuffer buffer = BufferUtils.newFloatBuffer(data.length);
        buffer.put(data);
        buffer.flip();

        handle = Gdx.gl.glGenTexture();
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, handle);
        // RGBA32F: 4 full-precision floats per texel — exact matrices, no half-float drift.
        Gdx.gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F,
            width, height, 0, GL20.GL_RGBA, GL20.GL_FLOAT, buffer);

        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MIN_FILTER, GL20.GL_NEAREST);
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MAG_FILTER, GL20.GL_NEAREST);
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_S, GL20.GL_CLAMP_TO_EDGE);
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_T, GL20.GL_CLAMP_TO_EDGE);
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0);
    }

    /** Texture width in texels ({@code frameCount * 4}). */
    public int width() {
        return width;
    }

    /** Texture height in texels ({@code boneCount}). */
    public int height() {
        return height;
    }

    /** Raw GL texture handle (for custom binding). */
    public int handle() {
        return handle;
    }

    /**
     * Re-uploads the matrices of {@code animation} into this texture (<b>same</b> dimensions).
     *
     * <p>This is what makes an animation blend interactive: swapping the clip of a running
     * {@link VatInstancedMesh} ({@link VatInstancedMesh#updateAnimation}) only rewrites the
     * {@code boneCount × frameCount × 16} floats of the matrix texture instead of rebuilding the
     * mesh, the VAO and the shader program — a handful of kilobytes per frame.</p>
     *
     * @param animation the new clip; must have the same bone count and frame count as the clip
     *                  this texture was created from
     * @throws IllegalArgumentException when the texture dimensions would change
     */
    public void update(VatAnimationData animation) {
        if (animation == null) {
            throw new IllegalArgumentException("animation cannot be null.");
        }
        if (animation.textureWidth() != width || animation.textureHeight() != height) {
            throw new IllegalArgumentException("Texture is " + width + "x" + height
                + " but the animation needs " + animation.textureWidth() + "x"
                + animation.textureHeight() + "; create a new VatInstancedMesh instead.");
        }
        if (Gdx.gl30 == null) {
            throw new IllegalStateException(
                "VAT bone textures require OpenGL ES 3.0 / OpenGL 3.x (Gdx.gl30 is null).");
        }
        float[] data = animation.boneMatrices();
        if (updateBuffer == null || updateBuffer.capacity() != data.length) {
            updateBuffer = BufferUtils.newFloatBuffer(data.length);
        }
        updateBuffer.clear();
        updateBuffer.put(data);
        updateBuffer.flip();

        // Unit 0 is what SpriteBatch/VatInstancedMesh expect to be active afterwards.
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, handle);
        Gdx.gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 0, 0, width, height,
            GL20.GL_RGBA, GL20.GL_FLOAT, updateBuffer);
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0);
    }

    /**
     * Binds this texture to the given texture unit and makes that unit active.
     *
     * @param unit zero-based texture unit ({@code 0} is the default for the VAT sampler)
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

    /** {@code GL_MAX_TEXTURE_SIZE} of the current context. */
    public static int maxTextureSize() {
        if (Gdx.gl30 == null) {
            throw new GdxRuntimeException(
                "VAT requires OpenGL ES 3.0 / OpenGL 3.x (Gdx.gl30 is null).");
        }
        IntBuffer max = BufferUtils.newIntBuffer(1);
        Gdx.gl.glGetIntegerv(GL20.GL_MAX_TEXTURE_SIZE, max);
        return max.get(0);
    }
}