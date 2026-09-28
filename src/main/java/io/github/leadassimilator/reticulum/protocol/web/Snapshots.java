package io.github.leadassimilator.reticulum.protocol.web;

import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;

import io.github.leadassimilator.reticulum.engine.CompiledSheet;
import io.github.leadassimilator.reticulum.engine.NetworkRuntime;
import io.github.leadassimilator.reticulum.engine.NetworkTicker;
import io.github.leadassimilator.reticulum.graph.ResourceKind;
import io.github.leadassimilator.reticulum.graph.Sheet;
import io.github.leadassimilator.reticulum.network.ConnectorRecord;
import io.github.leadassimilator.reticulum.network.DeviceRecord;
import io.github.leadassimilator.reticulum.network.Devices;
import io.github.leadassimilator.reticulum.network.FaceKey;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.security.Role;
import io.github.leadassimilator.reticulum.security.Security;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;

/** JSON views of networks for the web UI. Sheets use the same codecs as saving, so the two can't drift apart. */
final class Snapshots {
    private Snapshots() {
    }

    static DynamicOps<JsonElement> ops(MinecraftServer server) {
        return RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
    }

    static <T> JsonElement encode(MinecraftServer server, Codec<T> codec, T value) {
        return codec.encodeStart(ops(server), value).getOrThrow();
    }

    /** One line per network for the network list. */
    static JsonObject summary(MinecraftServer server, Network network, Optional<Role> role) {
        JsonObject out = new JsonObject();
        out.addProperty("id", network.id().toString());
        out.addProperty("name", network.name());
        out.addProperty("access", network.accessMode().getSerializedName());
        out.addProperty("owner", network.ownerName());
        out.addProperty("role", role.map(Role::getSerializedName).orElse("none"));
        out.addProperty("enabled", network.enabled());
        out.addProperty("running", NetworkTicker.isRunning(server, network));
        return out;
    }

    static JsonObject full(MinecraftServer server, Network network, Optional<Role> role) {
        JsonObject out = summary(server, network, role);
        out.addProperty("revision", network.revision());
        out.addProperty("canEdit", role.map(r -> r.atLeast(Role.EDITOR)).orElse(false));
        boolean canAdmin = role.map(r -> r.atLeast(Role.ADMIN)).orElse(false);
        out.addProperty("canAdmin", canAdmin);
        out.addProperty("hasPassword", network.passwordHash() != null);
        JsonArray disabledFolders = new JsonArray();
        network.disabledFolders().forEach(disabledFolders::add);
        out.add("disabledFolders", disabledFolders);
        if (canAdmin) {
            // The member list is shown to those who can change it.
            JsonArray members = new JsonArray();
            for (io.github.leadassimilator.reticulum.security.Member member : network.members().values()) {
                JsonObject m = new JsonObject();
                m.addProperty("id", member.id().toString());
                m.addProperty("name", member.name());
                m.addProperty("role", member.role().getSerializedName());
                members.add(m);
            }
            out.add("members", members);
        }
        if (network.controller() != null) {
            out.add("controller", encode(server, GlobalPos.CODEC, network.controller()));
        }

        NetworkRuntime runtime = NetworkTicker.runtime(network.id());
        JsonArray sheets = new JsonArray();
        for (Sheet sheet : network.sheets()) {
            JsonObject json = encode(server, Sheet.CODEC, sheet).getAsJsonObject();
            CompiledSheet compiled = runtime.compiled(sheet, network);
            JsonArray errors = new JsonArray();
            compiled.errors().forEach(errors::add);
            json.add("errors", errors);
            sheets.add(json);
        }
        out.add("sheets", sheets);

        JsonArray devices = new JsonArray();
        for (DeviceRecord record : network.devices()) {
            devices.add(device(server, record));
        }
        out.add("devices", devices);

        JsonArray connectors = new JsonArray();
        for (ConnectorRecord connector : network.connectors()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", connector.id().toString());
            json.add("pos", encode(server, GlobalPos.CODEC, connector.pos()));
            if (connector.name() != null) {
                json.addProperty("name", connector.name());
            }
            JsonArray reached = new JsonArray();
            connector.devices().forEach(d -> reached.add(encode(server, GlobalPos.CODEC, d)));
            json.add("devices", reached);
            connectors.add(json);
        }
        out.add("connectors", connectors);

        JsonObject filters = new JsonObject();
        for (Map.Entry<String, String> filter : network.filters().entrySet()) {
            filters.addProperty(filter.getKey(), filter.getValue());
        }
        out.add("filters", filters);

        JsonObject overflow = new JsonObject();
        JsonArray items = new JsonArray();
        network.overflowItems().forEach(stack -> items.add(encode(server, ItemStack.CODEC, stack)));
        JsonArray fluids = new JsonArray();
        network.overflowFluids().forEach(stack -> fluids.add(encode(server, FluidStack.CODEC, stack)));
        overflow.add("items", items);
        overflow.add("fluids", fluids);
        out.add("overflow", overflow);
        return out;
    }

    /** A device with its names, state, icon and, per resource, the faces that offer it. */
    static JsonObject device(MinecraftServer server, DeviceRecord record) {
        JsonObject json = new JsonObject();
        GlobalPos pos = record.pos();
        json.add("pos", encode(server, GlobalPos.CODEC, pos));
        if (record.name() != null) {
            json.addProperty("name", record.name());
        }
        json.addProperty("block", record.blockId().toString());
        json.addProperty("state", record.state().getSerializedName());
        JsonObject faces = new JsonObject();
        record.faceNames().forEach((face, name) -> faces.addProperty(face.getSerializedName(), name));
        json.add("faceNames", faces);

        ServerLevel level = server.getLevel(pos.dimension());
        BlockPos at = pos.pos();
        if (level != null && level.isLoaded(at)) {
            BlockState state = level.getBlockState(at);
            json.addProperty("defaultName", Devices.defaultName(level, at));
            json.addProperty("icon", BuiltInRegistries.ITEM.getKey(state.getBlock().asItem()).toString());
            JsonObject resources = new JsonObject();
            resources.add(ResourceKind.ITEM.getSerializedName(), faces(level, at, Capabilities.ItemHandler.BLOCK));
            resources.add(ResourceKind.FLUID.getSerializedName(), faces(level, at, Capabilities.FluidHandler.BLOCK));
            resources.add(ResourceKind.ENERGY.getSerializedName(), faces(level, at, Capabilities.EnergyStorage.BLOCK));
            json.add("resources", resources);
            json.addProperty("loaded", true);
        } else {
            json.addProperty("defaultName", Devices.defaultName(net.minecraft.network.chat.Component.literal(record.blockId().getPath()), at));
            json.addProperty("loaded", false);
        }
        return json;
    }

    private static JsonArray faces(ServerLevel level, BlockPos pos, net.neoforged.neoforge.capabilities.BlockCapability<?, net.minecraft.core.Direction> capability) {
        JsonArray faces = new JsonArray();
        for (FaceKey face : FaceKey.values()) {
            if (level.getCapability(capability, pos, face.direction()) != null) {
                faces.add(face.getSerializedName());
            }
        }
        return faces;
    }

    static boolean visible(Network network, net.minecraft.world.entity.player.Player player) {
        return Security.canView(network, player);
    }
}
