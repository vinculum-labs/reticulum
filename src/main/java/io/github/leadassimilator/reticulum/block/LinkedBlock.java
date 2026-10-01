package io.github.leadassimilator.reticulum.block;

import java.util.EnumMap;
import java.util.Map;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity;
import io.github.leadassimilator.reticulum.item.FacadeItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.phys.BlockHitResult;
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

    // ---------------------------------------------------------------- facades

    @Nullable
    private static BlockState facadeAt(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof NetworkBlockEntity be ? be.facade() : null;
    }

    /** Right-click with a facade puts it on (handing back the one it had). */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hitResult) {
        BlockState facade = FacadeItem.state(stack);
        if (facade == null) {
            return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
        }
        if (!(level.getBlockEntity(pos) instanceof NetworkBlockEntity be) || facade.equals(be.facade())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide && !mayChangeFacade(level, pos, player)) {
            return ItemInteractionResult.CONSUME;
        }
        if (!level.isClientSide) {
            BlockState old = be.facade();
            be.setFacade(facade);
            level.playSound(null, pos, facade.getSoundType(level, pos, player).getPlaceSound(), SoundSource.BLOCKS, 1f, 1f);
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
                if (old != null) {
                    net.neoforged.neoforge.items.ItemHandlerHelper.giveItemToPlayer(player, FacadeItem.of(old, 1));
                }
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Takes the facade off (the network tool in Configure mode, sneaking), telling the player how it went; returns
     * whether there was one (so the tool does nothing else).
     */
    public static boolean removeFacade(Level level, BlockPos pos, Player player) {
        if (!(level.getBlockEntity(pos) instanceof NetworkBlockEntity be) || be.facade() == null) {
            return false;
        }
        if (!mayChangeFacade(level, pos, player)) {
            return true;
        }
        BlockState old = be.facade();
        be.setFacade(null);
        if (!player.getAbilities().instabuild) {
            net.neoforged.neoforge.items.ItemHandlerHelper.giveItemToPlayer(player, FacadeItem.of(old, 1));
        }
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.reticulum.facade_removed"), true);
        return true;
    }

    /** Facades on a network's blocks are its editors' to change (and to take: they are items). */
    private static boolean mayChangeFacade(Level level, BlockPos pos, Player player) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel server) || !(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return true;
        }
        var network = io.github.leadassimilator.reticulum.topology.Topology.network(server, pos);
        return network == null
                || io.github.leadassimilator.reticulum.security.Access.require(serverPlayer, network, io.github.leadassimilator.reticulum.security.Role.EDITOR) != null;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && facadeAt(level, pos) != null) {
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), FacadeItem.of(facadeAt(level, pos), 1));
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public SoundType getSoundType(BlockState state, LevelReader level, BlockPos pos, @Nullable Entity entity) {
        BlockState facade = facadeAt(level, pos);
        return facade != null ? facade.getSoundType(level, pos, entity) : super.getSoundType(state, level, pos, entity);
    }

    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        BlockState facade = facadeAt(level, pos);
        return facade != null ? facade.getLightEmission(level, pos) : super.getLightEmission(state, level, pos);
    }

    /** A solid facade hides the neighbor's face against it, as the real block would. */
    @Override
    public boolean hidesNeighborFace(BlockGetter level, BlockPos pos, BlockState state, BlockState neighborState, Direction dir) {
        BlockState facade = facadeAt(level, pos);
        return facade != null && facade.canOcclude();
    }

    /** Connected textures and the like see the facade. */
    @Override
    public BlockState getAppearance(BlockState state, BlockAndTintGetter level, BlockPos pos, Direction side, @Nullable BlockState queryState,
            @Nullable BlockPos queryPos) {
        BlockState facade = facadeAt(level, pos);
        return facade != null ? facade : state;
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
