package io.github.leadassimilator.reticulum.block;

import java.util.Map;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity;
import io.github.leadassimilator.reticulum.item.NetworkToolItem;
import io.github.leadassimilator.reticulum.protocol.Screens;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Base for cables, connectors and controllers. Hands placement, removal and shape updates to {@link Topology}. */
public abstract class NetworkBlock extends BaseEntityBlock {
    /** The side the player clicked against, carried from placement-state lookup to setPlacedBy on the same thread. */
    private static final ThreadLocal<Direction> PLACED_AGAINST = new ThreadLocal<>();

    protected NetworkBlock(Properties properties) {
        super(properties);
    }

    /** Per-side link properties, or an empty map for blocks that render the same on every side. */
    public Map<Direction, EnumProperty<Link>> linkProperties() {
        return Map.of();
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        PLACED_AGAINST.set(context.replacingClickedOnBlock() ? null : context.getClickedFace().getOpposite());
        return defaultBlockState();
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        Direction against = PLACED_AGAINST.get();
        PLACED_AGAINST.remove();
        if (level instanceof ServerLevel serverLevel) {
            Topology.onPlaced(serverLevel, pos, against, placer instanceof ServerPlayer player ? player : null);
        }
    }

    /**
     * Placed any way at all (/setblock, /clone, a structure): joins the network on the next tick. Placed by a player,
     * {@link #setPlacedBy} has already done so and the tick finds nothing to do.
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide && !oldState.is(state.getBlock())) {
            level.scheduleTick(pos, this, 1);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        Topology.onPlaced(level, pos, null, null);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (state.is(newState.getBlock()) || !(level instanceof ServerLevel serverLevel)) {
            super.onRemove(state, level, pos, newState, movedByPiston);
            return;
        }
        Topology.Removed removed = level.getBlockEntity(pos) instanceof NetworkBlockEntity be ? Topology.capture(be) : null;
        // Moved by a piston or contraption (Create), not broken: it comes back elsewhere with its data, so a connector
        // keeps its record (its name, its devices) meanwhile, and arriving it isn't taken for a copy.
        if (movedByPiston && removed != null && !removed.controller() && level.getBlockEntity(pos) instanceof NetworkBlockEntity moving) {
            Topology.noteMoving(serverLevel, moving);
            removed = new Topology.Removed(removed.networkId(), false, null);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (removed != null) {
            Topology.onRemoved(serverLevel, pos, removed);
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (level instanceof ServerLevel serverLevel) {
            return Topology.computeState(serverLevel, pos, state);
        }
        return state;
    }

    /**
     * Like a chest: right-click opens the screen whatever is in hand, and sneak + right-click places the held block
     * (vanilla skips the block when sneaking with an item). The network tool still acts on the block itself.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hitResult) {
        return stack.getItem() instanceof NetworkToolItem ? ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION
                : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (player instanceof ServerPlayer serverPlayer) {
            Screens.open(serverPlayer, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
