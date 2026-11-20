package com.jb.rts.player;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Disposable;
import com.jb.rts.mesh.vat.VatAnimationBlender;
import com.jb.rts.mesh.vat.VatAnimationData;
import com.jb.rts.mesh.vat.VatInstancedMesh;

/**
 * The <b>animation blend preview</b> of the mesh player: one extra VAT batch that plays the mix
 * of the current animation and a chosen target clip (e.g. <i>walk</i> → <i>run</i>).
 *
 * <p>The batch is built lazily, once per clip pair. While only the blend <b>weight</b> changes
 * (the slider is dragged), it is re-uploaded through
 * {@link VatInstancedMesh#updateAnimation(VatAnimationData)} — a few kilobytes of bone matrices
 * instead of a shader compile and a full VBO upload. A different pair rebuilds the batch.
 * {@link #select} runs once per rendered frame, so a whole slider drag costs at most one blend
 * and one texture upload per frame.</p>
 *
 * <p>Blending needs the two clips to share their mesh (identical expanded vertex count) and their
 * bone table size; otherwise {@link #error()} explains why the preview fell back to the plain
 * clip. All GL work happens on the render thread, in the caller's frame.</p>
 *
 * <p>The blended batch <b>does not own</b> the export texture — it is handed to it by the screen
 * and lives as long as the export.</p>
 */
public final class AnimationBlend implements Disposable {

    /** The blended batch, or {@code null} while no pair is selected / the pair cannot blend. */
    private VatInstancedMesh group;
    private VatInstancedMesh from;
    private VatInstancedMesh to;
    private float weight;
    private String error;
    /** True after a failed build for this pair, so the same invalid request is not retried. */
    private boolean failed;

    /**
     * Ensures the preview batch matches the requested pair and weight.
     *
     * <p>Call it every frame with the current animation's batch and the selected target; it is a
     * cheap no-op when nothing changed. An invalid request clears the preview (and reports the
     * reason through {@link #error()}) instead of throwing, so a broken pair never stops the
     * player.</p>
     *
     * @param fromClip the batch of the animation on screen
     * @param toClip   the selected blend target, or {@code null} for "blend off"
     * @param weight   blend weight in {@code 0..1} ({@code 0} = from, {@code 1} = to)
     * @return the blended batch to draw, or {@code null} to draw {@code fromClip} unchanged
     */
    public VatInstancedMesh select(VatInstancedMesh fromClip, VatInstancedMesh toClip,
                                   float weight) {
        float w = MathUtils.clamp(weight, 0f, 1f);
        if (fromClip == null || toClip == null || fromClip == toClip) {
            clear();
            return null;
        }
        if (from == fromClip && to == toClip && failed) {
            return null;
        }
        if (group != null && from == fromClip && to == toClip) {
            if (w != this.weight) {
                this.weight = w;
                updateAnimation();
            }
            return error == null ? group : null;
        }
        disposeGroup();
        this.from = fromClip;
        this.to = toClip;
        this.weight = w;
        this.error = null;
        this.failed = false;
        if (fromClip.meshData().vertexCount() != toClip.meshData().vertexCount()) {
            error = "The clips have different geometry ("
                + fromClip.meshData().vertexCount() + " / "
                + toClip.meshData().vertexCount() + " vertices).";
            failed = true;
            return null;
        }
        try {
            VatAnimationData blended = VatAnimationBlender.blend(
                fromClip.animation(), toClip.animation(), w);
            group = new VatInstancedMesh(fromClip.meshData(), blended,
                Math.max(1, fromClip.maxInstances()), fromClip.texture());
            if (Gdx.app != null) {
                Gdx.app.log("AnimationBlend", "Blending '" + fromClip.animation().name() + "' -> '"
                    + toClip.animation().name() + "' at " + Math.round(w * 100f) + "% ("
                    + blended.frameCount() + " frames).");
            }
        } catch (RuntimeException e) {
            error = e.getMessage() != null ? e.getMessage() : e.toString();
            failed = true;
            disposeGroup();
            if (Gdx.app != null) {
                Gdx.app.error("AnimationBlend", "Cannot blend: " + error, e);
            }
            return null;
        }
        return group;
    }

    /** Re-bakes the current pair at the new weight and swaps the texture content in place. */
    private void updateAnimation() {
        try {
            VatAnimationData blended = VatAnimationBlender.blend(
                from.animation(), to.animation(), weight);
            group.updateAnimation(blended);
            error = null;
        } catch (RuntimeException e) {
            error = e.getMessage() != null ? e.getMessage() : e.toString();
            failed = true;
            disposeGroup();
            if (Gdx.app != null) {
                Gdx.app.error("AnimationBlend", "Cannot update the blend: " + error, e);
            }
        }
    }

    /** The blended batch to draw, or {@code null} when the plain clip is used. */
    public VatInstancedMesh group() {
        return error == null ? group : null;
    }

    /** Why the last request could not be blended, or {@code null} while everything is fine. */
    public String error() {
        return error;
    }

    /** Drops the preview (e.g. on Prev/Next another pair is required). */
    public void clear() {
        disposeGroup();
        from = null;
        to = null;
        weight = 0f;
        error = null;
        failed = false;
    }

    private void disposeGroup() {
        if (group != null) {
            group.dispose();
            group = null;
        }
    }

    /** Frees the blended batch; the export texture and the source batches stay owned elsewhere. */
    @Override
    public void dispose() {
        clear();
    }
}
