package com.jb.rts.mesh.vat;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;

import java.nio.ByteBuffer;

/**
 * Per-vertex <b>step mask</b> of one mesh: a single {@code RGBA8} texture with
 * one texel per expanded vertex (red channel = 1 for attachment geometry).
 *
 * <p>Attachment vertices must not be blended between two animation frames — an
 * attachment is either placed on its slot or collapsed to size&nbsp;0, and
 * interpolating those states would make it grow/shrink. The VAT shader samples
 * this texture with {@code gl_VertexID} and snaps the frame index to
 * {@code floor(frame)} for flagged vertices, so a switch happens exactly at a
 * keyframe.
 *
 * <p>Layout (same row folding as {@link VatFrameColorTexture}):
 * {@code x = vertex % rowLength}, {@code y = vertex / rowLength}, with
 * {@code rowLength = min(vertexCount, 2048)}. A mesh without attachments simply
 * does not create this texture ({@code u_hasStep = 0}).
 */
public final class VatStepTexture implements Disposable {

    private final int handle;
    private final int width;
    private final int height;

    /**
     * Uploads {@code meshData}'s step mask.
     *
     * @throws IllegalArgumentException when the mesh has no step vertices
     */
    public VatStepTexture(VatMeshData meshData) {
        if (Gdx.gl30 == null) {
            throw new IllegalStateException(
                "VAT step textures require OpenGL ES 3.0 / OpenGL 3.x (Gdx.gl30 is null).");
        }
        if (meshData == null || !meshData.hasStepVertices()) {
            throw new IllegalArgumentException("Mesh carries no step vertices, nothing to upload.");
        }
        int vertexCount = meshData.vertexCount();
        this.width = Math.max(1, Math.min(vertexCount, VatFormat.COLOR_TEXTURE_MAX_WIDTH));
        this.height = Math.max(1, (vertexCount + width - 1) / width);

        ByteBuffer buffer = BufferUtils.newByteBuffer(vertexCount * VatFormat.BYTES_PER_COLOR_TEXEL);
        boolean[] steps = meshData.stepVertices();
        for (int i = 0; i < vertexCount; i++) {
            byte value = (byte) (steps[i] ? 255 : 0);
            buffer.put(value);  // r
            buffer.put((byte) 0); // g
            buffer.put((byte) 0); // b
            buffer.put((byte) 255); // a
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

    /** Texture height in texels ({@code ceil(vertexCount / width)}). */
    public int height() {
        return height;
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
