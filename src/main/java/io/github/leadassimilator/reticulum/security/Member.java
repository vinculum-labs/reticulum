package io.github.leadassimilator.reticulum.security;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.network.Ids;

/** A network member. The name is the last known player name, kept only for display. */
public record Member(UUID id, String name, Role role) {
    public static final Codec<Member> CODEC = RecordCodecBuilder.create(i -> i.group(
            Ids.UUID_CODEC.fieldOf("id").forGetter(Member::id),
            Codec.STRING.fieldOf("name").forGetter(Member::name),
            Role.CODEC.fieldOf("role").forGetter(Member::role)
    ).apply(i, Member::new));
}
