package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.BrowserFence;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserEntry;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserFilter;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserSummary;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserPage;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeIndexSnapshot;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

class PatternBrowserPacketTest {
    @BeforeAll static void bootstrap() { PatternBrowserServiceTest.bootstrap(); }
    @Test void newSourceFenceRetainsTheExactHistoricalWireLayout() {
        var moving = new net.minecraft.core.BlockPos.MutableBlockPos(3, 64, -7);
        var location = new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation(
            net.minecraft.world.level.Level.OVERWORLD, new UUID(4, 5), moving);
        var fence = new BrowserFence(new UUID(1, 2), location, new UUID(8, 9), 17);
        moving.set(99, 99, 99);
        assertEquals(new net.minecraft.core.BlockPos(3, 64, -7), fence.coreAddress().localPos());
        var actual = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        var historical = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            PatternBrowserWire.writeFence(actual, fence);
            historical.writeUUID(fence.sessionId());
            historical.writeResourceLocation(net.minecraft.world.level.Level.OVERWORLD.location());
            historical.writeBoolean(true); historical.writeUUID(new UUID(4, 5));
            historical.writeBlockPos(new net.minecraft.core.BlockPos(3, 64, -7));
            historical.writeUUID(fence.libraryId()); historical.writeVarLong(17);
            assertEquals(historical, actual, "New source value must not change field ordering or packed position encoding");
            assertEquals(fence, PatternBrowserWire.readFence(actual)); assertFalse(actual.isReadable());
            assertEquals(16384, PatternBrowserWire.MAX_REQUEST_BYTES);
            assertEquals(PatternBrowserFilter.MAX_REQUEST_BYTES, PatternBrowserWire.MAX_REQUEST_BYTES);
        } finally { actual.release(); historical.release(); }
    }

    @Test void ghostWithinWireLimitButOverNbtQuotaIsNotSent() {
        var request = new PatternBrowserRequestPacket(1, PatternBrowserServiceTest.FENCE, 1,
            new PatternBrowserFilter("", Optional.of(integerFieldsStack(512)), Optional.empty()), 0, Optional.empty());
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, net.neoforged.neoforge.network.connection.ConnectionType.OTHER);
        try {
            request.write(wire);
            assertTrue(wire.readableBytes() < PatternBrowserWire.MAX_REQUEST_BYTES);
            System.out.println("quota-request-wire-bytes=" + wire.readableBytes());
            assertThrows(net.minecraft.nbt.NbtAccounterException.class, () -> new PatternBrowserRequestPacket(wire));
            var sent = new ArrayList<PatternBrowserRequestPacket>();
            assertFalse(request.sendIfBounded(RegistryAccess.EMPTY, sent::add));
            assertTrue(sent.isEmpty(), "The actual UI send boundary must reject allocation-heavy ghosts locally");
        } finally { wire.release(); }
    }

    @Test void detailWithinWireLimitButOverNbtQuotaBecomesDecodableTooLarge() {
        var original = PatternBrowserServiceTest.entry(0).pattern();
        var entry = new PatternBrowserEntry(new PatternRecord(original.patternId(), original.source(), original.inputs(),
            List.of(new PatternOutput(integerFieldsStack(4096), 1)), original.body(), original.priorityOverride()));
        var snapshot = new RecipeIndexSnapshot(7, false, List.of(entry.pattern()), 0, 0, List.of());
        var page = PatternBrowserServiceTest.service(snapshot).page(PatternBrowserServiceTest.FENCE, PatternBrowserFilter.EMPTY, 0, Optional.of(entry.patternId()));
        var response = new PatternBrowserResponsePacket(1, 1, page, PatternBrowserDiagnostics.empty());
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, net.neoforged.neoforge.network.connection.ConnectionType.OTHER);
        try {
            response.write(wire);
            assertTrue(wire.readableBytes() < PatternBrowserWire.MAX_RESPONSE_BYTES);
            System.out.println("quota-response-wire-bytes=" + wire.readableBytes());
            assertThrows(net.minecraft.nbt.NbtAccounterException.class, () -> new PatternBrowserResponsePacket(wire));
            var bounded = response.bounded(RegistryAccess.EMPTY);
            assertEquals(PatternBrowserPage.Status.TOO_LARGE, bounded.page().status());
            assertTrue(bounded.page().detail().isEmpty()); assertTrue(bounded.page().entries().isEmpty());
            wire.clear(); bounded.write(wire);
            assertEquals(bounded, new PatternBrowserResponsePacket(wire));
            assertEquals(entry, response.page().detail().orElseThrow(), "Never truncate the original component facts");
        } finally { wire.release(); }
    }

    private static StackKey integerFieldsStack(int count) {
        var data = new net.minecraft.nbt.CompoundTag();
        for (int i = 0; i < count; i++) data.putInt("field" + i, i);
        var stack = Items.IRON_INGOT.getDefaultInstance();
        stack.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(data));
        return new StackKey(stack);
    }

    @Test void boundedRequestRoundTripPreservesComponentsAndIntent() {
        var item = Items.IRON_INGOT.getDefaultInstance(); item.set(DataComponents.CUSTOM_NAME, Component.literal("precise"));
        var names = new BitSet();
        names.set(net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(Items.IRON_INGOT));
        var request = new PatternBrowserRequestPacket(1, PatternBrowserServiceTest.FENCE, 9,
            new PatternBrowserFilter("forge:plates", Optional.of(new StackKey(item)), Optional.of(PatternKind.STANDARD), names), 1, Optional.empty());
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY), net.neoforged.neoforge.network.connection.ConnectionType.OTHER);
        try {
            var delivered = new ArrayList<PatternBrowserRequestPacket>();
            assertTrue(request.sendIfBounded(buffer.registryAccess(), packet -> {
                packet.write(buffer); delivered.add(new PatternBrowserRequestPacket(buffer));
            }));
            assertEquals(List.of(request), delivered); assertEquals(0, buffer.readableBytes());
            assertEquals("precise", item.getHoverName().getString()); assertEquals(1, item.getCount());
        }
        finally { buffer.release(); }
    }
    @Test void responseRoundTripHasAtMost32SummariesAndNoUnselectedDetail() {
        var page = PatternBrowserServiceTest.service(PatternBrowserServiceTest.snapshot(40)).page(PatternBrowserServiceTest.FENCE, PatternBrowserFilter.EMPTY, 0);
        var response = new PatternBrowserResponsePacket(1, 3, page, PatternBrowserDiagnostics.empty());
        assertSame(response, response.bounded(RegistryAccess.EMPTY));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, net.neoforged.neoforge.network.connection.ConnectionType.OTHER);
        try {
            response.write(buffer); assertTrue(buffer.readableBytes() <= PatternBrowserWire.MAX_RESPONSE_BYTES);
            var decoded = new PatternBrowserResponsePacket(buffer);
            assertEquals(32, decoded.page().entries().size()); assertTrue(decoded.page().detail().isEmpty());
            assertEquals(response, decoded); assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }
    @Test void clientRejectsOldIntentForeignSessionAndRefreshesStaleWithoutMixing() {
        var state = new PatternBrowserClientSession(1, PatternBrowserServiceTest.FENCE);
        var first = state.request(PatternBrowserFilter.EMPTY, 0, Optional.empty());
        var next = state.request(new PatternBrowserFilter("iron", Optional.empty(), Optional.empty()), 0, Optional.empty());
        var page = PatternBrowserServiceTest.service(PatternBrowserServiceTest.snapshot(2)).page(PatternBrowserServiceTest.FENCE, PatternBrowserFilter.EMPTY, 0);
        assertFalse(state.accept(new PatternBrowserResponsePacket(1, first.intentId(), page, PatternBrowserDiagnostics.empty())));
        assertFalse(state.accept(new PatternBrowserResponsePacket(2, next.intentId(), page, PatternBrowserDiagnostics.empty())));
        assertTrue(state.accept(new PatternBrowserResponsePacket(1, next.intentId(), page, PatternBrowserDiagnostics.empty())));
        var stale = PatternBrowserPage.empty(PatternBrowserPage.Status.STALE, PatternBrowserServiceTest.FENCE.withGeneration(8), 0, true);
        assertTrue(state.accept(new PatternBrowserResponsePacket(1, next.intentId(), stale, PatternBrowserDiagnostics.empty())));
        assertTrue(state.page().orElseThrow().entries().isEmpty()); assertEquals(8, state.fence().generation()); assertTrue(state.needsRefresh());
        assertFalse(state.accept(new PatternBrowserResponsePacket(1, next.intentId(), page, PatternBrowserDiagnostics.empty())));
    }
    @Test void oversizedLengthsVersionsAndNegativeIntentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PatternBrowserRequestPacket(1, PatternBrowserServiceTest.FENCE, -1, PatternBrowserFilter.EMPTY, 0, Optional.empty()));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, net.neoforged.neoforge.network.connection.ConnectionType.OTHER);
        try { buffer.writeVarInt(999); assertThrows(IllegalArgumentException.class, () -> new PatternBrowserRequestPacket(buffer)); }
        finally { buffer.release(); }
    }

    @Test void newNavigationClearsOldRowsBeforeAnyResponseButRefreshKeepsSameView() {
        var state = new PatternBrowserClientSession(1, PatternBrowserServiceTest.FENCE);
        var request = state.request(PatternBrowserFilter.EMPTY, 0, Optional.empty());
        var page = PatternBrowserServiceTest.service(PatternBrowserServiceTest.snapshot(40)).page(PatternBrowserServiceTest.FENCE, PatternBrowserFilter.EMPTY, 0);
        assertTrue(state.accept(new PatternBrowserResponsePacket(1, request.intentId(), page, PatternBrowserDiagnostics.empty())));
        state.request(PatternBrowserFilter.EMPTY, 0, Optional.empty());
        assertTrue(state.page().isPresent());
        state.request(PatternBrowserFilter.EMPTY, 1, Optional.empty());
        assertTrue(state.page().isEmpty(), "Old rows must not be displayed under a newly requested page/filter");
    }

    @Test void selectedDetailPreservesTagPredicateFactsWithoutRevalidatingLiveTags() {
        var page = PatternBrowserServiceTest.service(PatternBrowserServiceTest.snapshot(1)).page(PatternBrowserServiceTest.FENCE,
            PatternBrowserFilter.EMPTY, 0, Optional.of(new UUID(0, 0)));
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, net.neoforged.neoforge.network.connection.ConnectionType.OTHER);
        try {
            new PatternBrowserResponsePacket(1, 1, page, PatternBrowserDiagnostics.empty()).write(wire);
            var decoded = new PatternBrowserResponsePacket(wire).page().detail().orElseThrow();
            assertEquals(page.detail(), Optional.of(decoded));
            assertEquals("forge:plates", decoded.inputs().getFirst().tagId().toString());
            assertNull(decoded.inputs().getFirst().itemId());
        } finally { wire.release(); }
    }

    @Test void envelopeRejectsExcessAllocationTrailingDataAndMoreThan32Rows() {
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, net.neoforged.neoforge.network.connection.ConnectionType.OTHER);
        try {
            wire.writeVarInt(PatternBrowserWire.VERSION); wire.writeVarInt(PatternBrowserWire.MAX_REQUEST_BYTES + 1);
            assertThrows(IllegalArgumentException.class, () -> new PatternBrowserRequestPacket(wire));
            wire.clear();
            var request = new PatternBrowserRequestPacket(1, PatternBrowserServiceTest.FENCE, 1, PatternBrowserFilter.EMPTY, 0, Optional.empty());
            request.write(wire);
            // The inner envelope size must be exact, not silently accept an appended byte.
            var decoded = new PatternBrowserRequestPacket(wire); assertEquals(request, decoded);
            wire.clear(); request.write(wire); wire.readVarInt(); byte[] inner = wire.readByteArray(PatternBrowserWire.MAX_REQUEST_BYTES);
            wire.clear(); wire.writeVarInt(PatternBrowserWire.VERSION); wire.writeVarInt(inner.length + 1); wire.writeBytes(inner); wire.writeByte(0);
            assertThrows(IllegalArgumentException.class, () -> new PatternBrowserRequestPacket(wire));
            assertThrows(IllegalArgumentException.class, () -> new PatternBrowserPage(PatternBrowserPage.Status.OK,
                PatternBrowserServiceTest.FENCE, 0, 40, 40, false, 0, 0,
                java.util.stream.IntStream.range(0, 33).mapToObj(i -> PatternBrowserSummary.from(PatternBrowserServiceTest.entry(i), 0, false)).toList(), Optional.empty()));
        } finally { wire.release(); }
    }

    @Test void responseForOtherCoreOrLibraryCannotReplaceCurrentView() {
        var state = new PatternBrowserClientSession(1, PatternBrowserServiceTest.FENCE);
        var request = state.request(PatternBrowserFilter.EMPTY, 0, Optional.empty());
        for (var fence : List.of(new BrowserFence(UUID.randomUUID(), PatternBrowserServiceTest.SOURCE, PatternBrowserServiceTest.LIBRARY, 7),
            new BrowserFence(PatternBrowserServiceTest.FENCE.sessionId(), PatternBrowserServiceTest.SOURCE, UUID.randomUUID(), 7))) {
            assertFalse(state.accept(new PatternBrowserResponsePacket(1, request.intentId(),
                PatternBrowserPage.empty(PatternBrowserPage.Status.STALE, fence, 0, false), PatternBrowserDiagnostics.empty())));
            assertTrue(state.page().isEmpty());
        }
    }

    @Test void unicodeLowercaseExpansionDoesNotExceedThe64CharacterWireField() {
        var request = new PatternBrowserRequestPacket(1, PatternBrowserServiceTest.FENCE, 1,
            new PatternBrowserFilter("İ".repeat(64), Optional.empty(), Optional.empty()), 0, Optional.empty());
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, net.neoforged.neoforge.network.connection.ConnectionType.OTHER);
        try { request.write(wire); assertEquals(request, new PatternBrowserRequestPacket(wire)); }
        finally { wire.release(); }
    }

    @Test void periodicRefreshWaitsForSlowResponsesBeforeReplacingIntent() {
        var state = new PatternBrowserClientSession(1, PatternBrowserServiceTest.FENCE);
        var request = state.request(PatternBrowserFilter.EMPTY, 0, Optional.empty());
        assertEquals(100, state.refreshAfterTicks());
        var page = PatternBrowserServiceTest.service(PatternBrowserServiceTest.snapshot(1)).page(PatternBrowserServiceTest.FENCE, PatternBrowserFilter.EMPTY, 0);
        assertTrue(state.accept(new PatternBrowserResponsePacket(1, request.intentId(), page, PatternBrowserDiagnostics.empty())));
        assertEquals(20, state.refreshAfterTicks());
    }

    @Test void oversizedSelectedComponentsReturnExplicitStatusInsteadOfTruncation() {
        var stack = Items.IRON_INGOT.getDefaultInstance();
        var data = new net.minecraft.nbt.CompoundTag();
        for (int i = 0; i < 64; i++) data.putString("field" + i, "x".repeat(4096));
        stack.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(data));
        var original = PatternBrowserServiceTest.entry(0).pattern();
        var entry = new PatternBrowserEntry(new PatternRecord(original.patternId(), original.source(), original.inputs(),
            List.of(new PatternOutput(new StackKey(stack), 1)), original.body(), original.priorityOverride()));
        var snapshot = new RecipeIndexSnapshot(7, false, List.of(entry.pattern()), 0, 0, List.of());
        var page = PatternBrowserServiceTest.service(snapshot).page(PatternBrowserServiceTest.FENCE, PatternBrowserFilter.EMPTY, 0, Optional.of(entry.patternId()));
        var response = new PatternBrowserResponsePacket(1, 1, page, PatternBrowserDiagnostics.empty()).bounded(RegistryAccess.EMPTY);
        assertEquals(PatternBrowserPage.Status.TOO_LARGE, response.page().status());
        assertTrue(response.page().entries().isEmpty()); assertTrue(response.page().detail().isEmpty());
    }
}
