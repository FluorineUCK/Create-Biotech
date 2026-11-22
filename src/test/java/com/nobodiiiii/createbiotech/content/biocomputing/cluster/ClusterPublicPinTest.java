package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterRouterTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ClusterPublicPinTest {
    @Test void allOtherDraftEditsPreserveExportsAndConflictsDoNotPartiallyPublish() {
        var original = exported(); var wire = ClusterXBusWireTest.pair().wires().get("wire");
        var signal = new SimpleIoWire(new SimpleIoWire.Pin("A", "left", "p0"), new SimpleIoWire.Pin("B", "right", "p1"), false);
        var actions = List.<java.util.function.Consumer<ClusterControlDraft>>of(
            d -> d.addMember("C", new ClusterControlView.Candidate(new net.minecraft.core.BlockPos(9, 70, 9), Optional.of(UUID.randomUUID()), List.of("in"), true, true, "ready")),
            d -> d.putPort(null, "old", "A", "left", "send", "external", false), d -> d.removePort("old"),
            d -> d.putConnection(null, "buffered", "A", "out", "B", "right", false), d -> d.removeConnection("buffered"),
            d -> d.putWire(null, "data", wire), d -> d.removeWire("data"), d -> d.putSignal(null, "voltage", signal), d -> d.removeSignal("voltage"));
        var draft = new ClusterControlDraft(original);
        for (var action : actions) { action.accept(draft); assertEquals(original.publicPins(), draft.configuration().publicPins()); }
        var before = draft.configuration();
        assertThrows(IllegalArgumentException.class, () -> draft.putPort(null, "io", "A", "left", "reply", "external", false));
        assertThrows(IllegalArgumentException.class, () -> draft.putPublicPin(null, "io", original.publicPins().get("io")));
        assertThrows(IllegalArgumentException.class, () -> draft.putPublicPin("io", "new", new XBusPublicPin(new XBusWire.Pin("absent", "left", "x0"), true)));
        assertEquals(before, draft.configuration());
        draft.putPublicPin("io", "renamed", new XBusPublicPin(original.publicPins().get("io").target(), false));
        assertFalse(draft.configuration().publicPins().containsKey("io")); assertFalse(draft.configuration().publicPins().get("renamed").enabled());
        draft.removePublicPin("renamed"); assertTrue(draft.configuration().publicPins().isEmpty());
    }

    @Test void exportsShareExistingPortCapacityAndFailedSaveRetainsEveryOldValue() {
        var router = new ClusterRouter(() -> {}); var original = exported();
        assertEquals("configured", router.configure(original, LIMITS)); var before = router.write();
        var pins = new LinkedHashMap<String, XBusPublicPin>();
        for (int i = 0; i < LIMITS.configurationEntries(); i++) pins.put("io" + i, original.publicPins().get("io"));
        var excessive = new ClusterConfiguration(original.members(), Map.of("old", new ClusterConfiguration.Port("A", "left", "reply", "outside", false)),
            Map.of(), Map.of(), Map.of(), pins);
        assertEquals("configuration_limit", router.configure(excessive, LIMITS)); assertEquals(before, router.write());
        var tiny = new ClusterLimits(LIMITS.configurationEntries(), LIMITS.mailbox(), 0, LIMITS.archive());
        assertNotEquals("configured", router.configure(original, tiny)); assertEquals(before, router.write());
    }

    @Test void oldArchivesNeverInventExportsAndSchemaFiveRequiresItsCompleteTable() {
        var state = ClusterState.empty().configure(exported());
        var fields = new LinkedHashMap<>(ClusterNbtCodec.value(state).fields());
        assertEquals(new DataValue.IntegerValue(5), fields.get("version"));
        var cfg = new LinkedHashMap<>(((DataValue.RecordValue)fields.get("configuration")).fields());
        cfg.remove("public_pins"); fields.put("configuration", new DataValue.RecordValue(cfg));
        assertThrows(ArchiveException.class, () -> decode(fields));
        fields.put("version", new DataValue.IntegerValue(4)); assertTrue(decode(fields).configuration().publicPins().isEmpty());
        cfg.put("public_pins", DataValue.NullValue.INSTANCE); fields.put("configuration", new DataValue.RecordValue(cfg));
        assertThrows(ArchiveException.class, () -> decode(fields), "Old archives reject unknown fields rather than silently granting new permissions");
        fields.put("version", new DataValue.IntegerValue(5)); assertThrows(ArchiveException.class, () -> decode(fields));
    }

    private static ClusterState decode(Map<String, DataValue> fields) {
        return ClusterNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE), ARCHIVE);
    }

    @Test void memberRenameAndRemovalCannotLeavePublicPinsDanglingOrSilentlyEraseThem() {
        var draft = new ClusterControlDraft(exported());
        draft.renameMember("A", "renamed");
        assertEquals(new XBusPublicPin(new XBusWire.Pin("renamed", "left", "x0"), true), draft.configuration().publicPins().get("io"));
        draft.removeMember("B");
        assertTrue(draft.configuration().publicPins().containsKey("io"));
        draft.removeMember("renamed");
        assertTrue(draft.configuration().publicPins().isEmpty());
    }

    static ClusterConfiguration exported() {
        var pair = ClusterXBusWireTest.pair();
        return new ClusterConfiguration(pair.members(), Map.of(), Map.of(), Map.of(), Map.of(),
            Map.of("io", new XBusPublicPin(new XBusWire.Pin("A", "left", "x0"), true)));
    }

    @Test void publicPinMenuValueSurvivesSaveAndReloadWithoutCreatingABufferedRoute() {
        var original = new ClusterConfiguration(Map.of("worker", WORKER), Map.of());
        var fields = new LinkedHashMap<>(((DataValue.RecordValue)ProgramEditorWire.decodeValue(
            ClusterControlWire.encodeConfiguration(original, ARCHIVE), ARCHIVE)).fields());
        fields.put("public_pins", new DataValue.RecordValue(Map.of("io", new DataValue.RecordValue(Map.of(
            "member", new DataValue.TextValue("worker"), "endpoint", new DataValue.TextValue("input"),
            "pin", new DataValue.TextValue("x0"), "enabled", new DataValue.BooleanValue(true))))));
        var value = new DataValue.RecordValue(fields);
        var config = ClusterControlWire.decodeConfiguration(ProgramEditorWire.encodeValue(value, ARCHIVE), ARCHIVE);
        assertEquals(value, ProgramEditorWire.decodeValue(ClusterControlWire.encodeConfiguration(config, ARCHIVE), ARCHIVE));
        var router = new ClusterRouter(() -> {});
        assertEquals("configured", router.configure(config, LIMITS));
        var loaded = new ClusterRouter(() -> {}); loaded.read(router.write());
        assertEquals(config, loaded.snapshot(LIMITS).orElseThrow().configuration());
        assertTrue(config.ports().isEmpty()); assertTrue(config.wires().isEmpty());
        assertTrue(config.outputNames("worker").isEmpty());
        assertTrue(loaded.snapshot(LIMITS).orElseThrow().mailbox().queued().isEmpty());
    }
}
