package io.github.leadassimilator.reticulum.block;

import java.util.EnumMap;
import java.util.Map;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/** A network block with a {@link Link} property per side (cables and connectors). */
public abstract class LinkedBlock extends NetworkBlock {
    private final Map<Direction, EnumProperty<Link>> links;

    protected LinkedBlock(Properties properties) {
        super(properties);
        this.links = linkPropertiesFor(this);
        BlockState state = stateDefinition.any();
        for (EnumProperty<Link> property : links.values()) {
            state = state.setValue(property, Link.NONE);
        }
        registerDefaultState(state);
    }

    @Override
    public Map<Direction, EnumProperty<Link>> linkProperties() {
        return links;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(linkPropertiesFor(this).values().toArray(EnumProperty[]::new));
    }

    // Called from createBlockStateDefinition before fields are set, so it must not rely on instance state.
    private static Map<Direction, EnumProperty<Link>> linkPropertiesFor(LinkedBlock block) {
        return block instanceof ConnectorBlock ? ConnectorBlock.LINKS : CableBlock.LINKS;
    }

    static Map<Direction, EnumProperty<Link>> create(Link... allowed) {
        Map<Direction, EnumProperty<Link>> map = new EnumMap<>(Direction.class);
        for (Direction dir : Direction.values()) {
            map.put(dir, EnumProperty.create(dir.getSerializedName(), Link.class, allowed));
        }
        return Map.copyOf(map);
    }
}
