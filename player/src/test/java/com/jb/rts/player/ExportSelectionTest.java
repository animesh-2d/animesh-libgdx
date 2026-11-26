package com.jb.rts.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ExportSelection}: the mesh player's export list. The list's <b>order is the
 * meaning</b> of the nav bar's selector index (and of {@code MeshPlayer.currentIndex}), and a file is
 * identified by its <b>name</b> — so both are protected here.
 */
class ExportSelectionTest {

    private static final String[] BUNDLED = {
        "meshes/fire-export.json",
        "meshes/dino_longneck-export.json",
    };

    /** Records what was opened, so the selector callback can be asserted. */
    private static final class Recorder implements ExportSelection.Opener {
        String opened;

        @Override
        public void open(String path) {
            opened = path;
        }
    }

    @Test
    void bundledExamplesAreAppendedInOrderAndTheListDefinesTheIndex() {
        Recorder recorder = new Recorder();
        ExportSelection selection = new ExportSelection(BUNDLED, recorder);

        selection.addBundledExamples();

        assertEquals(2, selection.size());
        assertEquals("meshes/fire-export.json", selection.pathAt(0));
        assertEquals("meshes/dino_longneck-export.json", selection.pathAt(1));
        assertEquals("fire-export.json", selection.labels().get(0), "labels are short names");
    }

    @Test
    void aFileOnDiskWinsOverTheBundledExampleOfTheSameName() {
        Recorder recorder = new Recorder();
        ExportSelection selection = new ExportSelection(BUNDLED, recorder);

        // The startup scan adds the checkout's copy first ...
        selection.add("C:/repo/player/assets/meshes/fire-export.json");
        // ... so the bundled entry of that name is skipped.
        selection.addBundledExamples();

        assertEquals(2, selection.size());
        assertEquals("C:/repo/player/assets/meshes/fire-export.json", selection.pathAt(0),
            "the disk copy keeps its position and path");
        assertTrue(selection.isListed("meshes/fire-export.json"),
            "the bundled name counts as listed");
    }

    @Test
    void addingTwiceKeepsOneEntryAndBlankPathsAreIgnored() {
        ExportSelection selection = new ExportSelection(BUNDLED, new Recorder());

        selection.add("a/one.json");
        selection.add("b/one.json"); // same file name
        selection.add("");
        selection.add(null);

        assertEquals(1, selection.size());
        assertEquals("a/one.json", selection.pathAt(0));
    }

    @Test
    void openAtSkipsTheExportAlreadyOnScreenAndOutOfRangeIndexes() {
        Recorder recorder = new Recorder();
        ExportSelection selection = new ExportSelection(BUNDLED, recorder);
        selection.addBundledExamples();

        selection.openAt(0, 0);
        assertNull(recorder.opened, "the export on screen is not reopened");

        selection.openAt(1, 0);
        assertEquals("meshes/dino_longneck-export.json", recorder.opened);

        recorder.opened = null;
        selection.openAt(9, 0);
        assertNull(recorder.opened, "an out-of-range index opens nothing");
    }

    @Test
    void indexOfMatchesByName() {
        ExportSelection selection = new ExportSelection(BUNDLED, new Recorder());
        selection.addBundledExamples();

        assertEquals(1, selection.indexOf("other/dir/dino_longneck-export.json"));
        assertEquals(-1, selection.indexOf("meshes/unknown.json"));
        assertNull(selection.pathAt(5));
        assertFalse(selection.isListed("meshes/unknown.json"));
    }

    @Test
    void normalizedIsACopySoAReloadCannotDisturbTheList() {
        ExportSelection selection = new ExportSelection(BUNDLED, new Recorder());
        selection.addBundledExamples();

        com.badlogic.gdx.utils.Array<String> copy = selection.normalized();
        copy.clear();

        assertEquals(2, selection.size(), "the original list is untouched");
    }
}
