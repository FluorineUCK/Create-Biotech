package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.io.*;
import java.util.*;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;

class RegisterPublicMemoryTest {
    @Test void legacyInstructionFingerprintAndContinuationArchiveRemainUnchanged() throws IOException {
        var schema = Map.of("counter", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, integer(0)));
        var program = image(List.of(op("BEGIN", -1, -1), op("ROLLBACK", -1, -1)), List.of(DataType.Scalar.INTEGER), schema);
        var state = program.begin(record("counter", integer(1)));
        // Golden from the pre-slice RegisterDataIdentity framing, not enum ordinals or summary hashes.
        assertEquals("07b11262e8ca3c60097c24a7a730453fa1b437908b705ce3b476324d0824fc53", state.image());
        assertEquals(Set.of("image", "pc", "registers", "data_version", "scan", "transaction"), state.value().fields().keySet());
        assertEquals(integer(3), state.value().field("data_version"));
        var owner = UUID.randomUUID();
        var begun = program.stepMemory(state, RegisterMemory.initial(schema), owner);
        var restoredState = decodeScalarMemoryState(binaryRoundTrip(begun.registers().value()));
        var restoredMemory = RegisterMemory.restore(binaryRoundTrip(begun.memory().value()), schema);
        program.validate(restoredState, 1, false);
        assertEquals(Optional.of(true), restoredState.transaction());
        assertEquals(integer(0), restoredMemory.read(owner, 0));
        var rolledBack = program.stepMemory(restoredState, restoredMemory, owner);
        assertEquals(RegisterMemory.initial(schema).value(), rolledBack.memory().value());
        assertEquals(Optional.of(false), rolledBack.registers().transaction());
    }

    @Test void restoredTypedStateAndBankRejectForgedTypesAndChangedInstructionImage() throws IOException {
        var type = new DataType.RecordType(Map.of("items", new DataType.ListType(DataType.Scalar.INTEGER)));
        var good = record("items", new DataValue.ListValue(List.of(integer(1))));
        var bad = record("items", new DataValue.ListValue(List.of(new DataValue.TextValue("forged"))));
        var schema = Map.of("counter", new ProgramDefinition.Cell(type, good));
        var program = image(List.of(op("PUBLIC_STORE", 0, 0)), List.of(type), schema);
        var state = program.begin(record("counter", good));
        var forged = decodeScalarMemoryState(binaryRoundTrip(replace(state.value(), "registers", record("0", bad))));
        assertCode(ComputationException.Code.INVALID_CHECKPOINT, () -> program.validate(forged, 0, false));
        var archive = RegisterMemory.initial(schema).value();
        var bank = (DataValue.RecordValue)archive.field("bank");
        var cell = (DataValue.RecordValue)((DataValue.ListValue)bank.field("cells")).values().getFirst();
        var forgedBank = replace(archive, "bank", replace(bank, "cells", new DataValue.ListValue(List.of(replace(cell, "value", bad)))));
        var decodedBank = binaryRoundTrip(forgedBank);
        assertThrows(ArchiveException.class, () -> RegisterMemory.restore(decodedBank, schema));
        var changed = image(List.of(op("PUBLIC_LOAD", 0, 0)), List.of(type), schema);
        var restored = decodeScalarMemoryState(binaryRoundTrip(state.value()));
        assertCode(ComputationException.Code.INVALID_CHECKPOINT, () -> changed.validate(restored, 0, false));
        assertCode(ComputationException.Code.INVALID_CHECKPOINT,
            () -> changed.stepMemory(restored, RegisterMemory.initial(schema), UUID.randomUUID()));
    }

    @Test void binaryCheckpointsAtEveryInstructionBoundaryResumeWithoutReplayingPublicWrites() throws IOException {
        var schema = Map.of("counter", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, integer(0)));
        var instructions = List.<RegisterProgram.Instruction>of(op("PUBLIC_STORE", 0, 0),
            op("PUBLIC_ADD", 0, 0), op("PUBLIC_ADD", 0, 0), op("PUBLIC_LOAD", 0, 1));
        var program = image(instructions, List.of(DataType.Scalar.INTEGER, DataType.Scalar.INTEGER), schema);
        var owner = UUID.randomUUID();
        var prefix = new RegisterProgram.MemoryExecution(program.begin(record("counter", integer(1))), RegisterMemory.initial(schema));
        for (int boundary = 0; boundary <= instructions.size(); boundary++) {
            var checkpoint = binaryRoundTrip(new DataValue.RecordValue(Map.of(
                "state", prefix.registers().value(), "memory", prefix.memory().value())));
            var resumedState = decodeScalarMemoryState((DataValue.RecordValue)checkpoint.field("state"));
            var resumedMemory = RegisterMemory.restore(checkpoint.field("memory"), schema);
            assertEquals(prefix.registers().value(), resumedState.value());
            assertEquals(prefix.memory().value(), resumedMemory.value());
            assertEquals(prefix.memory().storageUnits(), resumedMemory.storageUnits());
            program.validate(resumedState, boundary, false);
            for (int cycle = boundary; !program.complete(resumedState); cycle++) {
                var next = program.stepMemory(resumedState, resumedMemory, owner);
                resumedState = next.registers(); resumedMemory = next.memory();
                program.validate(resumedState, cycle + 1, false);
            }
            assertEquals(integer(3), resumedMemory.cells().field("counter"));
            assertEquals(integer(3), resumedState.registers().get(1));
            assertTrue(program.memoryOp(resumedState).isEmpty());
            assertTrue(resumedMemory.owners().isEmpty());
            if (boundary < instructions.size()) prefix = program.stepMemory(prefix.registers(), prefix.memory(), owner);
        }
    }

    @Test void invalidPublicOperandsAreRejectedAtInstallationOrTheDefensiveBankBoundary() {
        var schema = Map.of("counter", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, integer(0)));
        for (String kind : List.of("PUBLIC_LOAD", "PUBLIC_STORE", "PUBLIC_ADD")) {
            assertThrows(IllegalArgumentException.class, () -> op(kind, -1, 0));
            assertThrows(IllegalArgumentException.class, () -> op(kind, 0, -1));
            assertThrows(IllegalArgumentException.class, () -> image(List.of(op(kind, 1, 0)), List.of(DataType.Scalar.INTEGER), schema));
            assertThrows(IllegalArgumentException.class, () -> image(List.of(op(kind, 0, 1)), List.of(DataType.Scalar.INTEGER), schema));
            assertThrows(IllegalArgumentException.class, () -> image(List.of(op(kind, 0, 0)), List.of(DataType.Scalar.DECIMAL), schema));
        }
        var text = new DataValue.TextValue("not numeric");
        var textSchema = Map.of("counter", new ProgramDefinition.Cell(DataType.Scalar.TEXT, text));
        assertThrows(IllegalArgumentException.class, () -> image(List.of(op("PUBLIC_ADD", 0, 0)), List.of(DataType.Scalar.TEXT), textSchema));
        var memory = RegisterMemory.initial(schema); var owner = UUID.randomUUID();
        for (int index : List.of(-1, 1)) {
            assertCode(ComputationException.Code.INDEX_OUT_OF_BOUNDS, () -> memory.publicLoad(owner, index));
            assertCode(ComputationException.Code.INDEX_OUT_OF_BOUNDS, () -> memory.publicStore(owner, index, integer(1)));
            assertCode(ComputationException.Code.INDEX_OUT_OF_BOUNDS, () -> memory.publicAdd(owner, index, integer(1)));
        }
        assertCode(ComputationException.Code.TYPE_MISMATCH, () -> memory.publicStore(owner, 0, text));
        assertCode(ComputationException.Code.TYPE_MISMATCH, () -> memory.publicAdd(owner, 0, new DataValue.DecimalValue(1)));
        assertCode(ComputationException.Code.TYPE_MISMATCH, () -> RegisterMemory.initial(textSchema).publicAdd(owner, 0, integer(1)));
        var listType = new DataType.ListType(DataType.Scalar.INTEGER);
        var lists = Map.of("counter", new ProgramDefinition.Cell(listType, new DataValue.ListValue(List.of())));
        assertCode(ComputationException.Code.TYPE_MISMATCH,
            () -> RegisterMemory.initial(lists).publicStore(owner, 0, new DataValue.ListValue(List.of(text))));
        assertThrows(IllegalArgumentException.class, () -> image(List.of(op("PUBLIC_STORE", 0, 0)),
            List.of(new DataType.ListType(DataType.Scalar.ANY)), lists));
        assertEquals(integer(0), memory.cells().field("counter"));
    }

    @Test void numericAddsAccumulateDecimalAndFailOverflowWithoutChangingStateOrBank() {
        var decimalSchema = Map.of("counter", new ProgramDefinition.Cell(DataType.Scalar.DECIMAL, new DataValue.DecimalValue(0)));
        var decimalProgram = image(List.of(op("PUBLIC_ADD", 0, 0)), List.of(DataType.Scalar.DECIMAL), decimalSchema);
        var decimalState = decimalProgram.begin(record("counter", new DataValue.DecimalValue(0.25)));
        var a = decimalProgram.stepMemory(decimalState, RegisterMemory.initial(decimalSchema), UUID.randomUUID());
        var b = decimalProgram.stepMemory(decimalState, a.memory(), UUID.randomUUID());
        assertEquals(new DataValue.DecimalValue(0.5), b.memory().cells().field("counter"));
        for (var pair : List.of(List.<DataValue>of(integer(Long.MAX_VALUE), integer(1)),
                List.<DataValue>of(integer(Long.MIN_VALUE), integer(-1)),
                List.<DataValue>of(new DataValue.DecimalValue(Double.MAX_VALUE), new DataValue.DecimalValue(Double.MAX_VALUE)))) {
            var type = pair.getFirst() instanceof DataValue.IntegerValue ? DataType.Scalar.INTEGER : DataType.Scalar.DECIMAL;
            var schema = Map.of("counter", new ProgramDefinition.Cell(type, pair.getFirst()));
            var program = image(List.of(op("PUBLIC_ADD", 0, 0)), List.of(type), schema);
            var state = program.begin(record("counter", pair.get(1)));
            var memory = RegisterMemory.initial(schema);
            var oldState = state.value(); var oldBank = memory.value();
            assertCode(ComputationException.Code.ARITHMETIC_OVERFLOW, () -> program.stepMemory(state, memory, UUID.randomUUID()));
            assertEquals(oldState, state.value()); assertEquals(oldBank, memory.value());
        }
    }

    @Test void everyPublicOperationRejectsThisTasksLegacyTransactionWithoutBypassingEitherActiveFlag() {
        var schema = Map.of("counter", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, integer(3)));
        for (String kind : List.of("PUBLIC_LOAD", "PUBLIC_STORE", "PUBLIC_ADD")) {
            var program = image(List.of(op("BEGIN", -1, -1), op(kind, 0, 0)), List.of(DataType.Scalar.INTEGER), schema);
            var owner = UUID.randomUUID();
            var before = program.stepMemory(program.begin(record("counter", integer(9))), RegisterMemory.initial(schema), owner);
            var bank = before.memory();
            assertCode(ComputationException.Code.INVALID_ARGUMENT, () -> program.stepMemory(before.registers(), bank, owner));
            assertCode(ComputationException.Code.INVALID_ARGUMENT,
                () -> program.stepMemory(before.registers().withTransaction(false), bank, owner));
            assertCode(ComputationException.Code.INVALID_ARGUMENT,
                () -> program.stepMemory(before.registers(), bank.rollback(owner), owner));
            assertCode(ComputationException.Code.INVALID_ARGUMENT,
                () -> program.stepMemory(before.registers(), bank.prepare(owner), owner));
            assertEquals(integer(3), bank.cells().field("counter"));
            assertTrue(bank.active(owner));
            assertEquals(1, before.registers().pc());
        }
    }

    @Test void publicWritesLeaveOtherLegacySnapshotsOldAndRejectTheirCommitWithoutPartialPublication() {
        var schema = Map.of("a", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, integer(1)),
            "b", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, integer(2)));
        var legacy = image(List.of(op("BEGIN", -1, -1), op("READ", 0, 1), op("WRITE", 1, 0),
            op("PREPARE", -1, -1), op("PREPARE_CELL", 0, -1), op("PREPARE_CELL", 1, -1),
            op("COMMIT", -1, 2)), List.of(DataType.Scalar.INTEGER, DataType.Scalar.INTEGER, RegisterMemory.RESULT_TYPE), schema);
        var writer = image(List.of(op("PUBLIC_ADD", 0, 0)), List.of(DataType.Scalar.INTEGER), schema);
        // A touched-cell conflict before preparation, and a bank-version conflict after preparation.
        for (int publishAfter : List.of(2, 6)) {
            var owner = UUID.randomUUID();
            var state = legacy.begin(record("counter", integer(9)));
            var memory = RegisterMemory.initial(schema);
            for (int cycle = 0; cycle < legacy.code().size(); cycle++) {
                if (cycle == publishAfter) {
                    var candidate = writer.stepMemory(writer.begin(record("counter", integer(1))), memory, UUID.randomUUID());
                    memory = RegisterMemory.restore(candidate.memory().value(), schema);
                    assertEquals(integer(1), memory.read(owner, 0));
                    assertEquals(integer(2), memory.cells().field("a"));
                    assertEquals(integer(2), memory.cells().field("b"));
                    assertTrue(memory.active(owner));
                }
                var next = legacy.stepMemory(state, memory, owner);
                state = next.registers(); memory = next.memory();
                legacy.validate(state, cycle + 1, false);
            }
            var result = (DataValue.RecordValue)state.registers().get(2);
            assertEquals(new DataValue.BooleanValue(false), result.field("ok"));
            assertEquals(new DataValue.TextValue("write_conflict"), result.field("code"));
            assertEquals(integer(2), memory.cells().field("a"));
            assertEquals(integer(2), memory.cells().field("b"));
            assertFalse(memory.active(owner));
            assertEquals(Optional.of(false), state.transaction());
        }
    }

    @Test void allSupportedImmutableTypesLoadAndStoreByReferenceWithDefensiveOwnership() {
        var mutableItems = new ArrayList<DataValue>(List.of(integer(4), integer(5)));
        var items = new DataValue.ListValue(mutableItems);
        var mutableFields = new LinkedHashMap<String, DataValue>(); mutableFields.put("items", items);
        var payload = new DataValue.RecordValue(mutableFields);
        mutableItems.clear(); mutableFields.clear();
        var listType = new DataType.ListType(DataType.Scalar.INTEGER);
        var payloadType = new DataType.RecordType(Map.of("items", listType));
        var cases = List.of(
            Map.entry(DataType.Scalar.INTEGER, integer(8)),
            Map.entry(DataType.Scalar.DECIMAL, new DataValue.DecimalValue(0.5)),
            Map.entry(DataType.Scalar.BOOLEAN, new DataValue.BooleanValue(true)),
            Map.entry(DataType.Scalar.TEXT, new DataValue.TextValue("immutable")),
            Map.entry(DataType.Scalar.NULL, DataValue.NullValue.INSTANCE),
            Map.entry(DataType.Scalar.ERROR, new DataValue.ErrorValue("code", "message")),
            Map.entry(DataType.Scalar.NBT, new DataValue.NbtValue(3, integer(6))),
            Map.entry(listType, items), Map.entry(payloadType, payload),
            Map.entry(new DataType.MapType(listType), new DataValue.MapValue(Map.of("key", items))),
            Map.entry(new DataType.OptionalType(payloadType), payload), Map.entry(DataType.Scalar.ANY, payload));
        for (var entry : cases) {
            var schema = Map.of("counter", new ProgramDefinition.Cell(entry.getKey(), entry.getValue()));
            var program = image(List.of(op("PUBLIC_STORE", 0, 0), op("PUBLIC_LOAD", 0, 1)),
                List.of(entry.getKey(), entry.getKey()), schema);
            var owner = UUID.randomUUID();
            var state = program.begin(record("counter", entry.getValue()));
            var original = RegisterMemory.initial(schema);
            var stored = program.stepMemory(state, original, owner);
            var loaded = program.stepMemory(stored.registers(), stored.memory(), owner);
            assertSame(entry.getValue(), loaded.registers().registers().get(1));
            assertSame(entry.getValue(), stored.memory().publicLoad(owner, 0));
            assertEquals(original.storageUnits(), stored.memory().storageUnits());
            assertTrue(stored.memory().owners().isEmpty());
            program.validate(loaded.registers(), 2, false);
        }
        assertEquals(List.of(integer(4), integer(5)), items.values());
        assertThrows(UnsupportedOperationException.class, () -> items.values().clear());
        assertThrows(UnsupportedOperationException.class, () -> payload.fields().clear());
    }

    @Test void loadAndOrderedStoresNeedNoTransactionAndDoNotOverwritePrivateSource() {
        var schema = Map.of("counter", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, integer(3)));
        var program = image(List.of(op("PUBLIC_STORE", 0, 0), op("PUBLIC_LOAD", 0, 1)),
            List.of(DataType.Scalar.INTEGER, DataType.Scalar.INTEGER), schema);
        var first = program.begin(record("counter", integer(8)));
        var second = program.begin(record("counter", integer(9)));
        var memory = RegisterMemory.initial(schema);
        var a = program.stepMemory(first, memory, UUID.randomUUID());
        var b = program.stepMemory(second, a.memory(), UUID.randomUUID());
        var loaded = program.stepMemory(a.registers(), b.memory(), UUID.randomUUID());
        assertEquals(integer(9), loaded.registers().registers().get(1));
        assertEquals(integer(8), loaded.registers().registers().get(0));
        assertEquals(integer(9), b.registers().registers().get(0));
        assertSame(b.memory(), loaded.memory());
        assertEquals(integer(3), memory.cells().field("counter"));
        assertEquals(Optional.of(false), loaded.registers().transaction());
    }

    @Test void independentTasksBothIncrementThePublicIntegerWithoutSharingPrivateRegisters() {
        var schema = Map.of("counter", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, integer(0)));
        var program = image(List.of(op("PUBLIC_ADD", 0, 0)), List.of(DataType.Scalar.INTEGER), schema);
        var first = program.begin(record("counter", integer(1)));
        var second = program.begin(record("counter", integer(1)));
        var original = RegisterMemory.initial(schema);
        var firstCandidate = program.stepMemory(first, original, UUID.randomUUID());
        assertEquals(integer(0), original.cells().field("counter"));
        assertEquals(0, first.pc());
        var secondCandidate = program.stepMemory(second, firstCandidate.memory(), UUID.randomUUID());
        assertEquals(integer(2), secondCandidate.memory().cells().field("counter"));
        assertEquals(integer(1), program.locals(firstCandidate.registers()).field("counter"));
        assertEquals(integer(1), program.locals(secondCandidate.registers()).field("counter"));
        assertEquals(Optional.of(false), secondCandidate.registers().transaction());
        assertTrue(secondCandidate.memory().owners().isEmpty());
        assertTrue(program.complete(secondCandidate.registers()));
    }

    private static RegisterMemoryOp op(String kind, int cell, int register) {
        return new RegisterMemoryOp(RegisterMemoryOp.Kind.valueOf(kind), cell, register);
    }
    private static RegisterProgram image(List<RegisterProgram.Instruction> instructions, List<DataType> types,
            Map<String, ProgramDefinition.Cell> schema) {
        var code = new ArrayList<RegisterProgram.Code>();
        for (int pc = 0; pc < instructions.size(); pc++) code.add(new RegisterProgram.Code(instructions.get(pc), pc, "native/" + pc));
        return new RegisterProgram(code, types, List.of(), Map.of("counter", 0), Map.of("counter", 0),
            instructions.size(), List.of(), schema);
    }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
    private static DataValue.RecordValue record(String key, DataValue value) {
        return new DataValue.RecordValue(Map.of(key, value));
    }
    private static DataValue.RecordValue replace(DataValue.RecordValue value, String key, DataValue replacement) {
        var fields = new LinkedHashMap<>(value.fields()); fields.put(key, replacement);
        return new DataValue.RecordValue(fields);
    }
    private static void assertCode(ComputationException.Code code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ComputationException.class, action).code());
    }
    private static DataValue.RecordValue binaryRoundTrip(DataValue.RecordValue value) throws IOException {
        var limits = new ArchiveLimits(10000, 100000, 100000);
        var bytes = new ByteArrayOutputStream();
        NbtIo.write(ValueNbtCodec.encode(value, limits), new DataOutputStream(bytes));
        return (DataValue.RecordValue)ValueNbtCodec.decode(NbtIo.read(new DataInputStream(
            new ByteArrayInputStream(bytes.toByteArray()))), limits);
    }
    // Test-only reconstruction of this slice's closed no-call/no-scan state after the real binary codec.
    // The future native owner still needs a production structural decoder and binding/preflight boundary.
    private static RegisterState decodeScalarMemoryState(DataValue.RecordValue value) {
        assertEquals(integer(3), value.field("data_version"));
        assertSame(DataValue.NullValue.INSTANCE, value.field("scan"));
        var registers = new TreeMap<Integer, DataValue>();
        ((DataValue.RecordValue)value.field("registers")).fields().forEach((name, data) -> registers.put(Integer.parseInt(name), data));
        return new RegisterState(((DataValue.TextValue)value.field("image")).value(),
            Math.toIntExact(((DataValue.IntegerValue)value.field("pc")).value()), registers, true,
            Optional.empty(), Optional.empty(), Optional.of(((DataValue.BooleanValue)value.field("transaction")).value()));
    }
}
