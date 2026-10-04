package io.github.leadassimilator.reticulum.protocol.web;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
import io.github.leadassimilator.reticulum.integration.Mods;
import io.github.leadassimilator.reticulum.graph.Sheet;
import io.github.leadassimilator.reticulum.network.ConnectorRecord;
import io.github.leadassimilator.reticulum.network.DeviceRecord;
import io.github.leadassimilator.reticulum.network.Devices;
import io.github.leadassimilator.reticulum.network.FaceKey;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
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
        // What is going off, for its tab and the dashboard (sent every second the list changes).
        JsonArray alarms = new JsonArray();
        for (io.github.leadassimilator.reticulum.engine.Alarms.Alarm alarm : io.github.leadassimilator.reticulum.engine.Alarms.cached(server, network)) {
            JsonObject json = new JsonObject();
            json.addProperty("key", alarm.key());
            json.addProperty("severity", alarm.severity().getSerializedName());
            json.addProperty("message", alarm.message());
            if (alarm.sheet() != null) {
                json.addProperty("sheet", alarm.sheet().toString());
            }
            if (alarm.node() != null) {
                json.addProperty("node", alarm.node().toString());
            }
            json.addProperty("since", alarm.since());
            alarms.add(json);
        }
        out.add("alarms", alarms);
        return out;
    }

    static JsonObject full(MinecraftServer server, Network network, net.minecraft.world.entity.player.Player player) {
        JsonObject out = summary(server, network, io.github.leadassimilator.reticulum.security.Security.role(network, player));
        out.addProperty("revision", network.revision());
        // As the server decides edits (an operator's bypass included), so the editor offers what it will accept.
        out.addProperty("canEdit", io.github.leadassimilator.reticulum.security.Security.canEdit(network, player));
        boolean canAdmin = io.github.leadassimilator.reticulum.security.Security.canAdmin(network, player);
        out.addProperty("canAdmin", canAdmin);
        // Deleting takes the owner (or an operator's bypass), as the server checks it.
        out.addProperty("canDelete", io.github.leadassimilator.reticulum.security.Security.has(network, player, Role.OWNER));
        out.addProperty("hasPassword", network.passwordHash() != null);
        out.addProperty("root", network.root().id().toString());
        out.addProperty("redstone", network.switches().redstone().getSerializedName());
        JsonArray identified = new JsonArray();
        for (io.github.leadassimilator.reticulum.network.Identify identify : network.identified()) {
            JsonObject json = encode(server, io.github.leadassimilator.reticulum.network.Identify.CODEC, identify).getAsJsonObject();
            json.addProperty("label", label(server, network, identify));
            identified.add(json);
        }
        out.add("identified", identified);
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
        Map<java.util.UUID, Network.Placement> placements = network.placements();
        for (Sheet sheet : network.sheets()) {
            JsonObject json = encode(server, Sheet.CODEC, sheet).getAsJsonObject();
            // Where it sits in the tree, and its own switch (its sheet node's; the root's is the network's).
            Network.Placement placement = placements.get(sheet.id());
            if (placement != null) {
                json.addProperty("parent", placement.parent().id().toString());
                json.addProperty("node", placement.node().id().toString());
            }
            json.addProperty("enabled", sheet.root() ? network.enabled() : placement != null && placement.node().enabled());
            CompiledSheet compiled = runtime.compiled(sheet, network);
            JsonArray errors = new JsonArray();
            for (CompiledSheet.Problem problem : sheet.blueprint() ? compiled.errors() : compiled.errorsFor(sheet.id())) {
                JsonObject error = new JsonObject();
                error.addProperty("message", problem.message());
                if (problem.node() != null) {
                    UUID owner = compiled.owner(problem.node());
                    error.addProperty("node", owner.toString());
                    // Not for a node inside an instance: it is named in the message, the instance shown (a device's side is
                    // its device node's own, which is shown).
                    if (problem.detail() != null && !compiled.fromInstance(problem.node())) {
                        error.addProperty("detail", problem.detail());
                    }
                }
                errors.add(error);
            }
            json.add("errors", errors);
            sheets.add(json);
        }
        out.add("sheets", sheets);

        // Faces touching a connector carry redstone; faces touching a connector's ME side are ME pins.
        Map<GlobalPos, java.util.Set<FaceKey>> redstone = new java.util.HashMap<>();
        Map<GlobalPos, java.util.Set<FaceKey>> meFacesOf = new java.util.HashMap<>();
        for (ConnectorRecord connector : network.connectors()) {
            BlockPos at = connector.pos().pos();
            for (GlobalPos device : connector.devices()) {
                net.minecraft.core.Direction toConnector = io.github.leadassimilator.reticulum.network.Devices.facing(device.pos(), at);
                if (toConnector != null) {
                    redstone.computeIfAbsent(device, k -> java.util.EnumSet.noneOf(FaceKey.class)).add(FaceKey.of(toConnector));
                }
            }
            ServerLevel connectorLevel = Mods.AE2 ? server.getLevel(connector.pos().dimension()) : null;
            if (connectorLevel != null && connectorLevel.isLoaded(at)) {
                for (net.minecraft.core.Direction side : io.github.leadassimilator.reticulum.integration.ae2.Ae2Integration.sides(connectorLevel, at)) {
                    meFacesOf.computeIfAbsent(GlobalPos.of(connector.pos().dimension(), at.relative(side)), k -> java.util.EnumSet.noneOf(FaceKey.class))
                            .add(FaceKey.of(side.getOpposite()));
                }
            }
        }
        JsonArray devices = new JsonArray();
        for (DeviceRecord record : network.devices()) {
            JsonObject device = device(server, record);
            if (device.get("resources") instanceof JsonObject resources) {
                JsonArray redstoneFaces = new JsonArray();
                redstone.getOrDefault(record.pos(), java.util.Set.of()).forEach(f -> redstoneFaces.add(f.getSerializedName()));
                resources.add(ResourceKind.REDSTONE.getSerializedName(), redstoneFaces);
                JsonArray meFaces = new JsonArray();
                meFacesOf.getOrDefault(record.pos(), java.util.Set.of()).forEach(f -> meFaces.add(f.getSerializedName()));
                if (!meFaces.isEmpty()) {
                    resources.add(ResourceKind.ME.getSerializedName(), meFaces);
                }
            }
            if (runtime.outOfRange(record.pos())) {
                device.addProperty("outOfRange", true);
            }
            devices.add(device);
        }
        out.addProperty("antenna", antennaTier(server, network));
        out.add("power", WebApi.powerJson(runtime.powerState(server, network)));
        out.add("devices", devices);
        JsonArray charts = new JsonArray();
        for (io.github.leadassimilator.reticulum.network.ChartSeries chart : network.charts()) {
            JsonObject json = encode(server, io.github.leadassimilator.reticulum.network.ChartSeries.CODEC, chart).getAsJsonObject();
            json.addProperty("key", chart.key());
            json.addProperty("series", chart.seriesId().toString());
            if (chart.kind() == io.github.leadassimilator.reticulum.network.ChartSeries.Kind.THROUGH) {
                json.addProperty("seriesIn", chart.seriesId(true).toString());
                json.addProperty("seriesOut", chart.seriesId(false).toString());
            }
            charts.add(json);
        }
        out.add("charts", charts);
        JsonObject totals = new JsonObject();
        for (io.github.leadassimilator.reticulum.graph.ResourceKind kind : io.github.leadassimilator.reticulum.graph.ResourceKind.values()) {
            if (kind != io.github.leadassimilator.reticulum.graph.ResourceKind.REDSTONE && kind != io.github.leadassimilator.reticulum.graph.ResourceKind.ME) {
                totals.addProperty(kind.getSerializedName(), io.github.leadassimilator.reticulum.network.ChartSeries.totalId(kind).toString());
            }
        }
        out.add("chartTotals", totals);

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
            if (Mods.AE2) {
                ServerLevel connectorLevel = server.getLevel(connector.pos().dimension());
                if (connectorLevel != null && connectorLevel.isLoaded(connector.pos().pos())) {
                    JsonArray me = new JsonArray();
                    io.github.leadassimilator.reticulum.integration.ae2.Ae2Integration.sides(connectorLevel, connector.pos().pos())
                            .forEach(side -> me.add(side.getSerializedName()));
                    json.add("me", me);
                }
            }
            NetworkRuntime.Reach reach = runtime.reach(connector.id());
            if (reach != null) {
                json.addProperty("reach", reach.key());
            }
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
        JsonArray chemicals = new JsonArray();
        network.overflow().chemicals().forEach((id, amount) -> {
            JsonObject chemical = new JsonObject();
            chemical.addProperty("id", id.toString());
            chemical.addProperty("amount", amount);
            chemicals.add(chemical);
        });
        overflow.add("chemicals", chemicals);
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
            // Another network's controller: only redstone, to switch that network (its own redstone setting decides how).
            boolean controller = level.getBlockEntity(at) instanceof io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity;
            json.addProperty("defaultName", Devices.defaultName(level, at));
            json.addProperty("icon", BuiltInRegistries.ITEM.getKey(Devices.icon(level, at).getItem()).toString());
            JsonObject resources = new JsonObject();
            resources.add(ResourceKind.ITEM.getSerializedName(), controller ? new JsonArray() : faces(level, at, Capabilities.ItemHandler.BLOCK));
            resources.add(ResourceKind.FLUID.getSerializedName(), controller ? new JsonArray() : faces(level, at, Capabilities.FluidHandler.BLOCK));
            resources.add(ResourceKind.ENERGY.getSerializedName(), controller ? new JsonArray() : faces(level, at, Capabilities.EnergyStorage.BLOCK));
            if (Mods.chemicalCapability() != null && !controller) {
                resources.add(ResourceKind.CHEMICAL.getSerializedName(), faces(level, at, Mods.chemicalCapability()));
            }
            json.add("resources", resources);
            // Which way energy goes on each face, so a new device node gets the energy pins its faces can use.
            JsonArray energyIn = new JsonArray();
            JsonArray energyOut = new JsonArray();
            for (FaceKey face : FaceKey.values()) {
                if (!controller && level.getCapability(Capabilities.EnergyStorage.BLOCK, at, face.direction()) instanceof net.neoforged.neoforge.energy.IEnergyStorage energy) {
                    if (energy.canReceive()) {
                        energyIn.add(face.getSerializedName());
                    }
                    if (energy.canExtract()) {
                        energyOut.add(face.getSerializedName());
                    }
                }
            }
            json.add("energyIn", energyIn);
            json.add("energyOut", energyOut);
            json.addProperty("givesRedstone", Devices.givesRedstone(level, at));
            json.addProperty("takesRedstone", Devices.takesRedstone(level, at));
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
            if (usable(level.getCapability(capability, pos, face.direction()))) {
                faces.add(face.getSerializedName());
            }
        }
        return faces;
    }

    /**
     * Whether a handler can actually hold or pass something. Some blocks offer an empty one on every face (AE2 gives
     * its machines, the ME controller included, an item handler with no slots), which isn't something to move from.
     */
    private static boolean usable(@javax.annotation.Nullable Object handler) {
        return switch (handler) {
            case null -> false;
            case net.neoforged.neoforge.items.IItemHandler items -> items.getSlots() > 0;
            case net.neoforged.neoforge.fluids.capability.IFluidHandler fluids -> fluids.getTanks() > 0;
            case net.neoforged.neoforge.energy.IEnergyStorage energy -> energy.getMaxEnergyStored() > 0 || energy.canReceive() || energy.canExtract();
            default -> true;
        };
    }

    /** The name shown for an identify in the editor's Identified panel (the same as above it in the world). */
    private static String label(MinecraftServer server, Network network, io.github.leadassimilator.reticulum.network.Identify identify) {
        return io.github.leadassimilator.reticulum.network.Identifies.label(server, network, identify);
    }

    /** The controller's antenna tier, or 0 when it has none or isn't loaded. */
    static int antennaTier(MinecraftServer server, Network network) {
        var controller = NetworkManager.controller(server, network);
        return controller == null ? 0 : controller.antennaTier();
    }

    static boolean visible(Network network, net.minecraft.world.entity.player.Player player) {
        return Security.canView(network, player);
    }
}
