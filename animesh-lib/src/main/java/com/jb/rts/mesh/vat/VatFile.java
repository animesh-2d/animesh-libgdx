package com.jb.rts.mesh.vat;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.GdxRuntimeException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reader / writer for the mesheditor's {@code .vat} (Vertex Animation Texture) binary format.
 *
 * <h3>File layout (version 1)</h3>
 * <pre>
 * offset size  field
 * ------ ----  ----------------------------------------------------------------------
 *   0     4    int    magic            'V','A','T','1'  ({@link VatFormat#MAGIC})
 *   4     4    int    version          {@link VatFormat#VERSION}
 *   8     4    int    boneCount        texture rows
 *  12     4    int    frameCount       texture columns are frameCount*4
 *  16     4    int    vertexCount      expanded bind-pose vertices (multiple of 3)
 *  20     4    int    triangleCount    vertexCount / 3
 *  24     4    float  fps
 *  28     4    int    flags            bit0 = looping
 *  32     4    int    influences       must be {@link VatFormat#INFLUENCES}
 *  36     4    int    metaBytes        length of the UTF-8 block that follows
 *  40     n    utf8   meta             line 0: animation name, lines 1..boneCount: bone names
 *  40+n   ...  float  frameTimes       frameCount entries, seconds
 *   ...   ...  float  boneMatrices     boneCount*frameCount*16 (RGBA32F texture data)
 *   ...   ...  float  positions        vertexCount*3 (bind pose)
 *   ...   ...  float  colors           vertexCount*4 (RGBA 0..1)
 *   ...   ...  int    boneIds          vertexCount*influences
 *   ...   ...  float  weights          vertexCount*influences (normalized)
 * </pre>
 *
 * <p>All scalars are big-endian (written with {@link DataOutputStream}). The matrix block is the
 * exact array that is uploaded to the GPU — no conversion happens at load time.
 */
public final class VatFile {

    /** A loaded pair of static mesh + baked animation. */
    public record Content(VatMeshData mesh, VatAnimationData animation) {}

    /**
     * A loaded multi-clip container: the static mesh shared by every clip plus all baked
     * animations (at least one).
     */
    public record FileContent(VatMeshData mesh, List<VatAnimationData> animations) {}

    private VatFile() {}

    // -----------------------------------------------------------------------
    // Write
    // -----------------------------------------------------------------------

    /**
     * Serializes {@code mesh} and {@code animation} into {@code file} (overwrites).
     *
     * @throws IllegalArgumentException when the mesh bone table and the animation disagree
     * @throws GdxRuntimeException      when the file cannot be written
     */
    public static void write(FileHandle file, VatMeshData mesh, VatAnimationData animation) {
        if (mesh == null || animation == null) {
            throw new IllegalArgumentException("mesh and animation must not be null.");
        }
        if (mesh.boneNames().length != animation.boneCount()) {
            throw new IllegalArgumentException(
                "Bone table mismatch: mesh has " + mesh.boneNames().length
                    + " bones but the animation baked " + animation.boneCount() + ".");
        }

        String meta = buildMeta(animation.name(), mesh.boneNames());
        byte[] metaBytes = meta.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(
            VatFormat.HEADER_BYTES + metaBytes.length
                + animation.boneMatrices().length * 4
                + mesh.vertexCount() * VatFormat.FLOATS_PER_VERTEX * 4);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(VatFormat.MAGIC);
            out.writeInt(VatFormat.VERSION);
            out.writeInt(animation.boneCount());
            out.writeInt(animation.frameCount());
            out.writeInt(mesh.vertexCount());
            out.writeInt(mesh.triangleCount());
            out.writeFloat(animation.fps());
            out.writeInt(animation.looping() ? VatFormat.FLAG_LOOPING : 0);
            out.writeInt(VatFormat.INFLUENCES);
            out.writeInt(metaBytes.length);
            out.write(metaBytes);

            float[] times = animation.frameTimes();
            if (times == null) times = uniformTimes(animation.frameCount(), animation.fps());
            for (float t : times) out.writeFloat(t);
            for (float v : animation.boneMatrices()) out.writeFloat(v);
            for (float v : mesh.positions()) out.writeFloat(v);
            for (float v : mesh.colors()) out.writeFloat(v);
            for (int v : mesh.boneIds()) out.writeInt(v);
            for (float v : mesh.weights()) out.writeFloat(v);
            out.flush();
        } catch (IOException e) {
            throw new GdxRuntimeException(
                "Failed to serialize VAT data for '" + file.path() + "'.", e);
        }
        file.writeBytes(bytes.toByteArray(), false);
    }

    /**
     * Serializes one static mesh plus <b>all</b> of its clips into a single multi-clip container
     * (magic {@code VAT2}): geometry, colours and influences are written once, followed by one
     * block per animation.
     *
     * @throws IllegalArgumentException when the bone tables disagree
     * @throws GdxRuntimeException      when the file cannot be written
     */
    public static void writeAll(FileHandle file, VatMeshData mesh,
                                List<VatAnimationData> animations) {
        if (mesh == null || animations == null || animations.isEmpty()) {
            throw new IllegalArgumentException("mesh and at least one animation are required.");
        }
        for (VatAnimationData animation : animations) {
            if (animation.boneCount() != mesh.boneNames().length) {
                throw new IllegalArgumentException("Clip '" + animation.name() + "' has "
                    + animation.boneCount() + " bones but the mesh table has "
                    + mesh.boneNames().length + ".");
            }
        }

        byte[] metaBytes = buildBoneMeta(mesh.boneNames()).getBytes(StandardCharsets.UTF_8);
        int size = VatFormat.HEADER_BYTES_MULTI + metaBytes.length
            + mesh.vertexCount() * VatFormat.FLOATS_PER_VERTEX * 4;
        for (VatAnimationData animation : animations) {
            size += 4 + animation.name().getBytes(StandardCharsets.UTF_8).length
                + 4 + 4 + 4 + 4 * animation.frameCount() + 4 * animation.boneMatrices().length;
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(size);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(VatFormat.MAGIC_MULTI);
            out.writeInt(VatFormat.VERSION_MULTI);
            out.writeInt(VatFormat.INFLUENCES);
            out.writeInt(mesh.boneNames().length);
            out.writeInt(animations.size());
            out.writeInt(mesh.vertexCount());
            out.writeInt(mesh.triangleCount());
            out.writeInt(metaBytes.length);
            out.write(metaBytes);

            for (float v : mesh.positions()) out.writeFloat(v);
            for (float v : mesh.colors()) out.writeFloat(v);
            for (int v : mesh.boneIds()) out.writeInt(v);
            for (float v : mesh.weights()) out.writeFloat(v);

            for (VatAnimationData animation : animations) {
                byte[] nameBytes = animation.name().getBytes(StandardCharsets.UTF_8);
                out.writeInt(nameBytes.length);
                out.write(nameBytes);
                out.writeFloat(animation.fps());
                out.writeInt(animation.looping() ? VatFormat.FLAG_LOOPING : 0);
                out.writeInt(animation.frameCount());
                float[] times = animation.frameTimes();
                if (times == null) times = uniformTimes(animation.frameCount(), animation.fps());
                for (float t : times) out.writeFloat(t);
                for (float v : animation.boneMatrices()) out.writeFloat(v);
            }
            out.flush();
        } catch (IOException e) {
            throw new GdxRuntimeException(
                "Failed to serialize VAT container for '" + file.path() + "'.", e);
        }
        file.writeBytes(bytes.toByteArray(), false);
    }

    /** Reads a {@code .vat} file (version 1 or 2) and returns its first clip. */
    public static Content load(FileHandle file) {
        return read(file.readBytes(), file.path());
    }

    /**
     * Reads a {@code .vat} file: a version-2 container yields all clips, a version-1 file is
     * wrapped as a single-clip result.
     */
    public static FileContent loadAll(FileHandle file) {
        return readAll(file.readBytes(), file.path());
    }

    /** Reads VAT data from an in-memory buffer (used by the loader and by tests). */
    public static Content read(byte[] data, String sourceName) {
        FileContent content = readAll(data, sourceName);
        return new Content(content.mesh(), content.animations().get(0));
    }

    /** Reads a version 1 or version 2 buffer without discarding any clip. */
    public static FileContent readAll(byte[] data, String sourceName) {
        int magic = magicOf(data);
        if (magic == VatFormat.MAGIC) {
            Content single = readVersion1(data, sourceName);
            return new FileContent(single.mesh(), List.of(single.animation()));
        }
        if (magic == VatFormat.MAGIC_MULTI) {
            return readVersion2(data, sourceName);
        }
        throw new GdxRuntimeException("Not a VAT file (" + sourceName + "): bad magic 0x"
            + Integer.toHexString(magic));
    }

    private static int magicOf(byte[] data) {
        if (data == null || data.length < 4) {
            throw new GdxRuntimeException("VAT file is too short to contain a header.");
        }
        return ((data[0] & 0xff) << 24) | ((data[1] & 0xff) << 16)
            | ((data[2] & 0xff) << 8) | (data[3] & 0xff);
    }

    /** Version 1: one static mesh + exactly one animation (see the class documentation). */
    private static Content readVersion1(byte[] data, String sourceName) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int magic = in.readInt();
            if (magic != VatFormat.MAGIC) {
                throw new GdxRuntimeException("Not a VAT file (" + sourceName + "): bad magic 0x"
                    + Integer.toHexString(magic));
            }
            int version = in.readInt();
            if (version != VatFormat.VERSION) {
                throw new GdxRuntimeException("Unsupported VAT version " + version + " in "
                    + sourceName + " (expected " + VatFormat.VERSION + ").");
            }
            int boneCount = in.readInt();
            int frameCount = in.readInt();
            int vertexCount = in.readInt();
            int triangleCount = in.readInt();
            float fps = in.readFloat();
            int flags = in.readInt();
            int influences = in.readInt();
            int metaBytes = in.readInt();

            checkCount(boneCount, "boneCount", sourceName);
            checkCount(frameCount, "frameCount", sourceName);
            checkCount(vertexCount, "vertexCount", sourceName);
            if (influences != VatFormat.INFLUENCES) {
                throw new GdxRuntimeException("VAT " + sourceName + " stores " + influences
                    + " influences per vertex, this build expects " + VatFormat.INFLUENCES
                    + ". Re-export the mesh.");
            }
            if (vertexCount % 3 != 0 || triangleCount != vertexCount / 3) {
                throw new GdxRuntimeException("VAT " + sourceName + " has an invalid topology ("
                    + vertexCount + " vertices / " + triangleCount + " triangles).");
            }

            String meta = readMeta(in, metaBytes, sourceName);
            String[] lines = meta.isEmpty() ? new String[0] : meta.split("\n", -1);
            String animationName = lines.length > 0 ? lines[0] : "";
            String[] boneNames = new String[boneCount];
            for (int i = 0; i < boneCount; i++) {
                boneNames[i] = (i + 1 < lines.length && !lines[i + 1].isEmpty())
                    ? lines[i + 1] : "bone" + i;
            }

            float[] frameTimes = readFloats(in, frameCount);
            float[] matrices = readFloats(in, VatFormat.matrixFloatCount(boneCount, frameCount));
            float[] positions = readFloats(in, vertexCount * 3);
            float[] colors = readFloats(in, vertexCount * 4);
            int[] boneIds = new int[vertexCount * influences];
            for (int i = 0; i < boneIds.length; i++) boneIds[i] = in.readInt();
            float[] weights = readFloats(in, vertexCount * influences);

            VatMeshData mesh = new VatMeshData(positions, colors, boneIds, weights, null, boneNames);
            VatAnimationData animation = new VatAnimationData(
                animationName, boneCount, frameCount, fps,
                (flags & VatFormat.FLAG_LOOPING) != 0, frameTimes, matrices);
            return new Content(mesh, animation);
        } catch (IOException e) {
            throw new GdxRuntimeException("Failed to read VAT file " + sourceName + ".", e);
        }
    }

    /** Version 2: one static mesh + {@code clipCount} animations. */
    private static FileContent readVersion2(byte[] data, String sourceName) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            in.readInt(); // magic
            int version = in.readInt();
            if (version != VatFormat.VERSION_MULTI) {
                throw new GdxRuntimeException("Unsupported VAT container version " + version
                    + " in " + sourceName + " (expected " + VatFormat.VERSION_MULTI + ").");
            }
            int influences = in.readInt();
            int boneCount = in.readInt();
            int clipCount = in.readInt();
            int vertexCount = in.readInt();
            int triangleCount = in.readInt();
            int metaBytes = in.readInt();

            checkCount(boneCount, "boneCount", sourceName);
            checkCount(clipCount, "clipCount", sourceName);
            checkCount(vertexCount, "vertexCount", sourceName);
            if (influences != VatFormat.INFLUENCES) {
                throw new GdxRuntimeException("VAT " + sourceName + " stores " + influences
                    + " influences per vertex, this build expects " + VatFormat.INFLUENCES
                    + ". Re-export the mesh.");
            }
            if (vertexCount % 3 != 0 || triangleCount != vertexCount / 3) {
                throw new GdxRuntimeException("VAT " + sourceName + " has an invalid topology ("
                    + vertexCount + " vertices / " + triangleCount + " triangles).");
            }

            String meta = readMeta(in, metaBytes, sourceName);
            String[] lines = meta.isEmpty() ? new String[0] : meta.split("\n", -1);
            String[] boneNames = new String[boneCount];
            for (int i = 0; i < boneCount; i++) {
                boneNames[i] = (i < lines.length && !lines[i].isEmpty()) ? lines[i] : "bone" + i;
            }

            float[] positions = readFloats(in, vertexCount * 3);
            float[] colors = readFloats(in, vertexCount * 4);
            int[] boneIds = new int[vertexCount * influences];
            for (int i = 0; i < boneIds.length; i++) boneIds[i] = in.readInt();
            float[] weights = readFloats(in, vertexCount * influences);
            VatMeshData mesh = new VatMeshData(positions, colors, boneIds, weights, null, boneNames);

            List<VatAnimationData> animations = new ArrayList<>(clipCount);
            for (int c = 0; c < clipCount; c++) {
                int nameBytes = in.readInt();
                if (nameBytes < 0 || nameBytes > 1 << 12) {
                    throw new GdxRuntimeException(
                        "VAT " + sourceName + " declares an invalid clip name.");
                }
                byte[] raw = new byte[nameBytes];
                in.readFully(raw);
                String name = new String(raw, StandardCharsets.UTF_8);
                float fps = in.readFloat();
                int flags = in.readInt();
                int frameCount = in.readInt();
                checkCount(frameCount, "frameCount", sourceName);
                float[] times = readFloats(in, frameCount);
                float[] matrices = readFloats(in,
                    VatFormat.matrixFloatCount(boneCount, frameCount));
                animations.add(new VatAnimationData(name, boneCount, frameCount, fps,
                    (flags & VatFormat.FLAG_LOOPING) != 0, times, matrices));
            }
            return new FileContent(mesh, animations);
        } catch (IOException e) {
            throw new GdxRuntimeException("Failed to read VAT container " + sourceName + ".", e);
        }
    }

    /** Bone names, one per line (the container meta block has no animation name). */
    private static String buildBoneMeta(String[] boneNames) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < boneNames.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(boneNames[i] == null ? "" : boneNames[i].replace('\n', ' '));
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static float[] uniformTimes(int frameCount, float fps) {
        float[] times = new float[frameCount];
        for (int i = 0; i < frameCount; i++) times[i] = i / fps;
        return times;
    }

    private static String buildMeta(String animationName, String[] boneNames) {
        StringBuilder sb = new StringBuilder();
        sb.append(animationName == null ? "" : animationName.replace('\n', ' '));
        for (String b : boneNames) {
            sb.append('\n').append(b == null ? "" : b.replace('\n', ' '));
        }
        return sb.toString();
    }

    private static String readMeta(DataInputStream in, int metaBytes, String sourceName)
            throws IOException {
        if (metaBytes < 0 || metaBytes > 1 << 20) {
            throw new GdxRuntimeException("VAT " + sourceName + " declares an invalid meta block ("
                + metaBytes + " bytes).");
        }
        byte[] raw = new byte[metaBytes];
        in.readFully(raw);
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static float[] readFloats(DataInputStream in, int count) throws IOException {
        float[] values = new float[count];
        for (int i = 0; i < count; i++) values[i] = in.readFloat();
        return values;
    }

    private static void checkCount(int value, String name, String sourceName) {
        if (value <= 0 || value > 1 << 22) {
            throw new GdxRuntimeException(
                "VAT " + sourceName + " declares an invalid " + name + ": " + value);
        }
    }
}