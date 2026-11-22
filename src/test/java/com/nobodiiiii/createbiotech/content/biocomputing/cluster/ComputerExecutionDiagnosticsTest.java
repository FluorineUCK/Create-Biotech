package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterControllerRuntimeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.ComputerExecutionTicks;
import java.util.*;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.junit.jupiter.api.*;

class ComputerExecutionDiagnosticsTest {
    @BeforeAll static void setup() throws Exception { bootstrap(); }

    @Test void actualWorldTickPublishesPausedPeerToComputerAndControllerWithoutRepeatedEventsOrPayload() throws Exception {
        var f = ClusterWorldIndicatorTest.writers(); var a = f.fixture().computer(); var b = f.caller();
        b.installFlow(ClusterWorldIndicatorTest.reader(), new DataValue.RecordValue(Map.of()));
        var before = b.flowSnapshot();
        for (int i = 0; i < 8; i++) { tick(f); f.fixture().world().clock++; }
        assertEquals(before, b.flowSnapshot());
        assertEquals(1, events(a.recentEvents(), "execution_paused", "x0"));
        assertEquals(1, events(f.fixture().controller().recentEvents(), "execution_paused", "data"));
        assertEquals(0, events(a.recentEvents(), "xbus_exchanged", "x0"));
        assertFalse(a.recentEvents().toString().contains("IntegerValue[value=12]"));
        var failed = a.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.FAILED, failed.state().status());
        assertEquals(ComputationException.Code.XBUS_PEER_PAUSED, failed.state().diagnostic().orElseThrow().code());
        b.setPaused(false); tick(f);
        assertEquals(failed, a.flowSnapshot().orElseThrow(), "Resuming a peer cannot retry a failed legacy operation");
        assertEquals(0, events(a.recentEvents(), "xbus_exchanged", "x0"));
        a.reset(); a.setPaused(false); f.fixture().world().clock++; tick(f);
        assertEquals(1, events(a.recentEvents(), "xbus_exchanged", "x0"));
        assertEquals(1, events(b.recentEvents(), "xbus_exchanged", "x0"));
        assertEquals(new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(12))),
            b.flowSnapshot().orElseThrow().state().locals().field("received"));
    }

    @Test void normalWorldTypeRejectionIsVisibleWithoutPayloadAndDoesNotRepeatOrConsumeTheWait() throws Exception {
        var f = ClusterWorldIndicatorTest.writers();
        var writer = f.fixture().computer(); var reader = f.caller();
        reader.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x0", "secret_result",
                new DataType.RecordType(Map.of("value", DataType.Scalar.TEXT))))),
            new DataValue.RecordValue(Map.of()));
        reader.setPaused(false);
        tick(f);
        assertEquals(FlowRuntime.Status.WAITING_READ, reader.flowSnapshot().orElseThrow().state().status());
        assertEquals(FlowRuntime.Status.WAITING_WRITE, writer.flowSnapshot().orElseThrow().state().status());
        assertEquals(1, events(reader.recentEvents(), "execution_type_mismatch", "x0"));
        assertEquals(1, events(f.fixture().controller().recentEvents(), "execution_type_mismatch", "data"));
        var beforeReader = reader.flowSnapshot(); var beforeWriter = writer.flowSnapshot();
        for (int i = 0; i < 8; i++) { f.fixture().world().clock++; tick(f); }
        assertEquals(beforeReader, reader.flowSnapshot()); assertEquals(beforeWriter, writer.flowSnapshot());
        assertEquals(1, events(reader.recentEvents(), "execution_type_mismatch", "x0"));
        assertEquals(1, events(f.fixture().controller().recentEvents(), "execution_type_mismatch", "data"));
        assertFalse(reader.recentEvents().toString().contains("secret_result"));
        assertFalse(f.fixture().controller().recentEvents().toString().contains("IntegerValue[value=12]"));
        assertTrue(f.fixture().controller().routingState().orElseThrow().mailbox().queued().isEmpty());
        reader.reset(); reader.setPaused(false); f.fixture().world().clock++; tick(f);
        assertEquals(2, events(reader.recentEvents(), "execution_type_mismatch", "x0"), "A new operation is not hidden by the old error's deduplication");
    }

    @Test void twoOrdinaryWaitingWritersDoNotManufactureExecutionErrorsOrSuccess() throws Exception {
        var f = ClusterWorldIndicatorTest.writers();
        for (int i = 0; i < 4; i++) { tick(f); f.fixture().world().clock++; }
        for (var computer : List.of(f.fixture().computer(), f.caller())) {
            var events = ((DataValue.ListValue)computer.recentEvents().field("entries")).values();
            assertTrue(events.stream().map(DataValue.RecordValue.class::cast)
                .noneMatch(e -> ((DataValue.TextValue)e.field("code")).value().startsWith("execution_")));
            assertEquals(FlowRuntime.Status.WAITING_WRITE, computer.flowSnapshot().orElseThrow().state().status());
            assertTrue(computer.flowSnapshot().orElseThrow().receipts().isEmpty());
        }
    }

    @Test void missingOriginalReceiptsShowRecoveryWaitRatherThanPretendingAHealthyCableCanConsume() throws Exception {
        var f = ClusterWorldIndicatorTest.writers(); var writer = f.fixture().computer(); var reader = f.caller();
        reader.installFlow(ClusterWorldIndicatorTest.reader(), new DataValue.RecordValue(Map.of()));
        writer.step(); reader.step(); writer.setPaused(false); reader.setPaused(false);
        var oldWriter = new net.minecraft.nbt.CompoundTag(); var oldReader = new net.minecraft.nbt.CompoundTag();
        writer.write(oldWriter, net.minecraft.core.RegistryAccess.EMPTY, false);
        reader.write(oldReader, net.minecraft.core.RegistryAccess.EMPTY, false);
        tick(f);
        assertEquals(FlowRuntime.Status.COMPLETE, reader.flowSnapshot().orElseThrow().state().status());
        var completedWriter = new net.minecraft.nbt.CompoundTag();
        writer.write(completedWriter, net.minecraft.core.RegistryAccess.EMPTY, false);
        writer.read(oldWriter, net.minecraft.core.RegistryAccess.EMPTY, false);
        reader.read(oldReader, net.minecraft.core.RegistryAccess.EMPTY, false);
        writer.setPaused(false); reader.setPaused(false);
        var history = com.nobodiiiii.createbiotech.content.biocomputing.network.XBusHistorySavedData.get(f.fixture().world());
        var beforeHistory = history.save(new net.minecraft.nbt.CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY);
        var beforeReader = reader.flowSnapshot(); var beforeWriter = writer.flowSnapshot();
        for (int i = 0; i < 4; i++) { f.fixture().world().clock++; tick(f); }
        assertEquals(beforeReader, reader.flowSnapshot()); assertEquals(beforeWriter, writer.flowSnapshot());
        assertEquals(beforeHistory, history.save(new net.minecraft.nbt.CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY));
        assertEquals(1, events(reader.recentEvents(), "execution_recovery_required", "x0"));
        assertEquals(1, events(writer.recentEvents(), "execution_recovery_required", "x0"));
        writer.setPaused(true); var pausedWriter = writer.flowSnapshot();
        f.fixture().world().clock++; tick(f);
        assertEquals(pausedWriter, writer.flowSnapshot()); assertEquals(beforeReader, reader.flowSnapshot());
        assertEquals(0, events(reader.recentEvents(), "execution_paused", "x0"), "Pause must not mask missing consumption evidence");
        assertEquals(1, events(reader.recentEvents(), "execution_recovery_required", "x0"));
        assertEquals(beforeHistory, history.save(new net.minecraft.nbt.CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY));
        writer.read(completedWriter, net.minecraft.core.RegistryAccess.EMPTY, false);
        f.fixture().world().clock++; tick(f);
        assertEquals(FlowRuntime.Status.COMPLETE, reader.flowSnapshot().orElseThrow().state().status());
        assertEquals(1, events(reader.recentEvents(), "xbus_exchanged", "x0"));
        assertEquals(1, reader.flowSnapshot().orElseThrow().receipts().size());
    }

    static void tick(InternalFixture f) {
        f.fixture().computer().tick(); f.caller().tick();
        ComputerExecutionTicks.endTick(new LevelTickEvent.Post(() -> true, f.fixture().world()));
    }
    static long events(DataValue.RecordValue log, String code, String subject) {
        return ((DataValue.ListValue)log.field("entries")).values().stream().map(DataValue.RecordValue.class::cast)
            .filter(e -> e.field("code").equals(new DataValue.TextValue(code)) && e.field("subject").equals(new DataValue.TextValue(subject))).count();
    }
}
