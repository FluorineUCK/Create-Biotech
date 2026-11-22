package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import org.junit.jupiter.api.Test;

class LibraryReadDriverTest {
    static final ArchiveLimits ARCHIVE = new ArchiveLimits(10000, 100000, 1000000);
    static ProgramDocument load() throws Exception {
        try (var input = LibraryReadDriverTest.class.getClassLoader().getResourceAsStream("assets/create_biotech/biocomputing/examples/library_read_driver.bcp")) {
            assertNotNull(input, "The public editable positioned-read driver must be packaged");
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(input.readAllBytes()).strip()), ARCHIVE);
        }
    }
    @Test void publicMatchGraphIgnoresUnrelatedAndOldRepliesWithoutTreatingMissingDataAsZero() throws Exception {
        var document = load();
        var packet = (DataValue.RecordValue)document.flow().orElseThrow().initial().field("packet");
        var valid = new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("library_reply"),
            "target", packet.field("target"), "request", packet.field("request"), "code", new DataValue.TextValue("idle"),
            "sequence", new DataValue.IntegerValue(0), "value", DataValue.NullValue.INSTANCE));
        var old = new LinkedHashMap<>(valid.fields()); old.put("request", new DataValue.TextValue("old-run:1"));
        var elsewhere = new LinkedHashMap<>(valid.fields()); elsewhere.put("target", new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(12))));
        for (var frame : List.of(new DataValue.RecordValue(Map.of("alarm", new DataValue.BooleanValue(true))),
                new DataValue.RecordValue(old), new DataValue.RecordValue(elsewhere), valid)) {
            var machine = new ComputationMachine(document.archive().compile(new ResourceLimits(192, 49152, 6)).select("match"));
            machine.begin(new DataValue.RecordValue(Map.of("packet", packet, "received",
                com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.receive("received", frame))));
            for (int tick = 0; machine.report().status() == ComputationMachine.Status.RUNNING; tick++) {
                assertTrue(tick < 300); machine.tick();
            }
            assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status(), () -> machine.report().diagnostic().toString());
            assertEquals(new DataValue.BooleanValue(frame.equals(valid)), machine.report().output().orElseThrow().field("matched"));
        }
        var downgraded = new LinkedHashMap<>(ProgramDocumentCodec.encode(document).fields());
        downgraded.put("version", new DataValue.IntegerValue(11));
        assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException.class,
            () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(downgraded)));
    }
    @Test void publishedDriverIsAnOrdinaryEditableProgramWithPinnedSubgraphsAndNoHiddenService() throws Exception {
        var document = load(); document.validate(new ResourceLimits(192, 49152, 6));
        assertEquals(document, new ProgramWorkspace(document).snapshot());
        assertFalse(document.archive().modules().isEmpty());
        assertTrue(document.archive().program().sends().isEmpty());
        assertTrue(document.archive().program().cells().isEmpty());
        var workspace = new ProgramWorkspace(document);
        for (var key : document.archive().modules().keySet()) {
            var exported = workspace.exportSubgraph(key, ARCHIVE);
            assertEquals(exported, SubgraphDocumentWire.decode(SubgraphDocumentWire.encode(exported, ARCHIVE), ARCHIVE));
        }
        assertThrows(ComputationException.class, () -> document.validate(new ResourceLimits(32, 32768, 4)), "A larger driver cannot borrow a Cluster neighbor's capacity");
    }
}
