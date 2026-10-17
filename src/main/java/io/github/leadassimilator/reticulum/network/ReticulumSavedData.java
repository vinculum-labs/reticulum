package io.github.leadassimilator.reticulum.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.Reticulum;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

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
    /** The unreadable file couldn't be copied aside: it is never saved over (see keepUnreadable). */
    private boolean refuseSave;

    @Override
    public boolean isDirty() {
        return !refuseSave && super.isDirty();
    }
    /** Networks merged into others: old id to the id that absorbed it. Blocks still carrying an old id follow it. */
    private final Map<UUID, UUID> aliases = new LinkedHashMap<>();

    public static ReticulumSavedData get(MinecraftServer server) {
        ReticulumSavedData data = server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ReticulumSavedData::new, ReticulumSavedData::load), FILE_NAME);
        // Made fresh although the file is there: Minecraft couldn't read it at all (a broken file), so load never ran.
        if (!data.checkedFile) {
            data.checkedFile = true;
            if (!data.loaded && Files.exists(file(server))) {
                data.loadError = "the file itself couldn't be read";
            }
        }
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
        Path file = file(server);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path copy = file.resolveSibling(FILE_NAME + ".dat.unreadable-" + stamp);
        try {
            Files.copy(file, copy);
            keptAs = copy.getFileName().toString();
            Reticulum.LOGGER.error("Reticulum couldn't read all of its saved networks ({}). The file was copied to {} and "
                    + "the networks that couldn't be read start empty.", error, copy);
        } catch (IOException e) {
            keptAs = null;
            refuseSave = true;
            Reticulum.LOGGER.error("Reticulum couldn't read all of its saved networks ({}), nor copy the file aside: nothing is saved over it "
                    + "this session (changes are lost on stop)", error, e);
        }
    }

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE_NAME + ".dat");
    }

    /** Whether this came from the file (load ran), and whether that was checked after it was first asked for. */
    private boolean loaded;
    private boolean checkedFile;

    /** Operators joining are told when the saved networks couldn't be read, and where the copy is. */
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.hasPermissions(2)) {
            return;
        }
        ReticulumSavedData data = get(player.server);
        if (data.keptAs != null) {
            player.sendSystemMessage(Component.translatable("message.reticulum.save_unreadable", data.keptAs)
                    .withStyle(ChatFormatting.RED));
        }
    }

    private static ReticulumSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ReticulumSavedData data = new ReticulumSavedData();
        data.loaded = true;
        data.lastGood = tag.copy();
        Ids.leftOut = false;
        Contents.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, registries), tag)
                .resultOrPartial(err -> data.loadError = err)
                .ifPresent(contents -> {
                    // A network with no controller and nothing in it is left over (a controller placed and broken while
                    // an empty top sheet kept it from being deleted): dropped.
                    contents.networks().stream()
                            .filter(n -> n.controller() != null || n.owner() != null || !NetworkManager.isEmpty(n))
                            .forEach(n -> data.networks.put(n.id(), n));
                    data.aliases.putAll(contents.aliases());
                });
        // Something left out (a node or a chart it couldn't read): the file is kept as it was, as for a failed read.
        if (Ids.leftOut && data.loadError == null) {
            data.loadError = "some entries couldn't be read (see the log)";
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        Contents contents = new Contents(SCHEMA_VERSION, List.copyOf(networks.values()), Map.copyOf(aliases));
        var encoded = Contents.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), contents);
        if (encoded.result().isPresent()) {
            lastGood = ((CompoundTag) encoded.result().get()).copy();
        } else {
            // Something the save can't hold: the last save that worked is written again, rather than a partial (or
            // empty) one losing networks.
            Reticulum.LOGGER.error("Failed to save Reticulum networks, keeping the last good save: {}",
                    encoded.error().map(e -> e.message()).orElse("?"));
        }
        if (lastGood != null) {
            tag.merge(lastGood.copy());
        }
        return tag;
    }

    /** What was last saved (or loaded) whole. */
    @Nullable
    private CompoundTag lastGood;

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
