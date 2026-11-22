package com.nobodiiiii.createbiotech.content.biocomputing.network;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;

/** Pure, transient inspection of a complete supplied group. Does not restore, execute or retain payloads. */
final class XBusRecovery {
    private record Operation(UUID computer, UUID flow, long generation, long operation) {
        static Operation of(XBusReceipt.Position p) { return new Operation(p.computer(), p.flow(), p.generation(), p.operation()); }
    }
    private XBusRecovery() {}

    static XBusAccess.Inspection inspect(List<FlowBlockHost.Saved> participants) {
        return inspect(participants, receipt -> false);
    }
    static XBusAccess.Inspection inspect(List<FlowBlockHost.Saved> participants,
            java.util.function.Predicate<XBusReceipt> durablyCompleted) {
        var owners = new HashMap<UUID, FlowBlockHost.Saved>();
        for (var saved : participants) owners.put(saved.state().computer(), saved);
        // Retired operations remain retired even when reset has compacted the last receipt away.
        for (var saved : participants) for (var id : saved.control().peers().keySet()) {
            var peer = owners.get(id); if (peer == null) continue;
            var rejected = saved.control().check(peer.state(), peer.control().generation());
            if (rejected.isPresent()) return denied(rejected.orElseThrow());
        }
        var claims = new LinkedHashMap<Operation, XBusReceipt>();
        var held = new HashMap<Operation, XBusReceipt>();
        var receipts = new LinkedHashSet<XBusReceipt>();
        for (var saved : participants) for (var receipt : saved.receipts()) {
            for (var local : receipt.local(saved.state().computer())) held.put(Operation.of(local), receipt);
            receipts.add(receipt);
            for (var position : receipt.positions()) {
                var previous = claims.putIfAbsent(Operation.of(position), receipt);
                if (previous != null && !previous.equals(receipt)) return denied(XBusExchange.Status.HISTORY_CONFLICT);
            }
        }
        boolean pending = false;
        var repairs = new LinkedHashSet<XBusNetwork.Selection>();
        for (var receipt : receipts) {
            // Mixed newer saves may retain a completed payload on only one owner. The
            // dimension journal checks old operation positions against the durable proof.
            if (durablyCompleted.test(receipt)) continue;
            boolean missing = false, exact = true;
            for (var position : receipt.positions()) {
                var peer = owners.get(position.computer());
                if (peer == null) { exact = false; continue; }
                if (FlowControl.Peer.of(peer).supersedes(position)) continue;
                if (peer.control().generation() < position.generation()) return denied(XBusExchange.Status.RETIRED);
                var context = peer.context(position);
                if (context.isEmpty()) return denied(XBusExchange.Status.HISTORY_CONFLICT);
                if (held.containsKey(Operation.of(position))) continue;
                var state = context.orElseThrow();
                if (state.operation() > position.operation()) return denied(XBusExchange.Status.HISTORY_CONFLICT);
                if (state.operation() == position.operation() && state.cursor() != position.cursor()) return denied(XBusExchange.Status.HISTORY_CONFLICT);
                missing = true; exact &= state.operation() == position.operation();
            }
            if (missing) {
                pending = true;
                if (exact) {
                    for (var read : receipt.readers()) repairs.add(new XBusNetwork.Selection(pin(read), pin(receipt.writer())));
                    for (var write : receipt.otherWriters()) repairs.add(new XBusNetwork.Selection(pin(receipt.reader()), pin(write)));
                }
            }
        }
        return new XBusAccess.Inspection(pending ? Optional.of(XBusExchange.Status.RECOVERY_REQUIRED) : Optional.empty(), List.copyOf(repairs));
    }
    private static XBusNetwork.Pin pin(XBusReceipt.Position p) { return new XBusNetwork.Pin(p.computer(), p.pin()); }
    private static XBusAccess.Inspection denied(XBusExchange.Status status) {
        return new XBusAccess.Inspection(Optional.of(status), List.of());
    }
}
