package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegisterMemoryTest {
	@Test
	void competingWriteRejectsAnOldSnapshotWithoutPublishingAnyOfItsWrites() {
		var first = UUID.randomUUID();
		var second = UUID.randomUUID();
		var memory = twoCells().begin(first).write(first, 0, integer(8)).write(first, 1, integer(9))
			.begin(second).write(second, 0, integer(5));
		memory = finish(memory, second).memory();
		assertEquals(integer(1), memory.read(first, 0));
		var rejected = finish(memory, first);
		assertEquals(new DataValue.BooleanValue(false), rejected.result().field("ok"));
		assertEquals(new DataValue.TextValue("write_conflict"), rejected.result().field("code"));
		assertEquals(integer(5), rejected.memory().cells().field("a"));
		assertEquals(integer(2), rejected.memory().cells().field("b"));
		assertFalse(rejected.memory().active(first));
	}

	@Test
	void onlyExplicitReadMarksConflictAndUnrelatedChangesBeforePrepareAreAllowed() {
		var unmarked = UUID.randomUUID();
		var marked = UUID.randomUUID();
		var writer = UUID.randomUUID();
		var memory = twoCells().begin(unmarked).write(unmarked, 1, integer(8))
			.begin(marked).readMark(marked, 0).begin(writer).write(writer, 0, integer(5));
		assertEquals(integer(1), memory.read(unmarked, 0));
		memory = finish(memory, writer).memory();
		var allowed = finish(memory, unmarked);
		assertEquals(new DataValue.BooleanValue(true), allowed.result().field("ok"));
		assertEquals(integer(5), allowed.memory().cells().field("a"));
		assertEquals(integer(8), allowed.memory().cells().field("b"));
		var rejected = finish(allowed.memory(), marked);
		assertEquals(new DataValue.BooleanValue(false), rejected.result().field("ok"));
	}

	@Test
	void bankChangesAfterPrepareConservativelyConflictEvenWhenTheChangedCellWasUntouched() {
		var prepared = UUID.randomUUID();
		var writer = UUID.randomUUID();
		var memory = fullyPrepared(twoCells().begin(prepared).write(prepared, 0, integer(8)), prepared)
			.begin(writer).write(writer, 1, integer(5));
		memory = finish(memory, writer).memory();
		var rejected = memory.commit(prepared);
		assertEquals(new DataValue.BooleanValue(false), rejected.result().field("ok"));
		assertEquals(integer(1), rejected.memory().cells().field("a"));
		assertEquals(integer(5), rejected.memory().cells().field("b"));
	}

	@Test
	void aRestoredReadOnlyCommitDoesNotInvalidateAnotherPreparedWriter() {
		var writer = UUID.randomUUID();
		var reader = UUID.randomUUID();
		var memory = fullyPrepared(twoCells().begin(writer).write(writer, 0, integer(8)), writer)
			.begin(reader).readMark(reader, 1);
		memory = fullyPrepared(memory, reader);
		memory = RegisterMemory.restore(memory.value(), twoCellSchema()).commit(reader).memory();
		assertEquals(new DataValue.BooleanValue(true), memory.commit(writer).result().field("ok"));
	}

	@Test
	void rollbackAndRetentionReleaseOnlyTheirSelectedSnapshots() {
		var first = UUID.randomUUID();
		var second = UUID.randomUUID();
		var original = twoCells();
		var memory = original.begin(first).write(first, 0, integer(9)).prepare(first).begin(second);
		assertEquals(Set.of(first, second), memory.owners());
		assertThrows(UnsupportedOperationException.class, () -> memory.owners().clear());
		var retained = memory.retain(Set.of(first));
		assertTrue(retained.active(first));
		assertFalse(retained.active(second));
		assertTrue(retained.storageUnits() < memory.storageUnits());
		assertEquals(original.storageUnits(), retained.rollback(first).storageUnits());
		assertEquals(original.value(), retained.rollback(first).value());
		assertEquals(0, RegisterMemory.EMPTY.begin(first).rollback(first).storageUnits());
		assertFalse(memory.rollback(first).active(first));
		assertTrue(memory.active(first));
	}

	@Test
	void invalidTypesDuplicateWritesBoundsAndPreparationOrderHaveExplicitFailures() {
		var owner = UUID.randomUUID();
		var memory = twoCells().begin(owner);
		assertCode(ComputationException.Code.INVALID_ARGUMENT, () -> memory.begin(owner));
		assertCode(ComputationException.Code.INVALID_ARGUMENT, () -> memory.rollback(UUID.randomUUID()));
		assertCode(ComputationException.Code.TYPE_MISMATCH, () -> memory.write(owner, 0, new DataValue.TextValue("bad")));
		assertCode(ComputationException.Code.INDEX_OUT_OF_BOUNDS, () -> memory.read(owner, -1));
		assertCode(ComputationException.Code.INDEX_OUT_OF_BOUNDS, () -> memory.write(owner, 2, integer(1)));
		assertCode(ComputationException.Code.INDEX_OUT_OF_BOUNDS, () -> memory.readMark(owner, 2));
		assertCode(ComputationException.Code.INVALID_ARGUMENT, () -> memory.commit(owner));
		assertCode(ComputationException.Code.INVALID_ARGUMENT, () -> memory.prepareCell(owner, 0));
		var written = memory.write(owner, 0, integer(9));
		assertCode(ComputationException.Code.WRITE_CONFLICT, () -> written.write(owner, 0, integer(8)));
		var prepared = written.prepare(owner);
		assertCode(ComputationException.Code.INVALID_ARGUMENT, () -> prepared.write(owner, 1, integer(8)));
		assertCode(ComputationException.Code.INVALID_ARGUMENT, () -> prepared.readMark(owner, 1));
		assertCode(ComputationException.Code.INVALID_ARGUMENT, () -> prepared.prepare(owner));
		assertCode(ComputationException.Code.INVALID_ARGUMENT, () -> prepared.prepareCell(owner, 1));
		assertCode(ComputationException.Code.INVALID_ARGUMENT, () -> prepared.commit(owner));
		assertEquals(integer(1), prepared.read(owner, 0));
		var partiallyPrepared = prepared.prepareCell(owner, 0);
		assertCode(ComputationException.Code.INVALID_ARGUMENT, () -> partiallyPrepared.prepareCell(owner, 0));
	}

	@Test
	void malformedOrForgedArchiveCannotRestoreAPreparationCandidate() {
		var owner = UUID.randomUUID();
		var value = twoCells().begin(owner).write(owner, 0, integer(9)).prepare(owner).prepareCell(owner, 0).value();
		assertThrows(ArchiveException.class, () -> RegisterMemory.restore(replace(value, "extra", integer(0)), twoCellSchema()));
		assertThrows(ArchiveException.class, () -> RegisterMemory.restore(value, Map.of()));
		var transaction = firstTransaction(value);
		var preparation = (DataValue.RecordValue) transaction.field("preparation");
		var candidate = (DataValue.ListValue) preparation.field("candidate");
		var forgedCell = replace((DataValue.RecordValue) candidate.values().getFirst(), "value", integer(99));
		var forgedCandidate = new DataValue.ListValue(List.of(forgedCell, candidate.values().get(1)));
		var forgedPreparation = replace(preparation, "candidate", forgedCandidate);
		assertThrows(ArchiveException.class, () -> RegisterMemory.restore(withTransaction(value,
			replace(transaction, "preparation", forgedPreparation)), twoCellSchema()));
		assertThrows(ArchiveException.class, () -> RegisterMemory.restore(withTransaction(value,
			replace(transaction, "preparation", replace(preparation, "progress", integer(0)))), twoCellSchema()));
		assertThrows(ArchiveException.class, () -> RegisterMemory.restore(withTransaction(value,
			replace(transaction, "preparation", replace(preparation, "conflict", new DataValue.BooleanValue(true)))), twoCellSchema()));
		var snapshot = (DataValue.RecordValue) transaction.field("snapshot");
		assertThrows(ArchiveException.class, () -> RegisterMemory.restore(withTransaction(value,
			replace(transaction, "snapshot", replace(snapshot, "version", integer(1)))), twoCellSchema()));
	}

	private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
	private static Map<String, ProgramDefinition.Cell> twoCellSchema() {
		return Map.of("a", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, integer(1)),
			"b", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, integer(2)));
	}
	private static RegisterMemory twoCells() { return RegisterMemory.initial(twoCellSchema()); }
	private static RegisterMemory fullyPrepared(RegisterMemory memory, UUID owner) {
		memory = memory.prepare(owner);
		for (int i = 0; i < memory.size(); i++) memory = memory.prepareCell(owner, i);
		return memory;
	}
	private static RegisterMemory.Commit finish(RegisterMemory memory, UUID owner) { return fullyPrepared(memory, owner).commit(owner); }
	private static void assertCode(ComputationException.Code code, org.junit.jupiter.api.function.Executable action) {
		assertEquals(code, assertThrows(ComputationException.class, action).code());
	}
	private static DataValue.RecordValue replace(DataValue.RecordValue value, String name, DataValue replacement) {
		var fields = new LinkedHashMap<>(value.fields());
		fields.put(name, replacement);
		return new DataValue.RecordValue(fields);
	}
	private static DataValue.RecordValue firstTransaction(DataValue.RecordValue value) {
		return (DataValue.RecordValue) ((DataValue.ListValue) value.field("transactions")).values().getFirst();
	}
	private static DataValue.RecordValue withTransaction(DataValue.RecordValue value, DataValue.RecordValue transaction) {
		return replace(value, "transactions", new DataValue.ListValue(List.of(transaction)));
	}

	@Test
	void everyPreparationCheckpointRestoresAndFinishesTheSameCommit() {
		var schema = Map.of(
			"a", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(1)),
			"b", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(2)));
		var owner = UUID.randomUUID();
		var memory = RegisterMemory.initial(schema).begin(owner).readMark(owner, 1)
			.write(owner, 0, new DataValue.IntegerValue(8));
		long stagedUnits = memory.storageUnits();
		assertEquals(-1, memory.preparationIndex(owner));
		memory = RegisterMemory.restore(memory.value(), schema).prepare(owner);
		assertTrue(memory.storageUnits() > stagedUnits);
		for (int index = 0; index < memory.size(); index++) {
			assertEquals(index, memory.preparationIndex(owner));
			var restored = RegisterMemory.restore(memory.value(), schema);
			assertEquals(memory.value(), restored.value());
			assertEquals(memory.storageUnits(), restored.storageUnits());
			memory = restored.prepareCell(owner, index);
		}
		var committed = RegisterMemory.restore(memory.value(), schema).commit(owner);
		assertEquals(new DataValue.BooleanValue(true), committed.result().field("ok"));
		assertEquals(new DataValue.IntegerValue(8), committed.memory().cells().field("a"));
		assertEquals(new DataValue.IntegerValue(2), committed.memory().cells().field("b"));
		assertEquals(0, RegisterMemory.EMPTY.storageUnits());
		assertEquals(RegisterMemory.EMPTY.value(), RegisterMemory.restore(RegisterMemory.EMPTY.value(), Map.of()).value());
	}

	@Test
	void preparedCommitPublishesAllStagedCellsAndReleasesItsOwner() {
		var owner = UUID.randomUUID();
		var memory = RegisterMemory.initial(Map.of(
			"b", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(2)),
			"a", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(1))))
			.begin(owner).readMark(owner, 0).write(owner, 0, new DataValue.IntegerValue(9)).prepare(owner);
		assertTrue(memory.preparing(owner));
		assertEquals(new DataValue.IntegerValue(1), memory.cells().field("a"));
		memory = memory.prepareCell(owner, 0).prepareCell(owner, 1);
		var committed = memory.commit(owner);
		assertEquals(new DataValue.BooleanValue(true), committed.result().field("ok"));
		assertEquals(new DataValue.TextValue("committed"), committed.result().field("code"));
		assertTrue(RegisterMemory.RESULT_TYPE.accepts(committed.result()));
		assertEquals(new DataValue.IntegerValue(9), committed.memory().cells().field("a"));
		assertEquals(new DataValue.IntegerValue(2), committed.memory().cells().field("b"));
		assertFalse(committed.memory().active(owner));
	}

	@Test
	void stagedWriteKeepsBankAndTransactionReadsAtTheirOldValues() {
		var schema = Map.of("counter", new ProgramDefinition.Cell(DataType.Scalar.INTEGER,
			new DataValue.IntegerValue(7)));
		var original = RegisterMemory.initial(schema);
		var owner = UUID.randomUUID();
		var staged = original.begin(owner).write(owner, 0, new DataValue.IntegerValue(9));
		assertEquals(new DataValue.IntegerValue(7), staged.read(owner, 0));
		assertEquals(new DataValue.IntegerValue(7), staged.cells().field("counter"));
		assertEquals(new DataValue.IntegerValue(7), original.cells().field("counter"));
		assertFalse(original.active(owner));
		assertTrue(staged.active(owner));
	}
}
