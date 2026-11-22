package com.nobodiiiii.createbiotech.content.biocomputing.library.drive;

import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBookAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Loaded-only physical discovery. No endpoint advertisement, transport, hidden routing or book search. */
final class LibraryControlWorld implements CableDiscovery.World {
    private final ServerLevel level; private final BlockPos origin; private final ServerPlayer actor;
    LibraryControlWorld(ServerLevel level, BlockPos origin, ServerPlayer actor) { this.level = level; this.origin = origin; this.actor = actor; }
    public boolean loaded(BlockPos pos) { return level.isOutsideBuildHeight(pos) || level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null; }
    private void check(BlockPos pos) {
        if (!loaded(pos)) throw failure("topology_unloaded");
        if (level.isOutsideBuildHeight(pos)) return;
        if (!level.getWorldBorder().isWithinBounds(pos) || !SubLevelCompat.sameSpace(level, origin, pos)
            || actor != null && !level.mayInteract(actor, pos)) throw failure("denied");
    }
    public CableDiscovery.Cell cell(BlockPos pos) {
        check(pos);
        if (level.isOutsideBuildHeight(pos)) return new CableDiscovery.Cell(CableDiscovery.Kind.EMPTY, null);
        var block = level.getBlockState(pos).getBlock();
        if (block instanceof DataCableBlock) return DataCableBlock.cell(level.getBlockState(pos));
                return new CableDiscovery.Cell(block instanceof DataPort ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
    }
    public boolean acceptsCable(BlockPos pos, Direction side) {
        check(pos); if (level.isOutsideBuildHeight(pos)) return false;
        var state = level.getBlockState(pos);
        return state.getBlock() instanceof DataPort port && port.acceptsCable(state, side);
    }
    private BlockEntity entity(BlockPos pos) {
        check(pos); if (level.isOutsideBuildHeight(pos)) throw failure("not_found");
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4).getBlockEntity(pos);
    }
    List<LibraryControlBinding> discover() {
        var limits = CBConfigs.SERVER.biocomputing.communication(CBConfigs.SERVER_SPEC.isLoaded());
        var choices = new LinkedHashSet<LibraryControlBinding>();
        for (var connection : CableDiscovery.scanEndpointConnections(this, origin, limits.maxVisited(), limits.maxEndpoints())) {
            var endpoint = entity(connection.position());
            if (endpoint instanceof com.nobodiiiii.createbiotech.content.biocomputing.block.SerialPanelEntity panel) {
                var cluster=panel.owner(); if(cluster==null) continue; var enclosure = cluster.enclosure().enclosure(); if (enclosure.isEmpty()) continue;
                if (!panel.onShell(enclosure.orElseThrow()) || panel.side()!=connection.side()) continue;
                for (var member : enclosure.orElseThrow().members().stream().sorted(Comparator.comparingInt((BlockPos p) -> p.getY())
                    .thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX)).toList()) {
                    var computer = computer(member); var id = computer.communicationIdentity();
                    if (id.isPresent() && computer.serialDevice().attachments().stream().anyMatch(a -> a.gateway().map(g->g.position().equals(connection.position())).orElse(false))) choices.add(new LibraryControlBinding(id.orElseThrow(), member, connection.position(), connection.side(), Optional.of(panel.identity())));
                }
            } else if (endpoint instanceof SmartBlockEntity smart && smart.getBehaviour(ComputerBehaviour.TYPE) != null) {
                var computer = computer(connection.position()); var id = computer.communicationIdentity();
                if (id.isPresent()) choices.add(new LibraryControlBinding(id.orElseThrow(), connection.position(), connection.position(), connection.side(), Optional.empty()));
            }
            if (choices.size() > limits.maxEndpoints()) throw failure("endpoint_limit");
        }
        var result = new ArrayList<LibraryControlBinding>();
        for (var choice : choices) {
            try { verify(choice); result.add(choice); }
            catch (LibraryBookAccess.Failure tooFar) { if (!tooFar.code().equals("control_too_far")) throw tooFar; }
        }
        return List.copyOf(result);
    }
    private ComputerBehaviour computer(BlockPos pos) {
        if (!level.shouldTickBlocksAt(pos)) throw failure("member_unavailable");
        var entity = entity(pos);
        if (!(entity instanceof SmartBlockEntity smart) || entity.isRemoved()) throw failure("member_missing");
        var computer = smart.getBehaviour(ComputerBehaviour.TYPE);
        if (computer == null) throw failure("member_missing");
        if (!computer.peripheralAvailable()) throw failure("hardware_unavailable");
        return computer;
    }
    void verify(LibraryControlBinding binding) {
        var endpoint = entity(binding.terminal());
        if (binding.controller().isPresent()) {
            if (!(endpoint instanceof com.nobodiiiii.createbiotech.content.biocomputing.block.SerialPanelEntity panel) || panel.owner()==null) throw failure("gateway_unavailable");
            var cluster=panel.owner();
            if (!panel.identity().equals(binding.controller().orElseThrow())) throw failure("gateway_changed");
            if (!level.shouldTickBlocksAt(binding.terminal())) throw failure("gateway_unavailable");
            var enclosure = cluster.enclosure().enclosure().orElseThrow(() -> failure("gateway_unformed"));
            if (!panel.onShell(enclosure) || panel.side()!=binding.face() || computer(binding.member()).serialDevice().attachments().stream().noneMatch(a -> a.gateway().map(g -> g.position().equals(binding.terminal()) && g.controller().equals(panel.identity())).orElse(false))) throw failure("gateway_changed");
            if (!enclosure.containsMember(binding.member())) throw failure("gateway_not_member");
        }
        var computer = computer(binding.member());
        var identity = computer.communicationIdentity().orElseThrow(() -> failure(computer.missingCommunicationIdentityCode()));
        if (!identity.equals(binding.computer())) throw failure("member_changed");
        var limits = CBConfigs.SERVER.biocomputing.communication(CBConfigs.SERVER_SPEC.isLoaded());
        // Restrict the target to its saved face; another face cannot silently replace the chosen connection.
        CableDiscovery.World route = new CableDiscovery.World() {
            public boolean loaded(BlockPos pos) { return LibraryControlWorld.this.loaded(pos); }
            public CableDiscovery.Cell cell(BlockPos pos) { return LibraryControlWorld.this.cell(pos); }
            public boolean acceptsCable(BlockPos pos, Direction side) {
                return (!pos.equals(binding.terminal()) || side == binding.face()) && LibraryControlWorld.this.acceptsCable(pos, side);
            }
        };
        var path = CableDiscovery.findEndpointPath(route, origin, binding.terminal(), limits.maxVisited(), limits.maxEndpoints());
        if (path.isEmpty()) throw failure("disconnected");
        if (path.size() - 2 > CBConfigs.SERVER.biocomputing.libraryControlCableBlocks(CBConfigs.SERVER_SPEC.isLoaded())) throw failure("control_too_far");
    }
    private static LibraryBookAccess.Failure failure(String code) { return new LibraryBookAccess.Failure(code); }
}
