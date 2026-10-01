package io.github.leadassimilator.reticulum.util;

import java.util.Locale;

import net.minecraft.util.StringRepresentable;

/** An enum saved and shown by its constant's name in lower case ({@code PER_TYPE} as {@code per_type}). */
public interface LowercaseName extends StringRepresentable {
    String name();

    @Override
    default String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
