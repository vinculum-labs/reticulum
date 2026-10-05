package io.github.leadassimilator.reticulum.network;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.Reticulum;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** All networks on the server, stored once in the overworld's data folder. */
public final class ReticulumSavedData extends SavedData {
    public static final int SCHEMA_VERSION = 1;
    private static final String FILE_NAME = Reticulum.MODID + "_networks";

    private record Contents(int schemaVersion, List<Network> networks, Map<UUID, UUID> aliases) {
        static final Codec<Contents> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("schema_version").forGetter(Contents::schemaVersion),
                Network.CODEC.listOf().fieldOf("networks").forGetter(Contents::networks),
                Codec.unboundedMap(UUIDUtil.STRING_CODEC, Ids.UUID_CODEC).optionalFieldOf("aliases", Map.of()).forGetter(Contents::aliases)
        ).apply(i, Contents::new));
    }

    private final Map<UUID, Network> networks = new LinkedHashMap<>();
    /** Why the saved networks couldn't all be read, or null: the file is copied aside before anything overwrites it. */
    @Nullable
    private String loadError;
    /** The copy kept of a file that couldn't be read, told to operators as they join. */
    @Nullable
    private String keptAs;
    /** Networks merged into others: old id to the id that absorbed it. Blocks still carrying an old id follow it. */
    private final Map<UUID, UUID> aliases = new LinkedHashMap<>();

    public static ReticulumSavedData get(MinecraftServer server) {
        ReticulumSavedData data = server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ReticulumSavedData::new, ReticulumSavedData::load), FILE_NAME);
        if (data.loadError != null) {
            data.keepUnreadable(server);
        }
        return data;
    }

    /**
     * The file couldn't be read (in whole or in part): it is copied aside, never to be overwritten, before the next save
     * replaces it with what did load. A fixed version of the mod can read the copy (renamed back), or it can be mended
     * by hand. Done once, on first use, so before any save.
     */
    private void keepUnreadable(MinecraftServer server) {
        String error = loadError;
        loadError = null;
        java.nio.file.Path file = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").resolve(FILE_NAME + ".dat");
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        java.nio.file.Path copy = file.resolveSibling(FILE_NAME + ".dat.unreadable-" + stamp);
        try {
            java.nio.file.Files.copy(file, copy);
            keptAs = copy.getFileName().toString();
            Reticulum.LOGGER.error("Reticulum couldn't read all of its saved networks ({}). The file was copied to {} and "
                    + "the networks that couldn't be read start empty.", error, copy);
        } catch (java.io.IOException e) {
            keptAs = null;
            Reticulum.LOGGER.error("Reticulum couldn't read all of its saved networks ({}), nor copy the file aside", error, e);
        }
    }

    /** Operators joining are told when the saved networks couldn't be read, and where the copy is. */
    public static void onLoggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) || !player.hasPermissions(2)) {
            return;
        }
        ReticulumSavedData data = get(player.server);
        if (data.keptAs != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("message.reticulum.save_unreadable", data.keptAs)
                    .withStyle(net.minecraft.ChatFormatting.RED));
        }
    }

    private static ReticulumSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ReticulumSavedData data = new ReticulumSavedData();
        Contents.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, registries), tag)
                .resultOrPartial(err -> data.loadError = err)
                .ifPresent(contents -> {
                    // A network with no controller and nothing in it is left over (a controller placed and broken while
                    // an empty top sheet kept it from being deleted): dropped.
                    contents.networks().stream()
                            .filter(n -> n.controller() != null || !NetworkManager.isEmpty(n))
                            .forEach(n -> data.networks.put(n.id(), n));
                    data.aliases.putAll(contents.aliases());
                });
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        Contents contents = new Contents(SCHEMA_VERSION, List.copyOf(networks.values()), Map.copyOf(aliases));
        Contents.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), contents)
                .resultOrPartial(err -> Reticulum.LOGGER.error("Failed to save Reticulum networks: {}", err))
                .ifPresent(encoded -> tag.merge((CompoundTag) encoded));
        return tag;
    }

    /** The network with this id, or the one it was merged into. */
    @Nullable
    public Network network(@Nullable UUID id) {
        UUID asked = id;
        for (int hops = 0; id != null && hops < 16; hops++) {
            Network network = networks.get(id);
            if (network != null) {
                if (hops > 1) {
                    // A chain of merges: point straight at where it ended, so chains stay short.
                    aliases.put(asked, network.id());
                    setDirty();
                }
                return network;
            }
            id = aliases.get(id);
        }
        if (asked != null && aliases.remove(asked) != null) {
            // Merged into a network since deleted: the alias leads nowhere.
            setDirty();
        }
        return null;
    }

    public void alias(UUID from, UUID into) {
        aliases.put(from, into);
        setDirty();
    }

    public Collection<Network> networks() {
        return networks.values();
    }

    public void add(Network network) {
        networks.put(network.id(), network);
        setDirty();
    }

    public void remove(UUID id) {
        if (networks.remove(id) != null) {
            setDirty();
        }
    }
}
