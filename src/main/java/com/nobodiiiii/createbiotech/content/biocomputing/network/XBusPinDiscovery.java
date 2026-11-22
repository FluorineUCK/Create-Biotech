package com.nobodiiiii.createbiotech.content.biocomputing.network;

import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins;
import java.util.List;

/** Bounded, grant-scoped names only. Observing pins never acquires a rendezvous handle. */
final class XBusPinDiscovery {
    private XBusPinDiscovery() {}

    static List<String> inspect(EndpointDirectory.World world, EndpointDirectory.Port port, int maxEndpoints) {
        var provider = connected(world, port);
        // Reuse the hardware encoding boundary, not a second gameplay pin count.
        var pins = new HardwarePins(provider.xbusPins(port.name()), List.of()).xbus();
        // Legacy providers do not gain extra advertisement callbacks just to return their old position-only record.
        if (pins.isEmpty()) return pins;
        granted(provider, port, maxEndpoints);
        for (String pin : pins) {
            var observed = provider.xbusPort(port.name(), pin);
            if (observed.isEmpty() || !observed.orElseThrow().pin().equals(pin)) changed();
        }
        // A provider callback may revoke its grant or replace its list during inspection.
        var after = current(world, port, maxEndpoints);
        if (!pins.equals(new HardwarePins(after.xbusPins(port.name()), List.of()).xbus())) changed();
        current(world, port, maxEndpoints);
        return pins;
    }

    private static CommunicationEndpoint current(EndpointDirectory.World world, EndpointDirectory.Port port, int maxEndpoints) {
        var provider = connected(world, port); granted(provider, port, maxEndpoints); return provider;
    }
    private static CommunicationEndpoint connected(EndpointDirectory.World world, EndpointDirectory.Port port) {
        var connection = port.connection(); var pos = connection.position();
        if (!world.loaded(pos) || world.cell(pos).kind() != CableDiscovery.Kind.ENDPOINT
                || !world.acceptsCable(pos, connection.side())) changed();
        var provider = world.endpoint(pos, connection.side());
        if (provider == null || !port.domain().equals(provider.domain())) changed();
        return provider;
    }
    private static void granted(CommunicationEndpoint provider, EndpointDirectory.Port port, int maxEndpoints) {
        if (EndpointDirectory.configurations(provider, maxEndpoints).stream()
                .noneMatch(value -> value.enabled() && value.name().equals(port.name()))) changed();
    }
    private static void changed() { throw new CableDiscovery.DiscoveryException("endpoint_changed"); }
}
