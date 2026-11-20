package com.jb.rts.player.desktop;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration.GLEmulation;
import com.jb.rts.player.VatDemoApplication;

/**
 * Launches {@link VatDemoApplication}: 100k animated instances rendered with a single
 * {@code glDrawArraysInstanced} call (VAT + hardware instancing).
 *
 * <p>Run with {@code ./gradlew :player:runVatDemo};
 * add {@code -Pvat.autoexit=5} for an unattended smoke test.
 */
public class VatDemoLauncher {

    public static void main(String[] args) {
        boolean benchmark = Boolean.getBoolean("vat.benchmark");

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("VAT instancing - 100k objects, 1 draw call");
        config.setWindowedMode(1280, 720);
        // glVertexAttribDivisor + RGBA32F + texelFetch need a GL 3.x core context.
        config.setOpenGLEmulation(GLEmulation.GL30, 3, 3);
        config.useVsync(!benchmark);
        config.setForegroundFPS(benchmark ? 0 : 60);
        new Lwjgl3Application(new VatDemoApplication(), config);
    }
}