package com.nobodiiiii.createbiotech.content.biocomputing.block;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialWireMedium.Material;
import com.nobodiiiii.createbiotech.content.biocomputing.network.signal.CableSignalWorldTicks;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import java.util.EnumMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Six saved face switches govern physical topology and are displayed as cable arms. */
public final class DataCableBlock extends Block implements DataPort, IWrenchable {
    public static final MapCodec<DataCableBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
        DataCableBlock.propertiesCodec(),
        Material.CODEC.optionalFieldOf("material", Material.BASIC).forGetter(DataCableBlock::material)
    ).apply(instance, DataCableBlock::new));
    private final Material material;
    public static final IntegerProperty POWER = BlockStateProperties.POWER;
    private static final EnumMap<Direction, BooleanProperty> CONNECTIONS = new EnumMap<>(Direction.class);
    private static final EnumMap<Direction, VoxelShape> ARMS = new EnumMap<>(Direction.class);
    private static final VoxelShape HUB = box(6, 6, 6, 10, 10, 10);
    static {
        for (Direction direction : Direction.values()) {
            CONNECTIONS.put(direction, BooleanProperty.create(direction.getName()));
            ARMS.put(direction, box(direction == Direction.WEST ? 0 : 6, direction == Direction.DOWN ? 0 : 6,
                direction == Direction.NORTH ? 0 : 6, direction == Direction.EAST ? 16 : 10,
                direction == Direction.UP ? 16 : 10, direction == Direction.SOUTH ? 16 : 10));
        }
    }
    public DataCableBlock(Properties properties) { this(properties, Material.BASIC); }
    public DataCableBlock(Properties properties, Material material) {
        super(properties);
        this.material = java.util.Objects.requireNonNull(material);
        BlockState state = defaultBlockState().setValue(POWER, 0);
        for (BooleanProperty property : CONNECTIONS.values()) state = state.setValue(property, false);
        registerDefaultState(state);
    }
    @Override protected MapCodec<? extends DataCableBlock> codec() { return CODEC; }
    public Material material() { return material; }
    public static BooleanProperty connection(Direction direction) { return CONNECTIONS.get(direction); }
    /** direction points from the cable to this neighbour. */
    public static boolean connects(BlockState neighbour, Direction direction) {
        return neighbour.is(Blocks.REDSTONE_WIRE)
            || neighbour.getBlock() instanceof DataPort port && port.acceptsCable(neighbour, direction.getOpposite());
    }
    @Override public boolean acceptsCable(BlockState state, Direction side) { return state.getValue(connection(side)); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWER);
        for (BooleanProperty property : CONNECTIONS.values()) builder.add(property);
    }
    // Six saved, player-operated contacts. Never infer a grant from a neighbour update.
    public static com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery.Cell cell(BlockState state) {
        int mask=0;
        for (var dir : Direction.values()) if (state.getValue(connection(dir))) mask |= 1 << dir.ordinal();
        return new com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery.Cell(
            com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery.Kind.CABLE,null,mask);
    }
    @Override public net.minecraft.world.InteractionResult onWrenched(BlockState state, net.minecraft.world.item.context.UseOnContext ctx) {
        if (ctx.getLevel() instanceof ServerLevel level) {
            var pos=ctx.getClickedPos(); var face=ctx.getClickedFace();
            // Clicking the side of an extended arm acts on that arm, not on the air above it.
            var hit=ctx.getClickLocation().subtract(net.minecraft.world.phys.Vec3.atCenterOf(pos));
            for(var dir:Direction.values()) if(state.getValue(connection(dir)) &&
                hit.x*dir.getStepX()+hit.y*dir.getStepY()+hit.z*dir.getStepZ()>0.125001){face=dir;break;}
            var other=pos.relative(face);
            if (ctx.getPlayer()==null || !level.mayInteract(ctx.getPlayer(),pos)) return net.minecraft.world.InteractionResult.FAIL;
            var neighbor=level.isLoaded(other)?level.getBlockState(other):null;
            if(neighbor!=null && neighbor.getBlock() instanceof DataCableBlock && !level.mayInteract(ctx.getPlayer(),other))
                return net.minecraft.world.InteractionResult.FAIL;
            boolean open=!state.getValue(connection(face));
            level.setBlock(pos,state.setValue(connection(face),open),Block.UPDATE_ALL);
            if(neighbor!=null && neighbor.getBlock() instanceof DataCableBlock)
                level.setBlock(other,neighbor.setValue(connection(face.getOpposite()),open),Block.UPDATE_ALL);
            CableSignalWorldTicks.invalidate(level,pos);
            ctx.getPlayer().displayClientMessage(net.minecraft.network.chat.Component.translatable(
                open?"create_biotech.biocomputing.cable.contact_open":"create_biotech.biocomputing.cable.contact_closed",
                net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.communication.face."+face.getName())),true);
        }
        return net.minecraft.world.InteractionResult.SUCCESS;
    }
    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape shape = HUB;
        for (Direction direction : Direction.values()) if (state.getValue(connection(direction))) shape = Shapes.or(shape, ARMS.get(direction));
        return shape;
    }
    @Override public boolean isSignalSource(BlockState state) { return true; }
    @Override public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        // Saved POWER is visual metadata, not proof that a source is still loaded and authorized.
        return state.getValue(connection(direction.getOpposite())) && level instanceof ServerLevel server ? CableSignalWorldTicks.power(server, pos) : 0;
    }
    @Override public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) { return 0; }
    @Override public boolean canConnectRedstone(BlockState state, BlockGetter level, BlockPos pos, Direction side) { return side != null && state.getValue(connection(side.getOpposite())); }
    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random) {
        int power = CableSignalWorldTicks.power(level, pos);
        if (state.getValue(POWER) != power) level.setBlock(pos, state.setValue(POWER, power), Block.UPDATE_ALL);
        else level.updateNeighborsAt(pos, this);
    }
    // Only the deliberate placement of a new segment joins cables. Chunk loads, power
    // changes and ordinary neighbour updates must never heal a player's wrench cut.
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel server) || !(placer instanceof Player player) || !server.mayInteract(player, pos)) return;
        var joined = level.getBlockState(pos);
        if (!joined.is(this)) return;
        for (Direction face : Direction.values()) {
            var other = pos.relative(face);
            if (!level.isLoaded(other)) continue;
            var neighbor = level.getBlockState(other);
            if (!(neighbor.getBlock() instanceof DataCableBlock)
                || !server.mayInteract(player, other)) continue;
            joined = joined.setValue(connection(face), true);
            var reverse = connection(face.getOpposite());
            if (!neighbor.getValue(reverse)) level.setBlock(other, neighbor.setValue(reverse, true), Block.UPDATE_ALL);
            CableSignalWorldTicks.invalidate(server, other);
        }
        if (level.setBlock(pos, joined, Block.UPDATE_ALL)) CableSignalWorldTicks.invalidate(server, pos);
    }
    @Override public void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        if (!old.is(this) && level instanceof ServerLevel server) {
            CableSignalWorldTicks.track(server,pos);
            CableSignalWorldTicks.invalidate(server, pos);
            for (var direction : Direction.values()) CableSignalWorldTicks.invalidate(server, pos.relative(direction));
        }
        super.onPlace(state, level, pos, old, moving);
    }
    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!next.is(this) && level instanceof ServerLevel server) {
            CableSignalWorldTicks.untrack(server, pos);
            CableSignalWorldTicks.invalidate(server, pos);
            // Removing a joined segment must not expose a live contact to a dust/device
            // placed in its old position. Other contacts on the surviving wire stay as set.
            for (Direction face : Direction.values()) {
                var other = pos.relative(face);
                if (!state.getValue(connection(face)) || !server.isLoaded(other)) continue;
                // An explicitly pre-connected replacement (e.g. a saved structure) retains this link.
                if (next.getBlock() instanceof DataCableBlock && next.getValue(connection(face))) continue;
                var neighbor = server.getBlockState(other);
                var reverse = connection(face.getOpposite());
                if (neighbor.getBlock() instanceof DataCableBlock && neighbor.getValue(reverse)) {
                    server.setBlock(other, neighbor.setValue(reverse, false), Block.UPDATE_ALL);
                    CableSignalWorldTicks.invalidate(server, other);
                }
            }
        }
        super.onRemove(state, level, pos, next, moving);
    }
}
