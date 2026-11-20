package com.jb.rts.mesh.vat;

/**
 * Layout constants and index helpers for the <b>Vertex Animation Texture</b> (VAT) data.
 *
 * <h3>Bone matrix texture (RGBA32F)</h3>
 * All bone matrices of one animation are baked into a single 2D floating-point texture:
 * <pre>
 *   x (width)  = frame * 4 + column     → 4 texels per frame, one per matrix column
 *   y (height) = bone                   → one row per bone
 * </pre>
 * A texel {@code (frame*4 + c, bone)} holds column {@code c} of that bone's 4x4 matrix for
 * that frame. Columns are stored in memory order (the same order as
 * {@link com.badlogic.gdx.math.Matrix4#val}), so the shader can rebuild the matrix without a
 * transpose: {@code mat4(col0, col1, col2, col3)}.
 *
 * <p>The 16 floats of one matrix are therefore <em>contiguous</em> in the upload array:
 * {@code [m00,m10,m20,m30, m01,m11,m21,m31, m02,m12,m22,m32, m03,m13,m23,m33]} where
 * {@code m03}/{@code m13} are the translation. For the 2D affine matrices this editor produces,
 * {@code m20 = m21 = m23 = 0} and {@code m22 = m32 = 1}.
 *
 * <p>Flat float index of a matrix:
 * <pre>{@code
 * offset(bone, frame) = (bone * frameCount + frame) * 16
 *              = textureFloatOffset(bone, frame)   // see below
 * }</pre>
 * which is exactly texture row {@code bone}, texels {@code frame*4 .. frame*4+3}.
 *
 * <h3>Limits</h3>
 * {@code textureWidth(frameCount) = frameCount * 4} must not exceed
 * {@code GL_MAX_TEXTURE_SIZE} (usually 16384 on desktop, 4096+ on mobile → up to 1024+ frames
 * per animation). Use {@link #maxFrames(int)} to check before uploading.
 */
public final class VatFormat {

    /** File magic: {@code 'V' 'A' 'T' '1'}. */
    public static final int MAGIC = ('V' << 24) | ('A' << 16) | ('T' << 8) | '1';

    /** File magic of the multi-clip container: {@code 'V' 'A' 'T' '2'}. */
    public static final int MAGIC_MULTI = ('V' << 24) | ('A' << 16) | ('T' << 8) | '2';

    /** Current file format version written by the exporter. */
    public static final int VERSION = 1;

    /** Version of the multi-clip container (static mesh once + one block per clip). */
    public static final int VERSION_MULTI = 2;

    /**
     * Number of bone influences baked per vertex ({@code a_boneIds}/{@code a_weights} are
     * {@code vec3}). Three influences is the sweet spot for 2D skeletons: it covers every
     * realistic skin weight, keeps the vertex shader at 18 texture fetches and the vertex
     * stride at 40 bytes. Raising this to 4 only requires changing this constant, the two
     * {@code VertexAttribute}s to {@code vec4} and the shader's {@code vec3} → {@code vec4}.
     */
    public static final int INFLUENCES = 3;

    /** Number of texels a single 4x4 matrix occupies horizontally (one per column). */
    public static final int TEXELS_PER_MATRIX = 4;

    /** Floats per texel of an RGBA32F texture. */
    public static final int FLOATS_PER_TEXEL = 4;

    /** Floats per baked 4x4 matrix ({@value #TEXELS_PER_MATRIX} × {@value #FLOATS_PER_TEXEL}). */
    public static final int FLOATS_PER_MATRIX = TEXELS_PER_MATRIX * FLOATS_PER_TEXEL;

    /** Size of the binary header in bytes (see {@code VatFile}). */
    public static final int HEADER_BYTES = 40;

    /** Size of the multi-clip container header in bytes. */
    public static final int HEADER_BYTES_MULTI = 32;

    /** Header flag: the animation loops (frame {@code frameCount-1} blends back to frame 0). */
    public static final int FLAG_LOOPING = 1;

    /** Floats per skinned vertex in the static VBO (3 pos + 1 packed colour + 2×influences). */
    public static final int FLOATS_PER_VERTEX = 3 + 1 + INFLUENCES * 2;

    /**
     * Bytes per texel of the per-frame colour strip (one {@code GL_RGBA8} texel per
     * vertex and frame).
     */
    public static final int BYTES_PER_COLOR_TEXEL = 4;

    /**
     * Row length of the per-frame colour strip. The strip stores
     * {@code frameCount * vertexCount} texels in <b>row-major</b> order and wraps every
     * {@value} texels to the next row, so a mesh with more vertices than
     * {@code GL_MAX_TEXTURE_SIZE} still fits (the bone texture cannot exceed that limit
     * anyway, but a skinned mesh has far fewer bones than vertices).
     */
    public static final int COLOR_TEXTURE_MAX_WIDTH = 2048;

    private VatFormat() {}

    // -----------------------------------------------------------------------
    // Texture geometry
    // -----------------------------------------------------------------------

    /** Texture width in texels for {@code frameCount} frames. */
    public static int textureWidth(int frameCount) {
        return frameCount * TEXELS_PER_MATRIX;
    }

    /** Texture height in texels (one row per bone). */
    public static int textureHeight(int boneCount) {
        return boneCount;
    }

    /** Number of floats in the baked matrix array. */
    public static int matrixFloatCount(int boneCount, int frameCount) {
        return boneCount * frameCount * FLOATS_PER_MATRIX;
    }

    /**
     * Flat float offset of one bone matrix inside the baked matrix array / texture upload
     * buffer.
     *
     * @param boneIndex  zero-based bone row
     * @param frameIndex zero-based frame
     * @param frameCount total frames of the animation
     */
    public static int matrixFloatOffset(int boneIndex, int frameIndex, int frameCount) {
        return (boneIndex * frameCount + frameIndex) * FLOATS_PER_MATRIX;
    }

    /** X texel coordinate of matrix column {@code column} of {@code frameIndex}. */
    public static int matrixTexelX(int frameIndex, int column) {
        return frameIndex * TEXELS_PER_MATRIX + column;
    }

    /**
     * Maximum number of frames that fit into a texture of {@code maxTextureSize} texels.
     * Frame count is limited by the horizontal axis ({@code frames*4}).
     */
    public static int maxFrames(int maxTextureSize) {
        return Math.max(1, maxTextureSize / TEXELS_PER_MATRIX);
    }

    // -----------------------------------------------------------------------
    // Static vertex geometry
    // -----------------------------------------------------------------------

    /** Number of floats in the interleaved static vertex buffer. */
    public static int vertexFloatCount(int vertexCount) {
        return vertexCount * FLOATS_PER_VERTEX;
    }

    // -----------------------------------------------------------------------
    // Per-frame colour strip
    // -----------------------------------------------------------------------

    /**
     * Texels per row of the per-frame colour strip: {@code min(vertexCount,
     * COLOR_TEXTURE_MAX_WIDTH)}. The shader receives the same value through
     * {@code u_colorRowLength}.
     */
    public static int colorRowLength(int vertexCount) {
        int row = Math.min(vertexCount, COLOR_TEXTURE_MAX_WIDTH);
        return Math.max(1, row);
    }

    /** Texture width in texels of the per-frame colour strip. */
    public static int colorTextureWidth(int vertexCount) {
        return colorRowLength(vertexCount);
    }

    /** Texture height in texels of the per-frame colour strip ({@code ceil(v*f / row)}). */
    public static int colorTextureHeight(int vertexCount, int frameCount) {
        long texels = (long) vertexCount * frameCount;
        long row = colorRowLength(vertexCount);
        return (int) ((texels + row - 1) / row);
    }

    /**
     * Flat texel index of one vertex colour inside the strip, <b>before</b> it is folded
     * into {@code (x, y)} by {@link #colorTextureWidth(int)}.
     *
     * @param vertexCount number of expanded vertices (one colour row per vertex)
     * @param frameIndex  zero-based frame
     * @param vertexIndex zero-based expanded vertex
     */
    public static int frameColorTexel(int vertexCount, int frameIndex, int vertexIndex) {
        return frameIndex * vertexCount + vertexIndex;
    }

    /** Number of texels (= bytes / {@value #BYTES_PER_COLOR_TEXEL}) of a colour strip. */
    public static int colorTexelCount(int vertexCount, int frameCount) {
        return vertexCount * frameCount;
    }
}