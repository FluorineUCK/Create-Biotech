package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.block.BlockReaderBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.DataCableBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.PeripheralNetworkLimits;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Explicit event-input capture. No live world object escapes into the computation engine. */
public final class PeripheralCaptureService {
    private PeripheralCaptureService() {}

    public static DataValue.RecordValue capture(SmartBlockEntity owner, ServerPlayer actor) {
        return capture(owner, actor, null);
    }
    public static DataValue.RecordValue capture(ClusterMemberEditorAccess access, ServerPlayer actor) {
        return capture(access.computer(), actor, access);
    }
    private static DataValue.RecordValue capture(SmartBlockEntity owner, ServerPlayer actor,
        @javax.annotation.Nullable ClusterMemberEditorAccess access) {
        try { return captureAuthorized(owner, actor, access); }
        catch (CaptureException known) { throw known; }
        catch (RuntimeException | StackOverflowError failure) { throw new CaptureException("invalid", "provider_failure"); }
    }

    private static DataValue.RecordValue captureAuthorized(SmartBlockEntity owner, ServerPlayer actor,
        @javax.annotation.Nullable ClusterMemberEditorAccess access) {
        if (owner.isRemoved() || !(owner.getLevel() instanceof ServerLevel level)
            || actor.serverLevel() != level || !level.hasChunkAt(owner.getBlockPos())
            || level.getBlockEntity(owner.getBlockPos()) != owner
            || (access == null ? actor.distanceToSqr(owner.getBlockPos().getX() + .5, owner.getBlockPos().getY() + .5,
                owner.getBlockPos().getZ() + .5) > 64 : !access.stillValid(actor)))
            throw new CaptureException("unavailable", "Computer is not an accessible loaded owner");
        var computer = owner.getBehaviour(ComputerBehaviour.TYPE);
        if (computer == null || !computer.peripheralAvailable())
            throw new CaptureException("unavailable", "Computer hardware is not ready");
        if (!allowed(level, actor, owner.getBlockPos())) throw new CaptureException("invalid", "access_denied");
        var limits = CBConfigs.SERVER.biocomputing.peripherals(CBConfigs.SERVER_SPEC.isLoaded());
        return capture(new CaptureWorld() {
            @Override public boolean loaded(BlockPos pos) { return level.isOutsideBuildHeight(pos) || level.hasChunkAt(pos); }
            @Override public CableDiscovery.Cell cell(BlockPos pos) {
                if (level.isOutsideBuildHeight(pos)) return empty();
                var state = level.getBlockState(pos);
                if (state.getBlock() instanceof DataCableBlock || state.getBlock() instanceof BlockReaderBlock) {
                    if (!allowed(level, actor, pos)) throw new CaptureException("invalid", "access_denied");
                    return state.getBlock() instanceof BlockReaderBlock
                        ? new CableDiscovery.Cell(CableDiscovery.Kind.READER, state.getValue(BlockReaderBlock.FACING))
                        : DataCableBlock.cell(state);
                }
                return empty(); // Other computers and ordinary blocks never forward a scan.
            }
            @Override public long gameTime() { return level.getGameTime(); }
            @Override public String dimension() { return level.dimension().location().toString(); }
            @Override public DataValue.RecordValue componentTypes() { return ReadComponents.registry().describe(); }
            @Override public DataValue.RecordValue read(BlockPos reader, BlockPos target, Direction face) {
                if (!level.hasChunkAt(reader)) throw new CaptureException("invalid", "topology_unloaded");
                if (!allowed(level, actor, reader)) throw new CaptureException("invalid", "access_denied");
                return BlockSnapshotReader.read(level, target, face, actor, limits.snapshot());
            }
        }, owner.getBlockPos(), limits);
    }

    /** Narrow loaded-topology seam: tests exercise discovery and aggregation without a replacement interpreter. */
    interface CaptureWorld extends CableDiscovery.World {
        long gameTime();
        String dimension();
        default DataValue.RecordValue componentTypes() { return new DataValue.RecordValue(Map.of()); }
        DataValue.RecordValue read(BlockPos reader, BlockPos target, Direction face);
    }
    static DataValue.RecordValue capture(CaptureWorld world, BlockPos origin, PeripheralNetworkLimits limits) {
        try {
            var positions = CableDiscovery.scan(world, origin, limits.maxVisited(), limits.maxReaders());
            var readers = new LinkedHashMap<String, DataValue>();
            long time = world.gameTime();
            String dimension = world.dimension();
            var componentTypes = world.componentTypes();
            DataValue.RecordValue result = snapshot(time, dimension, readers, componentTypes);
            SnapshotLimits.validate(result, limits.snapshot());
            for (BlockPos reader : positions) {
                if (!world.loaded(reader)) throw new CaptureException("invalid", "topology_unloaded");
                var cell = world.cell(reader);
                if (cell.kind() != CableDiscovery.Kind.READER) throw new CaptureException("invalid", "topology_changed");
                Direction facing = cell.facing();
                BlockPos target = reader.relative(facing);
                var fields = new LinkedHashMap<>(world.read(reader, target, facing.getOpposite()).fields());
                fields.put("reader", BlockSnapshotReader.position(reader));
                fields.put("target", BlockSnapshotReader.position(target));
                readers.put(reader.getX() + "," + reader.getY() + "," + reader.getZ(), new DataValue.RecordValue(fields));
                result = snapshot(time, dimension, readers, componentTypes);
                SnapshotLimits.validate(result, limits.snapshot());
            }
            return result;
        } catch (CableDiscovery.DiscoveryException invalid) {
            throw new CaptureException("invalid", invalid.code());
        } catch (SnapshotLimits.LimitExceeded exceeded) {
            throw new CaptureException("too_large", "snapshot_limit");
        } catch (CaptureException known) {
            throw known;
        } catch (RuntimeException | StackOverflowError failure) {
            throw new CaptureException("invalid", "provider_failure");
        }
    }
    private static DataValue.RecordValue snapshot(long time, String dimension, Map<String, DataValue> readers,
                                                 DataValue.RecordValue componentTypes) {
        return new DataValue.RecordValue(Map.of("game_time", new DataValue.IntegerValue(time),
            "dimension", new DataValue.TextValue(dimension), "readers", new DataValue.RecordValue(readers),
            "component_types", componentTypes));
    }
    private static CableDiscovery.Cell empty() { return new CableDiscovery.Cell(CableDiscovery.Kind.EMPTY, null); }
    private static boolean allowed(ServerLevel level, ServerPlayer actor, BlockPos pos) {
        return level.getWorldBorder().isWithinBounds(pos) && level.mayInteract(actor, pos);
    }
    public static final class CaptureException extends IllegalArgumentException {
        private final String code;
        public CaptureException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
