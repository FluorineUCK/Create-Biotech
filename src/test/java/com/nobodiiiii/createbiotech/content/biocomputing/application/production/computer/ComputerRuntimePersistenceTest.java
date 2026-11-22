package com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.TestComputer;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.TestComputer.*;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;

class ComputerRuntimePersistenceTest {
	@BeforeAll static void bootstrap() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) == null)
				type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
					.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
			net.minecraft.SharedConstants.tryDetectVersion();
			Field field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true); field.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
	}

	@Test void writesOnlyCurrentOrchestrationRuntimeKey() {
		ProductionComputer computer = computer();
		CompoundTag saved = new CompoundTag();
		physical(computer).write(saved, RegistryAccess.EMPTY, false);
		CompoundTag data = saved.getCompound("ComputerData");
		assertTrue(data.contains("OrchestrationRuntime"));
		assertFalse(data.contains("Runtime"));
	}

	@Test void oldRuntimeKeyInvalidatesCurrentLoadInsteadOfMigratingOrArchiving() {
		ProductionComputer computer = computer();
		CompoundTag saved = new CompoundTag();
		physical(computer).write(saved, RegistryAccess.EMPTY, false);
		CompoundTag data = saved.getCompound("ComputerData");
		data.put("Runtime", data.get("OrchestrationRuntime").copy());
		data.remove("OrchestrationRuntime");
		ProductionComputer restored = computer();
		physical(restored).read(saved, RegistryAccess.EMPTY, false);
		assertTrue(restored.runtimePersistenceInvalid());
		CompoundTag rewritten = new CompoundTag();
		physical(restored).write(rewritten, RegistryAccess.EMPTY, false);
		CompoundTag rewrittenData = rewritten.getCompound("ComputerData");
		assertFalse(rewrittenData.contains("Runtime"));
		assertTrue(rewrittenData.contains("OrchestrationRuntime"));
	}

	@Test void malformedProductionRuntimeCannotBecomeAValidEmptyRuntimeAfterSavingAgain() {
		var source = computer(); var saved = new CompoundTag();
		physical(source).write(saved, RegistryAccess.EMPTY, false);
		var raw = saved.getCompound("ComputerData").getCompound("OrchestrationRuntime");
		raw.putString("UnknownRuntimeChild", "keep evidence");
		var expected = raw.copy();
		for (int pass = 0; pass < 2; pass++) {
			var loaded = computer(); physical(loaded).read(saved, RegistryAccess.EMPTY, false);
			assertFalse(loaded.runtime().snapshot().persistenceValid(), "Malformed runtime stays explicitly invalid");
			var next = new CompoundTag(); physical(loaded).write(next, RegistryAccess.EMPTY, false);
			assertEquals(expected, next.getCompound("ComputerData").get("OrchestrationRuntime"),
				"Saving must not replace rejected production work with a valid empty snapshot");
			saved = next;
		}
	}

	@Test void actualComputerDirectlyOwnsTheNewProductionRuntimeWithoutTheLegacyWrapper() {
		var computer = computer();
		assertInstanceOf(com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionComputerRuntime.class,
			computer.runtime());
		assertEquals(ProductionComputer.EpochCloseResult.NO_EPOCH,
			computer.closeIdleEpoch(UUID.randomUUID(), computer.runtime()));
		assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of(
			"src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/ComputerNodeRuntime.java")));
	}

	@Test void actualRuntimeArchivesAndQuiescenceRemainOwnedByEachRestoredComputer() {
		var a = computer(); var b = computer();
		var aid = a.computerId().orElseThrow(); var bid = b.computerId().orElseThrow();
		var sa = com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState.empty(UUID.randomUUID(), UUID.randomUUID());
		var sb = com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState.empty(UUID.randomUUID(), UUID.randomUUID());
		a.runtime().install(sa); b.runtime().install(sb);
		assertNotSame(a.runtime(), b.runtime());
		assertSame(sa, a.runtime().snapshot().state()); assertSame(sb, b.runtime().snapshot().state());
		assertTrue(a.runtime().allRootsStopped()); assertTrue(a.runtime().nodeIdle(aid));
		assertTrue(a.runtime().nodeRootFree(aid)); assertTrue(a.runtime().nodeMailboxEmpty(aid));
		assertFalse(a.runtime().nodeIdle(bid)); assertFalse(a.runtime().nodeMailboxEmpty(bid));
		var saved = new CompoundTag(); physical(a).write(saved, RegistryAccess.EMPTY, false);
		assertEquals(com.nobodiiiii.createbiotech.content.factorycluster.runtime.RuntimeNbt.save(sa),
			saved.getCompound("ComputerData").get("OrchestrationRuntime"));
		var restored = computer(); var constructionId = restored.computerId().orElseThrow();
		physical(restored).read(saved, RegistryAccess.EMPTY, false);
		assertEquals(aid, restored.computerId().orElseThrow());
		assertEquals(sa, restored.runtime().snapshot().state()); assertTrue(restored.runtime().snapshot().persistenceValid());
		assertTrue(restored.runtime().nodeIdle(aid)); assertFalse(restored.runtime().nodeIdle(constructionId));
		assertSame(sb, b.runtime().snapshot().state()); assertTrue(b.runtime().nodeIdle(bid));
		var again = new CompoundTag(); physical(restored).write(again, RegistryAccess.EMPTY, false);
		assertEquals(saved.getCompound("ComputerData"), again.getCompound("ComputerData"));
	}

	@Test void wrongRuntimeTagKeepsTheEntireOpaqueEnvelopeAcrossTwoRestorations() {
		var initial = computer(); var saved = new CompoundTag(); physical(initial).write(saved, RegistryAccess.EMPTY, false);
		saved.getCompound("ComputerData").putString("OrchestrationRuntime", "retain opaque evidence");
		var expected = saved.getCompound("ComputerData").copy();
		for (int pass = 0; pass < 2; pass++) {
			var restored = computer(); physical(restored).read(saved, RegistryAccess.EMPTY, false);
			assertTrue(restored.runtimePersistenceInvalid());
			var rewritten = new CompoundTag(); physical(restored).write(rewritten, RegistryAccess.EMPTY, false);
			assertEquals(expected, rewritten.get("ComputerData")); saved = rewritten;
		}
	}

    private static ProductionComputer computer() {
        return TestComputer.create(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
    }
}
