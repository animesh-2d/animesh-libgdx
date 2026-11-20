package com.jb.rts.mesh.vat;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;

import java.nio.ByteBuffer;

/**
 * Per-frame <b>attachment step</b> mask of one animation: a small {@code RGBA8}
 * texture with one texel per baked frame (red channel = 1 when the attachment
 * state changes at that frame).
 *
 * <p>Attachment vertices must switch instantly between "placed on the slot" and
 * "collapsed to size 0". Only the <em>interval that ends at a changed frame</em>
 * is snapped; in every other interval the attachment can interpolate normally
 * and follow a moving slot smoothly. The shader samples this texture with the
 * upcoming frame index.
 *
 * <p>Layout: {@code x = frame % rowLength}, {@code y = frame / rowLength}, with
 * {@code rowLength = min(frameCount, 2048)}. A clip without state changes does
 * not create the texture ({@code u_hasStepFrames = 0}).
 */
public final class VatFrameStepTexture implements Disposable {

    private final int handle;
    private final int width;
    private final int height;
    private final int rowLength;

    /** Uploads {@code animation}'s per-frame step flags. */
    public VatFrameStepTexture(VatAnimationData animation) {
        if (Gdx.gl30 == null) {
            throw new IllegalStateException(
                "VAT step textures require OpenGL ES 3.0 / OpenGL 3.x (Gdx.gl30 is null).");
        }
        if (animation == null || !animation.hasStepFrames()) {
            throw new IllegalArgumentException("Animation has no step frames, nothing to upload.");
        }
        int frameCount = animation.frameCount();
        this.rowLength = Math.max(1, Math.min(frameCount, VatFormat.COLOR_TEXTURE_MAX_WIDTH));
        this.width = rowLength;
        this.height = Math.max(1, (frameCount + rowLength - 1) / rowLength);

        ByteBuffer buffer = BufferUtils.newByteBuffer(frameCount * VatFormat.BYTES_PER_COLOR_TEXEL);
        for (int i = 0; i < frameCount; i++) {
            buffer.put((byte) (animation.isStepFrame(i) ? 255 : 0));
            buffer.put((byte) 0);
            buffer.put((byte) 0);
            buffer.put((byte) 255);
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

    /** Texture width in texels ({@code min(frameCount, 2048)}). */
    public int width() {
        return width;
    }

    /** Texture height in texels ({@code ceil(frameCount / width)}). */
    public int height() {
        return height;
    }

    /** Texels per row (the shader receives this through a uniform). */
    public int rowLength() {
        return rowLength;
    }

    /** Raw GL texture handle (for custom binding). */
    public int handle() {
        return handle;
    }

    /** Binds this texture to the given texture unit and makes that unit active. */
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
