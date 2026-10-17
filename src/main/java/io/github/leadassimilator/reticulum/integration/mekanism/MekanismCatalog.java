package io.github.leadassimilator.reticulum.integration.mekanism;

import java.util.List;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.integration.ChemicalSupport.Entry;
import io.github.leadassimilator.reticulum.integration.ChemicalSupport.Icon;
import mekanism.api.MekanismAPI;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;

/** Mekanism's chemicals for the editor's filter picker: names, tags and icons. Client side; loaded only with Mekanism. */
public final class MekanismCatalog {
    private MekanismCatalog() {
    }

    public static List<Entry> entries() {
        return MekanismAPI.CHEMICAL_REGISTRY.holders()
                .filter(holder -> !holder.is(MekanismAPI.EMPTY_CHEMICAL_KEY))
                .map(holder -> new Entry(holder.key().identifier(), holder.value().getTextComponent().getString(),
                        holder.tags().map(TagKey::location).map(Object::toString).toList()))
                .toList();
    }

    @Nullable
    public static Icon icon(Identifier id) {
        return MekanismAPI.CHEMICAL_REGISTRY.getHolder(id)
                .filter(holder -> !holder.is(MekanismAPI.EMPTY_CHEMICAL_KEY))
                .map(holder -> new Icon(holder.value().getIcon(), holder.value().getTint()))
                .orElse(null);
    }
}
