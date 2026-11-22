package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.ProductionAdmissionLedger;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.ProductionAdmissionPhase;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.PendingProductionAdmission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class ProductionAdmissionLedgerTest {
	@Test
	void capacityCannotExceedTheCurrentSchemaMapBeforeAnyAdmissionWrite() {
		assertDoesNotThrow(() -> ProductionAdmissionLedger.empty(16_384));
		assertThrows(IllegalArgumentException.class,
			() -> ProductionAdmissionLedger.empty(16_385));
	}
	@BeforeAll
	static void bootstrapMinecraft() {
		try {
			Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (loadingModList.getMethod("get").invoke(null) == null)
				loadingModList.getMethod("of", List.class, List.class, List.class, List.class,
					Map.class).invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException(failure);
		}
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true); field.setBoolean(null, true);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException(failure);
		}
	}

	@Test
	void failedAdmissionsRetireOutOfTheLiveBudgetAndDiagnosticsRemainBoundedAcrossReload() {
		ProductionAdmissionLedger ledger = ProductionAdmissionLedger.empty(2);
		assertTrue(ledger.admit(admission(1)));
		assertTrue(ledger.admit(admission(2)));
		assertTrue(ledger.fail(id(1), "FIRST_FAILURE"));
		assertTrue(ledger.admit(admission(3)),
			"a terminal admission must stop consuming live admission capacity");
		assertTrue(ledger.fail(id(2), "SECOND_FAILURE"));
		assertTrue(ledger.fail(id(3), "THIRD_FAILURE"));

		assertTrue(ledger.schedulingBatch(1).isEmpty());
		assertEquals(2, ledger.snapshot().failures().size());
		assertFalse(ledger.snapshot().failures().containsKey(id(1)),
			"the oldest diagnostic tombstone must compact at the configured bound");
		assertEquals("SECOND_FAILURE", ledger.snapshot().failures().get(id(2)).diagnostic());
		assertEquals("THIRD_FAILURE", ledger.snapshot().failures().get(id(3)).diagnostic());

		var encoded = CurrentSchemaStateNbt.save(ledger.snapshot(), RegistryAccess.EMPTY);
		var restoredState = CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY,
			ProductionAdmissionLedger.State.class).orElseThrow();
		ProductionAdmissionLedger restored = ProductionAdmissionLedger.restore(restoredState, 2);
		assertEquals(ledger.snapshot(), restored.snapshot());
		assertTrue(restored.admit(admission(4)));
	}

	@Test
	void failedLowUuidEntriesCannotStarveLiveAdmissionScheduling() {
		ProductionAdmissionLedger ledger = ProductionAdmissionLedger.empty(8);
		for (long value = 1; value <= 4; value++) {
			assertTrue(ledger.admit(admission(value)));
			assertTrue(ledger.fail(id(value), "FAILED_" + value));
		}
		assertTrue(ledger.admit(admission(100)));
		assertTrue(ledger.admit(admission(101)));

		assertEquals(List.of(id(100)), ledger.schedulingBatch(1).stream()
			.map(PendingProductionAdmission::admissionId).toList());
		assertEquals(List.of(id(101), id(100)), ledger.schedulingBatch(2).stream()
			.map(PendingProductionAdmission::admissionId).toList());
	}

	@Test
	void liveAdmissionBudgetRotatesAcrossTicksAndContinuesAfterCurrentSchemaReload() {
		ProductionAdmissionLedger ledger = ProductionAdmissionLedger.empty(8);
		assertTrue(ledger.admit(admission(10)));
		assertTrue(ledger.admit(admission(20)));
		assertTrue(ledger.admit(admission(30)));

		assertEquals(List.of(id(10)), scheduledIds(ledger, 1),
			"the first bounded production tick starts from the canonical first admission");
		var encodedAfterFirstTick = CurrentSchemaStateNbt.save(ledger.snapshot(),
			RegistryAccess.EMPTY);
		ProductionAdmissionLedger restored = ProductionAdmissionLedger.restore(
			CurrentSchemaStateNbt.load(encodedAfterFirstTick, RegistryAccess.EMPTY,
				ProductionAdmissionLedger.State.class).orElseThrow(), 8);

		assertEquals(List.of(id(20)), scheduledIds(restored, 1),
			"reload must resume after the last admission that consumed a tick budget");
		assertEquals(List.of(id(30)), scheduledIds(restored, 1),
			"a long-waiting lower UUID admission must not monopolize the next tick");
		assertEquals(List.of(id(10)), scheduledIds(restored, 1),
			"the persisted cursor wraps only after every live admission receives a visit");
	}

	@Test
	void oneDurableSearchCursorCanBeClaimedByOnlyOneNonSearchingAdmission() {
		UUID searchId = id(9_000);
		var first = admission(40).searching(Optional.of(searchId), "SELECTED")
			.cleanupPending("FIRST_CLEANUP");
		var second = admission(41).searching(Optional.of(searchId), "SELECTED")
			.cleanupPending("SECOND_CLEANUP");
		ProductionAdmissionLedger ledger = ProductionAdmissionLedger.empty(4);
		assertTrue(ledger.admit(admission(40)));
		assertTrue(ledger.admit(admission(41)));
		assertTrue(ledger.update(first));
		assertFalse(ledger.update(second),
			"two admissions must not adopt/retire the same pre-admission process lineage");
		assertThrows(IllegalArgumentException.class, () -> new ProductionAdmissionLedger.State(
			Map.of(first.admissionId(), first, second.admissionId(), second), Map.of(), 0,
			Optional.empty()), "reload must reject duplicate durable search claims");
	}

	@Test
	void failedSearchingAdmissionDropsACursorAlreadyClaimedByAnotherAdmission() {
		UUID searchId = id(9_100);
		var first = admission(50).searching(Optional.of(searchId), "SELECTED")
			.cleanupPending("FIRST_CLEANUP");
		var second = admission(51).searching(Optional.of(searchId), "WAIT_SEARCH_CLAIM");
		ProductionAdmissionLedger ledger = ProductionAdmissionLedger.empty(4);
		assertTrue(ledger.admit(admission(50)));
		assertTrue(ledger.admit(second));
		assertTrue(ledger.update(first));

		var cleanup = ledger.cleanupFenceForFailure(second, "EPOCH_FENCED");
		assertTrue(cleanup.searchId().isEmpty(),
			"a non-claimant must neither block nor retire another admission's cursor");
		assertTrue(ledger.update(cleanup));
	}

	private static List<UUID> scheduledIds(ProductionAdmissionLedger ledger, int limit) {
		return ledger.schedulingBatch(limit).stream()
			.map(PendingProductionAdmission::admissionId).toList();
	}

	private static PendingProductionAdmission admission(long value) {
		UUID admission = id(value), goal = id(1_000 + value), cluster = id(2_000),
			epoch = id(2_001), library = id(3_000), pattern = id(3_001), network = id(4_000);
		StackKey output = new StackKey(new ItemStack(Items.IRON_INGOT));
		return new PendingProductionAdmission(admission, goal, cluster,
			epoch, 0, 0, id(5_000), new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO),
			id(5_001), Optional.empty(), network, output, "delivery", 1, InputMode.BULK, 0,
			ProductionAdmissionPhase.SEARCHING, Optional.empty(),
			false, Optional.empty(), Optional.empty(), Optional.empty(), "PENDING");
	}

	private static UUID id(long value) { return new UUID(0, value); }
}
