package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class ClusterAttachmentTest {
    @Test void configuredOutputsSurviveSaveAndInputMutationWithoutSharingState() {
        var position = new BlockPos.MutableBlockPos(1, 2, 3); var names = new HashSet<>(Set.of("return", "events"));
        var controller = UUID.randomUUID(); var attachment = new ClusterAttachment(controller, position, names);
        var store = new ClusterAttachmentStore(); store.configure(attachment);
        position.set(4, 5, 6); names.clear();
        var saved = store.write(); var restored = new ClusterAttachmentStore(); restored.read(saved);
        ((CompoundTag)saved).remove("Controller");
        assertEquals(new ClusterAttachment(controller, new BlockPos(1, 2, 3), Set.of("return", "events")), restored.attachment().orElseThrow());
        assertTrue(restored.diagnostic().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> restored.attachment().orElseThrow().outputs().clear());
    }
    @Test void missingAndCorruptBindingsRemainDistinguishableAndUnknownDataIsRetained() {
        var store = new ClusterAttachmentStore(); store.read(null);
        assertTrue(store.attachment().isEmpty()); assertTrue(store.diagnostic().isEmpty());
        var unknown = new CompoundTag(); unknown.putInt("Version", 99); unknown.putString("Future", "keep");
        store.read(unknown); unknown.remove("Future");
        assertTrue(store.attachment().isEmpty()); assertEquals(Optional.of("invalid_attachment"), store.diagnostic());
        assertEquals("keep", ((CompoundTag)store.write()).getString("Future"));
        store.clear(); assertNull(store.write()); assertTrue(store.diagnostic().isEmpty());
    }
    @Test void partialFieldsDuplicateNamesAndOutOfRangeCoordinatesAreNotAccepted() {
        var store = new ClusterAttachmentStore(); store.configure(new ClusterAttachment(UUID.randomUUID(), BlockPos.ZERO, Set.of("out")));
        var base = (CompoundTag)store.write();
        for (var key : List.of("Controller", "Position", "Outputs")) {
            var broken = base.copy(); broken.remove(key); store.read(broken);
            assertEquals(Optional.of("invalid_attachment"), store.diagnostic(), key);
        }
        var duplicate = base.copy(); var outputs = new ListTag(); outputs.add(StringTag.valueOf("out")); outputs.add(StringTag.valueOf("out"));
        duplicate.put("Outputs", outputs); store.read(duplicate); assertTrue(store.diagnostic().isPresent());
        assertThrows(IllegalArgumentException.class, () -> new ClusterAttachment(UUID.randomUUID(), BlockPos.ZERO, Set.of("")));
        assertThrows(IllegalArgumentException.class, () -> new ClusterAttachment(UUID.randomUUID(), new BlockPos(Integer.MAX_VALUE, 0, 0), Set.of("out")));
    }
}
