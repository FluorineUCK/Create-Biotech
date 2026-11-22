package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import java.io.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class PeriodicNbtTest {
	private static final ArchiveLimits LIMITS = new ArchiveLimits(100_000, 1_000_000, 10_000_000);
	private static final ResourceLimits HARDWARE = new ResourceLimits(1000, 1000, 1);

	@Test void versionTwoRoundTripsScheduleThroughRealNbtIo() throws IOException {
		var runtime = new ComputerRuntime(UUID.randomUUID(), emptyProgram(), HARDWARE);
		var inputs = new DataValue.RecordValue(Map.of()); runtime.configurePeriodic(5, inputs); runtime.tick();
		var bytes = new ByteArrayOutputStream();
		NbtIo.write(ComputerNbtCodec.encode(runtime.snapshot(), LIMITS), new DataOutputStream(bytes));
		var decoded = ComputerNbtCodec.decode(NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))), LIMITS);
		assertEquals(runtime.snapshot(), decoded);
		assertEquals(4, decoded.periodic().orElseThrow().remainingTicks());
	}

	@Test void versionOneMigratesWithoutScheduleAndVersionTwoRequiresPeriodic() {
		var runtime = new ComputerRuntime(UUID.randomUUID(), emptyProgram(), HARDWARE);
		var encoded = ValueNbtCodec.decode(ComputerNbtCodec.encode(runtime.snapshot(), LIMITS), LIMITS);
		var legacyFields = new LinkedHashMap<>(((DataValue.RecordValue) encoded).fields());
		legacyFields.put("version", new DataValue.IntegerValue(1)); legacyFields.remove("periodic");
		assertTrue(ComputerNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(legacyFields), LIMITS), LIMITS).periodic().isEmpty());
		legacyFields.put("version", new DataValue.IntegerValue(2));
		assertThrows(ArchiveException.class, () -> ComputerNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(legacyFields), LIMITS), LIMITS));
	}

	@Test void corruptPeriodicBoundsAreRejected() {
		var runtime = new ComputerRuntime(UUID.randomUUID(), emptyProgram(), HARDWARE); runtime.configurePeriodic(5, new DataValue.RecordValue(Map.of()));
		var data = (DataValue.RecordValue) ValueNbtCodec.decode(ComputerNbtCodec.encode(runtime.snapshot(), LIMITS), LIMITS);
		var root = new LinkedHashMap<>(data.fields());
		var valid = new LinkedHashMap<String, DataValue>();
		valid.put("interval_ticks", new DataValue.IntegerValue(5)); valid.put("remaining_ticks", new DataValue.IntegerValue(3));
		valid.put("enabled", new DataValue.BooleanValue(true)); valid.put("inputs", new DataValue.RecordValue(Map.of()));
		for (String field : List.of("interval_ticks", "remaining_ticks", "enabled", "inputs")) {
			var missing = new LinkedHashMap<>(valid); missing.remove(field); assertCorrupt(root, missing);
		}
		for (String field : List.of("interval_ticks", "remaining_ticks", "enabled", "inputs")) {
			var wrong = new LinkedHashMap<>(valid); wrong.put(field, new DataValue.TextValue("wrong")); assertCorrupt(root, wrong);
		}
		var zero = new LinkedHashMap<>(valid); zero.put("interval_ticks", new DataValue.IntegerValue(0)); assertCorrupt(root, zero);
		var negative = new LinkedHashMap<>(valid); negative.put("remaining_ticks", new DataValue.IntegerValue(-1)); assertCorrupt(root, negative);
		var excessive = new LinkedHashMap<>(valid); excessive.put("remaining_ticks", new DataValue.IntegerValue(6)); assertCorrupt(root, excessive);
	}
	private static void assertCorrupt(Map<String, DataValue> root, Map<String, DataValue> periodic) {
		var corrupt = new LinkedHashMap<>(root); corrupt.put("periodic", new DataValue.RecordValue(periodic));
		assertThrows(ArchiveException.class, () -> ComputerNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(corrupt), LIMITS), LIMITS));
	}

	private static ProgramArchive emptyProgram() {
		return new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of()), Map.of());
	}
}
