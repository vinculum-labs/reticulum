package io.github.leadassimilator.reticulum.datagen;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.registry.ModBlocks;
import io.github.leadassimilator.reticulum.registry.ModItems;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public class ModLanguage extends LanguageProvider {
    public ModLanguage(PackOutput output) {
        super(output, Reticulum.MODID, "en_us");
    }

    /** A config section's name and screen title. */
    private void section(String key, String name) {
        add("reticulum.configuration." + key, name);
        add("reticulum.configuration." + key + ".title", name);
    }

    @Override
    protected void addTranslations() {
        add("itemGroup.reticulum", "Reticulum");
        add(ModBlocks.CABLE.get(), "Network Cable");
        add(ModBlocks.CONNECTOR.get(), "Network Connector");
        add(ModBlocks.CONTROLLER.get(), "Network Controller");
        add(ModItems.NETWORK_TOOL.get(), "Network Tool");
        add("tooltip.reticulum.remembers_network", "Remembers network %s");

        add("message.reticulum.no_access", "You don't have access to %s");
        add("message.reticulum.no_edit_access", "You don't have edit access to this network");
        add("message.reticulum.no_admin_access", "Only the network's admins can do that");
        add("tooltip.reticulum.stored_energy", "Holds %s FE");
        add("message.reticulum.member_is_owner", "%s owns the network");
        add("message.reticulum.too_many_members", "A network can have at most %s members");
        add("message.reticulum.password_empty", "The password can't be empty");
        add("message.reticulum.no_access_private", "That network is private");
        add("message.reticulum.still_has_controller", "These cables belong to %s, which still has its controller; join them to that network instead");
        add("message.reticulum.ambiguous_placement", "Touches several networks; not connected to any. Use the network tool or the cable screen to connect one.");
        add("message.reticulum.controller_exists", "%s already has a controller; this one starts a new network");
        add("message.reticulum.controller_reconnected", "Took over %s, which had lost its controller");
        add("command.reticulum.deleted", "Deleted network %s");
        add("command.reticulum.merged", "Merged %s into %s");
        add("command.reticulum.merge_same", "Those are the same network");
        add("command.reticulum.merge_has_controller", "%s still has a working controller; break it first");
        add("message.reticulum.network_gone", "The remembered network no longer exists; starting a new one");
        add("message.reticulum.nothing_to_connect", "Nothing to connect on that side");
        add("message.reticulum.disconnected", "Disconnected");
        add("message.reticulum.connected", "Connected");
        add("message.reticulum.two_controllers", "Can't connect: both sides have a controller");
        add("message.reticulum.device_attached", "Device attached");
        add("message.reticulum.device_detached", "Device detached");
        add("message.reticulum.not_a_device", "That side has no connected device");
        add("message.reticulum.password_required", "Set a password before choosing Password access");
        add("message.reticulum.no_network", "Not connected to a network");
        add("message.reticulum.protected", "%s is private; only members can break its blocks");
        add("message.reticulum.protected_private", "This network is private; only members can break its blocks");

        add("screen.reticulum.connector", "Connector");
        add("screen.reticulum.cable", "Cable");
        add("screen.reticulum.controller", "Network Controller");
        add("screen.reticulum.connector_name", "Name");
        add("screen.reticulum.device_name", "Device name");
        add("screen.reticulum.attached", "Attached");
        add("screen.reticulum.detached", "Detached");
        add("screen.reticulum.faces", "Faces");
        add("screen.reticulum.faces_title", "Face names");
        add("screen.reticulum.joint.connected", "Connected");
        add("screen.reticulum.joint.disconnected", "Disconnected");
        add("screen.reticulum.joint.conflict", "Network Conflict");
        add("screen.reticulum.attached_tip", "Attached: the network uses this device. Click to detach it.");
        add("screen.reticulum.detached_tip", "Detached: the network leaves this device alone. Click to attach it.");
        add("screen.reticulum.faces_tip", "Name this device's faces");
        add("screen.reticulum.joint.connected_tip", "Connected. Click to disconnect this side.");
        add("screen.reticulum.joint.disconnected_tip", "Disconnected. Click to connect this side.");
        add("screen.reticulum.joint.conflict_tip", "Network conflict: the two sides belong to different networks. Click to join them into the one with the controller (not possible when both have one).");
        add("screen.reticulum.no_network", "No network");
        add("screen.reticulum.network", "Network: %s");
        add("screen.reticulum.empty_side", "-");
        for (String side : new String[] {"down", "up", "north", "south", "west", "east"}) {
            String label = Character.toUpperCase(side.charAt(0)) + side.substring(1);
            add("screen.reticulum.side." + side, label);
            add("screen.reticulum.face." + side, label);
        }
        add("screen.reticulum.face.unsided", "Unsided");
        add("screen.reticulum.network_name", "Name");
        add("screen.reticulum.access", "Access");
        add("screen.reticulum.access.public", "Public");
        add("screen.reticulum.access.password", "Password");
        add("screen.reticulum.access.private", "Private");
        add("screen.reticulum.password", "Password");
        add("screen.reticulum.password_keep", "(unchanged)");
        add("screen.reticulum.password_set", "Set a password");
        add("screen.reticulum.owner", "Owner: %s");
        add("screen.reticulum.counts", "Connectors: %s   Devices: %s");
        add("screen.reticulum.orphans", "Orphaned devices: %s");
        add("screen.reticulum.open_editor", "Open editor");
        add("message.reticulum.editor_opened", "Opened the Reticulum editor in your browser: %s");
        add("message.reticulum.editor_focused", "Switched the open Reticulum editor to this network");
        add("message.reticulum.editor_link_hover", "Click to open the editor in your browser");

        add("command.reticulum.unknown_network", "No network with that name or id");
        add("command.reticulum.no_access", "Access denied");
        add("command.reticulum.unknown_role", "Role must be viewer, user, editor or admin");
        add("command.reticulum.list_empty", "No networks you can see");
        add("command.reticulum.list_entry", "%s [%s] owner %s, %s, %s connectors, %s devices");
        add("command.reticulum.already_member", "You already have access to %s");
        add("command.reticulum.joined", "Joined %s");
        add("command.reticulum.member_added", "Added %s member(s) to %s");
        add("command.reticulum.member_removed", "Removed %s member(s) from %s");

        add("lookat.reticulum.no_network", "No network");
        add("lookat.reticulum.private", "Private network");
        add("lookat.reticulum.devices", "Devices: %s");
        add("lookat.reticulum.face", "Face: %s");
        add("lookat.reticulum.state.orphaned", "Orphaned: no connector reaches it");
        add("lookat.reticulum.state.missing", "Missing");
        add("lookat.reticulum.reach.wired", "Cabled to the controller");
        add("lookat.reticulum.reach.wireless", "Wireless: in the antenna's reach");
        add("lookat.reticulum.reach.out_of_range", "Wireless: out of the antenna's reach");
        add("lookat.reticulum.device_out_of_range", "Offline: its connector is out of the antenna's reach");
        add("lookat.reticulum.redstone_out", "Redstone out: %s");
        add("lookat.reticulum.me", "ME: %s");
        add("lookat.reticulum.redstone_in", "Redstone from the network: %s");
        add("lookat.reticulum.identified", "Identified");
        add("lookat.reticulum.identifies", "Identified: %s");
        add("lookat.reticulum.antenna_none", "No antenna: cabled connectors only");
        add("lookat.reticulum.antenna_1", "Antenna reach: %s blocks");
        add("lookat.reticulum.antenna_2", "Antenna reach: this dimension");
        add("lookat.reticulum.antenna_3", "Antenna reach: every dimension");
        add("lookat.reticulum.power", "Energy: %s / %s FE");
        add("lookat.reticulum.no_power", "No power: %s / %s FE");
        add("config.jade.plugin_reticulum.network_info", "Reticulum network info");

        add("reticulum.configuration.title", "Reticulum");
        add("reticulum.configuration.section.reticulum.server.toml", "Reticulum");
        add("reticulum.configuration.section.reticulum.server.toml.title", "Reticulum");
        add("reticulum.configuration.protectPrivateBlocks", "Protect private network blocks");
        add("reticulum.configuration.operatorBypass", "Operators bypass security");
        add("reticulum.configuration.maxInteractDistance", "Max screen interaction distance");
        add("reticulum.configuration.section.reticulum.client.toml", "Reticulum (client)");
        add("reticulum.configuration.section.reticulum.client.toml.title", "Reticulum (client)");
        add("reticulum.configuration.webAddress", "Web editor address");
        add("reticulum.configuration.webPort", "Web editor port");
        add("reticulum.configuration.keepRunningWhileEditing", "Keep running while the editor is open");
        add("reticulum.configuration.rememberLogin", "Stay logged in to the web editor");
        add("reticulum.configuration.editorInGame", "Open the editor inside the game");
        add("reticulum.configuration.editorDisplay", "In-game editor display (Windows)");
        add("screen.reticulum.editor", "Reticulum editor");
        add("screen.reticulum.editor_close", "Close");
        add("screen.reticulum.editor_browser", "Open in browser");
        add("screen.reticulum.editor_loading", "Starting the editor…");
        add("screen.reticulum.editor_fullscreen", "The editor can't show over fullscreen");
        add("screen.reticulum.editor_fullscreen_hint", "Press F11 for windowed mode, or use Open in browser");
        add("reticulum.configuration.energyPerTick", "Energy per route per tick");
        add("reticulum.configuration.fluidPerTick", "Fluid per route per tick");
        add("reticulum.configuration.overflowItemStacks", "Overflow buffer item stacks");
        add("reticulum.configuration.overflowFluidBuckets", "Overflow buffer buckets per fluid");
        add("reticulum.configuration.chemicalPerTick", "Chemical per route per tick");
        add("reticulum.configuration.overflowChemicalBuckets", "Overflow buffer buckets per chemical");
        add("reticulum.configuration.antennaRange", "Tier 1 antenna range");
        add("reticulum.configuration.meEndpointPower", "AE power per ME endpoint (AE/t)");
        section("security", "Security");
        section("wireless", "Wireless");
        section("power", "Power");
        section("throughput", "Throughput and overflow");
        section("identify", "Identify");
        section("ae2", "Applied Energistics 2");
        section("webEditor", "Web editor");

        // Wireless: antennas, binding connectors, the tool's Link mode and the network picker.
        add(ModItems.ANTENNA_1.get(), "Antenna (Tier 1)");
        add(ModItems.ANTENNA_2.get(), "Antenna (Tier 2)");
        add(ModItems.ANTENNA_3.get(), "Antenna (Tier 3)");
        add("tooltip.reticulum.antenna_none", "No antenna: no wireless connectors");
        add("tooltip.reticulum.antenna_1", "Wireless range: %s blocks, same dimension");
        add("tooltip.reticulum.antenna_2", "Wireless range: the whole dimension");
        add("tooltip.reticulum.antenna_3", "Wireless range: every dimension");
        add("tooltip.reticulum.antenna_use", "Right-click a controller to install it; take it out with the network tool (Configure mode, sneak + right-click)");
        add("tooltip.reticulum.bound_to", "Bound to %s");
        add("tooltip.reticulum.linked_to", "Network: %s");
        add("tooltip.reticulum.tool_configure", "Right-click a joint to connect or disconnect it, or a device side to attach or detach it. Sneak + right-click takes off a facade, or a controller's antenna");
        add("tooltip.reticulum.tool_link", "Sneak + right-click a controller or connector to copy its network; right-click a connector to bind it; right-click the air to pick a network (or unlink the tool)");
        add("tooltip.reticulum.tool_switch", "Sneak + scroll to switch mode; sneak + right-click the air to open the editor");
        add("screen.reticulum.unlink_tool", "Unlink tool");
        add("tool.reticulum.mode.configure", "Configure");
        add("tool.reticulum.mode.link", "Link");
        add("message.reticulum.tool_mode", "Network tool: %s mode");
        add("message.reticulum.tool_linked", "Network tool linked to %s");
        add("message.reticulum.tool_cleared", "Network tool cleared");
        add("message.reticulum.tool_empty", "Copy a network first: sneak + right-click a controller or connector, or right-click the air");
        add("message.reticulum.not_a_connector", "Only connectors can be bound to a network");
        add("message.reticulum.wired_connector", "A cable joins this connector, so the cable decides its network");
        add("message.reticulum.already_bound", "Already on %s");
        add("message.reticulum.bound", "Bound to %s");
        add("message.reticulum.stack_bound", "Bound %s connector(s) to %s; place them where no cable joins them");
        add("message.reticulum.network_gone_bound", "That network no longer exists");
        add("message.reticulum.wrong_password", "Wrong password for %s");
        add("message.reticulum.antenna_same", "That antenna is already installed");
        add("message.reticulum.antenna_removed", "Antenna removed: cabled connectors only");
        add("message.reticulum.antenna_none_installed", "This controller has no antenna");
        add("message.reticulum.antenna_installed", "Antenna installed. %s");
        add("screen.reticulum.pick_network", "Network…");
        add("screen.reticulum.pick_network_tool", "Link the network tool to…");
        add("screen.reticulum.pick_network_connector", "Bind this connector to…");
        add("screen.reticulum.password_for", "Password for %s");
        add("screen.reticulum.join", "Join");
        add("screen.reticulum.no_networks", "No networks you can use");

        // Controller screen, Network tab
        add("screen.reticulum.tab_settings", "Settings");
        add("screen.reticulum.tab_network", "Network");
        add("screen.reticulum.on", "On");
        add("screen.reticulum.off", "Off");
        add("screen.reticulum.network_switch", "Network");
        add("screen.reticulum.redstone", "Redstone");
        add("screen.reticulum.redstone.ignored", "Ignored");
        add("screen.reticulum.redstone.high", "On while powered");
        add("screen.reticulum.redstone.low", "Off while powered");
        add("screen.reticulum.redstone_hint", "What redstone into this controller does to the whole network: nothing, run only while powered, or stop while powered.");
        add("screen.reticulum.running", "Running");
        add("screen.reticulum.stopped_off", "Switched off");
        add("screen.reticulum.stopped_redstone", "Not running (redstone, or the controller isn't loaded)");
        add("screen.reticulum.no_sheets", "No sheets yet: open the editor to make one");
        add("screen.reticulum.parent_off", "(a sheet above is off)");
        add("screen.reticulum.overflow", "Overflow: %s");

        add("screen.reticulum.power", "Power: %s / %s FE (%s FE/t)");
        add("screen.reticulum.power_out", "No power: %s / %s FE (needs %s FE/t)");
        add("reticulum.configuration.powerPreset", "Power costs");
        add("reticulum.configuration.controllerCapacity", "Controller FE capacity");
        add("reticulum.configuration.costBase", "Cost per network (FE/t)");
        add("reticulum.configuration.costPerConnector", "Cost per connector (FE/t)");
        add("reticulum.configuration.costWirelessTier1", "Cost per wireless connector, tier 1 (FE/t)");
        add("reticulum.configuration.costWirelessTier2", "Cost per wireless connector, tier 2+ (FE/t)");
        add("reticulum.configuration.costWirelessPerBlock", "Cost per block of wireless distance (FE/t)");
        add("reticulum.configuration.costCrossDimension", "Cost per wireless connector in another dimension (FE/t)");
        add("reticulum.configuration.costPerOperation", "Cost per item operation (FE)");

        // Facades
        add(ModItems.FACADE.get(), "Facade");
        add("item.reticulum.facade_of", "Facade (%s)");
        add("tooltip.reticulum.facade", "Right-click a cable or connector to make it look like this block. Take it off with the network tool (Configure mode, sneak + right-click).");
        add("tooltip.reticulum.facade_paint", "Right-click to choose any block for the whole stack; sneak + right-click a block to copy how it looks");
        add("item.reticulum.facade_blank", "Blank Facade");
        add("message.reticulum.facade_copied", "Facades now look like %s");
        add("message.reticulum.not_facade_block", "%s can't be a facade: only full blocks without a block entity");
        add("screen.reticulum.facade", "Facade");
        add("screen.reticulum.facade_search", "Search blocks");
        add("screen.reticulum.facade_blank", "Blank");
        add("screen.reticulum.facade_none", "Blank");
        add("screen.reticulum.facade_count", "%s in hand");
        add("screen.reticulum.facade_no_match", "No block matches");
        add("screen.reticulum.facade_hint", "Click a block to paint the stack. Sneak + right-click a block in the world to copy it exactly.");
        add("screen.reticulum.facade_hint_jei", "Click a block to paint the stack, or drag one from JEI onto the box above. Sneak + right-click a block in the world to copy it exactly.");
        add("message.reticulum.facade_removed", "Facade taken off");

        // Identify
        add("tool.reticulum.mode.identify", "Identify");
        add("tooltip.reticulum.tool_identify", "Right-click a connector or device to light it up (or off); sneak + right-click lights one face of a device");
        add("message.reticulum.identified", "Lit up %s");
        add("message.reticulum.unidentified", "Stopped lighting up %s");
        add("message.reticulum.not_a_device_of_yours", "That block isn't a device of a network you can use");
        add("screen.reticulum.identify_on", "Light it up in the world until turned off");
        add("screen.reticulum.identify_off", "Stop lighting it up");
        add("reticulum.configuration.identifyThroughWalls", "Show identified blocks through walls");
        add("reticulum.configuration.identifyBeamDistance", "Identify beam distance");
        add("reticulum.configuration.identifyAutoOffMinutes", "Identify auto-off (minutes, 0 = never)");
    }
}
