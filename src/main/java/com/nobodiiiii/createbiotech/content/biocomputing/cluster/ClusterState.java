package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import java.util.*;

/** Immutable reliable forwarding state. The physical adapter must honor the full Target, not only its name. */
public record ClusterState(ClusterConfiguration configuration, MailboxState mailbox, List<Forward> forwards,
    Map<RequestCorrelation, ReturnPath> pending, Map<UUID, Ingress> receiptPorts) {
    public enum Side { EXTERNAL, INTERNAL }
    public record Ingress(Side side, String port) {
        public Ingress { Objects.requireNonNull(side); ClusterConfiguration.name(port); }
    }
    public record Target(Side side, String port, Optional<ClusterConfiguration.Member> member) {
        public Target {
            Objects.requireNonNull(side); ClusterConfiguration.name(port); Objects.requireNonNull(member);
            if ((side == Side.INTERNAL) != member.isPresent()) throw new IllegalArgumentException("Internal target requires pinned member");
        }
    }
    public record Forward(Ingress ingress, Target target, Optional<String> returnEndpoint) {
        public Forward {
            Objects.requireNonNull(ingress); Objects.requireNonNull(target); Objects.requireNonNull(returnEndpoint);
            returnEndpoint.ifPresent(ClusterConfiguration::name);
            if (ingress.side() == target.side()) throw new IllegalArgumentException("A route must cross explicit domains");
        }
    }
    public record ReturnPath(Ingress expected, Target target, Optional<UUID> responder) {
        public ReturnPath {
            Objects.requireNonNull(expected); Objects.requireNonNull(target); Objects.requireNonNull(responder);
            if (expected.side() == target.side() || (expected.side() == Side.INTERNAL) != responder.isPresent())
                throw new IllegalArgumentException("Invalid return path domains or responder");
        }
    }
    public record Outbound(Target target, MessageEnvelope envelope) {
        public Outbound { Objects.requireNonNull(target); Objects.requireNonNull(envelope); }
    }
    record Admission(AdmissionStatus status, ClusterState state) {}

    public ClusterState {
        Objects.requireNonNull(configuration); Objects.requireNonNull(mailbox);
        forwards = List.copyOf(forwards); pending = copy(pending); receiptPorts = copy(receiptPorts);
        if (mailbox.queued().size() != forwards.size() || !mailbox.receipts().keySet().equals(receiptPorts.keySet()))
            throw new IllegalArgumentException("Routing metadata does not match mailbox");
        for (int i = 0; i < forwards.size(); i++) {
            var forward = forwards.get(i); var envelope = mailbox.queued().get(i);
            var route = route(configuration, forward.ingress()).orElseThrow(() -> new IllegalArgumentException("Queued route is absent"));
            var port = route.getValue(); var member = configuration.members().get(port.member());
            if (!port.enabled() || forward.ingress().side() == Side.INTERNAL && !envelope.source().equals(member.identity()))
                throw new IllegalArgumentException("Queued route not authorized");
            requireMember(configuration, forward.target());
            if (forward.target().side() == Side.INTERNAL && (!forward.target().member().orElseThrow().equals(member)
                || !forward.target().port().equals(port.input())))
                throw new IllegalArgumentException("Queued route targets another member or input");
            if (forward.returnEndpoint().isPresent() != (envelope.protocol() instanceof MessageProtocol.Request))
                throw new IllegalArgumentException("Request return endpoint metadata mismatch");
            if (!(envelope.protocol() instanceof MessageProtocol.Reply)) {
                String expectedTarget = forward.ingress().side() == Side.EXTERNAL ? port.input() : port.external();
                if (!expectedTarget.equals(forward.target().port())) throw new IllegalArgumentException("Queued target differs from configured route");
            }
            forward.returnEndpoint().ifPresent(name -> {
                if (!name.equals(forward.ingress().side() == Side.EXTERNAL ? port.output() : route.getKey()))
                    throw new IllegalArgumentException("Queued return endpoint differs from route");
            });
            if (mailbox.receipts().get(envelope.source()).sequence() == envelope.sequence()
                && !forward.ingress().equals(receiptPorts.get(envelope.source())))
                throw new IllegalArgumentException("Queued ingress differs from receipt");
        }
        for (var entry : pending.entrySet()) {
            var path = entry.getValue(); var route = route(configuration, path.expected()).orElseThrow(() -> new IllegalArgumentException("Pending route is absent"));
            var member = configuration.members().get(route.getValue().member());
            if (!route.getValue().enabled()) throw new IllegalArgumentException("Pending route is closed");
            requireMember(configuration, path.target());
            if (path.expected().side() == Side.INTERNAL && !path.responder().orElseThrow().equals(member.identity()))
                throw new IllegalArgumentException("Pending responder differs from configured member");
            if (path.target().side() == Side.INTERNAL && (!path.target().member().orElseThrow().equals(member)
                || !entry.getKey().requester().equals(member.identity()) || !path.target().port().equals(route.getValue().input())))
                throw new IllegalArgumentException("Pending return targets another member or input");
            if (!mailbox.receipts().containsKey(entry.getKey().requester()))
                throw new IllegalArgumentException("Pending request has no admission receipt");
        }
        validateExchanges(configuration, mailbox, forwards, pending);
    }
    public static ClusterState empty() { return new ClusterState(ClusterConfiguration.empty(), MailboxState.empty(), List.of(), Map.of(), Map.of()); }
    public boolean busy() { return !forwards.isEmpty() || !pending.isEmpty(); }
    public ClusterState configure(ClusterConfiguration config) {
        if (busy()) throw new IllegalStateException("Cluster has pending transfers");
        return new ClusterState(config, mailbox, forwards, pending, receiptPorts);
    }
    public Optional<Outbound> outbound() {
        if (forwards.isEmpty()) return Optional.empty();
        var route = forwards.getFirst(); var original = mailbox.queued().getFirst();
        var protocol = original.protocol();
        if (protocol instanceof MessageProtocol.Request request)
            protocol = new MessageProtocol.Request(new RequestContext(request.context().correlation(), route.returnEndpoint().orElseThrow()));
        return Optional.of(new Outbound(route.target(), new MessageEnvelope(original.source(), original.sequence(), original.eventId(),
            original.ordinal(), original.payload(), protocol)));
    }
    public ClusterState acknowledge(Outbound expected) {
        return outbound().filter(expected::equals).isEmpty() ? this : new ClusterState(configuration, mailbox.withoutHead(),
            forwards.subList(1, forwards.size()), pending, receiptPorts);
    }
    Admission admit(Ingress ingress, MessageEnvelope envelope, ClusterLimits limits) {
        var receipt = mailbox.receipts().get(envelope.source());
        if (receipt != null && envelope.sequence() <= receipt.sequence()) {
            var status = mailbox.admit(envelope, limits.mailbox()).status();
            if (status == AdmissionStatus.DUPLICATE && !ingress.equals(receiptPorts.get(envelope.source()))) status = AdmissionStatus.IDENTITY_CONFLICT;
            // A consumed RPC reply can finish even after later replies raised the source watermark.
            // It must still pass the current port/member grant below; never waive same-identity port conflicts.
            boolean consumedReply = status == AdmissionStatus.STALE && envelope.protocol() instanceof MessageProtocol.Reply reply
                && !pending.containsKey(reply.correlation());
            if (!consumedReply) return new Admission(status, this);
        }
        var found = route(configuration, ingress);
        if (found.isEmpty() || !found.get().getValue().enabled()) return new Admission(AdmissionStatus.CLOSED, this);
        var publicName = found.get().getKey(); var port = found.get().getValue(); var member = configuration.members().get(port.member());
        if (ingress.side() == Side.INTERNAL && !member.identity().equals(envelope.source())) return new Admission(AdmissionStatus.IDENTITY_CONFLICT, this);
        var nextPending = new LinkedHashMap<>(pending); Target target; Optional<String> returnEndpoint = Optional.empty();
        if (envelope.protocol() instanceof MessageProtocol.Reply reply) {
            var path = pending.get(reply.correlation());
            if (path == null) return new Admission(AdmissionStatus.STALE_REPLY, this);
            if (!path.expected().equals(ingress) || path.responder().filter(id -> !id.equals(envelope.source())).isPresent())
                return new Admission(AdmissionStatus.IDENTITY_CONFLICT, this);
            target = path.target(); nextPending.remove(reply.correlation());
        } else {
            target = ingress.side() == Side.EXTERNAL ? new Target(Side.INTERNAL, port.input(), Optional.of(member))
                : new Target(Side.EXTERNAL, port.external(), Optional.empty());
            if (envelope.protocol() instanceof MessageProtocol.Request request) {
                var context = request.context();
                if (pending.containsKey(context.correlation())) return new Admission(AdmissionStatus.IDENTITY_CONFLICT, this);
                if (pending.size() >= limits.mailbox().messages()) return new Admission(AdmissionStatus.QUEUE_FULL, this);
                if (ingress.side() == Side.INTERNAL && !context.returnEndpoint().equals(port.input()))
                    return new Admission(AdmissionStatus.IDENTITY_CONFLICT, this);
                var returnTarget = new Target(ingress.side(), context.returnEndpoint(), ingress.side() == Side.INTERNAL ? Optional.of(member) : Optional.empty());
                String returnName = ingress.side() == Side.EXTERNAL ? port.output() : publicName;
                returnEndpoint = Optional.of(returnName);
                nextPending.put(context.correlation(), new ReturnPath(new Ingress(target.side(), returnName), returnTarget,
                    target.side() == Side.INTERNAL ? Optional.of(member.identity()) : Optional.empty()));
            }
        }
        var admission = mailbox.admit(envelope, limits.mailbox());
        if (admission.status() != AdmissionStatus.ACCEPTED) return new Admission(admission.status(), this);
        var nextForwards = new ArrayList<>(forwards); nextForwards.add(new Forward(ingress, target, returnEndpoint));
        var nextPorts = new LinkedHashMap<>(receiptPorts); nextPorts.put(envelope.source(), ingress);
        return new Admission(AdmissionStatus.ACCEPTED, new ClusterState(configuration, admission.state(), nextForwards, nextPending, nextPorts));
    }
    private static Optional<Map.Entry<String, ClusterConfiguration.Port>> route(ClusterConfiguration config, Ingress ingress) {
        return config.ports().entrySet().stream().filter(entry -> (ingress.side() == Side.EXTERNAL ? entry.getKey() : entry.getValue().output()).equals(ingress.port())).findFirst();
    }
    private static void requireMember(ClusterConfiguration configuration, Target target) {
        if (target.member().filter(member -> !configuration.members().containsValue(member)).isPresent())
            throw new IllegalArgumentException("Target member is not in cluster");
    }
    private static void validateExchanges(ClusterConfiguration config, MailboxState mailbox, List<Forward> forwards,
        Map<RequestCorrelation, ReturnPath> pending) {
        var replies = new HashMap<RequestCorrelation, Integer>();
        for (int i = 0; i < mailbox.queued().size(); i++) {
            if (mailbox.queued().get(i).protocol() instanceof MessageProtocol.Reply reply) {
                if (pending.containsKey(reply.correlation()) || replies.putIfAbsent(reply.correlation(), i) != null)
                    throw new IllegalArgumentException("Reply correlation was not consumed exactly once");
            }
        }
        for (int i = 0; i < mailbox.queued().size(); i++) {
            if (!(mailbox.queued().get(i).protocol() instanceof MessageProtocol.Request request)) continue;
            var forward = forwards.get(i); var port = route(config, forward.ingress()).orElseThrow().getValue();
            var member = config.members().get(port.member());
            var returnTarget = new Target(forward.ingress().side(), request.context().returnEndpoint(),
                forward.ingress().side() == Side.INTERNAL ? Optional.of(member) : Optional.empty());
            var expected = new ReturnPath(new Ingress(forward.target().side(), forward.returnEndpoint().orElseThrow()), returnTarget,
                forward.target().member().map(ClusterConfiguration.Member::identity));
            var correlation = request.context().correlation(); var path = pending.get(correlation);
            if (path != null) {
                if (!path.equals(expected)) throw new IllegalArgumentException("Queued request return path differs from admission");
            } else {
                Integer index = replies.get(correlation);
                if (index == null || index <= i || !forwards.get(index).target().equals(expected.target())
                    || !forwards.get(index).ingress().equals(expected.expected()))
                    throw new IllegalArgumentException("Queued request lost its frozen return path");
            }
        }
    }
    private static <K,V> Map<K,V> copy(Map<K,V> source) {
        var copy = new LinkedHashMap<K,V>(); source.forEach((k,v) -> copy.put(Objects.requireNonNull(k), Objects.requireNonNull(v)));
        return Collections.unmodifiableMap(copy);
    }
}
