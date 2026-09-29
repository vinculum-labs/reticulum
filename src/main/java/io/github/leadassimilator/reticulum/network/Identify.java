package io.github.leadassimilator.reticulum.network;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.GlobalPos;
import net.minecraft.util.StringRepresentable;

/**
 * Something lit up so it can be found, like the locator LED on a rack server: a connector, a device, or one face of
 * a device. It stays on until someone turns it off (or the server's auto-off time passes), and everyone who can
 * view the network sees it.
 *
 * @param since when it was turned on (wall clock, ms), for the optional auto-off
 */
public record Identify(Kind kind, Optional<UUID> connector, Optional<GlobalPos> device, Optional<FaceKey> face, int color, long since) {
    public static final Codec<Identify> CODEC = RecordCodecBuilder.create(i -> i.group(
            Kind.CODEC.fieldOf("kind").forGetter(Identify::kind),
            Ids.UUID_CODEC.optionalFieldOf("connector").forGetter(Identify::connector),
            GlobalPos.CODEC.optionalFieldOf("device").forGetter(Identify::device),
            FaceKey.CODEC.optionalFieldOf("face").forGetter(Identify::face),
            // 0 (no color given) means "the next palette color" when turned on.
            Codec.INT.optionalFieldOf("color", 0).forGetter(Identify::color),
            Codec.LONG.optionalFieldOf("since", 0L).forGetter(Identify::since)
    ).apply(i, Identify::new));

    /** Colors handed out in turn when none is chosen, so several lit things can be told apart. */
    public static final int[] PALETTE = {0xFFC830, 0x3FD0FF, 0xFF5AD2, 0x7CFF5A, 0xFF7A3A, 0xB58CFF};

    public enum Kind implements StringRepresentable {
        CONNECTOR,
        DEVICE,
        FACE;

        public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static Identify connector(UUID connector, int color) {
        return new Identify(Kind.CONNECTOR, Optional.of(connector), Optional.empty(), Optional.empty(), color, System.currentTimeMillis());
    }

    public static Identify device(GlobalPos device, int color) {
        return new Identify(Kind.DEVICE, Optional.empty(), Optional.of(device), Optional.empty(), color, System.currentTimeMillis());
    }

    public static Identify face(GlobalPos device, FaceKey face, int color) {
        return new Identify(Kind.FACE, Optional.empty(), Optional.of(device), Optional.of(face), color, System.currentTimeMillis());
    }

    /** Whether two identifies light the same thing (whatever their color). */
    public boolean sameTarget(Identify other) {
        return kind == other.kind && connector.equals(other.connector) && device.equals(other.device) && face.equals(other.face);
    }
}
