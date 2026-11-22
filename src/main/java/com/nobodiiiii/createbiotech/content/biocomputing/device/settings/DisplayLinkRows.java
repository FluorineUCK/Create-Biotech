package com.nobodiiiii.createbiotech.content.biocomputing.device.settings;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayBoardTarget;
import com.simibubi.create.content.trains.display.FlapDisplayBlock;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;

/** Settings on a provably bound, loaded Create board. Native sources still populate the board. */
public final class DisplayLinkRows {
    static final String FIELD = "start_row";
    private static final String MANAGED = "BiocomputingDisplayLinkRow";
    private static final String NATIVE_RESERVATIONS = "DisplayLink";
    // Same walk bound as Create 6.0.10 FlapDisplayBlockEntity.getController().
    private static final int NATIVE_CONTROLLER_STEPS = 64;
    public interface Guarded {}

    private DisplayLinkRows() {}

    record Binding(ServerLevel level, DisplayLinkBlockEntity link, BlockPos source, Block sourceBlock,
                   Object sourceEntity, DisplaySource selected, BlockPos endpoint, FlapDisplayBlockEntity tile,
                   FlapDisplayBlockEntity board, int rows, int width, UUID space) {
        DataValue.RecordValue value() {
            return ComponentSettingsProtocol.record(Map.of("source", pos(source), "target", pos(endpoint),
                "controller", pos(board.getBlockPos()), "rows", ComponentSettingsProtocol.number(rows)));
        }
    }

    static Binding observe(ServerLevel level, DisplayLinkBlockEntity link) {
        if (!(link instanceof Guarded) || link.isRemoved() || link.activeSource == null) return null;
        var at = link.getBlockPos();
        UUID space = SubLevelCompat.getSpaceId(level, at);
        var own = loaded(level, at, space);
        if (own == null || own.getBlockEntity(at) != link) return null;
        var source = link.getSourcePosition();
        var endpoint = link.getTargetPosition();
        if (source.equals(endpoint) || endpoint.equals(at)) return null;
        var upstream = loaded(level, source, space);
        var downstream = loaded(level, endpoint, space);
        if (upstream == null || downstream == null
            || !AllBlocks.DISPLAY_BOARD.has(downstream.getBlockState(endpoint))
            || !(downstream.getBlockEntity(endpoint) instanceof FlapDisplayBlockEntity tile)
            || tile.isRemoved() || tile.isVirtual()
            || !DisplaySource.getAll(level, source).contains(link.activeSource)) return null;
        var nativeTarget = DisplayTarget.get(level, endpoint);
        if (nativeTarget == null || nativeTarget.getClass() != DisplayBoardTarget.class) return null;
        var board = controller(level, endpoint, space);
        if (board == null || board.xSize < 1 || board.ySize < 1 || board.manualLines == null) return null;
        int rows = board.manualLines.length;
        if (rows != 2L * board.ySize || rows != board.getLines().size()
            || link.targetLine < 0 || link.targetLine >= rows) return null;
        return new Binding(level, link, source, upstream.getBlockState(source).getBlock(), upstream.getBlockEntity(source),
            link.activeSource, endpoint, tile, board, rows, board.xSize, space);
    }

    static String canSet(Binding binding, int row, ServerPlayer actor) {
        if (actor == null || !binding.level().mayInteract(actor, binding.endpoint())
            || !binding.level().mayInteract(actor, binding.board().getBlockPos())) return "access_denied";
        return safeRows(binding, row, false) ? "ok" : "busy";
    }

    static boolean set(Binding expected, int row, ServerPlayer actor) {
        var link = expected.link();
        var actual = observe(expected.level(), link);
        if (!expected.equals(actual) || !canSet(actual, row, actor).equals("ok")) return false;
        var marker = new CompoundTag();
        marker.putInt("Row", row);
        marker.putLong("Source", actual.source().asLong());
        marker.putLong("Target", actual.endpoint().asLong());
        marker.putLong("Board", actual.board().getBlockPos().asLong());
        DisplayTarget.reserve(row, actual.board(), new DisplayLinkContext(actual.level(), link));
        actual.board().setChanged();
        link.getPersistentData().put(MANAGED, marker);
        // The native row-only configuration updates and syncs the link, not its old rendered text.
        link.targetLine = row;
        link.notifyUpdate();
        return true;
    }

    /** For programs' moves only: the native target unconditionally overwrites its starting row. */
    public static boolean mayTransfer(DisplayLinkContext context, DisplayTarget target, int row) {
        if (!(context.level() instanceof ServerLevel level)) return true;
        var link = context.blockEntity();
        var data = link.getPersistentData();
        if (!data.contains(MANAGED)) return true;
        if (!data.contains(MANAGED, Tag.TAG_COMPOUND)) return false;
        var marker = data.getCompound(MANAGED);
        if (!marker.contains("Row", Tag.TAG_INT) || !marker.contains("Target", Tag.TAG_LONG)
            || !marker.contains("Source", Tag.TAG_LONG) || !marker.contains("Board", Tag.TAG_LONG)) return false;
        if (row != marker.getInt("Row")) return true; // Native user selected a different start row.
        if (link.getSourcePosition().asLong() != marker.getLong("Source")
            || link.getTargetPosition().asLong() != marker.getLong("Target")) return false;
        if (target.getClass() != DisplayBoardTarget.class) return false;
        var binding = observe(level, link);
        return binding != null && binding.board().getBlockPos().asLong() == marker.getLong("Board")
            && safeRows(binding, row, true);
    }

    private static boolean safeRows(Binding binding, int row, boolean claimed) {
        // Create skips reservation on index zero, so an empty first row cannot prove that it is unclaimed.
        if (row < 1 || row >= binding.rows()) return false;
        var board = binding.board();
        var data = board.getPersistentData();
        if (data.contains(NATIVE_RESERVATIONS) && !data.contains(NATIVE_RESERVATIONS, Tag.TAG_COMPOUND)) return false;
        var reservations = data.getCompound(NATIVE_RESERVATIONS);
        long ours = binding.link().getBlockPos().asLong();
        for (int i = row; i < binding.rows(); i++) {
            String key = "Line" + i;
            boolean reserved = reservations.contains(key);
            if (reserved && !reservations.contains(key, Tag.TAG_LONG)) return false;
            boolean foreign = reserved && reservations.getLong(key) != ours;
            if (i == row) {
                if (board.manualLines[i] || foreign || (claimed && !reserved)) return false;
            } else {
                if (foreign) {
                    // Native isReserved would otherwise load this chunk or discard the mark.
                    var other = BlockPos.of(reservations.getLong(key));
                    var chunk = loaded(binding.level(), other, binding.space());
                    return chunk != null && AllBlocks.DISPLAY_LINK.has(chunk.getBlockState(other));
                }
                if (board.manualLines[i]) return true;
                if (reserved) return false; // Do not erase an earlier starting row of this link.
            }
            if (!claimed) {
                if (i == binding.link().targetLine) return false; // Keep the old starting row out of the writable span.
                var layout = board.getLines().get(i);
                if (!layout.isLayout("Default") || layout.getSections().isEmpty()
                    || layout.getSections().stream().anyMatch(section -> section.getText() != null
                        && !section.getText().getString().isEmpty())) return false;
            }
        }
        return true;
    }

    /** Resolve the native controller path using already-loaded, same-space cells only. */
    private static FlapDisplayBlockEntity controller(ServerLevel level, BlockPos tile, UUID space) {
        var cursor = tile;
        for (int i = 0; i < NATIVE_CONTROLLER_STEPS; i++) {
            var chunk = loaded(level, cursor, space);
            if (chunk == null || !AllBlocks.DISPLAY_BOARD.has(chunk.getBlockState(cursor))
                || !(chunk.getBlockEntity(cursor) instanceof FlapDisplayBlockEntity part)
                || part.isRemoved() || part.isVirtual()) return null;
            if (part.isController) return part;
            var state = chunk.getBlockState(cursor);
            if (state.getValue(FlapDisplayBlock.UP)) {
                cursor = cursor.above();
                continue;
            }
            var next = cursor.relative(state.getValue(FlapDisplayBlock.HORIZONTAL_FACING).getClockWise());
            var neighbour = loaded(level, next, space);
            if (neighbour == null) return null;
            if (!neighbour.getBlockState(next).getOptionalValue(FlapDisplayBlock.UP).orElse(true)) {
                cursor = next;
                continue;
            }
            return null;
        }
        return null;
    }

    private static LevelChunk loaded(ServerLevel level, BlockPos pos, UUID space) {
        if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)
            || !level.shouldTickBlocksAt(pos) || !SubLevelCompat.matchesSpace(level, pos, space)) return null;
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static DataValue.RecordValue pos(BlockPos pos) {
        return ComponentSettingsProtocol.record(Map.of("x", ComponentSettingsProtocol.number(pos.getX()),
            "y", ComponentSettingsProtocol.number(pos.getY()), "z", ComponentSettingsProtocol.number(pos.getZ())));
    }
}
