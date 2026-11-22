package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowProgramCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class XBusStatusFlowTest {
    private static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());
    private static final ComputerHostLimits LIMITS = new ComputerHostLimits(new ResourceLimits(100, 100_000, 1), FlowArchiveTest.ARCHIVE);

    static FlowProgram program() {
        var base = (DataValue.RecordValue)FlowProgramCodec.encode(new FlowProgram(Map.of(), List.of()));
        var fields = new LinkedHashMap<>(base.fields());
        fields.put("steps", new DataValue.ListValue(List.of(new DataValue.RecordValue(Map.of(
            "kind", new DataValue.TextValue("xbus_status"), "source", new DataValue.TextValue("probe"),
            "port", new DataValue.TextValue("x0"), "local", new DataValue.TextValue("code"))))));
        return FlowProgramCodec.decode(new DataValue.RecordValue(fields));
    }

    @Test void publicStatusInstructionReturnsFrozenCodeWithoutEnteringAWaitOrConsumingData() {
        for (var sample : List.of(Map.of("x0", 15), Map.of("x0", 0), Map.<String,Integer>of())) {
            var owner = new ComputerBlockHost(() -> {});
            owner.installFlow(program(), EMPTY, LIMITS, true);
            owner.step(LIMITS, true, sample);
            var result = owner.flowSnapshot().orElseThrow();
            assertEquals(FlowRuntime.Status.COMPLETE, result.state().status());
            assertEquals(new DataValue.IntegerValue(sample.getOrDefault("x0", 0)), result.state().locals().field("code"));
            assertEquals(1, result.state().work());
            assertTrue(result.receipts().isEmpty()); assertTrue(result.pinClaims().entries().isEmpty());
            assertEquals(SimpleIoState.EMPTY, result.state().signals());
            var restored = new ComputerBlockHost(() -> {}); restored.read(owner.write());
            restored.setPaused(true, LIMITS, true); assertTrue(restored.loaded()); assertEquals(result, restored.flowSnapshot().orElseThrow());
        }
    }
    @Test void statusDocumentsAndCheckpointsUseExplicitVersionsAndCannotMasqueradeAsOldInstructions() {
        var p = program();
        var document = new ProgramDocument(p.expressions(), Map.of(), Optional.empty(), Optional.of(FlowDocumentBody.fromProgram(p, EMPTY)));
        var encoded = ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(22), encoded.field("version"));
        assertEquals(document, ProgramDocumentCodec.decode(encoded));
        var oldDocument = new LinkedHashMap<>(encoded.fields()); oldDocument.put("version", new DataValue.IntegerValue(21));
        assertThrows(ArchiveException.class, () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(oldDocument)));
        var flow = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), p, EMPTY, LIMITS.hardware());
        var snapshot = new FlowNbtCodec.Snapshot(p, flow.checkpoint());
        var raw = FlowNbtCodec.encode(snapshot, LIMITS.archive());
        var value = (DataValue.RecordValue)ValueNbtCodec.decode(raw, LIMITS.archive());
        assertEquals(new DataValue.IntegerValue(21), value.field("version"));
        assertEquals(snapshot, FlowNbtCodec.decode(raw, LIMITS.archive()));
        var old = new LinkedHashMap<>(value.fields()); old.put("version", new DataValue.IntegerValue(20));
        var downgraded = new DataValue.RecordValue(old);
        assertThrows(ArchiveException.class, () -> FlowNbtCodec.decode(ValueNbtCodec.encode(downgraded, LIMITS.archive()), LIMITS.archive()));
    }
    @Test void managedTaskCanReadStatusWhileMainWaitsWithoutJoiningOrChangingItsPinClaim() {
        var key = new ModuleDefinition.Key("status", 1);
        var limits = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(LIMITS.hardware(), LIMITS.archive(), LIMITS.mailbox(), LIMITS.pins(), LIMITS.serial(), 2));
        var p = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("main", "x0", "packet", new DataType.RecordType(Map.of())),
            new FlowProgram.Procedure("status", key, Map.of(), Map.of("result", DataType.Scalar.INTEGER), Map.of("result", "code")),
            new FlowProgram.XBusStatus("probe", "x0", "code"), new FlowProgram.EndProcedure("end")));
        var host = new ComputerBlockHost(() -> {}); host.installFlow(p, EMPTY, limits, true); host.step(limits, true);
        var main = host.flowSnapshot().orElseThrow().state(); var claims = host.flowSnapshot().orElseThrow().pinClaims();
        var child = host.startFlowTask(key, EMPTY, limits, true);
        host.step(limits, true, Map.of("x0", 15));
        assertEquals(new DataValue.IntegerValue(15), host.flowTask(child).orElseThrow().locals().field("code"));
        assertEquals(1, host.flowTask(child).orElseThrow().work());
        assertEquals(main, host.flowSnapshot().orElseThrow().state()); assertEquals(claims, host.flowSnapshot().orElseThrow().pinClaims());
        var restored = new ComputerBlockHost(() -> {}); restored.read(host.write()); restored.setPaused(true, limits, true);
        assertEquals(host.flowSnapshot(), restored.flowSnapshot()); restored.step(limits, true);
        assertEquals(new DataValue.IntegerValue(15), restored.claimFlowTask(child, limits, true).locals().field("result"));
        assertEquals(main, restored.flowSnapshot().orElseThrow().state());
    }

    @Test void aParkedStatusBlockIsPortableButNotExecutedAndCannotBeDowngraded() {
        var p = program(); var draft = new FlowDraft(FlowDocumentBody.fromProgram(p, EMPTY));
        draft.drop("probe", null, new ProgramDocument.Position(200, 100));
        var document = new ProgramDocument(p.expressions(), Map.of(), Optional.empty(), Optional.of(draft.snapshot()));
        var raw = ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(22), raw.field("version"));
        assertEquals(document, ProgramDocumentCodec.decode(raw)); assertTrue(document.flow().orElseThrow().steps().isEmpty());
        var old = new LinkedHashMap<>(raw.fields()); old.put("version", new DataValue.IntegerValue(21));
        assertThrows(ArchiveException.class, () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(old)));
    }
}
