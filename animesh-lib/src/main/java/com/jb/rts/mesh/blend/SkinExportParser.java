package com.jb.rts.mesh.blend;

import com.badlogic.gdx.utils.JsonValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the skinning half of a mesheditor export into {@link MeshSkin} + {@link MeshSkinClip}s.
 *
 * <p>Two input dialects are supported:
 * <ul>
 *   <li><b>Version 4</b> — the {@code "skin"} block (bone table, expanded top-3 influences) and
 *       pre-baked per-frame matrices in {@code animations[].frames[].boneMatrices}.</li>
 *   <li><b>Version 2/3</b> — the legacy top-level {@code "bones"} array with dense, bone-major
 *       {@code weights} and per-frame {@code bones} objects (centre/direction). Those are
 *       resolved here into the same top-3 skin and converted into matrices using
 *       {@code M = T(c) · R(Δθ) · S(Δlen) · T(−c_bind)}.</li>
 * </ul>
 *
 * <p>Bones without any influence are dropped, unweighted vertices are bound to a synthetic
 * {@value #BIND_POSE_BONE} identity row, and frames sharing a keyframe time are merged (the last
 * pose at that time wins) so the GPU never sees a zero-length interval.
 */
final class SkinExportParser {

    /** Name of the synthetic identity row for vertices without skin weights. */
    static final String BIND_POSE_BONE = "<bind-pose>";

    /** Frame rate assumed when an animation declares no (usable) {@code fps}. */
    static final float DEFAULT_FPS = 30f;

    /**
     * Two keyframe times closer than this are one instant: the later pose replaces the earlier
     * one ({@link #mergeTimes}) and {@link MeshExportLoader} drops the superseded pose.
     */
    static final float TIME_EPSILON = 1e-6f;

    /** Result of {@link #parse}. */
    record Parsed(MeshSkin skin, List<MeshSkinClip> clips) {}

    private SkinExportParser() {}

    /** Returns {@code null} when the export has no usable bone data. */
    static Parsed parse(JsonValue root, List<Short> sharedRefs, int sharedCount,
                        int expandedCount) {
        JsonValue skinNode = root.get("skin");
        if (skinNode != null) {
            return parseVersion4(skinNode, root, expandedCount);
        }
        return parseLegacy(root.get("bones"), root, sharedRefs, sharedCount, expandedCount);
    }

    // =======================================================================
    // Version 4: explicit skin block + pre-baked matrices
    // =======================================================================

    private static Parsed parseVersion4(JsonValue skin, JsonValue root, int expandedCount) {
        int influences = skin.getInt("influences", 3);
        JsonValue namesNode = skin.get("boneNames");
        if (namesNode == null || namesNode.size == 0) return null;
        int boneCount = namesNode.size;
        String[] boneNames = new String[boneCount];
        for (int i = 0; i < boneCount; i++) {
            boneNames[i] = namesNode.get(i).asString();
        }

        float[] cx = new float[boneCount];
        float[] cy = new float[boneCount];
        float[] dx = new float[boneCount];
        float[] dy = new float[boneCount];
        JsonValue bind = skin.get("bind");
        if (bind != null) {
            for (int i = 0; i < Math.min(boneCount, bind.size); i++) {
                JsonValue b = bind.get(i);
                cx[i] = b.get(0).asFloat();
                cy[i] = b.get(1).asFloat();
                dx[i] = b.get(2).asFloat();
                dy[i] = b.get(3).asFloat();
            }
        }

        JsonValue idsNode = skin.get("boneIds");
        JsonValue weightsNode = skin.get("weights");
        if (idsNode == null || weightsNode == null) return null;
        int expected = expandedCount * influences;
        if (idsNode.size != expected || weightsNode.size != expected) {
            throw new IllegalArgumentException("Skin block declares " + idsNode.size
                + " bone ids / " + weightsNode.size + " weights, expected " + expected + ".");
        }
        int[] ids = new int[expected];
        float[] weights = new float[expected];
        for (int i = 0; i < expected; i++) {
            ids[i] = idsNode.get(i).asInt();
            weights[i] = weightsNode.get(i).asFloat();
        }

        int[] sourceVertex = null;
        JsonValue sourceNode = skin.get("sourceVertex");
        if (sourceNode != null && sourceNode.size == expandedCount) {
            sourceVertex = new int[expandedCount];
            for (int i = 0; i < expandedCount; i++) sourceVertex[i] = sourceNode.get(i).asInt();
        }

        int bindPoseRow = skin.getInt("bindPoseRow", -1);
        MeshSkin meshSkin = new MeshSkin(boneNames, influences, ids, weights, sourceVertex,
            bindPoseRow, cx, cy, dx, dy);
        return new Parsed(meshSkin, parseClips(root, meshSkin, true));
    }

    // =======================================================================
    // Version 2/3: dense bone-major weights + centre/direction frames
    // =======================================================================

    private static Parsed parseLegacy(JsonValue bonesNode, JsonValue root,
                                      List<Short> sharedRefs, int sharedCount,
                                      int expandedCount) {
        if (bonesNode == null || sharedCount <= 0 || sharedRefs.isEmpty()) return null;

        List<String> names = new ArrayList<>();
        List<float[]> accumulated = new ArrayList<>();
        List<float[]> bind = new ArrayList<>();
        for (JsonValue b = bonesNode.child; b != null; b = b.next) {
            JsonValue weightNode = b.get("weights");
            if (weightNode == null || weightNode.size != sharedCount) continue;
            float[] row = new float[sharedCount];
            boolean used = false;
            for (int v = 0; v < sharedCount; v++) {
                float w = weightNode.get(v).asFloat();
                if (w > 0f && Float.isFinite(w)) {
                    row[v] = w;
                    used = true;
                }
            }
            if (!used) continue;
            names.add(b.getString("name", "bone" + names.size()));
            accumulated.add(row);
            JsonValue center = b.get("center");
            JsonValue direction = b.get("direction");
            bind.add(new float[]{
                center != null ? center.getFloat("x", 0f) : 0f,
                center != null ? center.getFloat("y", 0f) : 0f,
                direction != null ? direction.getFloat("x", 0f) : 0f,
                direction != null ? direction.getFloat("y", 0f) : 0f});
        }
        if (names.isEmpty()) return null;

        int boneCount = names.size();
        int influences = 3;
        int[] sharedIds = new int[sharedCount * influences];
        float[] sharedWeights = new float[sharedCount * influences];
        int unweighted = resolveInfluences(accumulated, boneCount, sharedCount,
            sharedIds, sharedWeights);

        String[] boneNames = names.toArray(new String[0]);
        float[] cx = new float[boneCount];
        float[] cy = new float[boneCount];
        float[] dx = new float[boneCount];
        float[] dy = new float[boneCount];
        for (int i = 0; i < boneCount; i++) {
            float[] b = bind.get(i);
            cx[i] = b[0];
            cy[i] = b[1];
            dx[i] = b[2];
            dy[i] = b[3];
        }

        int bindPoseRow = -1;
        if (unweighted > 0) {
            bindPoseRow = boneCount;
            boneNames = java.util.Arrays.copyOf(boneNames, boneCount + 1);
            boneNames[boneCount] = BIND_POSE_BONE;
            cx = java.util.Arrays.copyOf(cx, boneCount + 1);
            cy = java.util.Arrays.copyOf(cy, boneCount + 1);
            dx = java.util.Arrays.copyOf(dx, boneCount + 1);
            dy = java.util.Arrays.copyOf(dy, boneCount + 1);
            dx[boneCount] = 1f;
            for (int v = 0; v < sharedCount; v++) {
                if (sharedWeights[v * influences] > 0f) continue;
                for (int k = 0; k < influences; k++) {
                    sharedIds[v * influences + k] = bindPoseRow;
                    sharedWeights[v * influences + k] = k == 0 ? 1f : 0f;
                }
            }
        }

        int[] ids = new int[expandedCount * influences];
        float[] weights = new float[expandedCount * influences];
        int[] sourceVertex = new int[expandedCount];
        for (int i = 0; i < expandedCount; i++) {
            int s = sharedRefs.get(i);
            sourceVertex[i] = s;
            System.arraycopy(sharedIds, s * influences, ids, i * influences, influences);
            System.arraycopy(sharedWeights, s * influences, weights, i * influences, influences);
        }

        MeshSkin meshSkin = new MeshSkin(boneNames, influences, ids, weights, sourceVertex,
            bindPoseRow, cx, cy, dx, dy);
        return new Parsed(meshSkin, parseClips(root, meshSkin, false));
    }

    /**
     * Keeps the {@code influences} strongest bones per shared vertex and normalizes them.
     *
     * @return number of vertices without any influence (they must be bound to the bind-pose row)
     */
    private static int resolveInfluences(List<float[]> accumulated, int boneCount, int sharedCount,
                                         int[] idsOut, float[] weightsOut) {
        final int influences = 3;
        int unweighted = 0;
        for (int v = 0; v < sharedCount; v++) {
            int i0 = -1, i1 = -1, i2 = -1;
            float w0 = 0f, w1 = 0f, w2 = 0f;
            for (int b = 0; b < boneCount; b++) {
                float w = accumulated.get(b)[v];
                if (!(w > 0f)) continue;
                if (w > w0) {
                    w2 = w1; i2 = i1;
                    w1 = w0; i1 = i0;
                    w0 = w;  i0 = b;
                } else if (w > w1) {
                    w2 = w1; i2 = i1;
                    w1 = w;  i1 = b;
                } else if (w > w2) {
                    w2 = w;  i2 = b;
                }
            }
            int base = v * influences;
            if (w0 <= 0f) {
                unweighted++;
                continue;
            }
            float total = w0 + w1 + w2;
            idsOut[base] = i0;
            idsOut[base + 1] = i1 >= 0 ? i1 : i0;
            idsOut[base + 2] = i2 >= 0 ? i2 : i0;
            weightsOut[base] = w0 / total;
            weightsOut[base + 1] = w1 / total;
            weightsOut[base + 2] = w2 / total;
        }
        return unweighted;
    }

    // =======================================================================
    // Clips: keyframe times + per-frame matrices
    // =======================================================================

    private static List<MeshSkinClip> parseClips(JsonValue root, MeshSkin skin,
                                                 boolean matricesInFile) {
        List<MeshSkinClip> clips = new ArrayList<>();
        JsonValue anims = root.get("animations");
        if (anims == null) return clips;

        for (JsonValue anim = anims.child; anim != null; anim = anim.next) {
            String name = anim.getString("name", "anim" + clips.size());
            boolean looping = anim.getBoolean("looping", false);
            float fps = anim.getFloat("fps", DEFAULT_FPS);
            JsonValue frames = anim.get("frames");
            if (frames == null || frames.size == 0) continue;

            int frameCount = frames.size;
            float[] times = readTimes(anim.get("frameTimes"), frameCount, fps);
            boolean[] rawSteps = readSteps(anim.get("attachmentSteps"), frameCount);
            int matrixFloats = skin.boneCount() * MeshSkinClip.FLOATS_PER_MATRIX;
            List<float[]> perFrame = new ArrayList<>(frameCount);
            for (JsonValue frame = frames.child; frame != null; frame = frame.next) {
                float[] matrices = new float[matrixFloats];
                fillIdentity(matrices, skin.boneCount());
                if (matricesInFile) {
                    JsonValue node = frame.get("boneMatrices");
                    if (node != null) readMatrices(node, skin.boneCount(), matrices);
                } else {
                    JsonValue node = frame.get("bones");
                    if (node != null) legacyBones(node, skin, matrices);
                }
                perFrame.add(matrices);
            }

            MeshSkinClip clip = mergeTimes(name, looping, fps, times, perFrame,
                skin.boneCount(), rawSteps);
            if (clip != null) clips.add(clip);
        }
        return clips;
    }

    /** Reads {@code animations[].attachmentSteps} (one boolean per authored frame). */
    private static boolean[] readSteps(JsonValue node, int frameCount) {
        if (node == null || node.size != frameCount) return null;
        boolean[] steps = new boolean[frameCount];
        boolean any = false;
        for (int i = 0; i < frameCount; i++) {
            steps[i] = node.get(i).asBoolean();
            any |= steps[i];
        }
        return any ? steps : null;
    }

    /** Keyframe times in seconds; missing/invalid times fall back to a uniform fps grid. */
    static float[] readTimes(JsonValue node, int frameCount, float fps) {
        if (node != null && node.size == frameCount) {
            float[] times = new float[frameCount];
            boolean valid = true;
            for (int i = 0; i < frameCount; i++) {
                times[i] = node.get(i).asFloat();
                if (i > 0 && times[i] < times[i - 1] - TIME_EPSILON) valid = false;
            }
            if (valid) return times;
        }
        float step = fps > 0f ? 1f / fps : 1f / DEFAULT_FPS;
        float[] times = new float[frameCount];
        for (int i = 0; i < frameCount; i++) times[i] = i * step;
        return times;
    }

    /** Reads {@code [[bone, m00,m01,m03,m10,m11,m13], ...]} into the dense matrix block. */
    private static void readMatrices(JsonValue node, int boneCount, float[] out) {
        for (JsonValue e = node.child; e != null; e = e.next) {
            if (e.size < 1 + MeshSkinClip.FLOATS_PER_MATRIX) continue;
            int bone = e.get(0).asInt();
            if (bone < 0 || bone >= boneCount) continue;
            int o = bone * MeshSkinClip.FLOATS_PER_MATRIX;
            for (int k = 0; k < MeshSkinClip.FLOATS_PER_MATRIX; k++) {
                out[o + k] = e.get(k + 1).asFloat();
            }
        }
    }

    /**
     * Converts one legacy frame's sparse {@code bones} objects (absolute centre/direction per
     * bone name) into matrices using the bind transforms of {@code skin}.
     */
    private static void legacyBones(JsonValue node, MeshSkin skin, float[] out) {
        for (JsonValue b = node.child; b != null; b = b.next) {
            int row = indexOf(skin.boneNames, b.getString("name", ""));
            if (row < 0) continue;
            JsonValue center = b.get("center");
            JsonValue direction = b.get("direction");
            if (center == null || direction == null) continue;
            int o = row * MeshSkinClip.FLOATS_PER_MATRIX;
            frameMatrix(out, o,
                center.getFloat("x", 0f), center.getFloat("y", 0f),
                direction.getFloat("x", 0f), direction.getFloat("y", 0f),
                skin.bindCenterX[row], skin.bindCenterY[row],
                skin.bindDirX[row], skin.bindDirY[row]);
        }
    }

    private static int indexOf(String[] names, String name) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) return i;
        }
        return -1;
    }

    /** Every bone row starts at the bind pose: {@code m00 = m11 = 1}, everything else 0. */
    private static void fillIdentity(float[] matrices, int boneCount) {
        for (int bone = 0; bone < boneCount; bone++) {
            int o = bone * MeshSkinClip.FLOATS_PER_MATRIX;
            matrices[o] = 1f;
            matrices[o + 4] = 1f;
        }
    }

    /**
     * Drops zero-length intervals: when consecutive frames share a keyframe time, the later pose
     * replaces the earlier one (the editor shows the later pose at that instant).
     */
    private static MeshSkinClip mergeTimes(String name, boolean looping, float fps,
                                           float[] times, List<float[]> perFrame, int boneCount,
                                           boolean[] rawSteps) {
        int stride = boneCount * MeshSkinClip.FLOATS_PER_MATRIX;
        List<Float> keptTimes = new ArrayList<>(perFrame.size());
        List<float[]> kept = new ArrayList<>(perFrame.size());
        List<Boolean> keptSteps = new ArrayList<>(perFrame.size());
        for (int i = 0; i < perFrame.size(); i++) {
            boolean step = rawSteps != null && i < rawSteps.length && rawSteps[i];
            if (!kept.isEmpty() && times[i] <= keptTimes.get(keptTimes.size() - 1) + TIME_EPSILON) {
                kept.set(kept.size() - 1, perFrame.get(i));
                keptTimes.set(keptTimes.size() - 1, times[i]);
                // The superseded pose's switch happens at the same instant.
                keptSteps.set(keptSteps.size() - 1, keptSteps.get(keptSteps.size() - 1) || step);
            } else {
                keptTimes.add(times[i]);
                kept.add(perFrame.get(i));
                keptSteps.add(step);
            }
        }
        if (kept.isEmpty()) return null;

        float[] frameTimes = new float[kept.size()];
        float[] matrices = new float[kept.size() * stride];
        boolean[] stepFrames = new boolean[kept.size()];
        boolean anySteps = false;
        for (int i = 0; i < kept.size(); i++) {
            frameTimes[i] = keptTimes.get(i);
            System.arraycopy(kept.get(i), 0, matrices, i * stride, stride);
            stepFrames[i] = keptSteps.get(i);
            anySteps |= stepFrames[i];
        }
        return new MeshSkinClip(name, looping, fps, boneCount, frameTimes, matrices,
            anySteps ? stepFrames : null);
    }

    // =======================================================================
    // Matrix maths (export space)
    // =======================================================================

    /**
     * Writes {@code M = T(cx, cy) · R(Δangle) · S(Δlength) · T(-rest)} — exactly the editor's
     * per-bone deformation, stored as {@code m00, m01, m03, m10, m11, m13}.
     */
    private static void frameMatrix(float[] out, int o,
                                    float cx, float cy, float dx, float dy,
                                    float restX, float restY, float restDx, float restDy) {
        double angle = Math.atan2(dy, dx) - Math.atan2(restDy, restDx);
        double restLength = Math.hypot(restDx, restDy);
        double poseLength = Math.hypot(dx, dy);
        float scale = restLength > 1e-6 ? (float) (poseLength / restLength) : 1f;
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float m00 = scale * cos;
        float m01 = -scale * sin;
        float m10 = scale * sin;
        float m11 = scale * cos;
        out[o]     = m00;
        out[o + 1] = m01;
        out[o + 2] = cx - (m00 * restX + m01 * restY);
        out[o + 3] = m10;
        out[o + 4] = m11;
        out[o + 5] = cy - (m10 * restX + m11 * restY);
    }
}
