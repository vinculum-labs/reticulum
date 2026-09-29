package io.github.leadassimilator.reticulum.graph;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.network.FaceKey;
import io.github.leadassimilator.reticulum.network.Ids;
import net.minecraft.core.GlobalPos;
import net.minecraft.util.StringRepresentable;

/**
 * A redstone condition on the network, a folder, a sheet or a node: it runs only while the condition allows it
 * (and its on/off button is on). {@code source} says where the signal is read.
 *
 * @param device    for {@link Source#FACE}: the device whose face is read
 * @param face      for {@link Source#FACE}: the face; empty reads the strongest face
 * @param connector for {@link Source#CONNECTOR}: the connector whose incoming signal is read
 * @param signal    for {@link Source#SIGNAL}: the network signal's name
 */
public record Redstone(Mode mode, Source source, Optional<GlobalPos> device, Optional<FaceKey> face, Optional<UUID> connector, String signal) {
    public static final Redstone IGNORED = new Redstone(Mode.IGNORED, Source.DEFAULT, Optional.empty(), Optional.empty(), Optional.empty(), "");

    public static final Codec<Redstone> CODEC = RecordCodecBuilder.create(i -> i.group(
            Mode.CODEC.optionalFieldOf("mode", Mode.IGNORED).forGetter(Redstone::mode),
            Source.CODEC.optionalFieldOf("source", Source.DEFAULT).forGetter(Redstone::source),
            GlobalPos.CODEC.optionalFieldOf("device").forGetter(Redstone::device),
            FaceKey.CODEC.optionalFieldOf("face").forGetter(Redstone::face),
            Ids.UUID_CODEC.optionalFieldOf("connector").forGetter(Redstone::connector),
            Codec.STRING.optionalFieldOf("signal", "").forGetter(Redstone::signal)
    ).apply(i, Redstone::new));

    public boolean ignored() {
        return mode == Mode.IGNORED;
    }

    public enum Mode implements StringRepresentable {
        /** Always runs (if its button is on). */
        IGNORED,
        /** Runs while the signal is 1 or more. */
        HIGH,
        /** Runs while the signal is 0. */
        LOW,
        /** Runs once per rising edge: item flows do one operation, continuous flows run for one tick. */
        PULSE;

        public static final Codec<Mode> CODEC = StringRepresentable.fromEnum(Mode::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Source implements StringRepresentable {
        /**
         * The usual place: the controller for the network, folders and sheets; for a device node, the connectors
         * that reach that device (strongest), as in XNet.
         */
        DEFAULT,
        /** The signal a connector receives from its neighbors (strongest side). */
        CONNECTOR,
        /** The signal a device gives out of one face (or its strongest face). */
        FACE,
        /** A named network signal transmitted by a Transmitter node on any sheet. */
        SIGNAL;

        public static final Codec<Source> CODEC = StringRepresentable.fromEnum(Source::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
