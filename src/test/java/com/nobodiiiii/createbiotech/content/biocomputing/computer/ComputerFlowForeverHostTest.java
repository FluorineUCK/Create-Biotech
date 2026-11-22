package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFlowHostTest.LIMITS;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerXBusExchangeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class ComputerFlowForeverHostTest {
    static final DataType.RecordType PACKET = new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER));
    @Test void realLoopExchangesThreeDifferentRepliesAndRecoversEitherOlderHostWithoutReset() {
        for (boolean advancedWriter : List.of(false, true)) {
            var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
            writer.installFlow(caller(), new DataValue.RecordValue(Map.of("out", packet(12))), LIMITS, true);
            reader.installFlow(service(), EMPTY, LIMITS, true);
            assertEquals(5, ((CompoundTag)writer.write()).getInt(FlowBlockNbtCodec.MARKER), "A not-yet-entered loop still needs the new owner version");
            writer.setPaused(false, LIMITS, true); reader.setPaused(false, LIMITS, true);
            var firstFlow = writer.flowSnapshot().orElseThrow().state().flow();
            for (int round = 0; round < 3; round++) {
                waiting(writer, FlowRuntime.Status.WAITING_WRITE); waiting(reader, FlowRuntime.Status.WAITING_READ);
                var beforeWriter = writer.write(); var beforeReader = reader.write();
                assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
                var a = cold(advancedWriter ? writer.write() : beforeWriter);
                var b = cold(advancedWriter ? beforeReader : reader.write());
                assertEquals(XBusExchange.Status.EXCHANGED, exchange(a, b));
                assertEquals(writer.flowSnapshot(), a.flowSnapshot()); assertEquals(reader.flowSnapshot(), b.flowSnapshot());
                writer = a; reader = b;
                waiting(writer, FlowRuntime.Status.WAITING_READ); waiting(reader, FlowRuntime.Status.WAITING_WRITE);
                assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
                assertEquals(packet(19 + round * 7), writer.flowSnapshot().orElseThrow().state().locals().field("reply"));
                assertEquals(firstFlow, writer.flowSnapshot().orElseThrow().state().flow());
                assertEquals((round + 1) * 2, writer.flowSnapshot().orElseThrow().receipts().size());
            }
            assertTrue(writer.flowSnapshot().orElseThrow().state().operation() > writer.flowSnapshot().orElseThrow().state().cursor());
        }
    }
    static void waiting(ComputerBlockHost host, FlowRuntime.Status expected) {
        for (int i = 0; host.flowSnapshot().orElseThrow().state().status() != expected; i++) {
            assertTrue(i < 100, () -> host.flowSnapshot().toString()); host.tick(LIMITS, true);
        }
    }
    static ComputerBlockHost cold(net.minecraft.nbt.Tag raw) {
        var host = new ComputerBlockHost(() -> {}); host.read(raw);
        assertTrue(host.synchronize(LIMITS, true), () -> host.diagnostic().toString()); return host;
    }
    static DataValue.RecordValue packet(long n) { return new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(n))); }
    static FlowProgram caller() {
        var graph = new ComputationGraph(Map.of("reply", PACKET), Map.of("reply", new ComputationGraph.Input("reply")), Map.of("out", "reply"));
        return new FlowProgram(Map.of("out", PACKET), List.of(new FlowProgram.Forever("loop"), new FlowProgram.Write("send", "x0", "out"),
            new FlowProgram.Read("reply", "x0", "reply", PACKET), new FlowProgram.Evaluate("carry", "carry"), new FlowProgram.EndForever("end")), archive("carry", graph));
    }
    static FlowProgram service() {
        var graph = new ComputationGraph(Map.of("input", PACKET), Map.of("input", new ComputationGraph.Input("input"),
            "n", new ComputationGraph.Field("input", "value"), "seven", new ComputationGraph.Constant(new DataValue.IntegerValue(7)),
            "sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "n", "seven"),
            "packet", new ComputationGraph.MakeRecord(Map.of("value", "sum"))), Map.of("out", "packet"));
        return new FlowProgram(Map.of(), List.of(new FlowProgram.Forever("loop"), new FlowProgram.Read("read", "x1", "input", PACKET),
            new FlowProgram.Evaluate("calculate", "calculate"), new FlowProgram.Write("send", "x1", "out"), new FlowProgram.EndForever("end")), archive("calculate", graph));
    }
    static ProgramArchive archive(String name, ComputationGraph graph) {
        return new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(),
            Map.of(name, new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
    }
}
