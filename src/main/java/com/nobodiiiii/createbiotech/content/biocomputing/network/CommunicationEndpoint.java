package com.nobodiiiii.createbiotech.content.biocomputing.network;

import java.util.Optional;
import java.util.List;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.AdmissionStatus;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageEnvelope;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.ReceptionStatus;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.minecraft.core.Direction;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange;

/**
 * Read-only advertisement and explicit value admission, shared by Computers and future cluster gateways.
 * Providers expose only immutable values; discovery never restores a program or accesses RAM.
 * A gateway is a terminal, not an implicit cable bridge or shared-memory cluster.
 * Faces in the same domain share logical ports; different domains on one block remain separate.
 * Register on accepted DataPort faces even while closed; null-side support is not required.
 */
@FunctionalInterface
public interface CommunicationEndpoint {
    BlockCapability<CommunicationEndpoint, Direction> CAPABILITY = BlockCapability.createSided(
        ResourceLocation.fromNamespaceAndPath("create_biotech", "communication_endpoint"), CommunicationEndpoint.class);

    /** Empty when closed, unconfigured, corrupt or unavailable. Query fresh; never cache advertisements. */
    Optional<EndpointConfiguration> advertisement();

    /** Stable local domain key, not a shared network or memory identifier. */
    default String domain() { return "primary"; }

    /** Physical device identity for explicit bindings, not a discovery field or a receive grant. */
    default Optional<UUID> communicationIdentity() { return Optional.empty(); }

    /**
     * Bounded by the caller's endpoint budget. Only enabled ports appear in discovery.
     * A configured disabled port may be included to diagnose an explicitly addressed name as closed;
     * it is not a receive grant. Legacy providers that omit closed metadata remain undisclosed.
     */
    default List<EndpointConfiguration> advertisements() { return advertisement().stream().toList(); }

    /** An open provider may durably enqueue values. Never run a recipient graph or expose member RAM here. */
    default AdmissionStatus receive(MessageEnvelope envelope) { return AdmissionStatus.UNSUPPORTED; }

    /** The transport passes the exact revalidated destination; it is never inferred from payload fields. */
    default AdmissionStatus receive(String port, MessageEnvelope envelope) { return receive(envelope); }

    /**
     * Opt-in direct reception into a ready, durably saved execution frame, not a buffered ACK.
     * The caller pins the target instance before offering. Cluster/Library buffered services do not
     * automatically support this operation; never implement it by forwarding to receive().
     */
    default ReceptionStatus receiveReady(String port, UUID expectedReceiver, MessageEnvelope envelope) {
        return ReceptionStatus.UNSUPPORTED;
    }

    /** An open device may expose a declared XBus pin's current instruction, never another Computer's RAM. */
    default Optional<XBusPortState> xbusPort(String endpoint, String pin) { return Optional.empty(); }

    /** Read-only, granted hardware pin names. Gateways list only their explicitly mapped pin. */
    default List<String> xbusPins(String endpoint) { return List.of(); }

    /**
     * Transient authority for exactly one observed continuation, not a wire or a receive queue.
     * Cable bindings additionally require XBusAccess.validated: a final observation-only proof of this
     * provider's identity/domain, grant and physical readiness. Wrappers must preserve member proofs.
     */
    default Optional<XBusAccess> xbusAccess(String endpoint, XBusPortState expected) { return Optional.empty(); }

    /** The physical coordinator must validate its explicit connection before invoking a rendezvous. */
    default XBusExchange.Status exchangeXBus(String endpoint, XBusPortState expected, CommunicationEndpoint peer,
            String peerEndpoint, XBusPortState expectedPeer) {
        java.util.Objects.requireNonNull(expected); java.util.Objects.requireNonNull(peer); java.util.Objects.requireNonNull(expectedPeer);
        var left = xbusAccess(endpoint, expected); var right = peer.xbusAccess(peerEndpoint, expectedPeer);
        if (left.isEmpty() || right.isEmpty()) return XBusExchange.Status.UNSUPPORTED;
        return left.orElseThrow().exchange(right.orElseThrow());
    }
}
