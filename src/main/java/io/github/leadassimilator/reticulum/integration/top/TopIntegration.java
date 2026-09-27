package io.github.leadassimilator.reticulum.integration.top;

import java.util.function.Function;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.lookat.LookAt;
import mcjty.theoneprobe.api.IProbeHitData;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.IProbeInfoProvider;
import mcjty.theoneprobe.api.ITheOneProbe;
import mcjty.theoneprobe.api.ProbeMode;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** The One Probe provider. Only loaded after checking the mod is present; see {@link #register()}. */
public final class TopIntegration implements Function<ITheOneProbe, Void> {
    public static final String MODID = "theoneprobe";

    public static void register() {
        net.neoforged.fml.InterModComms.sendTo(MODID, "getTheOneProbe", TopIntegration::new);
    }

    @Override
    public Void apply(ITheOneProbe probe) {
        probe.registerProvider(new IProbeInfoProvider() {
            @Override
            public ResourceLocation getID() {
                return ResourceLocation.fromNamespaceAndPath(Reticulum.MODID, "network_info");
            }

            @Override
            public void addProbeInfo(ProbeMode mode, IProbeInfo info, Player player, Level level, BlockState state, IProbeHitData data) {
                if (level instanceof ServerLevel serverLevel) {
                    for (Component line : LookAt.describe(serverLevel, data.getPos(), data.getSideHit(), player)) {
                        info.text(line);
                    }
                }
            }
        });
        return null;
    }
}
