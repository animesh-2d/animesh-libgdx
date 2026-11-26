package com.jb.rts.player;

import com.jb.rts.mesh.vat.VatAnimationData;

import java.util.Locale;

/**
 * The <b>playback rule</b> of one animation clip, inferred from its name and its baked
 * {@link VatAnimationData}. The FSM ({@link PlayerAnimationFsm}) uses it to decide which
 * transitions are allowed and where a finished clip returns to.
 *
 * <p>The player loads <em>arbitrary</em> exports at runtime, so nothing can be hard-wired: the
 * rules below are a convention over the animation names the mesheditor produces
 * ({@code idle}, {@code walk}, {@code run}, {@code defend}, {@code rest}, {@code attack},
 * {@code jump}, {@code hurt}, {@code die}, …). An unknown name is treated as
 * {@link #LOOPING} when the clip declares itself looping and as {@link #ONE_SHOT} otherwise,
 * so a new animation behaves sensibly without touching this class.</p>
 *
 * <table summary="rule table">
 *   <tr><th>rule</th><th>behaviour</th></tr>
 *   <tr><td>{@link #LOOPING}</td><td>plays forever; may be interrupted by any other state</td></tr>
 *   <tr><td>{@link #ONE_SHOT}</td><td>plays once, blocks locomotion until it ends, then returns to
 *       {@code idle} (or the locomotion state it came from)</td></tr>
 *   <tr><td>{@link #TERMINAL}</td><td>plays once and holds the last frame; blocks every outgoing
 *       transition unless the user forces a blend</td></tr>
 * </table>
 */
public enum AnimationRule {

    /** Loops forever and can be left at any time ({@code idle}, {@code walk}, {@code run}, …). */
    LOOPING("Looping"),

    /** Plays once, locks locomotion while it runs, then falls back to idle. */
    ONE_SHOT("One-Shot \u2192 Idle"),

    /** Plays once, holds the final frame and locks every exit ({@code die}). */
    TERMINAL("Terminal");

    /**
     * Lower-case animation names (without digits/separators) that always loop, even when the clip
     * forgot to declare {@code looping}. Deliberately a small, documented set.
     */
    private static final String[] LOOPING_NAMES = {
        "idle", "walk", "run", "sprint", "defend", "block", "guard", "rest", "fly", "swim",
        "hover", "loop", "breathe", "crawl",
    };

    /**
     * Lower-case animation names that <b>never</b> loop: they play once, lock the locomotion layer
     * and then hand control back to idle (or the interrupted state). Listed explicitly so the
     * convention wins over a {@code "looping": true} flag some legacy bakes carry on every clip.
     */
    private static final String[] ACTION_NAMES = {
        "attack", "hit", "hurt", "damage", "shoot", "throw", "punch", "kick", "jump", "jumping",
        "leap", "dodge", "roll", "cast", "spell", "skill", "emote", "wave", "taunt",
    };

    /**
     * Lower-case animation names that play once and then hold the last frame forever.
     */
    private static final String[] TERMINAL_NAMES = {
        "die", "death", "dead", "dying", "corpse", "collapse",
    };

    private final String label;

    AnimationRule(String label) {
        this.label = label;
    }

    /** Human-readable rule name for the graph tooltip and the panel ({@code "One-Shot → Idle"}). */
    public String label() {
        return label;
    }

    /** {@code true} for {@link #LOOPING} — the clip never finishes. */
    public boolean loops() {
        return this == LOOPING;
    }

    /** {@code true} for {@link #ONE_SHOT} — the clip blocks locomotion while it plays. */
    public boolean isAction() {
        return this == ONE_SHOT;
    }

    /** {@code true} for {@link #TERMINAL} — the clip never yields control back. */
    public boolean isTerminal() {
        return this == TERMINAL;
    }

    /**
     * Infers the rule of an animation from its name and its baked clip.
     *
     * <p>The <b>name wins over the data</b>: the mesheditor exports a one-shot {@code die} clip
     * whose last keyframe is a long hold, and some legacy pose bakes mark every clip looping. The
     * declared {@code looping} flag is therefore only consulted for names this class does not
     * know, and a one-frame clip is always {@link #ONE_SHOT} (there is nothing to loop).</p>
     *
     * @param animationName the clip's name as the export wrote it (may be {@code null})
     * @param animation     the baked clip, or {@code null} when the animation failed to bake
     * @return the inferred rule, never {@code null}
     */
    public static AnimationRule infer(String animationName, VatAnimationData animation) {
        String key = normalize(animationName);
        for (String terminal : TERMINAL_NAMES) {
            if (key.equals(terminal)) return TERMINAL;
        }
        for (String action : ACTION_NAMES) {
            if (key.equals(action)) return ONE_SHOT;
        }
        // A single frame cannot loop: looping it looks identical. Checked before the looping names
        // so that a one-pose "rest" is still an action (it then finishes immediately).
        if (animation != null && animation.frameCount() <= 1) return ONE_SHOT;
        for (String looping : LOOPING_NAMES) {
            if (key.equals(looping)) return LOOPING;
        }
        if (animation != null && animation.looping()) {
            // An explicit "looping": true is authoritative for names this class does not know.
            return LOOPING;
        }
        return ONE_SHOT;
    }

    /**
     * Normalises an animation name for matching: lower case, and only the <b>first word</b> is kept
     * — so {@code "Walk_01"}, {@code "walk"}, {@code "WALK "} and {@code "Die (hit)"} all match.
     *
     * <p>The first word is what the exporter puts in front of a variant marker; anything after the
     * first separator is decoration ({@code "die (hit)"}, {@code "walk 2"}) and must not be glued to
     * the key, or {@code "die (hit)"} would become {@code "diehit"} and match nothing.</p>
     *
     * @param name the raw name (may be {@code null})
     * @return the key, possibly empty
     */
    static String normalize(String name) {
        if (name == null) return "";
        StringBuilder key = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean wordChar = Character.isLetter(c) || Character.isDigit(c);
            if (wordChar) {
                key.append(Character.toLowerCase(c));
            } else if (key.length() > 0) {
                // A separator after the first word ends the key.
                break;
            }
        }
        return key.toString();
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT) + " (" + label + ")";
    }
}
