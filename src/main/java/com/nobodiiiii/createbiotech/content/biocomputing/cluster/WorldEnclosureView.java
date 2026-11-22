package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import com.nobodiiiii.createbiotech.content.biocomputing.block.EnclosureController;
import com.nobodiiiii.createbiotech.foundation.block.CBMultiBlockLifecycle;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.EnclosureScanner.Kind.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.EnclosureScanner.Cell;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.EnclosureScanner.Kind;

/** World state classification without reading BlockEntity data or restoring Computer state. */
public final class WorldEnclosureView implements EnclosureScanner.View {
    // Stable physical IDs survive the host migration; no dependency on old production classes.
    private static final ResourceLocation COMPUTER = ResourceLocation.parse("create_biotech:computer");
    private static final ResourceLocation CASING_ID = ResourceLocation.parse("create_biotech:computer_casing");
    private static final ResourceLocation GLASS_ID = ResourceLocation.parse("create_biotech:transparent_computer_casing");
    // These finite immutable values hold no position, world state, loading or authorization decision.
    private static final Cell AIR_CELL = new Cell(AIR, null), CASING_CELL = new Cell(CASING, null),
        INTERNAL_CELL = new Cell(INTERNAL, null), ILLEGAL_CELL = new Cell(ILLEGAL, null),
        UNLOADED_CELL = new Cell(UNLOADED, null), FOREIGN_CELL = new Cell(FOREIGN, null);
    private static final Cell[] CONTROLLER_CELLS = directionalCells(CONTROLLER_BLOCK),
        LOWER_CELLS = directionalCells(COMPUTER_LOWER), UPPER_CELLS = directionalCells(COMPUTER_UPPER);
    private static Cell[] directionalCells(Kind kind) {
        var directions = Direction.values();
        var cells = new Cell[directions.length];
        for (var direction : directions) cells[direction.ordinal()] = new Cell(kind, direction);
        return cells;
    }
    private final Level level;
    private final BlockPos origin;
    private final Block computer;
    private final Block casing;
    private final Block glass;

    public WorldEnclosureView(Level level, BlockPos origin) {
        this.level = level;
        this.origin = origin.immutable();
        // Registration is stable for this view; only block identities, never world state, are retained.
        computer = BuiltInRegistries.BLOCK.getOptional(COMPUTER).orElse(null);
        casing = BuiltInRegistries.BLOCK.getOptional(CASING_ID).orElse(null);
        glass = BuiltInRegistries.BLOCK.getOptional(GLASS_ID).orElse(null);
    }
    public boolean loaded(BlockPos position) { return !level.isInWorldBounds(position) || CBMultiBlockLifecycle.isLoaded(level, position); }
    public boolean sameSpace(BlockPos origin, BlockPos position) {
        return !level.isInWorldBounds(position) || SubLevelCompat.sameSpace(level, origin, position);
    }
    @Override public Cell observe(BlockPos origin, BlockPos position) {
        // cell() already performs a fresh loaded/space check. Do not perform the same chunk
        // and sublevel lookups again in the scanner. A different requested origin retains
        // both space checks, matching the original View contract.
        return this.origin.equals(origin) ? cell(position) : EnclosureScanner.View.super.observe(origin, position);
    }
    public Cell cell(BlockPos position) {
        if (!level.isInWorldBounds(position)) return ILLEGAL_CELL;
        if (!loaded(position)) return UNLOADED_CELL;
        if (!sameSpace(origin, position)) return FOREIGN_CELL;
        return classify(level.getBlockState(position));
    }

    // The batch assumes vanilla server chunk/height semantics. Custom level views keep their per-position reads.
    boolean canReadSection(BlockPos requestedOrigin) {
        return origin.equals(requestedOrigin) && level.getClass() == net.minecraft.server.level.ServerLevel.class
            && !level.isDebug() && !net.neoforged.fml.ModList.get().isLoaded("sable");
    }

    /** All positions share a 16-cube. Bounds, chunk and Sable space are column/section properties;
     * resolve them afresh at EACH barrier. The lookup never tickets an absent chunk. */
    boolean matchesSection(BlockPos[] positions, Cell[] expected) {
        return matchesSection(positions, expected, null);
    }

    // A representative is sufficient, not necessary, for a match. Only stable registered
    // block states whose classification needs no callbacks may bypass classify(). In
    // particular, neither an arbitrary "internal" block nor a controller is a representative.
    Map<Cell, BlockState> representatives() {
        var states = new IdentityHashMap<Cell, BlockState>();
        var air = Blocks.AIR.defaultBlockState();
        if (air.isAir() && !(air.getBlock() instanceof EnclosureController)) states.put(AIR_CELL, air);
        if (casing != null && !(casing instanceof EnclosureController)) states.put(CASING_CELL, casing.defaultBlockState());
        if (computer != null && computer.getClass() == com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock.class)
            for (var state : computer.getStateDefinition().getPossibleStates()) states.put(classify(state), state);
        return states;
    }

    boolean matchesSection(BlockPos[] positions, Cell[] expected, BlockState[] representatives) {
        var first = positions[0];
        Cell uniform = null;
        net.minecraft.world.level.chunk.LevelChunk chunk = null;
        if (!level.isInWorldBounds(first)) uniform = ILLEGAL_CELL;
        else if (!loaded(first)) uniform = UNLOADED_CELL;
        else if (!sameSpace(origin, first)) uniform = FOREIGN_CELL;
        else chunk = ((net.minecraft.server.level.ServerLevel)level).getChunkSource()
            .getChunkNow(first.getX() >> 4, first.getZ() >> 4);
        if (uniform != null) {
            for (var cell : expected) if (!uniform.equals(cell)) return false;
            return true;
        }
        // A modded chunk or a full chunk in transition may have a custom block-state view.
        if (chunk == null || chunk.getClass() != net.minecraft.world.level.chunk.LevelChunk.class)
            return individually(positions, expected);
        var section = chunk.getSection(chunk.getSectionIndex(first.getY()));
        if (section.hasOnlyAir()) {
            for (var cell : expected) if (!AIR_CELL.equals(cell)) return false;
            return true;
        }
        for (int i=0; i<positions.length; i++) {
            var pos = positions[i];
            var state = section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
            if (representatives != null && state == representatives[i]) continue;
            // Never hold a borrowed section across an unknown provider callback.
            if (state.getBlock() instanceof EnclosureController && !(state.getBlock() instanceof
                com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlock))
                return individually(positions, expected);
            if (!expected[i].equals(classify(state))) return false;
        }
        return true;
    }

    private boolean individually(BlockPos[] positions, Cell[] expected) {
        for (int i=0; i<positions.length; i++) if (!expected[i].equals(cell(positions[i]))) return false;
        return true;
    }

    private Cell classify(net.minecraft.world.level.block.state.BlockState state) {
        var block = state.getBlock();
        if (block instanceof EnclosureController controller) {
            var facing = controller.outward(state);
            if (facing == null) throw new IllegalArgumentException("missing_facing");
            return CONTROLLER_CELLS[facing.ordinal()];
        }
        if (block == computer) {
            if (!state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
                return ILLEGAL_CELL;
            var cells = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? LOWER_CELLS : UPPER_CELLS;
            return cells[state.getValue(BlockStateProperties.HORIZONTAL_FACING).ordinal()];
        }
        if (block == casing || block == glass || block instanceof com.nobodiiiii.createbiotech.content.biocomputing.block.SerialPanelBlock) return CASING_CELL;
        if (state.isAir()) return AIR_CELL;
        // Ordinary contents never authorize the shell, and never need a production-component whitelist.
        return INTERNAL_CELL;
    }
}
