package io.github.leadassimilator.telispex.client;

import com.google.gson.JsonObject;

/** Hears an app's pages on the game client. Called on the web server's thread, not the game's. */
public interface PageListener {
    /**
     * A request from a page ({@code {"id": ..., "op": ..., ...}}). True when it was answered here; false sends it on to
     * the server, as it is.
     */
    default boolean onRequest(Page page, String op, JsonObject request) {
        return false;
    }

    /** A page closed (or lost its connection). */
    default void onClose(Page page) {
    }
}
