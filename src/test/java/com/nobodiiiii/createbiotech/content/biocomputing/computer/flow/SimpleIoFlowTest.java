package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SimpleIoFlowTest {
    @Test void redstoneRangeClampsPhysicalOutputWithoutChangingTheProgramValue() {
        for (long value : new long[]{Long.MIN_VALUE, -1, 0, 1, 14, 15, 16, 100, Long.MAX_VALUE}) {
            var input = initial(value);
            var flow = runtime(writer(), input);
            flow.advanceRound(Map.of());
            assertEquals(Map.of("p0", (int)Math.clamp(value, 0, 15)), flow.checkpoint().signals().outputs());
            assertEquals(input, flow.checkpoint().locals(), "Electrical limiting must not rewrite program data");
        }
        assertThrows(IllegalArgumentException.class, () -> new SimpleIoState(Map.of("p0", 16)));
        assertThrows(IllegalArgumentException.class, () -> SimpleIoNetwork.Port.output(16));
        var flow = runtime(writer(), initial(1));
        var before = flow.checkpoint();
        assertThrows(IllegalArgumentException.class, () -> flow.advanceRound(Map.of("p0", 16)));
        assertEquals(before, flow.checkpoint(), "An invalid hardware sample cannot execute an instruction");
    }
    private static final ResourceLimits LIMITS = new ResourceLimits(100, 100000, 100);
    private static final ArchiveLimits ARCHIVE = new ArchiveLimits(100000, 1000000, 1000000);
    private static DataValue.RecordValue obj(String... pairs) {
        var fields = new LinkedHashMap<String, DataValue>();
        for (int i=0; i<pairs.length; i+=2) fields.put(pairs[i], new DataValue.TextValue(pairs[i+1]));
        return new DataValue.RecordValue(fields);
    }
    private static FlowProgram writer() {
        var base = new FlowProgram(Map.of("level", DataType.Scalar.INTEGER, "ticks", DataType.Scalar.INTEGER), List.of());
        var fields = new LinkedHashMap<>(((DataValue.RecordValue)FlowProgramCodec.encode(base)).fields());
        fields.put("steps", new DataValue.ListValue(List.of(obj("kind", "signal_write", "source", "write", "port", "p0", "local", "level"),
            obj("kind", "sleep", "source", "sleep", "ticks", "ticks"))));
        return FlowProgramCodec.decode(new DataValue.RecordValue(fields));
    }
    @Test void aSignalWriteDoesNotWaitForAReaderAndSleepRetainsTheSavedOutput() {
        var program = writer();
        var initial = new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(8), "ticks", new DataValue.IntegerValue(3)));
        var flow = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, initial, LIMITS);
        assertEquals(1, flow.advance(1));
        assertEquals(FlowRuntime.Status.READY, flow.checkpoint().status());
        assertEquals(1, flow.checkpoint().cursor());
        flow.advance(1);
        assertEquals(FlowRuntime.Status.SLEEPING, flow.checkpoint().status());
        var encoded = FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(program, flow.checkpoint()), ARCHIVE);
        var value = (DataValue.RecordValue)ValueNbtCodec.decode(encoded, ARCHIVE);
        assertEquals(new DataValue.IntegerValue(20), value.field("version"));
        var outputs = (DataValue.RecordValue)((DataValue.RecordValue)value.field("state")).field("signal_outputs");
        assertEquals(new DataValue.IntegerValue(8), outputs.field("p0"));
        var saved = FlowNbtCodec.decode(encoded, ARCHIVE);
        var restored = FlowRuntime.restore(saved.program(), saved.state(), LIMITS);
        assertEquals(flow.checkpoint(), restored.checkpoint());
    }

    private static DataValue.RecordValue initial(long level) {
        return new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(level), "ticks", new DataValue.IntegerValue(3)));
    }
    private static FlowRuntime runtime(FlowProgram p, DataValue.RecordValue initial) {
        return new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), p, initial, LIMITS);
    }
    @Test void integerOutputsClampButInputModesAndUnavailableSamplesStayDistinct() {
        for (long n : new long[]{Long.MIN_VALUE, -1, 0, 1, 14, 15, 16, Long.MAX_VALUE}) {
            var f = runtime(writer(), initial(n)); f.advanceRound(Map.of());
            assertEquals(Map.of("p0", (int)Math.clamp(n, 0, 15)), f.checkpoint().signals().outputs());
        }
        var p = new FlowProgram(writer().inputs(), List.of(new FlowProgram.SignalWrite("out", "p0", "level"),
            new FlowProgram.SignalRead("in", "p0", "received")));
        var f = runtime(p, initial(9)); f.advanceRound(Map.of()); f.advanceRound(Map.of("p0", 3));
        assertEquals(FlowRuntime.Status.COMPLETE, f.checkpoint().status());
        assertEquals(new DataValue.IntegerValue(3), f.checkpoint().locals().field("received"));
        assertEquals(SimpleIoState.EMPTY, f.checkpoint().signals());
        var unavailable = runtime(p, initial(9)); unavailable.advanceRound(Map.of()); unavailable.advanceRound(Map.of());
        assertEquals(FlowRuntime.Status.FAILED, unavailable.checkpoint().status());
        assertEquals(ComputationException.Code.MISSING_REFERENCE, unavailable.checkpoint().diagnostic().orElseThrow().code());
        assertEquals(Map.of("p0", 9), unavailable.checkpoint().signals().outputs(), "An unsuccessful read does not partially clear output");
        assertThrows(ComputationException.class, () -> new FlowProgram(Map.of("level", DataType.Scalar.TEXT),
            List.of(new FlowProgram.SignalWrite("out", "p0", "level"))).validate(LIMITS));
    }
    @Test void roundsNeverTickSleepAndPausedOrUnloadedMachinesRetainTheirDrive() {
        var f = runtime(writer(), initial(6)); f.advanceRound(Map.of()); f.advanceRound(Map.of());
        var sleeping = f.checkpoint();
        for (int i=0;i<100;i++) assertEquals(0, f.advanceRound(Map.of()));
        assertEquals(sleeping, f.checkpoint()); f.setPaused(true); f.elapseTick();
        assertEquals(3, f.checkpoint().sleepTicks()); assertEquals(sleeping.signals(), f.checkpoint().signals());
        f.setPaused(false); f.unload(); f.elapseTick(); assertEquals(3, f.checkpoint().sleepTicks());
        f.load(); f.elapseTick(); assertEquals(2, f.checkpoint().sleepTicks());
        assertEquals(sleeping.signals(), f.checkpoint().signals());
        f.elapseTick(); f.elapseTick(); assertEquals(FlowRuntime.Status.COMPLETE, f.checkpoint().status());
        assertEquals(sleeping.signals(), f.checkpoint().signals());
        assertEquals(SimpleIoState.EMPTY, runtime(writer(), initial(6)).checkpoint().signals(), "Fresh execution never inherits old drive");
    }
    @Test void signalMemoryIsChargedAndForgedOutputsCannotPrecedeTheirWrite() {
        var f = runtime(writer(), initial(12)); var before = f.checkpoint();
        var usage = writer().expressions().compile(LIMITS).usage();
        long memory = before.controlUnits() + usage.constantUnits() + 2 * usage.initialMemoryUnits();
        var tight = new FlowRuntime(before.computer(), before.flow(), writer(), initial(12), new ResourceLimits(100, memory, 100));
        tight.advanceRound(Map.of());
        assertEquals(FlowRuntime.Status.FAILED, tight.checkpoint().status());
        assertEquals(ComputationException.Code.MEMORY_LIMIT, tight.checkpoint().diagnostic().orElseThrow().code());
        assertEquals(SimpleIoState.EMPTY, tight.checkpoint().signals());
        f.advanceRound(Map.of()); assertTrue(f.checkpoint().controlUnits() > before.controlUnits());
        var forged = new FlowRuntime.Checkpoint(before.computer(), before.flow(), 0, before.status(), before.locals(), 0, false,
            Optional.empty(), Optional.empty(), 0, new SimpleIoState(Map.of("p0", 15)));
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class,
            () -> FlowRuntime.restore(writer(), forged, LIMITS)).code());
        assertThrows(IllegalArgumentException.class, () -> new SimpleIoState(Map.of("p0", 16)));
        assertThrows(IllegalArgumentException.class, () -> new SimpleIoState(Map.of("p0", -1)));
        assertThrows(IllegalArgumentException.class, () -> f.advanceRound(Map.of("p0", 16)));
    }
    @Test void mixedXbusAndPureGraphContinuationsDoNotDropSignalDrive() {
        var calc = XBusFlowTest.calculator();
        var steps = new ArrayList<FlowProgram.Step>(); steps.add(new FlowProgram.SignalWrite("drive", "p0", "level")); steps.addAll(calc.steps());
        var f = runtime(new FlowProgram(Map.of("level", DataType.Scalar.INTEGER), steps, calc.expressions()),
            new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(5))));
        var a = XBusFlowTest.writer(); a.advance(1); f.advanceRound(Map.of()); f.advanceRound(Map.of());
        assertEquals(XBusExchange.Status.EXCHANGED, XBusFlowTest.exchange(a, f).commit());
        int n = 0;
        while(f.checkpoint().status() != FlowRuntime.Status.WAITING_WRITE) {
            assertTrue(n++ < 50); f.advanceRound(Map.of());
            assertEquals(Map.of("p0", 5), f.checkpoint().signals().outputs());
            f = FlowArchiveTest.roundTrip(f);
        }
        var b = runtime(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("in", "x0", "value", XBusFlowTest.PACKET))), XBusFlowTest.EMPTY);
        b.advance(1); assertEquals(XBusExchange.Status.EXCHANGED, XBusExchange.prepare(f, "x1", b, "x0").commit());
        assertEquals(Map.of("p0", 5), f.checkpoint().signals().outputs());
    }


    private static FlowRuntime.Checkpoint withSignals(FlowRuntime.Checkpoint s, SimpleIoState signals) {
        return new FlowRuntime.Checkpoint(s.computer(),s.flow(),s.cursor(),s.status(),s.locals(),s.work(),s.paused(),s.expression(),s.diagnostic(),s.sleepTicks(),signals);
    }
    @Test void restoreRejectsAResurrectedDriveAfterReadAndAMissingDriveAfterMandatoryWrite() {
        var p=new FlowProgram(writer().inputs(),List.of(new FlowProgram.SignalWrite("out","p0","level"),
            new FlowProgram.SignalRead("in","p0","received"),new FlowProgram.Sleep("sleep","ticks")));
        var f=runtime(p,initial(6)); f.advanceRound(Map.of());
        var written=f.checkpoint(); f.advanceRound(Map.of("p0",0));
        assertEquals(SimpleIoState.EMPTY,f.checkpoint().signals());
        assertThrows(ComputationException.class,()->FlowRuntime.restore(p,withSignals(f.checkpoint(),new SimpleIoState(Map.of("p0",15))),LIMITS));
        assertThrows(ComputationException.class,()->FlowRuntime.restore(p,withSignals(written,SimpleIoState.EMPTY),LIMITS));
    }
    @Test void branchJoinAllowsEitherClearOrHeldDriveButTwoWritingArmsRequireDrive() {
        for(boolean condition:List.of(false,true)) {
            for(boolean bothWrite:List.of(false,true)) {
                var steps=List.<FlowProgram.Step>of(new FlowProgram.SignalWrite("before","p0","level"),new FlowProgram.If("if","condition"),
                    bothWrite ? new FlowProgram.SignalWrite("then","p0","level") : new FlowProgram.SignalRead("then","p0","level"),
                    new FlowProgram.Otherwise("else"),new FlowProgram.SignalWrite("otherwise","p0","level"),new FlowProgram.EndIf("end"));
                var values=new LinkedHashMap<>(initial(6).fields());values.put("condition",new DataValue.BooleanValue(condition));
                var types=new LinkedHashMap<>(writer().inputs());types.put("condition",DataType.Scalar.BOOLEAN);
                var p=new FlowProgram(types,steps);var f=runtime(p,new DataValue.RecordValue(values));
                int n=0;
                while(f.checkpoint().status()!=FlowRuntime.Status.COMPLETE) {
                    assertTrue(n++<10);f.advanceRound(Map.of("p0",12));f=FlowArchiveTest.roundTrip(f);
                }
                assertEquals(bothWrite || !condition ? Map.of("p0",6) : Map.of(),f.checkpoint().signals().outputs());
                if(bothWrite) {
                    var end=f.checkpoint();
                    assertThrows(ComputationException.class,()->FlowRuntime.restore(p,withSignals(end,SimpleIoState.EMPTY),LIMITS));
                }
            }
        }
    }
}
