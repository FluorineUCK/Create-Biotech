package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.ProductionAdmissionOrigin;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.ProductionAdmissionLedger;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.GoalObservationState;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.ProductionAdmissionLedger;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.ProductionAdmissionPhase;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.PendingProductionAdmission;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.Level;

class ProductionAdmissionOriginTest {
    @BeforeAll static void bootstrap() { ProductionAdmissionLedgerTest.bootstrapMinecraft(); }
    private static SpaceAddress address() { return new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO); }
    @Test void stockKeeperItemRequestsRoundUpOnlyAfterTheWinningRecipeIsKnown() {
        var origin = ProductionAdmissionOrigin.stockKeeper(UUID.randomUUID(), address(), UUID.randomUUID(), 9);
        assertEquals(3, origin.multiplier(1, 4));
        assertEquals(5, origin.multiplier(1, 2));
        assertEquals(9, origin.requestedItems());
        assertEquals(ProductionAdmissionOrigin.Kind.STOCK_KEEPER, origin.kind());
        assertThrows(IllegalArgumentException.class, () -> origin.multiplier(1, 0));
    }
    @Test void existingPanelBatchRequestsKeepTheirQuantitySemantics() {
        var origin = ProductionAdmissionOrigin.panel(UUID.randomUUID(), address(), UUID.randomUUID(), Optional.empty());
        assertEquals(9, origin.multiplier(9, 4));
        assertEquals(0, origin.requestedItems());
    }
    @Test void stockKeeperOriginSurvivesTheExistingDurableCodec() {
        var original = ProductionAdmissionOrigin.stockKeeper(UUID.randomUUID(), address(), UUID.randomUUID(), Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE / 2 + 1, original.multiplier(1, 2));
        var encoded = CurrentSchemaStateNbt.save(original, RegistryAccess.EMPTY);
        assertEquals(original, CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY, ProductionAdmissionOrigin.class).orElseThrow());
    }
    @Test void fixedHistoricalV5AndV6PanelFixturesDecodeToTheSameApplicationValue() throws Exception {
        var v5 = net.minecraft.nbt.TagParser.parseTag(java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/production-admission/legacy-v5-panel.snbt")));
        var v6 = net.minecraft.nbt.TagParser.parseTag(java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/production-admission/legacy-v6-panel.snbt")));
        var old = CurrentSchemaStateNbt.load(v5, RegistryAccess.EMPTY, PendingProductionAdmission.class).orElseThrow();
        assertEquals(old, CurrentSchemaStateNbt.load(v6, RegistryAccess.EMPTY, PendingProductionAdmission.class).orElseThrow());
        assertEquals(new UUID(0,1), old.admissionId()); assertEquals(9, old.multiplier()); assertEquals(4, old.priority());
        assertEquals(ProductionAdmissionOrigin.Kind.PANEL, old.origin().kind());
        assertEquals(v6, CurrentSchemaStateNbt.save(old, RegistryAccess.EMPTY));
        v5.putInt("Version",6);
        assertTrue(CurrentSchemaStateNbt.load(v5, RegistryAccess.EMPTY, PendingProductionAdmission.class).isEmpty());
    }
    @Test void fixedHistoricalLedgerFailureCursorAndGoalObservationKeepTheirWireIdentities() throws Exception {
        var ledgerTag = net.minecraft.nbt.TagParser.parseTag(java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/production-admission/legacy-v6-ledger.snbt")));
        var state = CurrentSchemaStateNbt.load(ledgerTag, RegistryAccess.EMPTY, ProductionAdmissionLedger.State.class).orElseThrow();
        assertEquals(Optional.of(new UUID(0,1)), state.schedulingCursor());
        assertEquals("HISTORICAL_FAILURE", state.failures().get(new UUID(0,100)).diagnostic());
        assertEquals(1, state.active().size()); assertEquals(ledgerTag, CurrentSchemaStateNbt.save(state, RegistryAccess.EMPTY));
        var observationTag = net.minecraft.nbt.TagParser.parseTag(java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/production-admission/legacy-v6-goal-observation.snbt")));
        var observation = CurrentSchemaStateNbt.load(observationTag, RegistryAccess.EMPTY, GoalObservationState.class).orElseThrow();
        assertEquals(10, observation.baselineCount()); assertEquals(4, observation.targetCount()); assertEquals(1, observation.confirmedOutputCount());
        assertEquals(observationTag, CurrentSchemaStateNbt.save(observation, RegistryAccess.EMPTY));
        ledgerTag.putString("Type", ProductionAdmissionLedger.State.class.getName());
        assertTrue(CurrentSchemaStateNbt.load(ledgerTag, RegistryAccess.EMPTY, ProductionAdmissionLedger.State.class).isEmpty());
    }
    @Test void everyMovedEnumAndFailureUsesAnExplicitHistoricalNameAndRejectsTheRelocatedName() {
        for (var phase : ProductionAdmissionPhase.values()) {
            var tag = CurrentSchemaStateNbt.save(phase, RegistryAccess.EMPTY);
            assertEquals("com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$ProductionAdmissionPhase", tag.getString("Type"));
            assertEquals(phase, CurrentSchemaStateNbt.load(tag, RegistryAccess.EMPTY, ProductionAdmissionPhase.class).orElseThrow());
            tag.getCompound("Value").putString("Type", ProductionAdmissionPhase.class.getName());
            assertTrue(CurrentSchemaStateNbt.load(tag, RegistryAccess.EMPTY, ProductionAdmissionPhase.class).isEmpty());
        }
        for (var kind : ProductionAdmissionOrigin.Kind.values()) {
            var tag = CurrentSchemaStateNbt.save(kind, RegistryAccess.EMPTY);
            assertEquals("com.nobodiiiii.createbiotech.content.factorycluster.runtime.ProductionAdmissionOrigin$Kind", tag.getString("Type"));
            assertEquals(kind, CurrentSchemaStateNbt.load(tag, RegistryAccess.EMPTY, ProductionAdmissionOrigin.Kind.class).orElseThrow());
        }
        var failure = new ProductionAdmissionLedger.Failure(new UUID(0,100), 0, "FAIL");
        var tag = CurrentSchemaStateNbt.save(failure, RegistryAccess.EMPTY);
        assertEquals("com.nobodiiiii.createbiotech.content.factorycluster.runtime.ProductionAdmissionLedger$Failure", tag.getString("Type"));
        assertEquals(failure, CurrentSchemaStateNbt.load(tag, RegistryAccess.EMPTY, ProductionAdmissionLedger.Failure.class).orElseThrow());
    }
    @Test void legacyV5PanelAdmissionMigratesWithoutInventingAStockKeeperOrigin() {
        var old = new PendingProductionAdmission(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), 1, 2, UUID.randomUUID(), address(), UUID.randomUUID(),
            Optional.empty(), UUID.randomUUID(), new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey(
                net.minecraft.world.item.Items.IRON_INGOT.getDefaultInstance()), "Depot", 9,
            com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode.BULK, 0,
            ProductionAdmissionPhase.SEARCHING, Optional.empty(), false,
            Optional.empty(), Optional.empty(), Optional.empty(), "PENDING");
        var encoded = CurrentSchemaStateNbt.save(old, RegistryAccess.EMPTY);
        assertEquals(6, encoded.getInt("Version"));
        var legacy = encoded.copy(); legacy.putInt("Version", 5);
        var value = legacy.getCompound("Value");
        var origin = value.getCompound("origin");
        value.remove("origin");
        value.put("panelId", origin.get("sourceId").copy());
        value.put("panelAddress", origin.get("sourceAddress").copy());
        value.put("menuSessionId", origin.get("requestId").copy());
        value.put("actorAddress", origin.get("actorAddress").copy());
        assertEquals(old, CurrentSchemaStateNbt.load(legacy, RegistryAccess.EMPTY,
            PendingProductionAdmission.class).orElseThrow());
        // Only exact legacy shape migrates; a current-schema packet missing provenance is corrupt.
        legacy.putInt("Version", 6);
        assertTrue(CurrentSchemaStateNbt.load(legacy, RegistryAccess.EMPTY,
            PendingProductionAdmission.class).isEmpty());
    }
}
