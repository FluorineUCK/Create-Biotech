package com.nobodiiiii.createbiotech.content.biocomputing.network;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** One complete loaded scan; logical identity includes the connection domain, not just block position. */
final class EndpointDirectory {
    private EndpointDirectory() {}
    interface World extends CableDiscovery.World {
        CommunicationEndpoint endpoint(BlockPos pos);
        default CommunicationEndpoint endpoint(BlockPos pos, Direction side) { return endpoint(pos); }
    }
    record Port(CableDiscovery.Connection connection, String domain, String name, boolean enabled) {}
    private record Key(BlockPos position, String domain, String name) {}

    static List<Port> scan(World world, BlockPos origin, CommunicationNetworkLimits limits) {
        return inspect(world, origin, limits).stream().filter(Port::enabled).toList();
    }

    /** Disabled metadata supports diagnostics only, never an admission grant or a discovered peer. */
    static List<Port> inspect(World world, BlockPos origin, CommunicationNetworkLimits limits) {
        var connections = CableDiscovery.scanEndpointConnections(world, origin, limits.maxVisited(), limits.maxEndpoints());
        var ports = new LinkedHashMap<Key, Port>();
        var inspected = new HashSet<Key>();
        for (var connection : connections) {
            var pos = connection.position();
            if (!world.loaded(pos) || world.cell(pos).kind() != CableDiscovery.Kind.ENDPOINT)
                throw new CableDiscovery.DiscoveryException("endpoint_changed");
            var provider = world.endpoint(pos, connection.side());
            if (provider == null) throw new CableDiscovery.DiscoveryException("endpoint_changed");
            String domain = provider.domain();
            new EndpointConfiguration(domain, true); // Domain keys obey the existing literal name boundary.
            for (var configuration : configurations(provider, limits.maxEndpoints())) {
                var key = new Key(pos, domain, configuration.name());
                inspected.add(key);
                if (inspected.size() > limits.maxEndpoints()) throw new CableDiscovery.DiscoveryException("endpoint_limit");
                var port = new Port(connection, domain, configuration.name(), configuration.enabled());
                // Prefer an open face of the same logical port, preserving enabled-only discovery semantics.
                ports.merge(key, port, (previous, next) -> previous.enabled() ? previous : next);
            }
        }
        return List.copyOf(ports.values());
    }

    static List<EndpointConfiguration> configurations(CommunicationEndpoint provider, int maxEndpoints) {
        var advertised = Objects.requireNonNull(provider.advertisements());
        if (advertised.size() > maxEndpoints) throw new CableDiscovery.DiscoveryException("endpoint_limit");
        return List.copyOf(advertised);
    }
}
