package com.jb.rts.player;

import com.jb.rts.mesh.vat.VatAnimationData;
import com.jb.rts.mesh.vat.VatInstancedMesh;

/**
 * One playable animation of a loaded file: its name, frame count and the VAT batch that draws it.
 *
 * <p>Produced by {@link MeshClipLoader} and consumed by the mesh player, which draws
 * {@link #group} (or a {@link AnimationBlend} of two clips' groups) as one instanced draw call. The
 * batch is owned by the screen: whoever built the list must {@code dispose()} every
 * {@link #group} when another file is loaded.</p>
 */
public final class PlayerClip {

    /** The animation name as the export wrote it. */
    public final String name;

    /** Number of frames in the clip. */
    public final int frameCount;

    /** The instanced batch that draws the clip, or {@code null} when the bake failed. */
    public final VatInstancedMesh group;

    /** Why the batch could not be built, or {@code null} when it was. */
    public final String error;

    /**
     * @param name       the animation name
     * @param frameCount number of frames
     * @param group      the instanced batch, or {@code null} on failure
     * @param error      the failure message, or {@code null} on success
     */
    public PlayerClip(String name, int frameCount, VatInstancedMesh group, String error) {
        this.name = name != null ? name : "";
        this.frameCount = frameCount;
        this.group = group;
        this.error = error;
    }

    /** True when the clip has a batch that can be drawn. */
    public boolean isPlayable() {
        return group != null;
    }

    /** The baked animation of the batch, or {@code null} when the bake failed. */
    public VatAnimationData animation() {
        return group != null ? group.animation() : null;
    }

    @Override
    public String toString() {
        return name + " (" + frameCount + " frames" + (error != null ? ", failed: " + error : "")
            + ")";
    }
}
