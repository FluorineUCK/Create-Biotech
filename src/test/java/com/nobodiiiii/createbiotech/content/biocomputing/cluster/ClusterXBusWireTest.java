package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterRouterTest.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.*;

class ClusterXBusWireTest {
    @Test void menuRoundTripDoesNotSilentlyEraseExplicitWires() {
        var config = pair();
        assertEquals(config, ClusterControlWire.decodeConfiguration(ClusterControlWire.encodeConfiguration(config, ARCHIVE), ARCHIVE));
        var draft = new ClusterControlDraft(config);
        draft.renameMember("A", "renamed");
        assertEquals(config.wires().get("wire").rename("A", "renamed"), draft.configuration().wires().get("wire"));
        draft.removeMember("renamed");
        assertTrue(draft.configuration().wires().isEmpty());
    }
    static ClusterConfiguration pair() {
        return new ClusterConfiguration(Map.of("A", WORKER, "B", new ClusterConfiguration.Member(CALLER, new BlockPos(7, 70, 9))),
            Map.of(), Map.of(), Map.of("wire", new XBusWire(new XBusWire.Pin("A", "left", "x0"), new XBusWire.Pin("B", "right", "x1"), true)));
    }
    @Test void explicitPinsSurviveRouterReloadWithoutBecomingBufferedConnections() {
        var members = Map.of("A", WORKER, "B", new ClusterConfiguration.Member(CALLER, new BlockPos(7, 70, 9)));
        var wire = new XBusWire(new XBusWire.Pin("A", "left", "x0"), new XBusWire.Pin("B", "right", "x1"), true);
        var config = new ClusterConfiguration(members, Map.of(), Map.of(), Map.of("wire", wire));
        var router = new ClusterRouter(() -> {});
        assertEquals("configured", router.configure(config, LIMITS));
        var loaded = new ClusterRouter(() -> {}); loaded.read(router.write());
        assertEquals(config, loaded.snapshot(LIMITS).orElseThrow().configuration());
        assertTrue(config.connections().isEmpty());
        assertTrue(config.outputNames("A").isEmpty());
        assertTrue(loaded.snapshot(LIMITS).orElseThrow().mailbox().queued().isEmpty());
    }
    @Test void sharedPinsDoNotChooseAFirstPeerAndDisabledBranchesDoNotConduct() {
        var wire = pair().wires().get("wire");
        var branch = new XBusWire(wire.left(), new XBusWire.Pin("B", "right", "x2"), true);
        assertFalse(wire.isolated(List.of(wire, branch))); assertFalse(branch.isolated(List.of(branch, wire)));
        assertTrue(wire.isolated(List.of(wire, new XBusWire(branch.left(), branch.right(), false))));
        var renamedGrant = new XBusWire(new XBusWire.Pin("A", "different_name", "x0"), branch.right(), true);
        assertFalse(wire.isolated(List.of(wire, renamedGrant)), "Grant names cannot disguise one shared physical pin");
    }
    @Test void wireLimitRejectsWholeCandidateAndDraftLegacyEditsPreserveWires() {
        var config = pair(); var router = new ClusterRouter(() -> {});
        assertEquals("configured", router.configure(config, LIMITS)); var before = router.write();
        var wires = new LinkedHashMap<String, XBusWire>();
        for (int i = 0; i < LIMITS.configurationEntries() + 1; i++) wires.put("wire" + i, config.wires().get("wire"));
        assertEquals("configuration_limit", router.configure(new ClusterConfiguration(config.members(), Map.of(), Map.of(), wires), LIMITS));
        assertEquals(before, router.write());
        var draft = new ClusterControlDraft(config);
        draft.putPort(null, "public", "A", "left", "output", "outside", false);
        draft.putConnection(null, "old", "A", "message", "B", "right", false);
        draft.removePort("public"); draft.removeConnection("old");
        assertEquals(config, draft.configuration());
        assertThrows(IllegalArgumentException.class, () -> draft.putWire("wire", "wire", new XBusWire(
            new XBusWire.Pin("missing", "in", "x0"), config.wires().get("wire").right(), true)));
        assertEquals(config, draft.configuration());
    }
    @Test void oldSchemasRemainUnwiredAndNewSchemaCannotOmitItsWireTable() {
        var state = ClusterState.empty().configure(pair());
        var fields = new LinkedHashMap<>(ClusterNbtCodec.value(state).fields());
        var cfg = new LinkedHashMap<>(((DataValue.RecordValue)fields.get("configuration")).fields());
        cfg.remove("wires");
        fields.put("configuration", new DataValue.RecordValue(cfg));
        var noWires = new ClusterConfiguration(pair().members(), Map.of(), Map.of());
        fields.put("version", new DataValue.IntegerValue(2));
        assertEquals(noWires, decode(fields).configuration());
        fields.put("version", new DataValue.IntegerValue(3));
        assertThrows(ArchiveException.class, () -> decode(fields));
    }
    private static ClusterState decode(Map<String, DataValue> fields) {
        return ClusterNbtCodec.decode(
            ValueNbtCodec.encode(
                new DataValue.RecordValue(fields), ARCHIVE), ARCHIVE);
    }

}
