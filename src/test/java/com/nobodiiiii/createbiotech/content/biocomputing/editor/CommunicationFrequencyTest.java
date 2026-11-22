package com.nobodiiiii.createbiotech.content.biocomputing.editor;


import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.util.AbstractList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.core.RegistryAccess;

import com.nobodiiiii.createbiotech.network.CBPackets;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu;
import com.simibubi.create.content.logistics.AddressEditBox;

import net.minecraft.nbt.CompoundTag;

class CommunicationFrequencyTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() throws Exception {
        Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null)
            type.getMethod("of", List.class, List.class, List.class, List.class, java.util.Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var bootstrapped = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        bootstrapped.setAccessible(true);
        bootstrapped.setBoolean(null, true);
    }

    @Test
    void matchingIsLiteralCaseSensitiveAndBlankIsUnconfigured() {
        assertNotEquals(CommunicationFrequency.of("Factory-A"), CommunicationFrequency.of("factory-a"));
        assertEquals("*", CommunicationFrequency.of("*").value());
        assertFalse(CommunicationFrequency.of("").configured());
        assertThrows(IllegalArgumentException.class, () -> CommunicationFrequency.of("x".repeat(26)));
    }

    @Test
    void clipboardSuggestionsKeepLiteralWhitespaceAndHashCharacters() {
        assertIterableEquals(List.of(CommunicationFrequency.of("  Factory-A  "),
            CommunicationFrequency.of("#not-a-comment"), CommunicationFrequency.of("Known")),
            CommunicationFrequencyEditBox.literalSuggestions(List.of("#  Factory-A  ",
                "##not-a-comment", "not-a-frequency", "#" + "x".repeat(26)),
                List.of(CommunicationFrequency.of("Known"))));
    }

    @Test
    void literalSuggestionsHaveABoundedCount() {
        List<CommunicationFrequency> known = java.util.stream.IntStream.range(0, 65)
            .mapToObj(index -> CommunicationFrequency.of("known-" + index)).toList();
        assertEquals(64, CommunicationFrequencyEditBox.literalSuggestions(List.of(), known).size());
    }

    @Test
    void authorizedFrequencySuggestionsStopBeforeReadingPastTheCandidateBound() {
        List<CommunicationFrequency> known = new AbstractList<>() {
            @Override public CommunicationFrequency get(int index) {
                if (index >= 64) throw new AssertionError("read beyond authorized suggestion bound");
                return CommunicationFrequency.of("known-" + index);
            }
            @Override public int size() { return 1_000; }
        };

        assertEquals(64, CommunicationFrequencyEditBox.literalSuggestions(List.of(), known).size());
    }

    @Test
    void literalMatchesDoesNotReadThe65thCandidate() {
        Iterable<CommunicationFrequency> poisonedCandidates = () -> new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public CommunicationFrequency next() {
                if (index >= 64)
                    throw new AssertionError("literal matcher read the 65th candidate");
                return CommunicationFrequency.of("candidate-" + index++);
            }
        };

        assertEquals(64,
            FrequencySuggestionBehavior.literalMatches("", 0, poisonedCandidates).size());
    }

    @Test
    void rejectsLegacyIntegerNbtWithoutMigration() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Frequency", 42);
        assertTrue(CommunicationFrequency.read(tag, "Frequency").isEmpty());
    }

    @Test
    void rejectsLegacyIntegerWhoseBytesCollideWithTheOldOneByteMarker() throws Exception {
        Object buffer = buffer();
        Class<?> registryBufferType = Class.forName("net.minecraft.network.RegistryFriendlyByteBuf");
        registryBufferType.getMethod("writeInt", int.class).invoke(buffer, 0x46024142);

        assertThrows(InvocationTargetException.class,
            () -> CommunicationFrequency.class.getMethod("read", registryBufferType).invoke(null, buffer));
    }

    @Test
    void rejectsMalformedUtf8InsteadOfSubstitutingReplacementCharacters() throws Exception {
        Object buffer = buffer();
        Class<?> registryBufferType = Class.forName("net.minecraft.network.RegistryFriendlyByteBuf");
        CommunicationFrequency.of("x").write((net.minecraft.network.RegistryFriendlyByteBuf) buffer);
        registryBufferType.getMethod("setByte", int.class, int.class).invoke(buffer, 6, 0xff);

        assertThrows(InvocationTargetException.class,
            () -> CommunicationFrequency.class.getMethod("read", registryBufferType).invoke(null, buffer));
    }

    @Test
    void rejectsUnpairedUtf16SurrogatesBeforePacketEncoding() {
        assertThrows(IllegalArgumentException.class, () -> CommunicationFrequency.of("\ud800"));
        assertThrows(IllegalArgumentException.class, () -> CommunicationFrequency.of("\udc00"));
    }

    @Test
    void clipboardEntryTraversalStopsBeforeReadingPastTheFrequencyCandidateBound() {
        List<String> overlongPage = new AbstractList<>() {
            @Override public String get(int index) {
                if (index >= 128) throw new AssertionError("read beyond candidate bound");
                return "#literal";
            }
            @Override public int size() { return 1_000; }
        };

        assertEquals(128, FrequencySuggestionBehavior.boundedClipboardLines(List.of(overlongPage),
            page -> page, line -> line).size());
    }

    @Test
    void nullMappedClipboardEntriesStillStopBeforeReadingThe129thEntry() {
        List<String> overlongPage = new AbstractList<>() {
            @Override public String get(int index) {
                if (index >= 128) throw new AssertionError("read beyond entry bound after null mapping");
                return "ignored";
            }
            @Override public int size() { return 1_000; }
        };

        assertTrue(FrequencySuggestionBehavior.boundedClipboardLines(List.of(overlongPage),
            page -> page, line -> null).isEmpty());
    }

    @Test
    void aCandidateEqualToTheCursorPrefixStillReplacesTheWholeDifferentValue() {
        List<FrequencySuggestionBehavior.Replacement> matches =
            FrequencySuggestionBehavior.literalMatches("factory-tail", "factory".length(),
                List.of(CommunicationFrequency.of("factory")));

        assertEquals(1, matches.size());
        assertEquals("factory", matches.getFirst().applyTo("factory-tail"));
    }

    @Test
    void clipboardCollectionDoesNotReadThe37thInventoryStack() {
        List<Integer> stacks = new AbstractList<>() {
            @Override public Integer get(int index) {
                if (index >= 36) throw new AssertionError("read beyond stack bound");
                return index;
            }
            @Override public int size() { return 1_000; }
        };

        assertTrue(FrequencySuggestionBehavior.boundedClipboardLines(stacks,
            stack -> List.<List<String>>of(), page -> page, entry -> entry).isEmpty());
    }

    @Test
    void clipboardCollectionDoesNotReadThe17thPageAcrossAllStacks() {
        List<List<String>> firstStackPages = java.util.stream.IntStream.range(0, 15)
            .mapToObj(index -> List.of("#page-" + index)).toList();
        List<List<String>> secondStackPages = new AbstractList<>() {
            @Override public List<String> get(int index) {
                if (index >= 1) throw new AssertionError("read beyond global page bound");
                return List.of("#page-15");
            }
            @Override public int size() { return 1_000; }
        };

        assertEquals(16, FrequencySuggestionBehavior.boundedClipboardLines(
            List.of(firstStackPages, secondStackPages),
            stack -> stack, page -> page, entry -> entry).size());
    }

    @Test
    void clipboardAndKnownSuggestionsKeepNoMoreThan64Candidates() {
        List<String> clipboard = java.util.stream.IntStream.range(0, 100)
            .mapToObj(index -> "#clipboard-" + index).toList();
        assertEquals(64, CommunicationFrequencyEditBox.literalSuggestions(clipboard,
            List.of(CommunicationFrequency.of("known"))).size());
    }

    @Test
    void suggestionStateClearsItsCacheAcrossBlurAndRebuildsForSameValueRefocus() {
        FrequencySuggestionBehavior.SuggestionState state =
            FrequencySuggestionBehavior.SuggestionState.inactive().withFocus(true);
        assertTrue(state.needsRefresh("factory", 7));
        state = state.refreshed("factory", 7);
        assertFalse(state.needsRefresh("factory", 7));

        state = state.withFocus(false);
        assertTrue(state.shouldClearDropdown());
		assertEquals(null, state.previous());
		assertEquals(-1, state.previousCursor());
        state = state.withFocus(true);
        assertTrue(state.needsRefresh("factory", 7));
    }

    @Test
    void suggestionStateRefreshesWhenOnlyTheCursorMovesWhileFocused() {
        FrequencySuggestionBehavior.SuggestionState state =
            FrequencySuggestionBehavior.SuggestionState.inactive().withFocus(true)
                .refreshed("factory-tail", "factory-tail".length());

        assertTrue(state.needsRefresh("factory-tail", "factory".length()));
    }

    @Test
    void literalDropdownTickCoordinatesAndRightClickUseTheSharedBehaviorBoundary() {
        assertTrue(FrequencySuggestionBehavior.clearGhostTextWhenDropdownHidden(false));
        assertTrue(FrequencySuggestionBehavior.shouldClearOnRightClick(true, 1));
        assertFalse(FrequencySuggestionBehavior.shouldClearOnRightClick(false, 1));
        assertEquals(118, FrequencySuggestionBehavior.dropdownY(100, 18, false));
        assertEquals(100, FrequencySuggestionBehavior.dropdownY(100, 18, true));
    }

    @Test
    void middleCursorSuggestionReplacesTheEntireLiteralFrequencyValue() {
        List<FrequencySuggestionBehavior.Replacement> matches =
            FrequencySuggestionBehavior.literalMatches("fa-tail", 2,
            List.of(CommunicationFrequency.of("factory")));

        assertEquals(1, matches.size());
        assertEquals("factory", matches.getFirst().applyTo("fa-tail"));
        assertEquals("fa-tail".length(), matches.getFirst().rangeEnd());
    }

    @Test
    void rejectsClaimedUtf8LengthBeforeAllocatingThePayload() throws Exception {
        Object buffer = buffer();
        Class<?> registryBufferType = Class.forName("net.minecraft.network.RegistryFriendlyByteBuf");
        CommunicationFrequency.of("x").write((net.minecraft.network.RegistryFriendlyByteBuf) buffer);
        registryBufferType.getMethod("setByte", int.class, int.class).invoke(buffer, 5, 76);

        assertThrows(InvocationTargetException.class,
            () -> CommunicationFrequency.class.getMethod("read", registryBufferType).invoke(null, buffer));
    }

    @Test
    void frequencyPayloadAndNetworkVersionsRejectMixedPeers() throws Exception {
		assertEquals(5, schemaVersion(PatternStorageCoreMenu.class));
        Field networkVersion = CBPackets.class.getDeclaredField("NETWORK_VERSION");
        networkVersion.setAccessible(true);
		// Version 75 adds explicit wiring enable/disable actions and the enabled status field.
		assertTrue(Integer.parseInt((String)networkVersion.get(null)) >= 75);
    }

    @Test
    void rejectsLegacyIntegerPacketWithoutMigration() throws Exception {
        Class<?> byteBufType = Class.forName("io.netty.buffer.ByteBuf");
        Class<?> registryBufferType = Class.forName("net.minecraft.network.RegistryFriendlyByteBuf");
        Object byteBuf = Class.forName("io.netty.buffer.Unpooled").getMethod("buffer").invoke(null);
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(
            net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        Class<?> connectionType = Class.forName(
            "net.neoforged.neoforge.network.connection.ConnectionType");
        Object buffer = registryBufferType.getConstructor(byteBufType, RegistryAccess.class,
            connectionType).newInstance(byteBuf, registries, connectionType.getField("NEOFORGE").get(null));
        registryBufferType.getMethod("writeInt", int.class).invoke(buffer, 42);

        InvocationTargetException rejected = assertThrows(InvocationTargetException.class,
            () -> CommunicationFrequency.class.getMethod("read", registryBufferType).invoke(null, buffer));
        assertEquals(IllegalArgumentException.class, rejected.getCause().getClass());
    }

    @Test
    void roundTripsLiteralWhitespaceUnicodeSymbolsAndMaximumLength() throws Exception {
        assertRoundTrip("  factory  ");
        assertRoundTrip("e\u0301");
        assertRoundTrip("é");
        assertRoundTrip("#channel");
        assertRoundTrip("?channel");
        assertRoundTrip("🚀");
        assertRoundTrip("x".repeat(25));
    }

    private static int schemaVersion(Class<?> type) throws Exception {
        Field field = type.getDeclaredField("VERSION");
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static Object buffer() throws Exception {
        Class<?> byteBufType = Class.forName("io.netty.buffer.ByteBuf");
        Class<?> registryBufferType = Class.forName("net.minecraft.network.RegistryFriendlyByteBuf");
        Object byteBuf = Class.forName("io.netty.buffer.Unpooled").getMethod("buffer").invoke(null);
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(
            net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        Class<?> connectionType = Class.forName(
            "net.neoforged.neoforge.network.connection.ConnectionType");
        return registryBufferType.getConstructor(byteBufType, RegistryAccess.class, connectionType)
            .newInstance(byteBuf, registries, connectionType.getField("NEOFORGE").get(null));
    }

    private static void assertRoundTrip(String value) {
        CommunicationFrequency frequency = CommunicationFrequency.of(value);
        CompoundTag tag = new CompoundTag();
        frequency.write(tag, "frequency");
        assertEquals(frequency, CommunicationFrequency.read(tag, "frequency").orElseThrow());
        try {
            Object buffer = buffer();
            Class<?> registryBufferType = Class.forName("net.minecraft.network.RegistryFriendlyByteBuf");
            frequency.write((net.minecraft.network.RegistryFriendlyByteBuf) buffer);
            assertEquals(frequency, CommunicationFrequency.class.getMethod("read", registryBufferType)
                .invoke(null, buffer));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void editorRetainsCreateTickAndRightClickPathsForTheLiveDropdown() throws Exception {
        assertEquals(AddressEditBox.class, CommunicationFrequencyEditBox.class.getSuperclass());
        assertEquals(void.class, AddressEditBox.class.getMethod("tick").getReturnType());
        assertEquals(boolean.class, AddressEditBox.class.getMethod("mouseClicked",
            double.class, double.class, int.class).getReturnType());
        assertTrue(CommunicationFrequencyEditBox.class.getDeclaredField("DESTINATION_SUGGESTIONS")
            .trySetAccessible());
    }
}
