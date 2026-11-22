package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SimpleIoFlowHostBoundaryTest {
    @Test void standaloneHardwareWritesAndReadsItsUnconnectedSignalWithoutReadingItsOwnDrive() {
        var limits = new ComputerHostLimits(new ResourceLimits(100,100000,100),new ArchiveLimits(100000,1000000,1000000));
        var program = new FlowProgram(Map.of("level", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.SignalWrite("out", "p0", "level"), new FlowProgram.SignalRead("in", "p0", "sample")));
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program, new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(4))), limits, true);
        host.step(limits, true);
        assertEquals(Map.of("p0", 4), host.flowSnapshot().orElseThrow().state().signals().outputs());
        host.setPaused(false, limits, true); host.tick(limits, true);
        var result = host.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.COMPLETE, result.status());
        assertEquals(new DataValue.IntegerValue(0), result.locals().field("sample"));
        assertEquals(SimpleIoState.EMPTY, result.signals());
    }
    @Test void hardwareWithoutSignalPinsRetainsTheSaveButCannotExecuteItAndExplicitResetClearsDrive() {
        var limits = new ComputerHostLimits(new ResourceLimits(100,100000,100),new ArchiveLimits(100000,1000000,1000000),
            com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT,
            new com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins(List.of("x0", "x1")));
        var p = new FlowProgram(Map.of("level",DataType.Scalar.INTEGER),List.of(new FlowProgram.SignalWrite("out","p0","level")));
        var input = new DataValue.RecordValue(Map.of("level",new DataValue.IntegerValue(4)));
        var f = new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),p,input,limits.hardware()); f.advanceRound(Map.of());
        var save = FlowBlockNbtCodec.encode(new FlowBlockHost.Saved(p,input,f.checkpoint()),limits.archive());
        var host = new ComputerBlockHost(()->{}); host.read(save); host.tick(limits,true);
        assertFalse(host.loaded()); assertEquals(ComputerBlockHost.Code.HARDWARE_MISMATCH,host.diagnostic().orElseThrow().code());
        assertEquals(save,host.write()); assertEquals(Map.of("p0",4),host.flowSnapshot().orElseThrow().state().signals().outputs());
        host.reset(limits,true);
        assertEquals(SimpleIoState.EMPTY,host.flowSnapshot().orElseThrow().state().signals());
        assertTrue(host.flowSnapshot().orElseThrow().state().paused());
        assertFalse(host.loaded());
        assertThrows(FlowPins.Unavailable.class,()->new ComputerBlockHost(()->{}).installFlow(p,input,limits,true));
    }
}
