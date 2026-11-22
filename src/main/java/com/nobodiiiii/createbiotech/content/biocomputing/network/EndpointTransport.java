package com.nobodiiiii.createbiotech.content.biocomputing.network;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.OutboundMessage;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/** Loaded physical routing between explicitly open endpoints; never accesses recipient RAM or runs its graph. */
public final class EndpointTransport {
    private EndpointTransport() {}
    public record Result(String code, Optional<AdmissionStatus> admission) {
        public Result { Objects.requireNonNull(code); Objects.requireNonNull(admission); }
        public boolean accepted() { return admission.map(AdmissionStatus::accepted).orElse(false); }
    }
    interface World extends EndpointDirectory.World {}
    public static Result deliver(SmartBlockEntity owner, UUID source, OutboundMessage output) {
        return deliver(owner, source, null, output);
    }
    public static Result deliver(SmartBlockEntity owner, UUID source, String returnEndpoint, OutboundMessage output) {
        try { return deliverEnvelope(owner, EnumSet.allOf(Direction.class), output.endpoint(), envelope(source, returnEndpoint, output)); }
        catch (RuntimeException | StackOverflowError failure) { return result("provider_failure"); }
    }
    /** Explicit physical egress for forwarding an existing immutable envelope, without reallocating its identity. */
    public static Result deliverEnvelope(SmartBlockEntity owner, Set<Direction> sourceFaces, String name, MessageEnvelope envelope) {
        try {
            if (owner == null || owner.isRemoved() || !(owner.getLevel() instanceof ServerLevel level)
                || !level.hasChunkAt(owner.getBlockPos()) || !level.getWorldBorder().isWithinBounds(owner.getBlockPos())
                || level.getBlockEntity(owner.getBlockPos()) != owner) return result("unavailable");
            // The target's open capability is the receive grant; no player is impersonated for world reads.
            var world = new LoadedEndpointWorld(level, owner.getBlockPos(), pos -> level.getWorldBorder().isWithinBounds(pos), sourceFaces);
            var limits = CBConfigs.SERVER.biocomputing.communication(CBConfigs.SERVER_SPEC.isLoaded());
            return deliver(world, owner.getBlockPos(), limits, name, Objects.requireNonNull(envelope));
        } catch (RuntimeException | StackOverflowError failure) { return result("provider_failure"); }
    }
    /** Physical host supplies the current open advertisement; player data cannot override it. */
    public static MessageEnvelope envelope(UUID source, String returnEndpoint, OutboundMessage output) {
        MessageProtocol protocol = switch (output.protocol()) {
            case OutboundProtocol.Message ignored -> MessageProtocol.Message.INSTANCE;
            case OutboundProtocol.Request request -> {
                var configuration = new EndpointConfiguration(returnEndpoint, true);
                yield new MessageProtocol.Request(new RequestContext(request.correlation(), configuration.name()));
            }
            case OutboundProtocol.Reply reply -> new MessageProtocol.Reply(reply.correlation());
        };
        return new MessageEnvelope(source, output.sequence(), output.eventId(), output.ordinal(), output.payload(), protocol);
    }
    static Result deliver(World world, BlockPos origin, CommunicationNetworkLimits limits, String name, MessageEnvelope envelope) {
        try { new EndpointConfiguration(name, true); }
        catch (IllegalArgumentException | NullPointerException invalid) { return result("invalid_name"); }
        try {
            var ports = EndpointDirectory.inspect(world, origin, limits);
            EndpointDirectory.Port target = null;
            boolean closed = false;
            for (var port : ports) {
                if (port.name().equals(name)) {
                    if (!port.enabled()) { closed = true; continue; }
                    if (target != null) return result("ambiguous");
                    target = port;
                }
            }
            if (target == null) return result(closed ? "closed" : "not_found");
            var pos = target.connection().position();
            var side = target.connection().side();
            if (!world.loaded(pos) || world.cell(pos).kind() != CableDiscovery.Kind.ENDPOINT || !world.acceptsCable(pos, side)) return result("endpoint_changed");
            var provider = world.endpoint(pos, side);
            if (provider == null || !target.domain().equals(provider.domain()) || !matches(provider, name, limits.maxEndpoints())) return result("endpoint_changed");
            var status = Objects.requireNonNull(provider.receive(name, envelope));
            return new Result(status.name().toLowerCase(Locale.ROOT), Optional.of(status));
        } catch (CableDiscovery.DiscoveryException invalid) { return result(invalid.code()); }
        catch (RuntimeException | StackOverflowError failure) { return result("provider_failure"); }
    }
    private static boolean matches(CommunicationEndpoint endpoint, String name, int maxEndpoints) {
        return EndpointDirectory.configurations(endpoint, maxEndpoints).stream().anyMatch(config -> config.enabled() && config.name().equals(name));
    }
    private static Result result(String code) { return new Result(code, Optional.empty()); }
}
