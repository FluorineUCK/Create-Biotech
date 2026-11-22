package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterControllerRuntimeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControlDraft;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.AdmissionStatus;
import net.minecraft.core.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ClusterPublicPinRuntimeTest {    @Test void internalWireLogsWaitDisconnectRecoveryAndOneActualPairNotPayloads() throws Exception {
        var f = internal(false); var controller = f.fixture().controller(); var reader = f.fixture().computer(); var writer = f.caller();
        reader.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet", PACKET))), new DataValue.RecordValue(Map.of()));
        writer.installFlow(new FlowProgram(Map.of("packet", PACKET, "ticks", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Sleep("delay", "ticks"), new FlowProgram.Write("write", "x0", "packet"))),
            new DataValue.RecordValue(Map.of("packet", payload(12), "ticks", new DataValue.IntegerValue(10))));
        var members = Map.of("A", new ClusterConfiguration.Member(reader.communicationIdentity().orElseThrow(), MEMBER),
            "B", new ClusterConfiguration.Member(writer.communicationIdentity().orElseThrow(), FAR));
        var wire = new XBusWire(new XBusWire.Pin("A", "worker", "x1"), new XBusWire.Pin("B", "caller", "x0"), true);
        assertEquals("configured", controller.configure(new ClusterConfiguration(members, Map.of(), Map.of(), Map.of("link", wire)), f.fixture().player()));
        reader.setPaused(false); writer.setPaused(false); finishFlowTick(f); controller.tick();
        f.fixture().world().clock++; finishFlowTick(f); controller.tick();
        writer.disableEndpoint(); f.fixture().world().clock++; finishFlowTick(f); controller.tick();
        writer.configureEndpoint(new EndpointConfiguration("caller", true)); f.fixture().world().clock++; finishFlowTick(f); controller.tick();
        for (int i = 0; i < 16; i++) { f.fixture().world().clock++; finishFlowTick(f); controller.tick(); }
        var events = ((DataValue.ListValue)controller.recentEvents().field("entries")).values();
        for (String code : List.of("closed", "recovered", "xbus_exchanged")) assertEquals(1, events.stream()
            .filter(e -> ((DataValue.RecordValue)e).field("code").equals(new DataValue.TextValue(code))).count(), code);
        assertEquals(2, events.stream().filter(e -> ((DataValue.RecordValue)e).field("code").equals(new DataValue.TextValue("xbus_read_idle"))).count());
        assertEquals(payload(12), reader.flowSnapshot().orElseThrow().state().locals().field("packet"));
        assertFalse(controller.recentEvents().toString().contains("packet"));
    }    @Test void publicPinWaitClosureAndRecoveryAreDistinctAndRepeatedStatusReadsDoNotSpam() throws Exception {
        var f = configured(); var controller = f.fixture().controller(); var reader = f.fixture().computer();
        controller.recentEvents(); controller.recentEvents();
        reader.disableEndpoint(); controller.recentEvents(); controller.recentEvents();
        reader.configureEndpoint(new EndpointConfiguration("worker", true)); controller.recentEvents(); controller.recentEvents();
        var events = ((DataValue.ListValue)controller.recentEvents().field("entries")).values();
        for (String code : List.of("closed", "recovered")) assertEquals(1, events.stream()
            .filter(e -> ((DataValue.RecordValue)e).field("code").equals(new DataValue.TextValue(code))).count());
        assertEquals(2, events.stream().filter(e -> ((DataValue.RecordValue)e).field("code").equals(new DataValue.TextValue("waiting_read"))).count());
        assertEquals(FlowRuntime.Status.WAITING_READ, reader.flowSnapshot().orElseThrow().state().status());
    }    @Test void concentratedPublicPinReportsActualRendezvousWithoutPublishingItsValueOrMemberRam() throws Exception {
        var f = configured(); var controller = f.fixture().controller(); var writer = f.caller();
        var expected = controller.xbusPort("io", "x1").orElseThrow();
        assertEquals(XBusExchange.Status.EXCHANGED, writer.xbusAccess("caller", writer.xbusPort("caller", "x0").orElseThrow()).orElseThrow()
            .exchange(controller.xbusAccess("io", expected).orElseThrow()));
        var events = controller.recentEvents();
        assertEquals(1, ((DataValue.ListValue)events.field("entries")).values().stream()
            .filter(e -> ((DataValue.RecordValue)e).field("code").equals(new DataValue.TextValue("xbus_exchanged"))).count());
        assertFalse(events.toString().contains("packet")); assertFalse(events.toString().contains("IntegerValue[value=12]"));
        assertEquals(events, controller.recentEvents());
        assertTrue(controller.routingState().orElseThrow().mailbox().queued().isEmpty());
    }
    @Test void resettingTheOriginalComputerRetiresItsOldPeerEvenAfterThePublicMappingIsRemoved() throws Exception {
        var f = configured(); var controller = f.fixture().controller(); var reader = f.fixture().computer(); var writer = f.caller();
        var oldBook = new net.minecraft.nbt.CompoundTag(); reader.write(oldBook, RegistryAccess.EMPTY, false);
        assertEquals(XBusExchange.Status.EXCHANGED, writer.exchangeXBus("caller", writer.xbusPort("caller", "x0").orElseThrow(),
            controller, "io", controller.xbusPort("io", "x1").orElseThrow()));
        writer.reset();
        var draft = new ClusterControlDraft(controller.routingState().orElseThrow().configuration()); draft.removePublicPin("io"); save(f, draft);
        f.fixture().world().entities.remove(FAR); // The original writer cannot be rediscovered or force-loaded.
        reader.read(oldBook, RegistryAccess.EMPTY, false);
        var readerState = reader.xbusPort("worker", "x1").orElseThrow(); var before = reader.flowSnapshot();
        var group = XBusAccess.network(List.of(reader.xbusAccess("worker", readerState).orElseThrow()));
        assertEquals(Optional.of(XBusExchange.Status.RETIRED), group.inspect().rejection());
        assertEquals(before, reader.flowSnapshot());
        var store = XBusHistorySavedData.get(f.fixture().world());
        var tag = store.save(new net.minecraft.nbt.CompoundTag(), RegistryAccess.EMPTY);
        assertFalse(tag.toString().contains("packet")); assertFalse(tag.toString().contains("payload"));
        f.fixture().world().storage.set(XBusHistorySavedData.DATA_NAME, XBusHistorySavedData.load(tag, RegistryAccess.EMPTY));
        assertEquals(Optional.of(XBusExchange.Status.RETIRED), XBusAccess.network(List.of(reader.xbusAccess("worker", readerState).orElseThrow())).inspect().rejection());
    }
    @Test void resettingARestoredReadWithNoLocalPeersStillPublishesItsIndependentRetirement() throws Exception {
        var f = configured(); var reader = f.fixture().computer(); var writer = f.caller(); var controller = f.fixture().controller();
        var oldReader = new net.minecraft.nbt.CompoundTag(); reader.write(oldReader, RegistryAccess.EMPTY, false);
        assertEquals(XBusExchange.Status.EXCHANGED, writer.exchangeXBus("caller", writer.xbusPort("caller", "x0").orElseThrow(),
            controller, "io", controller.xbusPort("io", "x1").orElseThrow()));
        reader.read(oldReader, RegistryAccess.EMPTY, false); reader.xbusPort("worker", "x1").orElseThrow();
        assertTrue(reader.flowSnapshot().orElseThrow().control().peers().isEmpty());
        reader.reset();
        // Unload/reload can immediately bring back the old block image. No subsequent access
        // to the new flow is available to repair missing lifecycle publication on reset.
        reader.read(oldReader, RegistryAccess.EMPTY, false);
        var port = reader.xbusPort("worker", "x1").orElseThrow(); var before = reader.flowSnapshot();
        assertEquals(Optional.of(XBusExchange.Status.RETIRED), XBusAccess.network(List.of(reader.xbusAccess("worker", port).orElseThrow())).inspect().rejection());
        assertEquals(before, reader.flowSnapshot());
    }
    static final DataType.RecordType PACKET = new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER));
    static final DataValue.RecordValue INPUTS = new DataValue.RecordValue(Map.of("packet", payload(12)));
    @BeforeAll static void setup() throws Exception { bootstrap(); }

    @Test void publicContractKeepsThePhysicalControllerIdentitySeparateFromItsMappedComputer() throws Exception {
        var f = configured(); CommunicationEndpoint endpoint = f.fixture().controller();
        var before = f.fixture().computer().flowSnapshot();
        assertEquals(Optional.of(f.fixture().controller().controllerId()), endpoint.communicationIdentity());
        assertNotEquals(endpoint.communicationIdentity().orElseThrow(), endpoint.xbusPort("io", "x1").orElseThrow().computer());
        assertEquals(before, f.fixture().computer().flowSnapshot());
        f.fixture().world().entities.remove(CONTROLLER);
        assertTrue(endpoint.communicationIdentity().isEmpty(), "A detached instance cannot grant its old physical identity");
        CommunicationEndpoint legacy = Optional::empty;
        assertTrue(legacy.communicationIdentity().isEmpty());
    }

    @Test void revokedMappingsAndWorldChangesInvalidateCapturedAccessWithoutMutatingEitherOwner() throws Exception {
        for (String fault : List.of("closed", "removed", "renamed", "broken", "controller_missing", "controller_reload",
                "grant_closed", "grant_renamed", "program_reset", "different_pin", "removed_then_restored", "member_missing")) {
            var f = configured(); var controller = f.fixture().controller(); var writer = f.caller(); var reader = f.fixture().computer();
            var old = controller.routingState().orElseThrow().configuration(); var draft = new ClusterControlDraft(old);
            var left = writer.xbusAccess("caller", writer.xbusPort("caller", "x0").orElseThrow()).orElseThrow();
            var observed = controller.xbusPort("io", "x1").orElseThrow(); var right = controller.xbusAccess("io", observed).orElseThrow();
            switch (fault) {
                case "closed" -> { draft.putPublicPin("io", "io", new XBusPublicPin(old.publicPins().get("io").target(), false)); save(f, draft); }
                case "removed" -> { draft.removePublicPin("io"); save(f, draft); }
                case "renamed" -> { draft.putPublicPin("io", "new_name", old.publicPins().get("io")); save(f, draft); }
                case "broken" -> f.fixture().world().states.remove(BlockPos.ZERO);
                case "controller_missing" -> f.fixture().world().entities.remove(CONTROLLER);
                case "controller_reload" -> controller.loadWithComponents(controller.saveWithFullMetadata(RegistryAccess.EMPTY), RegistryAccess.EMPTY);
                case "grant_closed" -> reader.disableEndpoint();
                case "grant_renamed" -> reader.configureEndpoint(new EndpointConfiguration("new_grant", true));
                case "program_reset" -> reader.reset();
                case "different_pin" -> { draft.putPublicPin("io", "io", new XBusPublicPin(new XBusWire.Pin("worker", "worker", "x0"), true)); save(f, draft); }
                case "removed_then_restored" -> { draft.removePublicPin("io"); save(f, draft); assertEquals("configured", controller.configure(old, f.fixture().player())); }
                case "member_missing" -> f.fixture().world().entities.remove(MEMBER);
            }
            var beforeA = writer.flowSnapshot(); var beforeB = reader.flowSnapshot();
            var expected = switch (fault) {
                case "closed", "grant_closed" -> XBusExchange.Status.CLOSED;
                case "removed", "renamed" -> XBusExchange.Status.NOT_FOUND;
                case "broken", "controller_missing", "member_missing" -> XBusExchange.Status.UNAVAILABLE;
                case "different_pin" -> XBusExchange.Status.PORT_MISMATCH;
                default -> XBusExchange.Status.STALE;
            };
            assertEquals(expected, left.exchange(right), fault);
            assertEquals(beforeA, writer.flowSnapshot(), fault); assertEquals(beforeB, reader.flowSnapshot(), fault);
        }
    }

    @Test void latePeerCallbacksCannotBypassTheRealControllersFinalLocalProof() throws Exception {
        for (String fault : List.of("grant_closed", "detached", "mapping", "shell", "upper", "member", "reload")) {
            var f = configured(); var controller = f.fixture().controller(); var reader = f.fixture().computer(); var writer = f.caller();
            var incoming = controller.xbusAccess("io", controller.xbusPort("io", "x1").orElseThrow()).orElseThrow();
            var peer = writer.xbusAccess("caller", writer.xbusPort("caller", "x0").orElseThrow()).orElseThrow().guarded(() -> {
                switch (fault) {
                    case "grant_closed" -> reader.disableEndpoint();
                    case "detached" -> reader.detachCluster(controller.controllerId());
                    case "mapping" -> {
                        var draft = new ClusterControlDraft(controller.routingState().orElseThrow().configuration());
                        draft.removePublicPin("io"); save(f, draft);
                    }
                    case "shell" -> f.fixture().world().states.remove(BlockPos.ZERO);
                    case "upper" -> f.fixture().world().states.remove(MEMBER.above());
                    case "member" -> f.fixture().world().entities.remove(MEMBER);
                    case "reload" -> controller.loadWithComponents(controller.saveWithFullMetadata(RegistryAccess.EMPTY), RegistryAccess.EMPTY);
                }
                return Optional.empty();
            });
            var a = writer.flowSnapshot(); var b = reader.flowSnapshot();
            var expected = switch (fault) {
                case "grant_closed" -> XBusExchange.Status.CLOSED;
                case "shell", "upper", "member" -> XBusExchange.Status.UNAVAILABLE;
                default -> XBusExchange.Status.STALE;
            };
            assertEquals(expected, incoming.exchange(peer), fault);
            assertEquals(a, writer.flowSnapshot(), fault); assertEquals(b, reader.flowSnapshot(), fault);
        }
    }

    @Test void publishingAliasesDoesNotWireOrExecuteAReceiverAndAllAliasesRetainOnePhysicalIdentity() throws Exception {
        var f = configured(); var controller = f.fixture().controller(); var writer = f.caller(); var reader = f.fixture().computer();
        var draft = new ClusterControlDraft(controller.routingState().orElseThrow().configuration());
        draft.putPublicPin(null, "another_name", draft.configuration().publicPins().get("io")); save(f, draft);
        var beforeA = writer.flowSnapshot(); var beforeB = reader.flowSnapshot();
        for (int i = 0; i < 3; i++) { f.fixture().world().clock++; controller.tick(); writer.tick(); }
        assertEquals(beforeA, writer.flowSnapshot()); assertEquals(beforeB, reader.flowSnapshot());
        var expected = controller.xbusPort("io", "x1").orElseThrow();
        assertEquals(Optional.of(expected), controller.xbusPort("another_name", "x1"));
        var a = writer.xbusAccess("caller", writer.xbusPort("caller", "x0").orElseThrow()).orElseThrow();
        var alias1 = controller.xbusAccess("io", expected).orElseThrow(); var alias2 = controller.xbusAccess("another_name", expected).orElseThrow();
        assertEquals(XBusExchange.Status.EXCHANGED, a.exchange(alias1));
        assertEquals(XBusExchange.Status.STALE, a.exchange(alias2));
        assertEquals(1, reader.flowSnapshot().orElseThrow().receipts().size());
    }

    @Test void impossiblePinsAreRejectedAtomicallyAndLegacyMessagesCannotUseAnExportAsAnInbox() throws Exception {
        var f = configured(); var controller = f.fixture().controller(); var before = controller.routingState().orElseThrow();
        var draft = new ClusterControlDraft(before.configuration());
        draft.putPublicPin("io", "io", new XBusPublicPin(new XBusWire.Pin("worker", "worker", "x9"), true));
        assertEquals("xbus_pin_missing", controller.configure(draft.configuration(), f.fixture().player()));
        assertEquals(before, controller.routingState().orElseThrow());
        assertEquals(AdmissionStatus.UNSUPPORTED, controller.receive("io", request()));
        assertEquals(before, controller.routingState().orElseThrow());
        assertEquals("public_pin_ready", controller.publicPinDiagnostics().get("io"));
        f.fixture().computer().disableEndpoint(); assertEquals("closed", controller.publicPinDiagnostics().get("io"));
    }

    @Test void denialsCannotReportProgressOrBecomeAuthorizedThroughAnAdditionalGuard() throws Exception {
        var f = configured(); var writer = f.caller(); var controller = f.fixture().controller();
        var expected = controller.xbusPort("io", "x1").orElseThrow();
        var draft = new ClusterControlDraft(controller.routingState().orElseThrow().configuration()); draft.removePublicPin("io"); save(f, draft);
        var denied = controller.xbusAccess("io", expected).orElseThrow().guarded(Optional::empty);
        var a = writer.xbusAccess("caller", writer.xbusPort("caller", "x0").orElseThrow()).orElseThrow();
        assertEquals(XBusExchange.Status.NOT_FOUND, a.exchange(denied));
        assertTrue(writer.flowSnapshot().orElseThrow().receipts().isEmpty());
        for (var status : List.of(XBusExchange.Status.EXCHANGED, XBusExchange.Status.READABLE, XBusExchange.Status.PREPARED))
            assertThrows(IllegalArgumentException.class, () -> XBusAccess.denied(status));
    }

    private static void save(InternalFixture fixture, ClusterControlDraft draft) {
        assertEquals("configured", fixture.fixture().controller().configure(draft.configuration(), fixture.fixture().player()));
    }

    @Test void genericPinDiscoveryRevealsOnlyTheSingleExplicitGrantWithoutNeedingMemberNames() throws Exception {
        var f = configured(); var controller = f.fixture().controller();
        assertEquals(List.of("x1"), controller.xbusPins("io"));
        assertTrue(controller.xbusPins("worker").isEmpty());
        assertEquals(List.of("x0", "x1"), f.fixture().computer().xbusPins("worker"));
        assertTrue(f.fixture().computer().xbusPins("io").isEmpty());
        f.fixture().computer().disableEndpoint(); assertTrue(controller.xbusPins("io").isEmpty());
    }

    @Test void controllerExportsOnlyTheSelectedPhysicalPinAndDirectlyRendezvousWithItsMember() throws Exception {
        var f = configured(); var controller = f.fixture().controller(); var writer = f.caller(); var reader = f.fixture().computer();
        assertEquals(List.of("io"), controller.advertisements().stream().map(port -> port.name()).toList());
        var expected = reader.xbusPort("worker", "x1").orElseThrow();
        assertEquals(Optional.of(expected), controller.xbusPort("io", "x1"));
        assertTrue(controller.xbusPort("io", "x0").isEmpty());
        assertTrue(controller.xbusPort("worker", "x1").isEmpty());
        // Trusted coordinator seam only: declaring an export did not create a physical external wire.
        assertEquals(XBusExchange.Status.EXCHANGED, writer.exchangeXBus("caller", writer.xbusPort("caller", "x0").orElseThrow(),
            controller, "io", expected));
        assertEquals(INPUTS, reader.flowSnapshot().orElseThrow().state().locals());
        assertEquals(FlowRuntime.Status.COMPLETE, writer.flowSnapshot().orElseThrow().state().status());
        assertTrue(controller.routingState().orElseThrow().mailbox().queued().isEmpty());
    }

    static InternalFixture configured() throws Exception {
        var f = internal(false); var reader = f.fixture().computer(); var writer = f.caller();
        reader.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet", PACKET))), new DataValue.RecordValue(Map.of()));
        writer.installFlow(new FlowProgram(Map.of("packet", PACKET), List.of(new FlowProgram.Write("send", "x0", "packet"))), INPUTS);
        var config = new ClusterConfiguration(Map.of("worker", new ClusterConfiguration.Member(reader.communicationIdentity().orElseThrow(), MEMBER)),
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of("io", new XBusPublicPin(new XBusWire.Pin("worker", "worker", "x1"), true)));
        assertEquals("configured", f.fixture().controller().configure(config, f.fixture().player()));
        reader.step(); writer.step(); reader.setPaused(false); writer.setPaused(false);
        return f;
    }
}
