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
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A network block with a {@link Link} property per side (cables and connectors). */
public abstract class LinkedBlock extends NetworkBlock {
    /** How far it was turned since its per-side data (joints cut, device sides off) was written. */
    public static final EnumProperty<Orientation> ORIENTATION = EnumProperty.create("orientation", Orientation.class);

    private final Map<Direction, EnumProperty<Link>> links;

    protected LinkedBlock(Properties properties) {
        super(properties);
        this.links = linkPropertiesFor(this);
        BlockState state = stateDefinition.any().setValue(ORIENTATION, Orientation.TURN_0);
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
        // Checked here too: a facade given by command could carry any block (one drawing nothing, but solid).
        if (facade == null || !FacadeItem.canBeFacade(facade)) {
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
        // Moved (a contraption), not broken: the facade travels in its data and comes back with it.
        if (!state.is(newState.getBlock()) && !movedByPiston && !level.isClientSide && facadeAt(level, pos) != null) {
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

    // ---------------------------------------------------------------- shape

    /** The drawn shape per state (core, arms and plugs), built once each. */
    private final Map<BlockState, VoxelShape> shapes = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * What is drawn, so a look through the empty parts reaches the block behind; a full block under a facade. Holding the
     * network tool it is a full block too, so a side with no arm drawn (a disconnected joint) can still be aimed at.
     */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (context instanceof EntityCollisionContext entity && entity.getEntity() instanceof Player player
                && (player.getMainHandItem().getItem() instanceof io.github.leadassimilator.reticulum.item.NetworkToolItem
                        || player.getOffhandItem().getItem() instanceof io.github.leadassimilator.reticulum.item.NetworkToolItem)) {
            return Shapes.block();
        }
        return getCollisionShape(state, level, pos, context);
    }

    /** Walked into as drawn (whatever is held), or as a full block under a facade. */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return facadeAt(level, pos) != null ? Shapes.block() : shapes.computeIfAbsent(state, this::drawnShape);
    }

    private VoxelShape drawnShape(BlockState state) {
        boolean connector = this instanceof ConnectorBlock;
        VoxelShape shape = connector ? Block.box(4, 4, 4, 12, 12, 12) : Block.box(6, 6, 6, 10, 10, 10);
        for (Map.Entry<Direction, EnumProperty<Link>> entry : links.entrySet()) {
            Direction side = entry.getKey();
            switch (state.getValue(entry.getValue())) {
                case CABLE -> shape = Shapes.or(shape, part(side, 6, 10, 0, connector ? 4 : 6));
                case DEVICE -> shape = Shapes.or(shape, part(side, 3, 13, 0, 1), part(side, 5, 11, 1, 4));
                case NONE -> {
                }
            }
        }
        return shape.optimize();
    }

    /**
     * A box centered on the side's axis ({@code lo} to {@code hi} across it, in pixels), reaching from {@code near} to
     * {@code far} pixels in from that side, as the models' parts drawn facing north are turned to face it.
     */
    private static VoxelShape part(Direction side, double lo, double hi, double near, double far) {
        double[] min = {lo, lo, lo};
        double[] max = {hi, hi, hi};
        int axis = side.getAxis().ordinal();
        if (side.getAxisDirection() == Direction.AxisDirection.NEGATIVE) {
            min[axis] = near;
            max[axis] = far;
        } else {
            min[axis] = 16 - far;
            max[axis] = 16 - near;
        }
        return Block.box(min[0], min[1], min[2], max[0], max[1], max[2]);
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
        builder.add(ORIENTATION);
    }

    // Turned or mirrored (a structure, a schematic, a contraption): its sides go with it, and so does its per-side data,
    // read through its orientation.
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return turned(state, state.getValue(ORIENTATION).rotate(rotation), rotation::rotate);
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return turned(state, state.getValue(ORIENTATION).mirror(mirror), mirror::mirror);
    }

    private BlockState turned(BlockState state, Orientation orientation, java.util.function.UnaryOperator<Direction> side) {
        BlockState out = state.setValue(ORIENTATION, orientation);
        for (Map.Entry<Direction, EnumProperty<Link>> entry : links.entrySet()) {
            out = out.setValue(links.get(side.apply(entry.getKey())), state.getValue(entry.getValue()));
        }
        return out;
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
