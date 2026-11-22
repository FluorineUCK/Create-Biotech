package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowControl;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

final class FlowControlCodec {
    private FlowControlCodec() {}
    static FlowControl decode(DataValue value, boolean wideOperations) {
        return decode(value, wideOperations, false);
    }
    static FlowControl decode(DataValue value, boolean wideOperations, boolean tasks) {
        if (!fields(value).keySet().equals(Set.of("generation", "peers"))) throw ArchiveException.invalid("Invalid flow control fields");
        var peers = new LinkedHashMap<UUID, FlowControl.Peer>();
        fields(at(value, "peers")).forEach((key, child) -> {
            FlowControl.Peer peer;
            if (tasks && fields(child).containsKey("slots")) {
                if (!fields(child).keySet().equals(Set.of("generation", "slots"))) throw ArchiveException.invalid("Invalid task peer fields");
                var slots = new LinkedHashMap<Integer, FlowControl.Frontier>();
                fields(at(child, "slots")).forEach((name, slot) -> {
                    int id = Integer.parseInt(name);
                    if (!Integer.toString(id).equals(name) || !fields(slot).keySet().equals(Set.of("flow", "incarnation", "retired_through", "retired")))
                        throw ArchiveException.invalid("Invalid task frontier fields");
                    slots.put(id, new FlowControl.Frontier(UUID.fromString(string(at(slot, "flow"))), number(at(slot, "incarnation")),
                        number(at(slot, "retired_through")), bool(at(slot, "retired"))));
                });
                peer = new FlowControl.Peer(number(at(child, "generation")), slots);
            } else {
                if (!fields(child).keySet().equals(Set.of("generation", "flow", "retired_through"))) throw ArchiveException.invalid("Invalid peer frontier fields");
                long operation = number(at(child, "retired_through"));
                if (operation < -1 || !wideOperations && operation > Integer.MAX_VALUE) throw ArchiveException.invalid("Invalid retired operation position");
                peer = new FlowControl.Peer(number(at(child, "generation")), UUID.fromString(string(at(child, "flow"))), operation);
            }
            if (peers.put(UUID.fromString(key), peer) != null)
                throw ArchiveException.invalid("Duplicate peer identity");
        });
        return new FlowControl(number(at(value, "generation")), peers);
    }
}
