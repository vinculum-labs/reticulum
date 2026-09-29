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
    /** Networks merged into others: old id to the id that absorbed it. Blocks still carrying an old id follow it. */
    private final Map<UUID, UUID> aliases = new LinkedHashMap<>();

    public static ReticulumSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ReticulumSavedData::new, ReticulumSavedData::load), FILE_NAME);
    }

    private static ReticulumSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ReticulumSavedData data = new ReticulumSavedData();
        Contents.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, registries), tag)
                .resultOrPartial(err -> Reticulum.LOGGER.error("Failed to load Reticulum networks: {}", err))
                .ifPresent(contents -> {
                    contents.networks().forEach(n -> data.networks.put(n.id(), n));
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
        for (int hops = 0; id != null && hops < 16; hops++) {
            Network network = networks.get(id);
            if (network != null) {
                return network;
            }
            id = aliases.get(id);
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
