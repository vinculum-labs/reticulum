package io.github.leadassimilator.reticulum;

import io.github.leadassimilator.reticulum.client.ToolHighlight;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Reticulum.MODID, dist = Dist.CLIENT)
public class ReticulumClient {
    public ReticulumClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.addListener(ToolHighlight::onRenderHighlight);
    }
}
