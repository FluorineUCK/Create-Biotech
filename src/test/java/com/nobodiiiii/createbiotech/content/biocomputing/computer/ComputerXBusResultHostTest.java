package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFlowHostTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class ComputerXBusResultHostTest {
    @Test void partialSaveRepairWinsOverPausedFailureAndReconstructsOnlyTheLocalResultWrapper() {
        var writer = writerHost(); var reader = readerHost(() -> {});
        var oldReader = reader.write();
        assertEquals(XBusExchange.Status.EXCHANGED, access(writer,"x0").exchange(access(reader,"x1")));
        var completedWriter = writer.flowSnapshot().orElseThrow();
        reader = restore(oldReader);
        writer.setPaused(true, LIMITS, true);
        var network = XBusAccess.network(List.of(access(writer,"x0"),access(reader,"x1")));
        var before = reader.write();
        assertEquals(XBusExchange.Status.RECOVERY_REQUIRED, network.rejectPaused(pin(reader,"x1")));
        assertEquals(before, reader.write());
        writer.setPaused(false, LIMITS, true);
        assertEquals(XBusExchange.Status.EXCHANGED, access(writer,"x0").exchange(access(reader,"x1")));
        assertEquals(completedWriter, writer.flowSnapshot().orElseThrow(), "Recovery must not execute the completed writer again");
        var saved = reader.flowSnapshot().orElseThrow();
        assertEquals(1, saved.receipts().size());
        assertEquals(INPUTS.field("packet"), saved.receipts().getFirst().payload(), "Receipt stores the original payload, not an operation wrapper");
        assertEquals(INPUTS.field("packet"), ((DataValue.RecordValue)saved.state().locals().field("result")).field("value"));
        assertEquals(saved, restore(reader.write()).flowSnapshot().orElseThrow());
    }
    @Test void dirtyCallbackFailureCannotErasePublishedResultOrPretendItWasATransfer() {
        var fail = new AtomicBoolean(); var changes = new AtomicInteger();
        var reader = readerHost(() -> { if (fail.get()) { changes.incrementAndGet(); throw new IllegalStateException("after publication"); } });
        var writer = writerHost(); writer.setPaused(true, LIMITS, true); var beforeWriter = writer.write();
        var network = XBusAccess.network(List.of(access(writer,"x0"),access(reader,"x1")));
        fail.set(true);
        assertEquals("after publication", assertThrows(IllegalStateException.class, () -> network.rejectPaused(pin(reader,"x1"))).getMessage());
        var saved = reader.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.COMPLETE, saved.state().status());
        assertEquals(new DataValue.BooleanValue(false), ((DataValue.RecordValue)saved.state().locals().field("result")).field("ok"));
        assertTrue(saved.receipts().isEmpty()); assertEquals(beforeWriter, writer.write());
        assertEquals(saved, restore(reader.write()).flowSnapshot().orElseThrow());
        assertNotEquals(XBusExchange.Status.PAUSED, network.rejectPaused(pin(reader,"x1")));
        assertEquals(1, changes.get());
    }
    private static ComputerBlockHost writerHost() {
        return started(new ComputerBlockHost(() -> {}), new FlowProgram(Map.of("packet",PACKET),
            List.of(new FlowProgram.Write("send","x0","packet",Optional.of("sent")))), INPUTS);
    }
    private static ComputerBlockHost readerHost(Runnable changed) {
        return started(new ComputerBlockHost(changed), new FlowProgram(Map.of(),List.of(new FlowProgram.Read("read","x1","result",PACKET,true))),new DataValue.RecordValue(Map.of()));
    }
    private static ComputerBlockHost started(ComputerBlockHost host, FlowProgram program, DataValue.RecordValue inputs) {
        host.installFlow(program, inputs, LIMITS, true); host.setPaused(false,LIMITS,true); host.tick(LIMITS,true); return host;
    }
    private static ComputerBlockHost restore(net.minecraft.nbt.Tag raw) {
        var host = new ComputerBlockHost(() -> {}); host.read(raw); host.tick(LIMITS,true); return host;
    }
    private static XBusAccess access(ComputerBlockHost host, String name) {
        return new XBusAccess(host, XBusPortState.of(host.flowSnapshot().orElseThrow(),LIMITS.pins(),name).orElseThrow(), () -> LIMITS, Optional::empty);
    }
    private static XBusNetwork.Pin pin(ComputerBlockHost host, String name) {
        return new XBusNetwork.Pin(host.flowSnapshot().orElseThrow().state().computer(),name);
    }
}
