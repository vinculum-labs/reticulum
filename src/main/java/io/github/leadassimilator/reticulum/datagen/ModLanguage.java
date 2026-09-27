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
        add("message.reticulum.ambiguous_placement", "Touches several networks; not connected to any. Use the network tool or the cable screen to connect one.");
        add("message.reticulum.controller_exists", "%s already has a controller; this one starts a new network");
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
        add("screen.reticulum.open_editor_unavailable", "The web editor is not available yet");
        add("screen.reticulum.save", "Save");

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
        add("config.jade.plugin_reticulum.network_info", "Reticulum network info");

        add("reticulum.configuration.title", "Reticulum");
        add("reticulum.configuration.section.reticulum.server.toml", "Reticulum");
        add("reticulum.configuration.section.reticulum.server.toml.title", "Reticulum");
        add("reticulum.configuration.protectPrivateBlocks", "Protect private network blocks");
        add("reticulum.configuration.operatorBypass", "Operators bypass security");
        add("reticulum.configuration.maxInteractDistance", "Max screen interaction distance");
    }
}
