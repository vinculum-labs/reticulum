package io.github.leadassimilator.reticulum.network;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.graph.Redstone;

/**
 * What switches a network's parts on and off: the network's button and redstone condition, folders switched off,
 * and folder redstone conditions. Sheets and nodes carry their own.
 */
public final class Switches {
    public static final Codec<Switches> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.optionalFieldOf("enabled", true).forGetter(s -> s.enabled),
            Redstone.CODEC.optionalFieldOf("redstone", Redstone.IGNORED).forGetter(s -> s.redstone),
            Codec.STRING.listOf().optionalFieldOf("disabled_folders", List.of()).forGetter(s -> List.copyOf(s.disabledFolders)),
            Codec.unboundedMap(Codec.STRING, Redstone.CODEC).optionalFieldOf("folder_redstone", Map.of()).forGetter(s -> s.folderRedstone)
    ).apply(i, Switches::new));

    private boolean enabled = true;
    private Redstone redstone = Redstone.IGNORED;
    private final Set<String> disabledFolders = new LinkedHashSet<>();
    private final Map<String, Redstone> folderRedstone = new LinkedHashMap<>();

    public Switches() {
    }

    private Switches(boolean enabled, Redstone redstone, List<String> disabledFolders, Map<String, Redstone> folderRedstone) {
        this.enabled = enabled;
        this.redstone = redstone;
        this.disabledFolders.addAll(disabledFolders);
        this.folderRedstone.putAll(folderRedstone);
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Redstone redstone() {
        return redstone;
    }

    public void setRedstone(Redstone redstone) {
        this.redstone = redstone;
    }

    public Set<String> disabledFolders() {
        return disabledFolders;
    }

    /** Redstone conditions of folders, by folder path; folders without one are left out. */
    public Map<String, Redstone> folderRedstone() {
        return folderRedstone;
    }
}
