package com.jb.rts.player;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Array;

import java.util.function.Consumer;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * The mesh player's <b>export list</b>: the known files (bundled examples plus everything opened
 * with <b>Load…</b> or drag &amp; drop), the nav bar's selector labels, and the system file chooser.
 *
 * <p>Split out of the screen so the list logic can be unit-tested and the screen only wires it: the
 * order of the list <em>is</em> the meaning of the selector's index and of
 * {@code MeshPlayer.currentIndex}, so keeping it in one small class makes that contract explicit.</p>
 *
 * <p>A file is identified by its <b>file name</b> ({@link MeshFileResolver#shortName(String)}), not
 * its path: the bundled examples live inside the jar, so the same name can refer to a classpath
 * entry and to a file on disk — the earlier entry wins, which keeps a checkout's own copy.</p>
 */
public final class ExportSelection {

    /** Opens the export at a list index (the screen reloads it). */
    public interface Opener {
        /**
         * Plays the export at {@code path}.
         *
         * @param path the entry that was chosen
         */
        void open(String path);
    }

    private final Array<String> paths = new Array<>();
    private final String[] bundledExamples;
    private final Opener opener;

    /**
     * @param bundledExamples the examples that ship inside the jar, in display order
     * @param opener          called with the chosen path (on the render thread)
     */
    public ExportSelection(String[] bundledExamples, Opener opener) {
        if (opener == null) throw new IllegalArgumentException("opener cannot be null.");
        this.bundledExamples = bundledExamples != null ? bundledExamples.clone() : new String[0];
        this.opener = opener;
    }

    /** The known exports, in selector order (treat as read-only). */
    public Array<String> paths() {
        return paths;
    }

    /** Number of known exports. */
    public int size() {
        return paths.size;
    }

    /**
     * The path at {@code index}.
     *
     * @param index position in the list
     * @return the path, or {@code null} when out of range
     */
    public String pathAt(int index) {
        return index >= 0 && index < paths.size ? paths.get(index) : null;
    }

    /** Appends {@code path} unless an export with the same file name is already known. */
    public void add(String path) {
        if (path == null || path.isBlank()) return;
        if (!isListed(path)) paths.add(path);
    }

    /** A defensive copy of the list, so iterating it can never be disturbed by a reload. */
    public Array<String> normalized() {
        Array<String> out = new Array<>();
        for (String s : paths) out.add(s);
        return out;
    }

    /** True when an export with the same file name is already listed. */
    public boolean isListed(String path) {
        String name = MeshFileResolver.shortName(path);
        for (String known : paths) {
            if (MeshFileResolver.shortName(known).equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /**
     * Index of the entry that refers to {@code path} (compared by file name, like
     * {@link #isListed(String)}).
     *
     * @param path the path to look for
     * @return its index, or {@code -1}
     */
    public int indexOf(String path) {
        String name = MeshFileResolver.shortName(path);
        for (int i = 0; i < paths.size; i++) {
            if (MeshFileResolver.shortName(paths.get(i)).equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    /**
     * Appends the bundled examples, skipping any whose file name is already listed: a checkout that
     * has the same export in a scanned {@code meshes} directory keeps the disk copy, which was added
     * first.
     */
    public void addBundledExamples() {
        for (String bundled : bundledExamples) add(bundled);
    }

    /**
     * Selector labels: one short file name per known export.
     *
     * @return a new array of labels, same order as {@link #paths()}
     */
    public Array<String> labels() {
        Array<String> out = new Array<>(paths.size);
        for (String path : paths) out.add(MeshFileResolver.shortName(path));
        return out;
    }

    /**
     * Calls {@link Opener#open(String)} for the entry at {@code index}, unless it is out of range or
     * already the one on screen.
     *
     * @param index   position in the list
     * @param current index of the export currently displayed
     */
    public void openAt(int index, int current) {
        if (index < 0 || index >= paths.size || index == current) return;
        opener.open(paths.get(index));
    }

    /**
     * Opens the system file chooser and hands the picked path back on the render thread.
     *
     * <p>LWJGL3 has no native file dialog, so Swing's {@link JFileChooser} is used. It must <b>not</b>
     * run on the render thread (that would freeze the window while the dialog is open), so it is
     * opened on a daemon thread and the load is handed back with {@code Gdx.app.postRunnable}. The
     * jpackage app-image bundles {@code java.desktop} for this, see {@code player/build.gradle}.</p>
     *
     * @param onRenderThread receives the chosen absolute path on the render thread
     */
    public static void chooseFile(Consumer<String> onRenderThread) {
        if (onRenderThread == null) return;
        Thread chooser = new Thread(() -> {
            try {
                JFileChooser dialog = new JFileChooser();
                dialog.setDialogTitle("Open mesh export");
                dialog.setFileFilter(new FileNameExtensionFilter(
                        "Mesh export (*.json, *.vat)", "json", "vat"));
                java.io.File dir = MeshFileResolver.resolveMeshesDir();
                if (dir != null) dialog.setCurrentDirectory(dir);
                if (dialog.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return;
                java.io.File chosen = dialog.getSelectedFile();
                if (chosen == null) return;
                String path = chosen.getAbsolutePath();
                Gdx.app.postRunnable(() -> onRenderThread.accept(path));
            } catch (RuntimeException e) {
                Gdx.app.error("ExportSelection", "File chooser failed: " + e.getMessage(), e);
            }
        }, "animesh-export-chooser");
        chooser.setDaemon(true);
        chooser.start();
    }
}
