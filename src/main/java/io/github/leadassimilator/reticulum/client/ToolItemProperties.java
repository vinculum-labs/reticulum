package io.github.leadassimilator.reticulum.client;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;

import io.github.leadassimilator.reticulum.item.NetworkToolItem;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RegisterRangeSelectItemModelPropertyEvent;

/** What the network tool's look follows (its item definition dispatches on these; see ModModels). */
public final class ToolItemProperties {
    private ToolItemProperties() {
    }

    /** {@code reticulum:mode}: the tool's mode, 0 Configure, 1/3 Link, 2/3 Identify, 1 Status. */
    public record Mode() implements RangeSelectItemModelProperty {
        public static final MapCodec<Mode> CODEC = MapCodec.unit(new Mode());

        @Override
        public float get(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity entity, int seed) {
            return NetworkToolItem.mode(stack).ordinal() / 3f;
        }

        @Override
        public MapCodec<Mode> type() {
            return CODEC;
        }
    }

    /** {@code reticulum:alarm}: an alarm going off on its network (or any of yours), 0.5 a warning, 1 critical. */
    public record Alarm() implements RangeSelectItemModelProperty {
        public static final MapCodec<Alarm> CODEC = MapCodec.unit(new Alarm());

        @Override
        public float get(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity entity, int seed) {
            return AlarmClient.toolProperty(stack);
        }

        @Override
        public MapCodec<Alarm> type() {
            return CODEC;
        }
    }

    public static void register(RegisterRangeSelectItemModelPropertyEvent event) {
        event.register(NetworkToolItem.MODE_PROPERTY, Mode.CODEC);
        event.register(NetworkToolItem.ALARM_PROPERTY, Alarm.CODEC);
    }
}
