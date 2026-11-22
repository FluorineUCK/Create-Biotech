package com.nobodiiiii.createbiotech.content.biocomputing.network;

import java.util.*;
import java.util.function.Supplier;
import java.util.function.Consumer;
import com.mojang.logging.LogUtils;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterIndicatorGeometry;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.nobodiiiii.createbiotech.registry.CBParticleTypes;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Cosmetic observer of this device's enabled declarations; never drives the XBus scheduler. */
public final class XBusWiringIndicatorBehaviour extends BlockEntityBehaviour {
    public static final BehaviourType<XBusWiringIndicatorBehaviour> TYPE = new BehaviourType<>();
    private static final DustParticleOptions UNAVAILABLE = new DustParticleOptions(new Vector3f(1, .15f, .15f), 1);
    // Rendering detail cap, not cable range, message capacity or a transfer budget.
    private static final int MAX_SEGMENTS = 32;
    private final Supplier<Optional<UUID>> identity;
    private final Consumer<Map<String, String>> diagnostics;
    private long lastTick = Long.MIN_VALUE;
    private boolean failureLogged;

    public XBusWiringIndicatorBehaviour(SmartBlockEntity owner, Supplier<Optional<UUID>> identity) {
        this(owner, identity, ignored -> {});
    }
    public XBusWiringIndicatorBehaviour(SmartBlockEntity owner, Supplier<Optional<UUID>> identity, Consumer<Map<String, String>> diagnostics) {
        super(owner); this.identity = Objects.requireNonNull(identity); this.diagnostics = Objects.requireNonNull(diagnostics);
    }
    @Override public BehaviourType<?> getType() { return TYPE; }

    @Override public void tick() {
        if (!(getWorld() instanceof ServerLevel level) || blockEntity.isRemoved()
                || !level.hasChunkAt(getPos()) || level.getBlockEntity(getPos()) != blockEntity) return;
        long now = level.getGameTime();
        if (now == lastTick || Math.floorMod(now, CBConfigs.SERVER.biocomputing.xbusIndicatorInterval(CBConfigs.SERVER_SPEC.isLoaded())) != 0) return;
        lastTick = now;
        var observed = new LinkedHashMap<XBusWiringSavedData.Entry, XBusWiringObservation.Result>();
        var codes = new LinkedHashMap<String, String>();
        try {
            var id = identity.get(); if (id.isEmpty()) { report(Map.of()); return; }
            var store = XBusWiringSavedData.get(level); var view = store.view(id.orElseThrow());
            var enabled = view.entries().stream().filter(XBusWiringSavedData.Entry::enabled).toList();
            int limit = CBConfigs.SERVER.biocomputing.communication(CBConfigs.SERVER_SPEC.isLoaded()).maxEndpoints();
            if ((long)enabled.size() * 2 > limit) codes.put("", "endpoint_limit");
            else if (enabled.stream().anyMatch(e -> !e.origin().position().equals(getPos()))) codes.put("", "owner_mismatch");
            else {
                codes.put("", "ready"); // The reserved empty subject describes the store, never a player entry name.
                for (var entry : enabled) {
                    var result = XBusWiringObservation.inspect(level, store, new XBusWiringSavedData.EnabledConnection(view, entry));
                    observed.put(entry, result);
                    boolean failed = result.modes().filter(m -> m.source() == XBusPortState.Mode.FAILED || m.target() == XBusPortState.Mode.FAILED).isPresent();
                    codes.put(entry.name(), failed ? "failed" : result.code());
                }
            }
        } catch (RuntimeException | StackOverflowError failure) {
            observed.clear(); codes.clear();
            codes.put("", failure instanceof IllegalArgumentException && "invalid_save".equals(failure.getMessage()) ? "invalid_save" : "provider_failure");
            logFailure(failure);
        }
        // Publish the same observation before rendering. Particle failure is not a broken cable or grant.
        report(Map.copyOf(codes));
        try {
            if (!codes.getOrDefault("", "ready").equals("ready")) unavailable(level, getPos());
            else for (var entry : observed.entrySet()) render(level, getPos(), entry.getKey().peer().position(), entry.getValue());
        } catch (RuntimeException | StackOverflowError failure) { logFailure(failure); }
    }
    private void report(Map<String, String> codes) {
        try { diagnostics.accept(codes); }
        catch (RuntimeException | StackOverflowError failure) { logFailure(failure); }
    }
    private void logFailure(Throwable failure) {
        // Neither diagnostics nor particles may abort this physical device's computation tick.
        if (!failureLogged) {
            failureLogged = true;
            LogUtils.getLogger().warn("Unable to display external XBus wiring at {}", getPos(), failure);
        }
    }

    static void render(ServerLevel level, BlockPos source, BlockPos target, XBusWiringObservation.Result observation) {
        render(level, source, target, observation, () -> cablePath(level, source, target));
    }
    static void render(ServerLevel level, BlockPos source, BlockPos target, XBusWiringObservation.Result observation,
            Supplier<List<BlockPos>> path) {
        if (observation.modes().isEmpty()) { unavailable(level, source); return; }
        var modes = observation.modes().orElseThrow();
        if (modes.source() == XBusPortState.Mode.FAILED || modes.target() == XBusPortState.Mode.FAILED) {
            unavailable(level, source); return;
        }
        var cable = path.get();
        if (cable.isEmpty()) { unavailable(level, source); return; }
        drawCable(level, cable, MAX_SEGMENTS + 1);
        var a = point(level, source, target); var b = point(level, target, source);
        if (modes.source() == XBusPortState.Mode.PAUSED || modes.target() == XBusPortState.Mode.PAUSED) return;
        waiting(level, modes.source(), a); waiting(level, modes.target(), b);
    }
    private static List<BlockPos> cablePath(ServerLevel level, BlockPos source, BlockPos target) {
        var limits = CBConfigs.SERVER.biocomputing.communication(CBConfigs.SERVER_SPEC.isLoaded());
        var world = new LoadedEndpointWorld(level, source, pos -> level.getWorldBorder().isWithinBounds(pos));
        return CableDiscovery.findEndpointPath(world, source, target, limits.maxVisited(), limits.maxEndpoints());
    }
    /** Sample individual cable edges, never a chord across corners. Offset outside the thin cable model. */
    static int drawCable(ServerLevel level, List<BlockPos> path, int maxPoints) {
        if (path.size() < 2 || maxPoints < 1) return 0;
        int edges = path.size() - 1;
        int count = (int)Math.min(maxPoints, (long)edges * 2 + 1);
        for (int i = 0; i < count; i++) {
            double distance = count == 1 ? edges / 2.0 : (double)i * edges / (count - 1);
            int edge = Math.min(edges - 1, (int)distance);
            var from = path.get(edge); var to = path.get(edge + 1);
            var a = SubLevelCompat.toWorld(level, from, Vec3.atCenterOf(from).add(.2, .2, .2));
            var b = SubLevelCompat.toWorld(level, to, Vec3.atCenterOf(to).add(.2, .2, .2));
            dot(level, ParticleTypes.END_ROD, a.lerp(b, distance - edge));
        }
        return count;
    }
    /** Display follows the captured explicit pin graph; it does not choose or authorize a handoff. */
    static List<XBusConnectedNetwork.DisplayWire> displayPath(List<XBusConnectedNetwork.DisplayWire> wires,
            XBusNetwork.Pin source, XBusNetwork.Pin target) {
        var previous = new HashMap<XBusNetwork.Pin, XBusConnectedNetwork.DisplayWire>();
        var visited = new HashSet<XBusNetwork.Pin>(); var pending = new ArrayDeque<XBusNetwork.Pin>();
        pending.add(source); visited.add(source);
        while (!pending.isEmpty() && !visited.contains(target)) {
            var current = pending.removeFirst();
            for (var wire : wires) {
                XBusConnectedNetwork.DisplayWire forward;
                if (wire.pins().left().equals(current)) forward = wire;
                else if (wire.pins().right().equals(current)) forward = new XBusConnectedNetwork.DisplayWire(
                    new XBusNetwork.Link(wire.pins().right(), wire.pins().left()), wire.right(), wire.left(), wire.cable());
                else continue;
                var next = forward.pins().right();
                if (visited.add(next)) { previous.put(next, forward); pending.addLast(next); }
            }
        }
        if (!visited.contains(target)) return List.of();
        var path = new ArrayList<XBusConnectedNetwork.DisplayWire>();
        for (var pin = target; !pin.equals(source); ) {
            var wire = previous.get(pin); path.add(wire); pin = wire.pins().left();
        }
        Collections.reverse(path); return List.copyOf(path);
    }
    static void transferred(ServerLevel level, XBusConnectedNetwork.Connected connected, XBusNetwork.Selection selected) {
        transferred(level, connected, selected, (source, target) -> cablePath(level, source, target));
    }
    static void transferred(ServerLevel level, XBusConnectedNetwork.Connected connected, XBusNetwork.Selection selected,
            java.util.function.BiFunction<BlockPos, BlockPos, List<BlockPos>> cablePath) {
        try {
            var internal = displayPath(connected.displayWires().stream().filter(w -> !w.cable()).toList(),
                selected.writer(), selected.reader());
            if (!internal.isEmpty()) {
                transferred(level, connected.ports().get(selected.writer()).position(), connected.ports().get(selected.reader()).position());
                return;
            }
            var path = displayPath(connected.displayWires(), selected.writer(), selected.reader());
            int remaining = MAX_SEGMENTS + 1;
            for (int i = 0; i < path.size() && remaining > 0; i++) {
                var wire = path.get(i);
                if (wire.cable()) remaining -= drawCable(level, cablePath.apply(wire.left(), wire.right()),
                    Math.max(1, remaining / (path.size() - i)));
                else { transferred(level, wire.left(), wire.right()); remaining--; }
            }
        } catch (RuntimeException | StackOverflowError failure) { /* Post-commit cosmetics cannot replay or reject a handoff. */ }
    }
    /** Post-commit cosmetic only. Never let a particle failure replay a durable handoff. */
    static void transferred(ServerLevel level, BlockPos writer, BlockPos reader) {
        try {
            var start = SubLevelCompat.toWorld(level, writer, Vec3.atCenterOf(writer));
            var end = SubLevelCompat.toWorld(level, reader, Vec3.atCenterOf(reader));
            var displacement = end.subtract(start);
            level.sendParticles(CBParticleTypes.STRAIGHT_ENCHANT.get(), start.x, start.y, start.z,
                0, displacement.x, displacement.y, displacement.z, 1);
        } catch (RuntimeException failure) { /* Diagnostics/particles are not communication authority. */ }
    }
    private static Vec3 point(ServerLevel level, BlockPos source, BlockPos target) {
        return SubLevelCompat.toWorld(level, source, ClusterIndicatorGeometry.waitingPoint(source, target));
    }
    private static void unavailable(ServerLevel level, BlockPos source) {
        dot(level, UNAVAILABLE, point(level, source, source));
    }
    private static void waiting(ServerLevel level, XBusPortState.Mode mode, Vec3 point) {
        ParticleOptions particle = switch (mode) {
            case READ -> CBParticleTypes.BIOCOMPUTING_READ_WAIT.get();
            case WRITE -> ParticleTypes.SMALL_FLAME;
            case READABLE -> ParticleTypes.COMPOSTER;
            default -> null;
        };
        if (particle != null) dot(level, particle, point.add(0, .15, 0));
    }
    private static void dot(ServerLevel level, ParticleOptions particle, Vec3 point) {
        level.sendParticles(particle, point.x, point.y, point.z, 1, 0, 0, 0, 0);
    }
}
