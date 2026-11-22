package com.nobodiiiii.createbiotech.content.biocomputing.network.signal;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.SimpleIoNetwork;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationNetworkLimits;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Explicit continuous-signal sources. No serial decoding, default pin or endpoint bridging. */
public final class CableSignalNetwork {
    private CableSignalNetwork() {}
    public record Key(BlockPos origin, String pin) {
        public Key { origin = origin.immutable(); Objects.requireNonNull(pin); }
    }
    public record Source(BlockPos origin, String pin, Direction side, SimpleIoNetwork.Port output, BlockPos connector, Set<BlockPos> guards) {
        public Source {
            origin = origin.immutable(); Objects.requireNonNull(pin); Objects.requireNonNull(side); Objects.requireNonNull(output);
            connector = connector.immutable(); guards = Set.copyOf(guards);
        }
        public Source(BlockPos origin, String pin, Direction side, SimpleIoNetwork.Port output) { this(origin, pin, side, output, origin, Set.of()); }
        public Key key() { return new Key(origin, pin); }
    }
    public record Frame(Map<BlockPos, Integer> outputs, Map<Key, String> status,
                        Map<BlockPos, Set<BlockPos>> dependencies) {
        public static final Frame EMPTY = new Frame(Map.of(), Map.of(), Map.of());
        public Frame {
            outputs = Map.copyOf(outputs); status = Map.copyOf(status);
            var copy = new HashMap<BlockPos, Set<BlockPos>>();
            dependencies.forEach((pos, values) -> copy.put(pos, Set.copyOf(values)));
            dependencies = Map.copyOf(copy);
        }
    }
    public static Frame sample(CableDiscovery.World world, List<Source> sources, CommunicationNetworkLimits limits) {
        var outputs = new HashMap<BlockPos, Integer>();
        var status = new HashMap<Key, String>();
        var dependencies = new HashMap<BlockPos, Set<BlockPos>>();
        var components = new HashMap<List<BlockPos>, List<Source>>();
        var discovered = new HashMap<CableDiscovery.Connection, CableDiscovery.Segment>();
        for (var source : sources) {
            var connection = new CableDiscovery.Connection(source.connector(), source.side());
            try {
                var segment = discovered.get(connection);
                if (segment == null) {
                    var selectedFace = new CableDiscovery.World() {
                        public boolean loaded(BlockPos pos) { return world.loaded(pos); }
                        public CableDiscovery.Cell cell(BlockPos pos) { return world.cell(pos); }
                        public boolean acceptsCable(BlockPos pos, Direction side) {
                            return (!pos.equals(source.connector()) || side == source.side()) && world.acceptsCable(pos, side);
                        }
                    };
                    var found = CableDiscovery.segments(selectedFace, source.connector(), limits.maxVisited(), limits.maxEndpoints());
                    if (!found.isEmpty()) {
                        segment = found.getFirst();
                        for (var terminal : segment.endpoints()) discovered.put(terminal, segment);
                    }
                }
                if (segment == null || segment.cables().isEmpty()) status.put(source.key(), "disconnected");
                else components.computeIfAbsent(segment.cables(), ignored -> new ArrayList<>()).add(source);
            } catch (CableDiscovery.DiscoveryException failure) { status.put(source.key(), failure.code()); }
        }
        components.forEach((cables, drivers) -> {
            var driving = drivers.stream().filter(x->x.output().availability()!=SimpleIoNetwork.Availability.AVAILABLE || x.output().output().isPresent()).toList();
            var port = (driving.isEmpty()?drivers.getFirst():driving.getFirst()).output();
            String code = driving.size() > 1 ? "source_conflict"
                : port.availability() != SimpleIoNetwork.Availability.AVAILABLE ? port.availability().name().toLowerCase(Locale.ROOT)
                : port.output().isEmpty() ? "input" : "active";
            int power = code.equals("active") ? port.output().getAsInt() : 0;
            var depends = new HashSet<BlockPos>(cables);
            drivers.forEach(driver -> { status.put(driver.key(), code.equals("active") && driver.output().output().isEmpty() ? "input" : code); depends.add(driver.origin()); depends.add(driver.connector()); depends.addAll(driver.guards()); });
            var frozen = Set.copyOf(depends);
            cables.forEach(cable -> { outputs.put(cable, power); dependencies.put(cable, frozen); });
        });
        return new Frame(outputs, status, dependencies);
    }
}
