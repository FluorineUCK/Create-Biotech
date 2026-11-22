package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterRouterTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import java.util.*;
import org.junit.jupiter.api.Test;

class ClusterSignalWireTest {
    @Test void typedWiresRespectSharedCapacityStrictSchemaAndMemberRenameOrRemoval() {
        var base = ClusterXBusWireTest.pair();
        var wire = new SimpleIoWire(new SimpleIoWire.Pin("A", "left", "p0"), new SimpleIoWire.Pin("B", "right", "p1"), true);
        var config = new ClusterConfiguration(base.members(), base.ports(), base.connections(), base.wires(), Map.of("signal", wire));
        var draft = new ClusterControlDraft(config); draft.renameMember("A", "renamed");
        assertEquals(wire.rename("A", "renamed"), draft.configuration().signals().get("signal"));
        draft.removeWire("wire"); assertEquals(1, draft.configuration().signals().size());
        draft.removeMember("renamed"); assertTrue(draft.configuration().signals().isEmpty());
        var many = new LinkedHashMap<String, SimpleIoWire>();
        for (int i = 0; i < LIMITS.configurationEntries(); i++) many.put("signal" + i, wire);
        var router = new ClusterRouter(() -> {}); assertEquals("configured", router.configure(config, LIMITS)); var before = router.write();
        assertEquals("configuration_limit", router.configure(new ClusterConfiguration(base.members(), Map.of(), Map.of(), base.wires(), many), LIMITS));
        assertEquals(before, router.write());
        assertThrows(IllegalArgumentException.class, () -> new ClusterConfiguration(base.members(), Map.of(), Map.of(), base.wires(), Map.of("wire", wire)));
        var encoded = com.nobodiiiii.createbiotech.content.biocomputing.persistence.ClusterNbtCodec.value(ClusterState.empty().configure(config));
        assertEquals(new DataValue.IntegerValue(4), encoded.field("version"));
        var fields = new LinkedHashMap<>(encoded.fields()); fields.put("version", new DataValue.IntegerValue(3));
        assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException.class, () ->
            com.nobodiiiii.createbiotech.content.biocomputing.persistence.ClusterNbtCodec.decode(
                com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE), ARCHIVE));
    }
    @Test void explicitSignalWireSurvivesMenuDraftLegacyEditsAndRouterSave() {
        var base = ClusterXBusWireTest.pair();
        var encoded = (DataValue.RecordValue) ProgramEditorWire.decodeValue(ClusterControlWire.encodeConfiguration(base, ARCHIVE), ARCHIVE);
        var fields = new LinkedHashMap<>(encoded.fields());
        fields.put("signals", com.nobodiiiii.createbiotech.content.biocomputing.editor.EditorValueFormat.parse(
            com.nobodiiiii.createbiotech.content.biocomputing.editor.EditorValueFormat.Kind.JSON,
            """
            {"voltage":{"left":{"member":"A","endpoint":"left","pin":"p0"},
            "right":{"member":"B","endpoint":"right","pin":"p1"},"enabled":true}}
            """));
        var signal = ClusterControlWire.decodeConfiguration(ProgramEditorWire.encodeValue(new DataValue.RecordValue(fields), ARCHIVE), ARCHIVE);
        assertNotEquals(base, signal);
        var draft = new ClusterControlDraft(signal);
        draft.putPort(null, "public", "A", "left", "output", "outside", false); draft.removePort("public");
        draft.putConnection(null, "old", "A", "message", "B", "right", false); draft.removeConnection("old");
        assertEquals(signal, draft.configuration());
        var router = new ClusterRouter(() -> {}); assertEquals("configured", router.configure(signal, LIMITS));
        var restored = new ClusterRouter(() -> {}); restored.read(router.write());
        assertEquals(signal, restored.snapshot(LIMITS).orElseThrow().configuration());
        assertEquals(signal, ClusterControlWire.decodeConfiguration(ClusterControlWire.encodeConfiguration(signal, ARCHIVE), ARCHIVE));
    }
}
