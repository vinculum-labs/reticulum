package io.github.leadassimilator.reticulum.network;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.graph.Node;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import io.github.leadassimilator.reticulum.graph.Sheet;

/**
 * How big a network's records may grow. Far beyond any real base, but small enough that one player can't bloat the
 * save, stall the server compiling sheets, or make the packets that carry names fail for everyone who sees them.
 * Names are cut to length where they're stored; the counts are checked before an edit is applied, so a refused edit
 * changes nothing.
 */
public final class Limits {
    /** Network, sheet, device, face, connector, node and pin names. */
    public static final int NAME = 64;
    public static final int SHEETS = 256;
    public static final int NODES_PER_SHEET = 1024;
    public static final int WIRES_PER_SHEET = 4096;
    public static final int FILTER_TEMPLATES = 256;
    public static final int IDENTIFIES = 256;
    public static final int MEMBERS = 256;
    public static final int KIT_LINES = 64;
    public static final int KITS_PER_OPERATION = 64;
    /** The largest weight of one splitter output. */
    public static final int SPLITTER_WEIGHT = 10_000;

    private Limits() {
    }

    /** A name as stored: trimmed, cut to {@link #NAME}, and null when blank. */
    @Nullable
    public static String name(@Nullable String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.strip();
        if (trimmed.length() > NAME) {
            trimmed = trimmed.substring(0, NAME).strip();
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Why a node can't be stored, or null when it can. */
    @Nullable
    public static String check(Node node) {
        if (node.label().isPresent() && node.label().get().length() > NAME) {
            return "A node's label can be at most " + NAME + " characters";
        }
        return switch (node.settings()) {
            case NodeSettings.SheetPinSettings s when s.name().length() > NAME -> "A pin name can be at most " + NAME + " characters";
            case NodeSettings.KitSettings s when s.lines().size() > KIT_LINES -> "A kit can have at most " + KIT_LINES + " lines";
            case NodeSettings.KitSettings s when s.maxKits() > KITS_PER_OPERATION -> "A kit can move at most " + KITS_PER_OPERATION + " kits at a time";
            case NodeSettings.SplitterSettings s when s.weights().stream().anyMatch(w -> w < 0 || w > SPLITTER_WEIGHT) ->
                    "A splitter output's weight must be 0 to " + SPLITTER_WEIGHT;
            default -> null;
        };
    }

    /** Why a whole sheet (an imported one) can't be stored, or null when it can. */
    @Nullable
    public static String check(Sheet sheet) {
        if (sheet.nodes().size() > NODES_PER_SHEET) {
            return "A sheet can have at most " + NODES_PER_SHEET + " nodes";
        }
        if (sheet.wires().size() > WIRES_PER_SHEET) {
            return "A sheet can have at most " + WIRES_PER_SHEET + " wires";
        }
        for (Node node : sheet.nodes()) {
            String problem = check(node);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }
}
