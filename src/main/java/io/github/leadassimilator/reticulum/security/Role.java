package io.github.leadassimilator.reticulum.security;

import com.mojang.serialization.Codec;

import io.github.leadassimilator.reticulum.util.LowercaseName;

import net.minecraft.util.StringRepresentable;

/** Member roles, lowest to highest. A higher role can do everything a lower one can. */
public enum Role implements LowercaseName {
    VIEWER,
    USER,
    EDITOR,
    ADMIN,
    OWNER;

    public static final Codec<Role> CODEC = StringRepresentable.fromEnum(Role::values);

    public boolean atLeast(Role other) {
        return ordinal() >= other.ordinal();
    }
}
