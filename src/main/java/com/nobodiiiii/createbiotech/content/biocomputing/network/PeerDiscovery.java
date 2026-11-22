package com.nobodiiiii.createbiotech.content.biocomputing.network;

import com.nobodiiiii.createbiotech.content.biocomputing.block.DataCableBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.DataPort;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.*;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Explicit, complete, loaded-only directory snapshots. No routing authority or world references escape. */
public final class PeerDiscovery {
    private PeerDiscovery() {}

    /** Current menu entry point. The caller also enforces its host's hardware availability. */
    public static DataValue.RecordValue discover(SmartBlockEntity owner, ServerPlayer actor) {
        return discover(owner, actor, EnumSet.allOf(Direction.class));
    }
    public static DataValue.RecordValue discover(SmartBlockEntity owner, ServerPlayer actor, Set<Direction> sourceFaces) {
        authorize(owner, actor);
        return discoverAuthorized(owner, actor, sourceFaces);
    }
    public static DataValue.RecordValue discover(ClusterMemberEditorAccess access, ServerPlayer actor) {
        authorize(access, actor);
        return discoverAuthorized(access.computer(), actor, EnumSet.allOf(Direction.class));
    }
    /** Physical self-network input, without invoking any legacy endpoint protocol or execution view. */
    public static DataValue.RecordValue topology(SmartBlockEntity owner, ServerPlayer actor) {
        authorize(owner, actor);
        return topologyAuthorized(owner, actor);
    }
    public static DataValue.RecordValue topology(ClusterMemberEditorAccess access, ServerPlayer actor) {
        authorize(access, actor);
        return topologyAuthorized(access.computer(), actor);
    }
    private static DataValue.RecordValue topologyAuthorized(SmartBlockEntity owner, ServerPlayer actor) {
        ServerLevel level = (ServerLevel)owner.getLevel();
        var limits = CBConfigs.SERVER.biocomputing.communication(CBConfigs.SERVER_SPEC.isLoaded());
        return topology(new LoadedEndpointWorld(level, owner.getBlockPos(), pos -> permitted(level, actor, pos)), owner.getBlockPos(), limits);
    }
    private static DataValue.RecordValue discoverAuthorized(SmartBlockEntity owner, ServerPlayer actor, Set<Direction> sourceFaces) {
        ServerLevel level = (ServerLevel)owner.getLevel();
        var limits = CBConfigs.SERVER.biocomputing.communication(CBConfigs.SERVER_SPEC.isLoaded());
        return discover(new LoadedEndpointWorld(level, owner.getBlockPos(), pos -> permitted(level, actor, pos), sourceFaces), owner.getBlockPos(), limits);
    }

    /** No offline actor, arbitrary coordinates, chunk loading, or remote memory authorization. */
    public static void authorize(SmartBlockEntity owner, ServerPlayer actor) {
        try { authorizeOwner(owner, actor); }
        catch (DiscoveryException known) { throw known; }
        catch (RuntimeException | StackOverflowError failure) { throw new DiscoveryException("invalid", "provider_failure"); }
    }
    /** A live physical controller session changes only the player reach anchor, not cable/target permissions. */
    public static void authorize(ClusterMemberEditorAccess access, ServerPlayer actor) {
        try {
            if (!access.stillValid(actor)) throw new DiscoveryException("unavailable", "owner_unavailable");
        } catch (DiscoveryException known) { throw known; }
        catch (RuntimeException | StackOverflowError failure) { throw new DiscoveryException("invalid", "provider_failure"); }
    }
    private static void authorizeOwner(SmartBlockEntity owner, ServerPlayer actor) {
        if (owner == null || owner.isRemoved() || !(owner.getLevel() instanceof ServerLevel level)
            || actor.serverLevel() != level || !level.hasChunkAt(owner.getBlockPos())
            || actor.distanceToSqr(owner.getBlockPos().getX()+.5, owner.getBlockPos().getY()+.5, owner.getBlockPos().getZ()+.5) > 64)
            throw new DiscoveryException("unavailable", "owner_unavailable");
        if (!permitted(level, actor, owner.getBlockPos())) throw new DiscoveryException("invalid", "access_denied");
        if (level.getBlockEntity(owner.getBlockPos()) != owner) throw new DiscoveryException("unavailable", "owner_unavailable");
    }
    private static boolean permitted(ServerLevel level, ServerPlayer actor, BlockPos pos) {
        return level.getWorldBorder().isWithinBounds(pos) && level.mayInteract(actor, pos);
    }
    interface DiscoveryWorld extends EndpointDirectory.World {
        boolean allowed(BlockPos pos);
        @Nullable CommunicationEndpoint endpoint(BlockPos pos);
        long gameTime();
        String dimension();
    }
    static DataValue.RecordValue topology(DiscoveryWorld world, BlockPos origin, CommunicationNetworkLimits limits) {
        try {
            var segments = CableDiscovery.segments(guarded(world), origin, limits.maxVisited(), limits.maxEndpoints());
            return new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("biocomputing:physical_cable"),
                "dimension", new DataValue.TextValue(world.dimension()), "game_time", new DataValue.IntegerValue(world.gameTime()),
                "cable_segments", new DataValue.ListValue(segments.stream().<DataValue>map(segment -> segmentView(segment, origin)).toList())));
        } catch (CableDiscovery.DiscoveryException invalid) {
            throw new DiscoveryException("invalid", invalid.code());
        } catch (DiscoveryException known) { throw known; }
        catch (RuntimeException | StackOverflowError failure) { throw new DiscoveryException("invalid", "provider_failure"); }
    }

    private static EndpointDirectory.World guarded(DiscoveryWorld world) {
        return new EndpointDirectory.World() {
            public boolean loaded(BlockPos pos) { return world.loaded(pos); }
            private void check(BlockPos pos) {
                if (!world.loaded(pos)) throw new DiscoveryException("invalid", "topology_unloaded");
                if (!world.allowed(pos)) throw new DiscoveryException("invalid", "access_denied");
            }
            public CableDiscovery.Cell cell(BlockPos pos) { check(pos); return world.cell(pos); }
            public boolean acceptsCable(BlockPos pos, Direction side) { check(pos); return world.acceptsCable(pos, side); }
            public CommunicationEndpoint endpoint(BlockPos pos) { check(pos); return world.endpoint(pos); }
            public CommunicationEndpoint endpoint(BlockPos pos, Direction side) { check(pos); return world.endpoint(pos, side); }
        };
    }

    static DataValue.RecordValue discover(DiscoveryWorld world, BlockPos origin, CommunicationNetworkLimits limits) {
        try {
            // Guard every provider/face inspection, including the origin and frontier. A denied path is not zero peers.
            EndpointDirectory.World guarded = guarded(world);
            var ports = EndpointDirectory.scan(guarded, origin, limits);
            var groups = new TreeMap<String, List<DataValue>>();
            var pinConnections = new HashSet<CableDiscovery.Connection>();
            for (var port : ports) {
                var pos = port.connection().position();
                var fields = new LinkedHashMap<String, DataValue>();
                fields.put("position", new DataValue.RecordValue(Map.of(
                    "x", new DataValue.IntegerValue(pos.getX()), "y", new DataValue.IntegerValue(pos.getY()),
                    "z", new DataValue.IntegerValue(pos.getZ()))));
                var pins = XBusPinDiscovery.inspect(guarded, port, limits.maxEndpoints());
                if (!pins.isEmpty()) {
                    fields.put("xbus_pins", new DataValue.ListValue(pins.stream().<DataValue>map(DataValue.TextValue::new).toList()));
                    pinConnections.add(port.connection());
                }
                groups.computeIfAbsent(port.name(), key -> new ArrayList<>()).add(
                    new DataValue.RecordValue(fields));
            }
            // Physical components also perform the final path observation after pin-provider callbacks.
            // One bounded walk shared by all faces, not a walk per pin or implicit bridging at the source.
            var segments = CableDiscovery.segments(guarded, origin, limits.maxVisited(), limits.maxEndpoints());
            var physicalConnections = new HashSet<CableDiscovery.Connection>();
            segments.forEach(segment -> physicalConnections.addAll(segment.endpoints()));
            if (!physicalConnections.containsAll(pinConnections))
                throw new CableDiscovery.DiscoveryException("endpoint_changed");
            var peers = new LinkedHashMap<String, DataValue>();
            groups.forEach((name, endpoints) -> peers.put(name, new DataValue.RecordValue(Map.of(
                "ambiguous", new DataValue.BooleanValue(endpoints.size() > 1), "endpoints", new DataValue.ListValue(endpoints)))));
            return new DataValue.RecordValue(Map.of("dimension", new DataValue.TextValue(world.dimension()),
                "game_time", new DataValue.IntegerValue(world.gameTime()), "peers", new DataValue.RecordValue(peers),
                "cable_segments", new DataValue.ListValue(segments.stream().<DataValue>map(segment -> segmentView(segment, origin)).toList())));
        } catch (CableDiscovery.DiscoveryException invalid) {
            throw new DiscoveryException("invalid", invalid.code());
        } catch (DiscoveryException known) { throw known; }
        catch (RuntimeException | StackOverflowError failure) { throw new DiscoveryException("invalid", "provider_failure"); }
    }
    private static DataValue.RecordValue segmentView(CableDiscovery.Segment segment, BlockPos origin) {
        var faces = segment.endpoints().stream().filter(connection -> connection.position().equals(origin))
            .map(CableDiscovery.Connection::side).distinct().sorted().<DataValue>map(side -> new DataValue.TextValue(side.getName())).toList();
        long devices = segment.endpoints().stream().map(CableDiscovery.Connection::position)
            .filter(position -> !position.equals(origin)).distinct().count();
        return new DataValue.RecordValue(Map.of("source_faces", new DataValue.ListValue(faces),
            "cable_blocks", new DataValue.IntegerValue(segment.cables().size()),
            "connected_devices", new DataValue.IntegerValue(devices)));
    }
    public static final class DiscoveryException extends IllegalArgumentException {
        private final String code;
        public DiscoveryException(String code, String detail) { super(detail); this.code = code; }
        public String code() { return code; }
    }
}
