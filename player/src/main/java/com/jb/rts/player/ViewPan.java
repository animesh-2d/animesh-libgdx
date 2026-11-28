package com.jb.rts.player;

/**
 * The world view's <b>pan offset</b> plus the <b>drag-to-pan gesture</b> that moves it — GL-free
 * and test-covered, so a screen only has to forward touches and read the offset.
 *
 * <p>The offset is in <b>world units</b> and is added to the point the camera looks at. Keyboard
 * panning ({@link #panBy}) and the drag gesture share that one state, so both move the same view;
 * the screen has to re-fit its camera after every change. {@link #reset()} drops the offset — a
 * newly opened export is centred on its own bounds again.</p>
 *
 * <p><b>The content follows the cursor.</b> Dragging right moves the scene right (the camera moves
 * left) and dragging down moves it down; screen y grows downwards while world y grows upwards, so
 * the y offset is added where the x offset is subtracted. Pixels are converted with the ratio the
 * caller passes in ({@code world units per pixel}, i.e. {@code camera.viewportWidth / windowWidth}),
 * which already contains the zoom — the grabbed point therefore stays under the cursor at every
 * zoom level.</p>
 *
 * <p>One pointer owns the gesture ({@link #begin}); a new press takes over, so a release that was
 * lost (window focus change, a finger lifted outside the window) can never block panning for good.
 * {@link #stop()} aborts a drag without touching the offset.</p>
 */
public final class ViewPan {

    /** Pan offset in world units, added to the camera's target position. */
    private float offsetX, offsetY;

    /** Pointer that owns the drag, or {@code -1} when no drag is running. */
    private int pointer = -1;

    /** Last screen position of {@link #pointer}, in window pixels. */
    private float lastX, lastY;

    /** Pan offset applied so far, x in world units. */
    public float offsetX() {
        return offsetX;
    }

    /** Pan offset applied so far, y in world units. */
    public float offsetY() {
        return offsetY;
    }

    /** True while a press started a drag that has not been released yet. */
    public boolean isDragging() {
        return pointer >= 0;
    }

    /**
     * Starts the drag gesture at a press position (or takes over a drag whose release was lost).
     *
     * @param pointer touch pointer index ({@code 0} is the mouse)
     * @param screenX press position, window pixels from the left
     * @param screenY press position, window pixels from the top (libGDX's y-down screen space)
     */
    public void begin(int pointer, float screenX, float screenY) {
        this.pointer = pointer;
        lastX = screenX;
        lastY = screenY;
    }

    /**
     * Moves the view by the pixels travelled since the previous call.
     *
     * @param pointer        touch pointer index of the move
     * @param screenX        cursor position, window pixels from the left
     * @param screenY        cursor position, window pixels from the top
     * @param worldPerPixelX world units per horizontal pixel (viewport width / window width)
     * @param worldPerPixelY world units per vertical pixel (viewport height / window height)
     * @return {@code true} when {@code pointer} owns the drag; {@code false} for any other pointer
     *         (the offsets are left untouched then)
     */
    public boolean drag(int pointer, float screenX, float screenY,
                        float worldPerPixelX, float worldPerPixelY) {
        if (pointer != this.pointer) return false;
        float dx = screenX - lastX;
        float dy = screenY - lastY;
        lastX = screenX;
        lastY = screenY;
        offsetX -= dx * worldPerPixelX;
        offsetY += dy * worldPerPixelY;
        return true;
    }

    /**
     * Ends the drag of {@code pointer} (its release).
     *
     * @return {@code true} when that pointer owned the drag — the screen consumes the event then
     */
    public boolean end(int pointer) {
        if (pointer != this.pointer) return false;
        stop();
        return true;
    }

    /** Ends the running drag (if any) and keeps the offset, e.g. when the pointer vanished. */
    public void stop() {
        pointer = -1;
    }

    /**
     * Pans by a world-space delta — the keyboard path ({@code A}/{@code D}, {@code W}/{@code S},
     * arrow keys), so it needs no pixel conversion.
     *
     * @param dxWorld offset added to x, world units (positive pans the view right)
     * @param dyWorld offset added to y, world units (positive pans the view up)
     */
    public void panBy(float dxWorld, float dyWorld) {
        offsetX += dxWorld;
        offsetY += dyWorld;
    }

    /** Drops the offset and the drag: the view is centred on the loaded export again. */
    public void reset() {
        offsetX = 0f;
        offsetY = 0f;
        stop();
    }
}
