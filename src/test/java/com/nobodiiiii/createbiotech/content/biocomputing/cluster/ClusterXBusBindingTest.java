package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterControllerRuntimeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.*;

class ClusterXBusBindingTest {
    @BeforeAll static void setup() throws Exception { bootstrap(); }
    @Test void configuredInternalWireCapturesRealPhysicalPinsWithoutRunningOrPublishingHistory() throws Exception {
        var f = ClusterWorldIndicatorTest.writers(); var controller = f.fixture().controller();
        var beforeLeft = f.fixture().computer().flowSnapshot(); var beforeRight = f.caller().flowSnapshot();
        var history = XBusHistorySavedData.get(f.fixture().world()).save(new CompoundTag(), null);
        var result = controller.captureXBusWire("data");
        assertEquals("ready", result.code());
        var wire = result.binding().orElseThrow();
        assertEquals(MEMBER, wire.left().position()); assertEquals(FAR, wire.right().position());
        assertEquals(f.fixture().computer().communicationIdentity().orElseThrow(), wire.left().pin().computer());
        assertEquals(f.caller().communicationIdentity().orElseThrow(), wire.right().pin().computer());
        assertTrue(XBusAccess.network(wire.accesses()).inspectBindings().isEmpty());
        assertEquals(beforeLeft, f.fixture().computer().flowSnapshot()); assertEquals(beforeRight, f.caller().flowSnapshot());
        assertEquals(history, XBusHistorySavedData.get(f.fixture().world()).save(new CompoundTag(), null));
    }
    @Test void disablingRemovingAndRecreatingALineNeverRevivesItsOldCapture() throws Exception {
        var f = ClusterWorldIndicatorTest.writers(); var controller = f.fixture().controller();
        var initial = controller.routingState().orElseThrow().configuration();
        var binding = controller.captureXBusWire("data").binding().orElseThrow();
        var wire = initial.wires().get("data");
        assertEquals("configured", controller.configure(wires(initial, Map.of("data",
            new XBusWire(wire.left(), wire.right(), false))), f.fixture().player()));
        assertEquals("closed", controller.captureXBusWire("data").code());
        assertEquals(Optional.of(XBusExchange.Status.STALE), XBusAccess.network(binding.accesses()).inspectBindings());
        assertEquals("configured", controller.configure(wires(initial, Map.of()), f.fixture().player()));
        assertEquals("not_found", controller.captureXBusWire("data").code());
        assertEquals("configured", controller.configure(initial, f.fixture().player()));
        assertEquals(Optional.of(XBusExchange.Status.STALE), XBusAccess.network(binding.accesses()).inspectBindings());
        assertEquals("ready", controller.captureXBusWire("data").code());
    }
    @Test void lateOtherRouteCallbacksCannotBreakTheShellCloseAGrantOrReconfigureBeforeConsumption() throws Exception {
        for (String fault : List.of("shell", "grant", "wire", "member")) {
            var f = ClusterWorldIndicatorTest.writers(); var controller = f.fixture().controller();
            f.caller().installFlow(ClusterWorldIndicatorTest.reader(),
                new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.RecordValue(Map.of()));
            f.fixture().computer().step(); f.caller().step(); f.caller().setPaused(false);
            var binding = controller.captureXBusWire("data").binding().orElseThrow();
            var beforeA = f.fixture().computer().flowSnapshot(); var beforeB = f.caller().flowSnapshot();
            var config = controller.routingState().orElseThrow().configuration();
            var armed = new boolean[1];
            var right = binding.right().access().withPhysicalRoute(() -> {
                if (!armed[0]) return Optional.empty();
                switch (fault) {
                    case "shell" -> f.fixture().world().states.remove(net.minecraft.core.BlockPos.ZERO);
                    case "grant" -> f.fixture().computer().disableEndpoint();
                    case "member" -> f.fixture().world().entities.remove(MEMBER);
                    case "wire" -> assertEquals("configured", controller.configure(wires(config, Map.of()), f.fixture().player()));
                }
                return Optional.empty();
            }, Optional::empty);
            var group = XBusAccess.network(List.of(binding.left().access(), right));
            var admitted = group.beginRound(List.of(binding.left().pin(), binding.right().pin()), true);
            assertTrue(admitted.inspection().rejection().isEmpty());
            assertTrue(admitted.writer().orElseThrow().rejection().isEmpty());
            var beforeHistory = XBusHistorySavedData.get(f.fixture().world()).save(new CompoundTag(), null);
            armed[0] = true; // A successful round admission must not authorize a later transfer.
            var result = group.exchange(binding.left().pin(), binding.right().pin());
            assertEquals(switch (fault) {
                case "grant" -> XBusExchange.Status.CLOSED;
                case "wire" -> XBusExchange.Status.STALE;
                default -> XBusExchange.Status.UNAVAILABLE;
            }, result, fault);
            assertEquals(beforeA, f.fixture().computer().flowSnapshot(), fault);
            assertEquals(beforeB, f.caller().flowSnapshot(), fault);
            assertEquals(beforeHistory, XBusHistorySavedData.get(f.fixture().world()).save(new CompoundTag(), null), fault);
        }
    }
    private static ClusterConfiguration wires(ClusterConfiguration source, Map<String, XBusWire> wires) {
        return new ClusterConfiguration(source.members(), source.ports(), source.connections(), wires, source.signals(), source.publicPins());
    }
}
