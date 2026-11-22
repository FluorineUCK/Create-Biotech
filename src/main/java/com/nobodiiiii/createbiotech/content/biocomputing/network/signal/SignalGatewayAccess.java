package com.nobodiiiii.createbiotech.content.biocomputing.network.signal;

import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;

/** SIO publication through one explicitly selected member pin. No protocol, state copying into the controller or fallback. */
public final class SignalGatewayAccess {
    private SignalGatewayAccess() {}
    public record Route(CableDiscovery.Connection connector, Set<BlockPos> dependencies) {
        public Route { dependencies = Set.copyOf(dependencies); }
    }
    public static ClusterPortBinding authorize(ClusterMemberEditorAccess access, ServerPlayer player, String pin, String alias) {
        var binding = ClusterPhysicalPortAccess.authorize(access, player, alias);
        var geometry = access.controller().enclosure().enclosure().orElseThrow(() -> failure("gateway_unformed"));
        unique(player.serverLevel(), geometry, binding, access.computer().getBlockPos(), pin);
        return binding;
    }
    public static Route resolve(ServerLevel level, BlockPos member, String pin, SignalConnections.Binding port) {
        if (port.gateway().isEmpty()) return new Route(new CableDiscovery.Connection(member, port.side()), Set.of());
        var binding = port.gateway().orElseThrow();
        var geometry = ClusterPhysicalPortAccess.resolve(level, member, binding);
        unique(level, geometry, binding, member, pin);
        var dependencies = new HashSet<>(geometry.shell()); dependencies.add(binding.position()); dependencies.add(member);
        return new Route(new CableDiscovery.Connection(binding.position(), binding.side()), dependencies);
    }
    private static void unique(ServerLevel level, ClusterEnclosure geometry, ClusterPortBinding binding, BlockPos member, String pin) {
        for (var pos : geometry.members()) {
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) throw failure("gateway_unloaded");
            if (!(chunk.getBlockEntity(pos) instanceof SmartBlockEntity owner) || owner.isRemoved()) throw failure("gateway_unavailable");
            var computer = owner.getBehaviour(ComputerBehaviour.TYPE);
            if (computer == null || computer.signalConnections().diagnostic().isPresent()) throw failure("gateway_unavailable");
            for (var entry : computer.signalConnections().bindings().entrySet()) {
                if (pos.equals(member) && entry.getKey().equals(pin)) continue;
                var route = entry.getValue().gateway();
                if (route.isPresent() && route.orElseThrow().controller().equals(binding.controller())
                    && route.orElseThrow().position().equals(binding.position()) && route.orElseThrow().alias().equals(binding.alias()))
                    throw failure("gateway_conflict");
            }
        }
    }
    private static CableDiscovery.DiscoveryException failure(String code) { return new CableDiscovery.DiscoveryException(code); }
}
