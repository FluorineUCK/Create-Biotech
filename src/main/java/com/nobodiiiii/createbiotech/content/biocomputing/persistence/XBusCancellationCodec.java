package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;

/** Uses the owner's existing bounded typed archive, not a Java or world-object reference. */
public final class XBusCancellationCodec {
    private XBusCancellationCodec() {}
    public static XBusCancellation decode(DataValue value) { return decode(value, 22); }
    /** The owner envelope, not the local source dialect, admits every participant's position fields. */
    public static XBusCancellation decode(DataValue value, int ownerVersion) {
        if (!fields(value).keySet().equals(Set.of("holder", "writers", "proof"))) throw ArchiveException.invalid("Invalid cancellation fields");
        var holder = FlowBlockNbtCodec.position(at(value, "holder"), ownerVersion);
        var proof = FlowControlCodec.decode(at(value, "proof"), true, true);
        if (proof.generation() != 0 || !proof.peers().keySet().equals(Set.of(holder.computer()))) throw ArchiveException.invalid("Invalid cancellation proof");
        var writers = new LinkedHashSet<XBusReceipt.Position>();
        for (var p : list(at(value, "writers"))) if (!writers.add(FlowBlockNbtCodec.position(p, ownerVersion))) throw ArchiveException.invalid("Duplicate cancellation participant");
        return new XBusCancellation(holder, writers, proof.peers().get(holder.computer()));
    }
}
