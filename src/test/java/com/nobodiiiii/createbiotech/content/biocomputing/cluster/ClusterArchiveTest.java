package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterRouterTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterState.Side.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class ClusterArchiveTest {
    @Test void pendingMemberReturnPortCannotChangeAfterOriginalRequestLeavesQueue() {
        var router = configured(); var request = request(MEMBER, 0, "worker");
        router.receive(INTERNAL, "result", request, LIMITS); router.acknowledge(router.outbound(LIMITS).orElseThrow(), LIMITS);
        var corrupt = withFirstTargetPort((CompoundTag)router.write(), "pending", "other");
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class, () -> ClusterNbtCodec.decode(corrupt, ARCHIVE)).code());
    }

    @Test void queuedMemberReplyCannotEscapeConfiguredInputPort() {
        var router = configured(); var request = request(MEMBER, 0, "worker");
        router.receive(INTERNAL, "result", request, LIMITS); router.acknowledge(router.outbound(LIMITS).orElseThrow(), LIMITS);
        router.receive(EXTERNAL, "calculate", reply(CALLER, 0, request), LIMITS);
        var corrupt = withFirstTargetPort((CompoundTag)router.write(), "forwards", "other");
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class, () -> ClusterNbtCodec.decode(corrupt, ARCHIVE)).code());
    }

    private static CompoundTag withFirstTargetPort(CompoundTag tag, String table, String port) {
        var root = (DataValue.RecordValue)ValueNbtCodec.decode(tag, ARCHIVE);
        var rows = new ArrayList<>(((DataValue.ListValue)root.field(table)).values());
        var row = (DataValue.RecordValue)rows.getFirst(); var target = (DataValue.RecordValue)row.field("target");
        rows.set(0, replace(row, "target", replace(target, "port", new DataValue.TextValue(port))));
        return ValueNbtCodec.encode(replace(root, table, new DataValue.ListValue(rows)), ARCHIVE);
    }
    private static DataValue.RecordValue replace(DataValue.RecordValue record, String key, DataValue value) {
        var fields = new LinkedHashMap<>(record.fields()); fields.put(key, value); return new DataValue.RecordValue(fields);
    }
    @Test void replyAcceptedBeforeRequestAcknowledgementStillCarriesTheFrozenReturnPath() {
        var router = configured(); var request = request(CALLER, 0, "client");
        router.receive(EXTERNAL, "calculate", request, LIMITS); var sent = router.outbound(LIMITS).orElseThrow();
        assertEquals(AdmissionStatus.ACCEPTED, router.receive(INTERNAL, "result", reply(MEMBER, 0, request), LIMITS));
        var restored = new ClusterRouter(() -> {}); restored.read(router.write());
        assertEquals(sent, restored.outbound(LIMITS).orElseThrow()); assertTrue(restored.acknowledge(sent, LIMITS));
        assertEquals("client", restored.outbound(LIMITS).orElseThrow().target().port());
        assertTrue(restored.snapshot(LIMITS).orElseThrow().pending().isEmpty());
    }
    @Test void unknownVersionAndUnknownFieldsAreRejectedWithoutReplacingDurableData() {
        var router = configured(); var data = ValueNbtCodec.decode((CompoundTag)router.write(), ARCHIVE);
        var fields = new LinkedHashMap<>(((DataValue.RecordValue)data).fields());
        fields.put("version", new DataValue.IntegerValue(9)); var future = ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE);
        assertEquals(ArchiveException.Code.UNSUPPORTED_VERSION, assertThrows(ArchiveException.class, () -> ClusterNbtCodec.decode(future, ARCHIVE)).code());
        fields.put("version", new DataValue.IntegerValue(1)); fields.put("unexpected", new DataValue.IntegerValue(0));
        var unknown = ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE);
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class, () -> ClusterNbtCodec.decode(unknown, ARCHIVE)).code());
        var restored = new ClusterRouter(() -> fail("must not overwrite unknown archive")); restored.read(future);
        assertTrue(restored.outbound(LIMITS).isEmpty()); assertTrue(restored.diagnostic().isPresent()); assertEquals(future, restored.write());
    }

    @Test void requestWithoutItsPendingPathCannotRestoreAsForwardable() {
        var router = configured(); router.receive(EXTERNAL, "calculate", request(CALLER, 0, "client"), LIMITS);
        var fields = new LinkedHashMap<>(((DataValue.RecordValue)ValueNbtCodec.decode((CompoundTag)router.write(), ARCHIVE)).fields());
        fields.put("pending", new DataValue.ListValue(List.of()));
        var invalid = ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE);
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class, () -> ClusterNbtCodec.decode(invalid, ARCHIVE)).code());
    }

    @Test void queuedReplyCannotAlsoRemainAvailableAsAnUnconsumedPendingRequest() {
        var router = configured(); var request = request(CALLER, 0, "client"); router.receive(EXTERNAL, "calculate", request, LIMITS);
        var waiting = router.snapshot(LIMITS).orElseThrow(); router.acknowledge(router.outbound(LIMITS).orElseThrow(), LIMITS);
        router.receive(INTERNAL, "result", reply(MEMBER, 0, request), LIMITS); var replied = router.snapshot(LIMITS).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> new ClusterState(replied.configuration(), replied.mailbox(), replied.forwards(), waiting.pending(), replied.receiptPorts()));
    }

    @Test void sourceReceiptsAndPerMessageTargetsMustHaveMatchingMetadata() {
        var router = configured(); router.receive(EXTERNAL, "calculate", message(CALLER, 0), LIMITS); var state = router.snapshot(LIMITS).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> new ClusterState(state.configuration(), state.mailbox(), List.of(), state.pending(), state.receiptPorts()));
        assertThrows(IllegalArgumentException.class, () -> new ClusterState(state.configuration(), state.mailbox(), state.forwards(), state.pending(), Map.of()));
        var wrong = new ClusterState.Forward(new ClusterState.Ingress(EXTERNAL, "calculate"), new ClusterState.Target(INTERNAL, "another", Optional.of(WORKER)), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> new ClusterState(state.configuration(), state.mailbox(), List.of(wrong), state.pending(), state.receiptPorts()));
    }

    @Test void lowerStorageBudgetSuspendsRatherThanDiscardingQueueAndCanRecover() {
        var router = configured(); router.receive(EXTERNAL, "calculate", request(CALLER, 0, "client"), LIMITS); var expected = router.outbound(LIMITS).orElseThrow(); var before = router.write();
        var reduced = new ClusterLimits(8, LIMITS.mailbox(), 1, ARCHIVE);
        assertTrue(router.outbound(reduced).isEmpty()); assertEquals(Optional.of("memory_limit"), router.diagnostic()); assertEquals(before, router.write());
        assertEquals(expected, router.outbound(LIMITS).orElseThrow()); assertTrue(router.diagnostic().isEmpty());
        router.unload(); assertEquals(before, router.write()); assertEquals(expected, router.outbound(LIMITS).orElseThrow());
    }

    @Test void dirtyCallbackCanObserveOnlyFullyEncodedAdmission() {
        var holder = new ClusterRouter[1]; var seen = new ArrayList<ClusterState>();
        holder[0] = new ClusterRouter(() -> seen.add(ClusterNbtCodec.decode((CompoundTag)holder[0].write(), ARCHIVE)));
        var router = holder[0]; router.configure(configuration("display"), LIMITS);
        assertEquals(AdmissionStatus.ACCEPTED, router.receive(EXTERNAL, "calculate", request(CALLER, 0, "client"), LIMITS));
        assertEquals(2, seen.size()); assertEquals(1, seen.getLast().mailbox().queued().size()); assertEquals(1, seen.getLast().pending().size());
        assertEquals(router.snapshot(LIMITS).orElseThrow(), seen.getLast());
    }
}
