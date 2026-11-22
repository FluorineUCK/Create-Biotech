package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BreakpointBusTest {
    @Test void unchangedZombieBudgetReceivesUnknownShapeOnlyOnceThenOffersTextAcrossColdRestore(){
        var h=new BiocomputingConfig(new ModConfigSpec.Builder()).select(new ResidentHardwareIdentity(
            ResourceLocation.withDefaultNamespace("zombie_villager"),ResourceLocation.withDefaultNamespace("none"),1),false).limits();
        assertFalse(h.isa1b());var limits=BreakpointBus.limits(h);
        var latch=new ResidentBreakpoint();latch.select("x0");
        var bus=new BreakpointBus(()->{});assertTrue(bus.open(h,latch),bus.failure());
        var value=new DataValue.RecordValue(Map.of("surprise",new DataValue.IntegerValue(43)));
        var shape=new DataType.RecordType(Map.of("surprise",DataType.Scalar.INTEGER));
        var writer=new ComputerBlockHost(()->{});
        writer.installFlow(new FlowProgram(Map.of("v",shape),List.of(new FlowProgram.Write("send","x0","v"))).withAssembly(),
            new DataValue.RecordValue(Map.of("v",value)),limits,true);
        writer.setPaused(false,limits,true);writer.tick(limits,true);bus.host.tick(limits,true);
        assertEquals(XBusExchange.Status.EXCHANGED,writer.exchange("x0",limits,true,bus.host,"x0",limits,true));
        assertEquals(value,bus.input().orElseThrow());
        var restored=new BreakpointBus(()->{});restored.load(bus.save());assertTrue(restored.open(h,latch),restored.failure());
        var serial=new SerialDevice(()->SerialHardware.NONE,()->true,()->{throw new AssertionError();},()->{});
        assertTrue(latch.receiveBus(restored.input().orElseThrow(),serial,List.of()));
        assertTrue(restored.output(latch,h));assertEquals("xbus_waiting",latch.outcome());
        var pending=new BreakpointBus(()->{});pending.load(restored.save());var after=new ResidentBreakpoint();after.load(latch.save());
        assertTrue(pending.open(h,after),pending.failure());assertFalse(pending.output(after,h));
        var reader=new ComputerBlockHost(()->{});var textType=new DataType.RecordType(Map.of("text",DataType.Scalar.TEXT));
        reader.installFlow(new FlowProgram(Map.of(),List.of(new FlowProgram.Read("read","x0","got",textType))).withAssembly(),
            new DataValue.RecordValue(Map.of()),limits,true);reader.setPaused(false,limits,true);reader.tick(limits,true);
        pending.host.tick(limits,true);
        assertEquals(XBusExchange.Status.EXCHANGED,pending.host.exchange("x0",limits,true,reader,"x0",limits,true));
        assertTrue(pending.output(after,h));assertEquals("xbus_complete",after.outcome());
        assertTrue(((DataValue.RecordValue)reader.flowSnapshot().orElseThrow().state().locals().field("got")).field("text") instanceof DataValue.TextValue v && v.value().contains("43"));
        assertFalse(pending.output(after,h));assertTrue(after.halted());
        pending.rearm(h,after);after.reset();assertTrue(pending.open(h,after));assertTrue(pending.input().isEmpty());
    }
}
