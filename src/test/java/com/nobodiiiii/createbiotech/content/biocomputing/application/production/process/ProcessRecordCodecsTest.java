package com.nobodiiiii.createbiotech.content.biocomputing.application.production.process;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class ProcessRecordCodecsTest {
    @Test void literalHistoricalProcessAndChildrenRoundTripWithoutChangingAcknowledgement() {
        var fixture = fixture(); var untouched = fixture.copy();
        var expected = ProductionProcessModelTest.parent().acceptChildRevision(ProductionProcessModelTest.child(0, 0)).parent();
        assertEquals(expected, ProcessRecordCodecs.load(fixture).orElseThrow());
        assertEquals(fixture, ProcessRecordCodecs.save(expected)); assertEquals(untouched, fixture);
        var ack = expected.childRevisions().get(ProductionProcessModelTest.id(0)).acknowledgement();
        assertThrows(IllegalStateException.class, () -> ProcessRecordCodecs.load(fixture).orElseThrow().authorizeIrreversibleChildBoundary(ack));
        var durable = expected.markChildAcknowledgementDurable(ack);
        var restored = ProcessRecordCodecs.load(ProcessRecordCodecs.save(durable)).orElseThrow();
        assertEquals(ack, restored.authorizeIrreversibleChildBoundary(ack).acknowledgement());
        assertFalse(fixture.getList("Children", Tag.TAG_COMPOUND).getCompound(0).getBoolean("Acknowledged"));
    }

    @Test void malformedChildOrProcessCannotPartiallyRestoreOrCreateDurablePermission() {
        var wrongSlot = fixture(); wrongSlot.putLong("Slot", 0);
        var extra = fixture(); extra.putBoolean("Allowed", true);
        var unknown = fixture(); unknown.putString("Kind", "PLAYER_COMMAND");
        var waitMismatch = fixture(); waitMismatch.putString("WaitReason", "PROCESS");
        var missingWait = fixture(); missingWait.putString("State", "WAITING");
        var negative = fixture(); negative.putLong("Generation", -1);
        var booleanTwo = fixture(); booleanTwo.getList("Children", Tag.TAG_COMPOUND).getCompound(0).putByte("Acknowledged", (byte) 2);
        var boolWrongType = fixture(); boolWrongType.getList("Children", Tag.TAG_COMPOUND).getCompound(0).putInt("Acknowledged", 1);
        var wrongPromise = fixture(); wrongPromise.getList("Children", Tag.TAG_COMPOUND).getCompound(0).putLong("Remaining", 6);
        var overflow = fixture(); var child = overflow.getList("Children", Tag.TAG_COMPOUND).getCompound(0);
        child.putLong("Feasible", Long.MAX_VALUE); child.putLong("Remaining", 1);
        var duplicate = fixture(); duplicate.getList("Children", Tag.TAG_COMPOUND).add(duplicate.getList("Children", Tag.TAG_COMPOUND).getCompound(0).copy());
        var wrongList = fixture(); var textList = new ListTag(); textList.add(StringTag.valueOf("child")); wrongList.put("Children", textList);
        var missing = fixture(); missing.remove("Children");
        for (var bad : List.of(wrongSlot, extra, unknown, waitMismatch, missingWait, negative, booleanTwo, boolWrongType, wrongPromise, overflow, duplicate, wrongList, missing)) {
            var original = bad.copy(); assertTrue(ProcessRecordCodecs.load(bad).isEmpty()); assertEquals(original, bad);
        }
        assertTrue(ProcessRecordCodecs.load(null).isEmpty());
    }

    @Test void fullChildTableIsSaveableAndRawOverBoundDataIsRejected() {
        var parent = ProductionProcessModelTest.parent();
        for (int i = 0; i < ProcessRecord.MAX_CHILD_REVISIONS; i++) parent = parent.acceptChildRevision(ProductionProcessModelTest.child(i, 0)).parent();
        var saved = ProcessRecordCodecs.save(parent);
        assertEquals(parent, ProcessRecordCodecs.load(saved).orElseThrow());
        var children = saved.getList("Children", Tag.TAG_COMPOUND);
        assertEquals(ProductionProcessModelTest.id(0), children.getCompound(0).getUUID("ProcessId"));
        var extra = children.getCompound(0).copy(); extra.putUUID("ProcessId", ProductionProcessModelTest.id(999)); children.add(extra);
        assertTrue(ProcessRecordCodecs.load(saved).isEmpty());
    }

    @Test void schemaPreservesAllNineHistoricalTypesWithoutAuthorizingNewAliasesOrPermits() {
        var accepted = ProductionProcessModelTest.parent().acceptChildRevision(ProductionProcessModelTest.child(0, 0));
        var values = new java.util.ArrayList<Object>();
        values.addAll(List.of(ProcessKind.values())); values.addAll(List.of(ProcessState.values()));
        values.addAll(List.of(WaitReason.values())); values.addAll(List.of(InputMode.values()));
        values.add(accepted.parent()); values.add(accepted); values.add(accepted.acknowledgement());
        values.add(ProductionProcessModelTest.child(0, 0)); values.add(accepted.parent().childRevisionStates().get(ProductionProcessModelTest.id(0)));
        var registries = net.minecraft.core.RegistryAccess.EMPTY;
        for (Object value : values) {
            String historical = value.getClass().getName().replace("biocomputing.application.production.process", "factorycluster.orchestration.model");
            var encoded = com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt.save(value, registries);
            assertEquals(6, encoded.getInt("Version")); assertEquals(historical, encoded.getString("Type"));
            assertEquals(historical, encoded.getCompound("Value").getString("Type"));
            assertEquals(value, com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt.load(encoded, registries, value.getClass()).orElseThrow());
            var alias = encoded.copy(); alias.putString("Type", value.getClass().getName());
            assertTrue(com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt.load(alias, registries, value.getClass()).isEmpty());
            var nested = encoded.copy(); nested.getCompound("Value").putString("Type", value.getClass().getName());
            assertTrue(com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt.load(nested, registries, value.getClass()).isEmpty());
        }
        var durable = accepted.parent().markChildAcknowledgementDurable(accepted.acknowledgement());
        assertThrows(IllegalArgumentException.class, () -> com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt.save(durable.authorizeIrreversibleChildBoundary(accepted.acknowledgement()), registries));
    }

    private static CompoundTag fixture() {
        var tag = new CompoundTag(); tag.putUUID("ProcessId", ProductionProcessModelTest.id(1000));
        tag.putUUID("GoalId", ProductionProcessModelTest.id(1001)); tag.putUUID("ComputerId", ProductionProcessModelTest.id(1002));
        tag.putInt("Slot", 0); tag.putString("Kind", "GOAL"); tag.putString("State", "RUNNABLE"); tag.putLong("Generation", 0);
        var profile = new CompoundTag(); profile.putInt("Version", 1); profile.putString("Profession", "CLERIC");
        profile.putInt("ProcessSlots", 2); profile.putInt("ListenerRangeBonus", 0);
        var capabilities = new ListTag(); capabilities.add(StringTag.valueOf("ALCHEMIST")); profile.put("Capabilities", capabilities); tag.put("Profile", profile);
        var child = new CompoundTag(); child.putUUID("ProcessId", ProductionProcessModelTest.id(0)); child.putLong("Generation", 0);
        child.putLong("Revision", 0); child.putString("PreviousMode", "BULK"); child.putString("EffectiveMode", "PARTIAL");
        child.putLong("Total", 8); child.putLong("Feasible", 3); child.putLong("Remaining", 5); child.putBoolean("Acknowledged", false);
        var children = new ListTag(); children.add(child); tag.put("Children", children); return tag;
    }
}
