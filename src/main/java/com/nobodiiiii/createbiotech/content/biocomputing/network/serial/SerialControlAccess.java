package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Physical serial source proof, shared by opt-in device protocols. No application, RAM or Library access. */
public final class SerialControlAccess implements CableDiscovery.World {
    public record Binding(UUID computer, BlockPos member, UUID serial, String pin, Direction side, Optional<SerialGatewayBinding> gateway) {
        public Binding {
            Objects.requireNonNull(computer); member = member.immutable(); Objects.requireNonNull(serial); Objects.requireNonNull(side);
            Objects.requireNonNull(gateway); new HardwarePins(List.of(pin), List.of());
            if (!BlockPos.of(member.asLong()).equals(member)) throw new IllegalArgumentException("invalid_binding");
        }
        public SharedSerialChannel.Port source() { return new SharedSerialChannel.Port(serial, pin); }
        public BlockPos terminal() { return gateway.map(SerialGatewayBinding::position).orElse(member); }
        public CompoundTag save() {
            var tag = new CompoundTag(); tag.putUUID("Computer", computer); tag.putLong("Member", member.asLong());
            tag.putUUID("Serial", serial); tag.putString("Pin", pin); tag.putString("Side", side.getName());
            gateway.ifPresent(value -> tag.put("Gateway", value.write())); return tag;
        }
        public static Binding load(Tag raw) {
            if (!(raw instanceof CompoundTag tag)) throw new IllegalArgumentException("invalid_binding");
            var keys = new HashSet<>(Set.of("Computer", "Member", "Serial", "Pin", "Side"));
            if (tag.contains("Gateway")) keys.add("Gateway");
            if (!tag.getAllKeys().equals(keys) || !tag.hasUUID("Computer") || !tag.hasUUID("Serial")
                || !tag.contains("Member", Tag.TAG_LONG) || !tag.contains("Pin", Tag.TAG_STRING) || !tag.contains("Side", Tag.TAG_STRING))
                throw new IllegalArgumentException("invalid_binding");
            var side = Direction.byName(tag.getString("Side"));
            if (side == null || !side.getName().equals(tag.getString("Side"))) throw new IllegalArgumentException("invalid_binding");
            return new Binding(tag.getUUID("Computer"), BlockPos.of(tag.getLong("Member")), tag.getUUID("Serial"), tag.getString("Pin"), side,
                tag.contains("Gateway") ? Optional.of(SerialGatewayBinding.read(tag.get("Gateway"))) : Optional.empty());
        }
        public DataValue.RecordValue summary() {
            return new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue(gateway.isPresent() ? "cluster" : "computer"),
                "member", new DataValue.TextValue(member.toShortString()), "terminal", new DataValue.TextValue(terminal().toShortString()),
                "port", new DataValue.TextValue(gateway.map(SerialGatewayBinding::alias).orElse(pin)), "face", new DataValue.TextValue(side.getName())));
        }
    }
    private final ServerLevel level;
    private final BlockPos origin;
    private final ServerPlayer actor;
    public SerialControlAccess(ServerLevel level, BlockPos origin, ServerPlayer actor) { this.level = level; this.origin = origin; this.actor = actor; }
    @Override public Predicate<BlockPos> traversalScope(BlockPos origin) {
        var space = SubLevelCompat.getSpaceId(level, origin);
        return candidate -> SubLevelCompat.matchesSpace(level, candidate, space);
    }
    public boolean loaded(BlockPos pos) { return level.isOutsideBuildHeight(pos) || level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null; }
    private void check(BlockPos pos) {
        if (!loaded(pos)) throw failure("topology_unloaded");
        if (!level.getWorldBorder().isWithinBounds(pos) || !SubLevelCompat.sameSpace(level, origin, pos)
            || actor != null && !level.mayInteract(actor, pos)) throw failure("access_denied");
    }
    public CableDiscovery.Cell cell(BlockPos pos) {
        check(pos); if (level.isOutsideBuildHeight(pos)) return new CableDiscovery.Cell(CableDiscovery.Kind.EMPTY, null);
        var block = level.getBlockState(pos).getBlock();
        if (block instanceof DataCableBlock) return DataCableBlock.cell(level.getBlockState(pos));
                return new CableDiscovery.Cell(block instanceof DataPort ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
    }
    public boolean acceptsCable(BlockPos pos, Direction face) {
        check(pos); if (level.isOutsideBuildHeight(pos)) return false;
        var state = level.getBlockState(pos); return state.getBlock() instanceof DataPort port && port.acceptsCable(state, face);
    }
    private BlockEntity entity(BlockPos pos) {
        check(pos); if (level.isOutsideBuildHeight(pos)) throw failure("not_found");
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4).getBlockEntity(pos);
    }
    private ComputerBehaviour computer(BlockPos pos) {
        if (!(entity(pos) instanceof SmartBlockEntity owner) || owner.isRemoved()) throw failure("member_missing");
        var computer = owner.getBehaviour(ComputerBehaviour.TYPE);
        if (computer == null) throw failure("member_missing");
        if (!level.shouldTickBlocksAt(pos) || !computer.peripheralAvailable()) throw failure("member_unavailable");
        return computer;
    }
    public List<Binding> discover() {
        var limits = CBConfigs.SERVER.biocomputing.communication(CBConfigs.SERVER_SPEC.isLoaded());
        var choices = new LinkedHashSet<Binding>();
        for (var end : CableDiscovery.scanEndpointConnections(this, origin, limits.maxVisited(), limits.maxEndpoints())) {
            var endpoint = entity(end.position()); var members = new ArrayList<BlockPos>();
            if (endpoint instanceof com.nobodiiiii.createbiotech.content.biocomputing.block.SerialPanelEntity panel && panel.owner()!=null) {
                var shape = panel.owner().enclosure().enclosure();
                if (shape.isEmpty() || !panel.onShell(shape.orElseThrow()) || panel.side() != end.side()) continue;
                members.addAll(shape.orElseThrow().members());
            } else if (endpoint instanceof SmartBlockEntity owner && owner.getBehaviour(ComputerBehaviour.TYPE) != null) members.add(end.position());
            members.sort(Comparator.comparingInt((BlockPos p) -> p.getY()).thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX));
            for (var pos : members) {
                var computer = computer(pos); var id = computer.communicationIdentity(); if (id.isEmpty()) continue;
                for (var port : computer.serialDevice().attachments()) {
                    if (!port.gateway().map(SerialGatewayBinding::position).orElse(pos).equals(end.position())) continue;
                    if (!SerialGatewayAccess.resolve(level, pos, port).equals(end)) continue;
                    choices.add(new Binding(id.orElseThrow(), pos, port.identity(), port.name(), port.side(), port.gateway()));
                    if (choices.size() > limits.maxEndpoints()) throw failure("endpoint_limit");
                }
            }
        }
        return List.copyOf(choices);
    }
    /** Recheck after the player selects a server-captured candidate, and at every command publication. */
    public ComputerBehaviour verify(Binding selected) {
        var computer = computer(selected.member());
        if (!computer.communicationIdentity().filter(selected.computer()::equals).isPresent()) throw failure("member_changed");
        var port = computer.serialDevice().attachments().stream().filter(p -> p.key().equals(selected.source())).findFirst()
            .orElseThrow(() -> failure("source_closed"));
        if (port.side() != selected.side() || !port.gateway().equals(selected.gateway())) throw failure("source_changed");
        var end = SerialGatewayAccess.resolve(level, selected.member(), port);
        if (!end.position().equals(selected.terminal()) || end.side() != selected.side()) throw failure("source_changed");
        var limits = CBConfigs.SERVER.biocomputing.communication(CBConfigs.SERVER_SPEC.isLoaded());
        if (!CableDiscovery.scanEndpointConnections(this, origin, limits.maxVisited(), limits.maxEndpoints()).contains(end)) throw failure("disconnected");
        return computer;
    }
    public UUID source(Binding selected, SharedSerialChannel.Port source) {
        if (!selected.source().equals(source)) throw failure("denied");
        verify(selected); return selected.computer();
    }
    private static CableDiscovery.DiscoveryException failure(String code) { return new CableDiscovery.DiscoveryException(code); }
}
