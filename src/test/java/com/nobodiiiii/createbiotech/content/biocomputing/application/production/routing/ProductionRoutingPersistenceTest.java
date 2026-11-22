package com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRoutingTest.*;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.RuntimeNbt;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProductionRoutingPersistenceTest {
    @BeforeAll static void bootstrapRegistry() throws ReflectiveOperationException { bootstrap(); }

    @Test void routingFactsKeepExactHistoricalIdentitiesIncludingNestedScopesAndCursors() {
        var scope = scope(1); var source = source(scope, 10, 7); var candidate = source.candidate();
        var identity = new DefaultRoutePolicy.RouteIdentity(source.libraryId(), source.patternId(), source.providerKey());
        var cases = new LinkedHashMap<Object, String>();
        cases.put(candidate, "RouteCandidate");
        for (var kind : RouteCandidate.ProviderKind.values()) cases.put(kind, "RouteCandidate$ProviderKind");
        for (var availability : RouteCandidate.Availability.values()) cases.put(availability, "RouteCandidate$Availability");
        for (var blocker : RouteCandidate.Blocker.values()) cases.put(blocker, "RouteCandidate$Blocker");
        cases.put(identity, "DefaultRoutePolicy$RouteIdentity");
        cases.put(new DefaultRoutePolicy.CursorState(Map.of(7, identity)), "DefaultRoutePolicy$CursorState");
        cases.put(new DefaultRoutePolicy.Decision(Optional.of(candidate), RouteCandidate.Blocker.NONE, false), "DefaultRoutePolicy$Decision");
        cases.put(scope, "RouteSelectionScope");
        cases.put(RouteSelectionRequest.patternOnly(scope), "RouteSelectionRequest");
        cases.put(SelectedProviderDescriptor.selected(scope, candidate), "SelectedProviderDescriptor");
        cases.put(source, "ProductionRouteTable$ProviderSource");
        cases.put(new OrdinaryProviderRegistry.Registration(id(20), id(30), scope.requestedStackKey(), 7,
            RouteCandidate.Availability.AVAILABLE), "OrdinaryProviderRegistry$Registration");
        cases.forEach((value, historicalName) -> {
            var tag = CurrentSchemaStateNbt.save(value, RegistryAccess.EMPTY);
            assertEquals(6, tag.getInt("Version"));
            assertEquals("com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library." + historicalName, tag.getString("Type"));
            assertEquals(tag.getString("Type"), tag.getCompound("Value").getString("Type"));
            assertEquals(value, CurrentSchemaStateNbt.load(tag, RegistryAccess.EMPTY, value.getClass()).orElseThrow());
            var alias = tag.copy(); alias.putString("Type", value.getClass().getName());
            assertTrue(CurrentSchemaStateNbt.load(alias, RegistryAccess.EMPTY, value.getClass()).isEmpty());
            var nestedAlias = tag.copy(); nestedAlias.getCompound("Value").putString("Type", value.getClass().getName());
            assertTrue(CurrentSchemaStateNbt.load(nestedAlias, RegistryAccess.EMPTY, value.getClass()).isEmpty());
        });
        assertThrows(IllegalArgumentException.class, () -> CurrentSchemaStateNbt.save(new ProductionRouteTable(), RegistryAccess.EMPTY));
    }

    @Test void wrongElementTypeCannotRestoreAsAnEmptySuccessfulRuntimeCursor() {
        var stored = RuntimeNbt.save(ClusterEngineState.empty(id(1), id(2)));
        var malformed = new ListTag(); malformed.add(StringTag.valueOf("not a cursor"));
        stored.getCompound("RouteCursor").put("Cursors", malformed);
        assertTrue(RouteCursorCodecs.load(stored.getCompound("RouteCursor")).isEmpty());
        assertTrue(RuntimeNbt.load(stored).isEmpty(), "invalid cursor data must not reset production fairness on reload");
    }

    @Test void malformedProviderTextReturnsDecodeFailureRatherThanThrowing() {
        var identity = new DefaultRoutePolicy.RouteIdentity(id(1), id(2), "valid");
        var tag = RouteCursorCodecs.save(new DefaultRoutePolicy.CursorState(Map.of(7, identity)));
        tag.getList("Cursors", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).putString("ProviderKey", "\u0000");
        assertTrue(assertDoesNotThrow(() -> RouteCursorCodecs.load(tag)).isEmpty());
    }

    @Test void historicalCursorFieldsRemainExactAndMalformedEntriesAreRejectedAsAWhole() {
        var identity = new DefaultRoutePolicy.RouteIdentity(id(1), id(2), "provider/first");
        var state = new DefaultRoutePolicy.CursorState(Map.of(7, identity));
        var entry = new CompoundTag(); entry.putInt("Priority", 7); entry.putUUID("LibraryId", id(1));
        entry.putUUID("PatternId", id(2)); entry.putString("ProviderKey", "provider/first");
        var entries = new ListTag(); entries.add(entry); var fixture = new CompoundTag(); fixture.put("Cursors", entries);
        assertEquals(fixture, RouteCursorCodecs.save(state));
        assertEquals(state, RouteCursorCodecs.load(fixture).orElseThrow());
        var runtime = ClusterEngineState.empty(id(10), id(20)).withRouteCursor(state);
        var saved = RuntimeNbt.save(runtime); assertEquals(fixture, saved.getCompound("RouteCursor"));
        assertEquals(runtime, RuntimeNbt.load(saved).orElseThrow());
        for (Consumer<CompoundTag> damage : List.<Consumer<CompoundTag>>of(
                root -> root.putString("extra", "invalid"),
                root -> root.getList("Cursors", Tag.TAG_COMPOUND).add(entry.copy()),
                root -> root.getList("Cursors", Tag.TAG_COMPOUND).getCompound(0).putString("Priority", "7"),
                root -> root.getList("Cursors", Tag.TAG_COMPOUND).getCompound(0).remove("LibraryId"),
                root -> root.getList("Cursors", Tag.TAG_COMPOUND).getCompound(0).putString("ProviderKey", " "),
                root -> root.getList("Cursors", Tag.TAG_COMPOUND).getCompound(0).putString("ProviderKey", "x".repeat(4097)),
                root -> { var tooMany = new ListTag(); for (int i = 0; i <= DefaultRoutePolicy.CursorState.MAX_PRIORITIES; i++) {
                    var copy = entry.copy(); copy.putInt("Priority", i); tooMany.add(copy); } root.put("Cursors", tooMany); })) {
            var invalid = fixture.copy(); damage.accept(invalid);
            assertTrue(RouteCursorCodecs.load(invalid).isEmpty());
        }
        assertEquals(fixture, RouteCursorCodecs.save(state));
    }
}
