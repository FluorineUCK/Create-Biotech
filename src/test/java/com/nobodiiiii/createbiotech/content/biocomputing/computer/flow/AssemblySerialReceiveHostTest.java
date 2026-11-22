package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import java.util.*;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class AssemblySerialReceiveHostTest {
    @Test void missingMasterDuringColdReceiveDoesNotConsumeTheFrameOrGrantReset() {
        var ordinary = SerialFlowHostTest.LIMITS;
        var master = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(
            ordinary.hardware(), ordinary.archive(), ordinary.mailbox(), ordinary.pins(), ordinary.serial(), ordinary.taskSlots(), false, true);
        var key = new ModuleDefinition.Key("dynamic", 1);
        var program = new FlowProgram(Map.of(), List.of(new FlowProgram.SerialReceive("receive", "s0", "got", true),
            new FlowProgram.Procedure("definition", key, Map.of(), Map.of(), Map.of(), FlowBlockRules.NONE,
                Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource("REG payload ANY"))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var empty = new DataValue.RecordValue(Map.of());
        var device = SerialFlowHostTest.device(); device.configure("s0", Direction.EAST, true);
        device.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(SerialFlowHostTest.RECORD,
            new SerialRecordCodec.Limits(4096, 1048576, SerialFlowHostTest.ARCHIVE)));
        var owner = FlowBlockHost.installed(() -> {}, UUID.randomUUID(), program, empty, master, FlowControl.INITIAL, device);
        owner.step(Map.of());
        assertTrue(owner.snapshot().orElseThrow().state().assembly().orElseThrow().serial().orElseThrow().receiving());
        var ownerBytes = owner.write(); var deviceBytes = device.write();
        var dirty = new java.util.concurrent.atomic.AtomicInteger();
        var guarded = new SerialDevice(() -> { throw new AssertionError("No receiver access without master capability"); },
            () -> true, () -> { throw new AssertionError("No codec access without master capability"); }, dirty::incrementAndGet);
        guarded.read(deviceBytes);
        var cold = new FlowBlockHost(dirty::incrementAndGet, ownerBytes, guarded);
        assertFalse(cold.synchronize(ordinary, true)); assertFalse(cold.loaded()); assertFalse(cold.hardwareMismatch());
        assertEquals(0, cold.maintainXBusReceipts(new Object(), r -> { fail("No proof access without master capability"); return true; }));
        assertTrue(cold.snapshot().isEmpty()); assertEquals(program, cold.source().orElseThrow().program());
        assertEquals(ownerBytes, cold.write()); assertEquals(deviceBytes, guarded.write()); assertEquals(0, dirty.get());
    }

    @Test void missingParserDuringColdReceiveExposesOnlySourceWithoutTouchingTheDeviceOrGrantingReset() {
        var base = SerialFlowHostTest.LIMITS;
        var ordinary = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(
            base.hardware(), base.archive(), base.mailbox(), base.pins(), base.serial(), base.taskSlots(), false, false);
        var parser = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(
            ordinary.hardware(), ordinary.archive(), ordinary.mailbox(), ordinary.pins(), ordinary.serial(), ordinary.taskSlots(), true);
        var graph = new ComputationGraph(Map.of("value", DataType.Scalar.ANY),
            Map.of("input", new ComputationGraph.Input("value"), "eq", new ComputationGraph.ValueEquals("input", "input")),
            Map.of("out", "eq"));
        var archive = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(
            new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(),
                Map.of("compare", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        var program = new FlowProgram(Map.of(), List.of(new FlowProgram.SerialReceive("receive", "s0", "got", false),
            new FlowProgram.Evaluate("compare", "compare", new FlowCallBindings(Map.of("value", "got"), Map.of("out", "same")))), archive).withAssembly();
        var empty = new DataValue.RecordValue(Map.of());
        var device = SerialFlowHostTest.device(); device.configure("s0", Direction.EAST, true);
        device.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(SerialFlowHostTest.RECORD,
            new SerialRecordCodec.Limits(4096, 1048576, SerialFlowHostTest.ARCHIVE)));
        var owner = FlowBlockHost.installed(() -> {}, UUID.randomUUID(), program, empty, parser, FlowControl.INITIAL, device);
        owner.step(Map.of());
        assertTrue(owner.snapshot().orElseThrow().state().assembly().orElseThrow().serial().orElseThrow().receiving());
        var ownerBytes = owner.write(); var deviceBytes = device.write();
        var dirty = new java.util.concurrent.atomic.AtomicInteger();
        var guarded = new SerialDevice(() -> { throw new AssertionError("Must not synchronize device while Parser is missing"); },
            () -> true, () -> { throw new AssertionError("Must not inspect codec while Parser is missing"); }, dirty::incrementAndGet);
        guarded.read(deviceBytes);
        var cold = new FlowBlockHost(dirty::incrementAndGet, ownerBytes, guarded);
        assertFalse(cold.synchronize(ordinary, true)); assertFalse(cold.loaded()); assertFalse(cold.hardwareMismatch());
        assertTrue(cold.snapshot().isEmpty(), "Source visibility is not validated runtime/maintenance authority");
        assertEquals(0, cold.maintainXBusReceipts(new Object(), r -> { fail("No proof access without parser capability"); return true; }));
        assertEquals(program, cold.source().orElseThrow().program());
        assertEquals(empty, cold.source().orElseThrow().initialInputs());
        assertEquals(ownerBytes, cold.write()); assertEquals(deviceBytes, guarded.write()); assertEquals(0, dirty.get());
    }

    static FlowBlockHost receiver(SerialDevice device,boolean nativeNbt) {
        return FlowBlockHost.installed(()->{},UUID.randomUUID(),new FlowProgram(Map.of(),List.of(new FlowProgram.SerialReceive("receive","s0","got",nativeNbt))).withAssembly(),
            new DataValue.RecordValue(Map.of()),SerialFlowHostTest.LIMITS,FlowControl.INITIAL,device);
    }
    static void finish(FlowBlockHost owner){owner.setPaused(false);for(int i=0;owner.snapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;i++){assertTrue(i<20000,()->owner.diagnostic().toString());assertEquals(1,owner.advanceRound(Map.of()),()->owner.diagnostic().toString());}}

    @Test void restoreUsesReceiveCodecEnvelopeRatherThanSmallerTransmitFrameEnvelope() {
        var device=new SerialDevice(()->SerialFlowHostTest.SERIAL,()->true,()->new SerialRecordCodec.Limits(65536,1048576,SerialFlowHostTest.ARCHIVE),()->{});device.configure("s0",Direction.EAST,true);
        device.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(SerialFlowHostTest.RECORD,new SerialRecordCodec.Limits(4096,1048576,SerialFlowHostTest.ARCHIVE)));
        var owner=receiver(device,false);owner.step(Map.of());var raw=owner.write();device.read(device.write());var restored=new FlowBlockHost(()->{},raw,device);
        assertTrue(restored.synchronize(SerialFlowHostTest.LIMITS,true),restored.diagnostic().toString());assertEquals(owner.snapshot(),restored.snapshot());finish(restored);assertEquals(0,device.status("s0").receive().frames());
    }

    @Test void changingHardwareWithTheSameCodecAndStillFittingFrameInvalidatesTheBoundReceive() {
        var hardware=new java.util.concurrent.atomic.AtomicReference<>(SerialFlowHostTest.SERIAL);
        var device=new SerialDevice(hardware::get,()->true,()->new SerialRecordCodec.Limits(4096,1048576,SerialFlowHostTest.ARCHIVE),()->{});
        device.configure("s0",Direction.EAST,true);
        device.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(SerialFlowHostTest.RECORD,new SerialRecordCodec.Limits(4096,1048576,SerialFlowHostTest.ARCHIVE)));
        var owner=receiver(device,false);owner.step(Map.of());
        hardware.set(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware(Map.of("s0",new com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware.Port(64,4096,2,8192))));
        var raw=owner.write();var work=owner.snapshot().orElseThrow().state().work();device.read(device.write());owner=new FlowBlockHost(()->{},raw,device);assertTrue(owner.synchronize(SerialFlowHostTest.LIMITS,true),owner.diagnostic().toString());assertEquals(work,owner.snapshot().orElseThrow().state().work());assertNull(owner.snapshot().orElseThrow().state().locals().fields().get("got"));
        finish(owner);
        assertEquals(SerialResult.receive("hardware_changed",null),owner.snapshot().orElseThrow().state().locals().field("got"));
        assertEquals(work+1,owner.snapshot().orElseThrow().state().work(),"Failure publication needs one paid step");
        assertEquals(1,device.status("s0").receive().frames());
    }

    @Test void everyPaidCheckpointRestoresAgainstLazyDeviceAndAfterClaimDoesNotRepeat() {
        var device=SerialFlowHostTest.device();device.configure("s0",Direction.WEST,true);
        var packet=new DataValue.RecordValue(Map.of("a",new DataValue.TextValue("保存-😀"),"b",new DataValue.ListValue(List.of(new DataValue.IntegerValue(3),new DataValue.DecimalValue(-0.0)))));
        var bytes=SerialRecordCodec.encode(packet,new SerialRecordCodec.Limits(4096,1048576,SerialFlowHostTest.ARCHIVE));
        device.attachments().getFirst().receiver().offer(bytes);device.attachments().getFirst().receiver().offer(bytes);
        var owner=receiver(device,false);long peak=0;int steps=0;
        while(owner.snapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE) {
            assertTrue(steps++<10000);owner.step(Map.of());var state=owner.snapshot().orElseThrow();
            if(state.state().assembly().orElseThrow().serial().isPresent())peak=Math.max(peak,state.state().assembly().orElseThrow().serial().orElseThrow().ownedUnits());
            var hardware=device.write();var saved=owner.write();device.read(hardware);
            owner=new FlowBlockHost(()->{},saved,device);assertTrue(owner.synchronize(SerialFlowHostTest.LIMITS,true),"restore "+steps+": "+device.diagnostic());
            assertEquals(state,owner.snapshot().orElseThrow());assertEquals(hardware,device.write());
            assertEquals(state.state().status()==FlowRuntime.Status.COMPLETE?1:2,device.status("s0").receive().frames());
        }
        assertEquals(SerialResult.receive("received",packet),owner.snapshot().orElseThrow().state().locals().field("got"));
        assertTrue(peak>packet.storageUnits());assertTrue(steps>bytes.length/8);
        var finalOwner=owner.write();var finalDevice=device.write();owner.setPaused(false);assertEquals(0,owner.advanceRound(Map.of()));assertEquals(finalDevice,device.write());
        var restored=new FlowBlockHost(()->{},finalOwner,device);assertTrue(restored.synchronize(SerialFlowHostTest.LIMITS,true));restored.setPaused(false);assertEquals(0,restored.advanceRound(Map.of()));assertEquals(1,device.status("s0").receive().frames());
    }
    @Test void defaultScratchIsaReceivesAnActualFiniteRecordOverMultipleNativeCycles() {
        var device=SerialFlowHostTest.device(); device.configure("s0",Direction.WEST,true);
        var original=new DataValue.RecordValue(Map.of("text",new DataValue.TextValue("receive-你好-😀")));
        device.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(original,
            new SerialRecordCodec.Limits(4096,1048576,SerialFlowHostTest.ARCHIVE)));
        var program=new FlowProgram(Map.of(),List.of(new FlowProgram.SerialReceive("receive","s0","got"))).withAssembly();
        var owner=FlowBlockHost.installed(()->{},UUID.randomUUID(),program,new DataValue.RecordValue(Map.of()),
            SerialFlowHostTest.LIMITS,FlowControl.INITIAL,device);
        owner.step(Map.of());
        assertTrue(owner.snapshot().orElseThrow().state().assembly().isPresent());
        assertNull(owner.snapshot().orElseThrow().state().locals().fields().get("got"));
        assertEquals(1,device.status("s0").receive().frames());
        owner.setPaused(false);
        for(int i=0;owner.snapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;i++) {
            assertTrue(i<10000,()->owner.diagnostic().toString());
            assertEquals(1,owner.advanceRound(Map.of()),()->owner.diagnostic().toString());
            if(owner.snapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE) {
                assertNull(owner.snapshot().orElseThrow().state().locals().fields().get("got"));
                assertEquals(1,device.status("s0").receive().frames());
            }
        }
        assertEquals(SerialResult.receive("received",original),owner.snapshot().orElseThrow().state().locals().field("got"));
        assertEquals(0,device.status("s0").receive().frames());
        assertTrue(owner.snapshot().orElseThrow().state().work()>10);
    }

    @Test void identicalConsecutiveFramesTailArrivalsFaultsAndCompetingReceiversKeepActualHeadIdentity() {
        var device=SerialFlowHostTest.device();device.configure("s0",Direction.EAST,true);
        byte[] bytes=SerialRecordCodec.encode(SerialFlowHostTest.RECORD,new SerialRecordCodec.Limits(4096,1048576,SerialFlowHostTest.ARCHIVE));
        var queue=device.attachments().getFirst().receiver();queue.offer(bytes);
        var a=receiver(device,false);var b=receiver(device,false);a.step(Map.of());b.step(Map.of());
        queue.offer(bytes);queue.offer(new byte[0]);
        finish(a);assertEquals(1,queue.status().frames());assertFalse(queue.status().faults().isEmpty());
        var staleSaved=b.write();var rejected=new FlowBlockHost(()->{},staleSaved,device);assertTrue(rejected.synchronize(SerialFlowHostTest.LIMITS,true),rejected.diagnostic().toString());assertEquals(b.snapshot().orElseThrow().state().work(),rejected.snapshot().orElseThrow().state().work());assertEquals(1,queue.status().frames());finish(rejected);assertEquals(SerialResult.receive("stale",null),rejected.snapshot().orElseThrow().state().locals().field("got"));assertEquals(1,queue.status().frames());
        finish(b);assertEquals(SerialResult.receive("stale",null),b.snapshot().orElseThrow().state().locals().field("got"));assertEquals(1,queue.status().frames());
        var next=receiver(device,false);finish(next);assertEquals(SerialResult.receive("received",SerialFlowHostTest.RECORD),next.snapshot().orElseThrow().state().locals().field("got"));assertEquals(0,queue.status().frames());
    }
    @Test void unpublishedSchema2NeedsItsMatchingHeadButCertifiedMissingHeadIsAPaidFailure() {
        var device=SerialFlowHostTest.device();device.configure("s0",Direction.EAST,true);byte[] bytes=SerialRecordCodec.encode(SerialFlowHostTest.RECORD,new SerialRecordCodec.Limits(4096,1048576,SerialFlowHostTest.ARCHIVE));device.attachments().getFirst().receiver().offer(bytes);
        var owner=receiver(device,false);owner.step(Map.of());var certified=owner.write();
        var value=(DataValue.RecordValue)com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.decode(certified.getCompound("Data"),SerialFlowHostTest.ARCHIVE);var flow=(DataValue.RecordValue)value.field("flow");var state=(DataValue.RecordValue)flow.field("state");var assembly=(DataValue.RecordValue)state.field("assembly");var progress=(DataValue.RecordValue)assembly.field("serial");var rx=(DataValue.RecordValue)progress.field("receive");var fields=new LinkedHashMap<>(rx.fields());for(String field:List.of("image","pc","certificate","failure"))fields.remove(field);
        var legacyProgress=AssemblySerialHostTest.replace(AssemblySerialHostTest.replace(progress,"version",new DataValue.IntegerValue(2)),"receive",new DataValue.RecordValue(fields));var legacy=certified.copy();legacy.put("Data",com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.encode(AssemblySerialHostTest.replace(value,"flow",AssemblySerialHostTest.replace(flow,"state",AssemblySerialHostTest.replace(state,"assembly",AssemblySerialHostTest.replace(assembly,"serial",legacyProgress)))),SerialFlowHostTest.ARCHIVE));
        var oldMatching=new FlowBlockHost(()->{},legacy,device);assertTrue(oldMatching.synchronize(SerialFlowHostTest.LIMITS,true));finish(oldMatching);assertEquals(SerialResult.receive("received",SerialFlowHostTest.RECORD),oldMatching.snapshot().orElseThrow().state().locals().field("got"));
        assertTrue(owner.snapshot().orElseThrow().state().assembly().orElseThrow().serial().orElseThrow().ownedUnits()>=bytes.length,"Old live input has owner RAM/archive ownership even after hardware claim");
        var oldMissing=new FlowBlockHost(()->{},legacy,device);assertFalse(oldMissing.synchronize(SerialFlowHostTest.LIMITS,true),"An unpublished schema2 cursor has no missing-head authentication proof");assertEquals(legacy,oldMissing.write());
        var resumed=new FlowBlockHost(()->{},certified,device);assertTrue(resumed.synchronize(SerialFlowHostTest.LIMITS,true));assertEquals(0,resumed.snapshot().orElseThrow().state().assembly().orElseThrow().serial().orElseThrow().ownedUnits(),"Pending failure drops old input/parser; fixed proof remains in owner value");assertEquals(owner.snapshot().orElseThrow().state().work(),resumed.snapshot().orElseThrow().state().work());assertNull(resumed.snapshot().orElseThrow().state().locals().fields().get("got"));
        resumed=new FlowBlockHost(()->{},resumed.write(),device);assertTrue(resumed.synchronize(SerialFlowHostTest.LIMITS,true));finish(resumed);assertEquals(SerialResult.receive("stale",null),resumed.snapshot().orElseThrow().state().locals().field("got"));assertEquals(owner.snapshot().orElseThrow().state().work()+1,resumed.snapshot().orElseThrow().state().work());assertEquals(0,device.status("s0").receive().frames());
    }
    @Test void configurationChangedAwayThenBackDoesNotReuseTheOldReceiveEnvelope() {
        var device=SerialFlowHostTest.device();device.configure("s0",Direction.EAST,true);
        device.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(SerialFlowHostTest.RECORD,new SerialRecordCodec.Limits(4096,1048576,SerialFlowHostTest.ARCHIVE)));
        var owner=receiver(device,false);owner.step(Map.of());device.configure("s0",Direction.WEST,false);device.configure("s0",Direction.EAST,true);
        var raw=owner.write();var work=owner.snapshot().orElseThrow().state().work();device.read(device.write());owner=new FlowBlockHost(()->{},raw,device);assertTrue(owner.synchronize(SerialFlowHostTest.LIMITS,true),owner.diagnostic().toString());assertEquals(work,owner.snapshot().orElseThrow().state().work());assertNull(owner.snapshot().orElseThrow().state().locals().fields().get("got"));
        finish(owner);assertEquals(SerialResult.receive("configuration_changed",null),owner.snapshot().orElseThrow().state().locals().field("got"));assertEquals(1,device.status("s0").receive().frames());
    }
    @Test void pausedMainAndChildPendingFailureSurviveUnpaidSaveAndReturningCodecEnvelope() {
        for(boolean child:List.of(false,true)) {
            var codec=new java.util.concurrent.atomic.AtomicReference<>(new SerialRecordCodec.Limits(4096,1048576,SerialFlowHostTest.ARCHIVE));var c0=codec.get();
            var device=new SerialDevice(()->SerialFlowHostTest.SERIAL,()->true,codec::get,()->{});device.configure("s0",Direction.EAST,true);device.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(SerialFlowHostTest.RECORD,c0));
            var key=new ModuleDefinition.Key("rx",1);var program=child?new FlowProgram(Map.of(),List.of(new FlowProgram.Procedure("def",key,Map.of(),Map.of("got",SerialResult.RECEIVE),Map.of("got","got")),new FlowProgram.SerialReceive("rx","s0","got"),new FlowProgram.EndProcedure("ret"))).withAssembly():new FlowProgram(Map.of(),List.of(new FlowProgram.SerialReceive("rx","s0","got"))).withAssembly();
            var limits=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(SerialFlowHostTest.LIMITS.hardware(),SerialFlowHostTest.LIMITS.archive(),SerialFlowHostTest.LIMITS.mailbox(),SerialFlowHostTest.LIMITS.pins(),SerialFlowHostTest.SERIAL,2,true,true);
            var owner=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{},pin->{},device);owner.installFlow(program,new DataValue.RecordValue(Map.of()),limits,true);UUID task=child?owner.startFlowTask(key,new DataValue.RecordValue(Map.of()),limits,true):null;owner.step(limits,true);if(child)for(int i=0;owner.flowTask(task).orElseThrow().assembly().orElseThrow().serial().isEmpty();i++){assertTrue(i<4);owner.step(limits,true);}var before=child?owner.flowTask(task).orElseThrow():owner.flowSnapshot().orElseThrow().state();assertTrue(before.paused());assertTrue(before.assembly().orElseThrow().serial().isPresent());
            var raw=owner.write();var hardware=device.write();codec.set(new SerialRecordCodec.Limits(8192,1048576,SerialFlowHostTest.ARCHIVE));device.read(hardware);var dirties=new java.util.concurrent.atomic.AtomicInteger();
            owner=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(dirties::incrementAndGet,pin->{},device);owner.read(raw);owner.tick(limits,true);assertTrue(owner.loaded(),owner.diagnostic().toString());var pending=child?owner.flowTask(task).orElseThrow():owner.flowSnapshot().orElseThrow().state();assertEquals(before.work(),pending.work());assertEquals(before.assembly().orElseThrow().pc(),pending.assembly().orElseThrow().pc());assertEquals("hardware_changed",pending.assembly().orElseThrow().serial().orElseThrow().failure());assertNull(pending.locals().fields().get("got"));
            // Ordinary write only: no step/setPaused/reset/candidate publication may hide a stale durable image.
            var saved=owner.write();var savedDevice=device.write();codec.set(c0);device.read(savedDevice);owner=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(dirties::incrementAndGet,pin->{},device);owner.read(saved);owner.tick(limits,true);owner.tick(limits,true);assertTrue(owner.loaded(),owner.diagnostic().toString());var again=child?owner.flowTask(task).orElseThrow():owner.flowSnapshot().orElseThrow().state();assertEquals(pending,again,"A normal save cannot undo an authenticated pending failure");assertEquals(1,dirties.get(),"New durable metadata must dirty its real BE adapter exactly once, not on repeat load/tick");assertEquals(1,device.status("s0").receive().frames());
            owner.step(limits,true);var failed=child?owner.flowTask(task).orElseThrow():owner.flowSnapshot().orElseThrow().state();assertEquals(before.work()+1,failed.work());assertEquals(SerialResult.receive("hardware_changed",null),failed.locals().field("got"));assertEquals(1,device.status("s0").receive().frames());
        }
    }
    @Test void failedPendingRecoveryGatesKeepOriginalDurableAndNeverDirtyOrConsume() {
        var codec=new java.util.concurrent.atomic.AtomicReference<>(new SerialRecordCodec.Limits(4096,1048576,SerialFlowHostTest.ARCHIVE));var device=new SerialDevice(()->SerialFlowHostTest.SERIAL,()->true,codec::get,()->{});device.configure("s0",Direction.EAST,true);device.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(SerialFlowHostTest.RECORD,codec.get()));var owner=receiver(device,false);owner.step(Map.of());var raw=owner.write();var hardware=device.write();
        long units=com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.decode(raw.getCompound("Data"),SerialFlowHostTest.ARCHIVE).storageUnits();codec.set(new SerialRecordCodec.Limits(8192,1048576,SerialFlowHostTest.ARCHIVE));
        for(String gate:List.of("ram","archive","port")) {
            var limits=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(gate.equals("ram")?new ResourceLimits(1000,1,8):SerialFlowHostTest.LIMITS.hardware(),gate.equals("archive")?new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(100000,1000000,units+8):SerialFlowHostTest.ARCHIVE,SerialFlowHostTest.LIMITS.mailbox(),SerialFlowHostTest.LIMITS.pins(),gate.equals("port")?com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware.NONE:SerialFlowHostTest.SERIAL);
            device.read(hardware);var dirties=new java.util.concurrent.atomic.AtomicInteger();var restored=new FlowBlockHost(dirties::incrementAndGet,raw,device);assertFalse(restored.synchronize(limits,true),gate);assertEquals(raw,restored.write(),gate);assertEquals(0,dirties.get(),gate);assertEquals(hardware,device.write(),gate);assertEquals(1,device.status("s0").receive().frames(),gate);
        }
    }
    @Test void unavailableClosedEmptyDisabledAndWrongFormatResultsAreExplicitAndNonblocking() {
        var available=new java.util.concurrent.atomic.AtomicBoolean(true);
        var device=new SerialDevice(()->SerialFlowHostTest.SERIAL,available::get,()->new SerialRecordCodec.Limits(4096,1048576,SerialFlowHostTest.ARCHIVE),()->{});
        var closed=receiver(device,false);finish(closed);assertEquals(SerialResult.receive("closed",null),closed.snapshot().orElseThrow().state().locals().field("got"));assertEquals(1,closed.snapshot().orElseThrow().state().work());
        device.configure("s0",Direction.EAST,true);var empty=receiver(device,false);finish(empty);assertEquals(SerialResult.receive("empty",null),empty.snapshot().orElseThrow().state().locals().field("got"));assertEquals(1,empty.snapshot().orElseThrow().state().work());
        device.configure("s0",Direction.EAST,false);var disabledEmpty=receiver(device,false);finish(disabledEmpty);assertEquals(SerialResult.receive("closed",null),disabledEmpty.snapshot().orElseThrow().state().locals().field("got"));assertEquals(1,disabledEmpty.snapshot().orElseThrow().state().work());device.configure("s0",Direction.EAST,true);
        available.set(false);var unavailable=receiver(device,false);finish(unavailable);assertEquals(SerialResult.receive("unavailable",null),unavailable.snapshot().orElseThrow().state().locals().field("got"));available.set(true);
        device.attachments().getFirst().receiver().offer(new byte[]{3,0,0,0,0,0,37});var wrong=receiver(device,false);finish(wrong);assertEquals(SerialResult.receive("invalid_record",null),wrong.snapshot().orElseThrow().state().locals().field("got"));assertEquals(1,device.status("s0").receive().frames());
        var nativeRead=receiver(device,true);finish(nativeRead);assertEquals(SerialResult.receive("received",new DataValue.NbtValue(3,new DataValue.IntegerValue(37))),nativeRead.snapshot().orElseThrow().state().locals().field("got"));
        device.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(SerialFlowHostTest.RECORD,new SerialRecordCodec.Limits(4096,1048576,SerialFlowHostTest.ARCHIVE)));device.configure("s0",Direction.EAST,false);
        var disabled=receiver(device,false);finish(disabled);assertEquals(SerialResult.receive("received",SerialFlowHostTest.RECORD),disabled.snapshot().orElseThrow().state().locals().field("got"));assertEquals(0,device.status("s0").receive().frames());
    }
    @Test void actualComputerCallAndNativeRawBitsSurviveEveryStepSave() {
        var master = com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture.withMasterParser(SerialFlowHostTest.LIMITS);
        var device=SerialFlowHostTest.device();device.configure("s0",Direction.EAST,true);
        // FLOAT raw -0 and DOUBLE raw NaN payload from the wire, not the lossy old Tag oracle.
        byte[] bytes={10,0,0,5,0,1,'f',(byte)0x80,0,0,0,6,0,1,'d',0x7f,(byte)0xf8,0,0,0,0,1,0x23,0};
        var original=new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of("f",new DataValue.NbtValue(5,new DataValue.IntegerValue(Integer.MIN_VALUE)),"d",new DataValue.NbtValue(6,new DataValue.IntegerValue(0x7ff8000000000123L)))));
        device.attachments().getFirst().receiver().offer(bytes);
        var key=new ModuleDefinition.Key("receive",1);var program=new FlowProgram(Map.of(),List.of(new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of(),Map.of("got","got"))),
            new FlowProgram.Procedure("def",key,Map.of(),Map.of("got",SerialResult.RECEIVE),Map.of("got","got")),new FlowProgram.SerialReceive("receive","s0","got",true),new FlowProgram.EndProcedure("ret"))).withAssembly();
        var owner=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{},pin->{},device);owner.installFlow(program,new DataValue.RecordValue(Map.of()),master,true);
        for(int i=0;owner.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;i++){
            assertTrue(i<10000);owner.step(master,true);var state=owner.flowSnapshot().orElseThrow();var saved=owner.write();var hardware=device.write();device.read(hardware);
            owner=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{},pin->{},device);owner.read(saved);owner.tick(master,true);assertTrue(owner.loaded(),owner.diagnostic().toString());assertEquals(state,owner.flowSnapshot().orElseThrow());
        }
        assertEquals(SerialResult.receive("received",original),owner.flowSnapshot().orElseThrow().state().locals().field("got"));assertEquals(0,device.status("s0").receive().frames());
    }
    @Test void ownerRamAndArchiveAdmissionBeforeDecodeAndAtClaimNeverPartiallyConsume() {
        for(boolean archive:List.of(false,true))for(boolean finalClaim:List.of(false,true)) {
            var device=SerialFlowHostTest.device();device.configure("s0",Direction.EAST,true);
            var shared=new DataValue.TextValue("x".repeat(900));var packet=new DataValue.RecordValue(Map.of("a",shared,"b",shared,"c",shared));
            device.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(packet,new SerialRecordCodec.Limits(4096,1048576,SerialFlowHostTest.ARCHIVE)));
            var owner=receiver(device,false);owner.setPaused(false);
            if(finalClaim){for(int i=0;owner.snapshot().orElseThrow().state().assembly().orElseThrow().serial().filter(p->p.done()).isEmpty();i++){assertTrue(i<20000);assertEquals(1,owner.advanceRound(Map.of()));}}
            var state=owner.snapshot().orElseThrow();long base=FlowRuntime.restore(state.program(),state.state(),SerialFlowHostTest.LIMITS.hardware()).retainedUnits()+state.initialInputs().storageUnits()+state.control().storageUnits()+2;
            long serialized=com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.decode(owner.write().getCompound("Data"),SerialFlowHostTest.ARCHIVE).storageUnits();
            long temporary=state.state().assembly().orElseThrow().serial().map(p->p.ownedUnits()).orElse(0L);
            var limits=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(new ResourceLimits(1000,archive?1000000:base+1,8),
                archive?new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(100000,1000000,serialized+temporary+1):SerialFlowHostTest.ARCHIVE,
                SerialFlowHostTest.LIMITS.mailbox(),SerialFlowHostTest.LIMITS.pins(),SerialFlowHostTest.SERIAL,SerialFlowHostTest.LIMITS.taskSlots(),SerialFlowHostTest.LIMITS.isa1a(),SerialFlowHostTest.LIMITS.isa1b());
            assertTrue(owner.synchronize(limits,true),"initial admission archive="+archive+" final="+finalClaim+": "+owner.diagnostic());var before=owner.write();var hardware=device.write();
            assertEquals(0,owner.advanceRound(Map.of()),"candidate admission archive="+archive+" final="+finalClaim);assertEquals(before,owner.write());assertEquals(hardware,device.write());
            assertTrue(owner.synchronize(SerialFlowHostTest.LIMITS,true));finish(owner);assertEquals(SerialResult.receive("received",packet),owner.snapshot().orElseThrow().state().locals().field("got"));assertEquals(0,device.status("s0").receive().frames());
        }
    }
    @Test void restoredTemporaryRamArchiveAndMalformedOrStaleCursorKeepOriginalOwnerBytes() {
        var device=SerialFlowHostTest.device();device.configure("s0",Direction.EAST,true);device.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(SerialFlowHostTest.RECORD,new SerialRecordCodec.Limits(4096,1048576,SerialFlowHostTest.ARCHIVE)));
        var owner=receiver(device,false);for(int i=0;i<12;i++)owner.step(Map.of());var good=owner.write();var hardware=device.write();
        var value=(DataValue.RecordValue)com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.decode(good.getCompound("Data"),SerialFlowHostTest.ARCHIVE);
        var flow=(DataValue.RecordValue)value.field("flow");var state=(DataValue.RecordValue)flow.field("state");var assembly=(DataValue.RecordValue)state.field("assembly");var progress=(DataValue.RecordValue)assembly.field("serial");var rx=(DataValue.RecordValue)progress.field("receive");
        for(String corrupt:List.of("version","paid","image","pc","head","flow","origin","witness","length","native","truncated")){
            DataValue altered=corrupt.equals("image")?AssemblySerialHostTest.replace(assembly,"image",new DataValue.TextValue("0".repeat(64))):corrupt.equals("pc")?AssemblySerialHostTest.replace(assembly,"pc",new DataValue.IntegerValue(1)):
                AssemblySerialHostTest.replace(assembly,"serial",corrupt.equals("truncated")?new DataValue.RecordValue(Map.of("version",new DataValue.IntegerValue(2))):
                    corrupt.equals("version")||corrupt.equals("paid")?AssemblySerialHostTest.replace(progress,corrupt,new DataValue.IntegerValue(corrupt.equals("version")?4:Long.MAX_VALUE)):
                    AssemblySerialHostTest.replace(progress,"receive",AssemblySerialHostTest.replace(rx,corrupt,corrupt.equals("native")?new DataValue.BooleanValue(true):corrupt.equals("head")||corrupt.equals("flow")?new DataValue.TextValue(UUID.randomUUID().toString()):new DataValue.IntegerValue(corrupt.equals("witness")?42:corrupt.equals("origin")?Long.MAX_VALUE:1))));
            var bad=good.copy();bad.put("Data",com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.encode(AssemblySerialHostTest.replace(value,"flow",AssemblySerialHostTest.replace(flow,"state",AssemblySerialHostTest.replace(state,"assembly",altered))),SerialFlowHostTest.ARCHIVE));
            var restored=new FlowBlockHost(()->{},bad,device);assertFalse(restored.synchronize(SerialFlowHostTest.LIMITS,true),corrupt);assertEquals(bad,restored.write());assertEquals(hardware,device.write());assertEquals(0,restored.advanceRound(Map.of()));
        }
        long temporary=owner.snapshot().orElseThrow().state().assembly().orElseThrow().serial().orElseThrow().ownedUnits();
        var tight=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(SerialFlowHostTest.LIMITS.hardware(),new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(100000,1000000,value.storageUnits()+temporary-1),SerialFlowHostTest.LIMITS.mailbox(),SerialFlowHostTest.LIMITS.pins(),SerialFlowHostTest.SERIAL,SerialFlowHostTest.LIMITS.taskSlots(),SerialFlowHostTest.LIMITS.isa1a(),SerialFlowHostTest.LIMITS.isa1b());
        var restored=new FlowBlockHost(()->{},good,device);assertFalse(restored.synchronize(tight,true));assertEquals(good,restored.write());assertEquals(hardware,device.write());
        assertTrue(restored.synchronize(SerialFlowHostTest.LIMITS,true));assertEquals(owner.snapshot(),restored.snapshot());
        owner.reset();assertEquals(1,device.status("s0").receive().frames());assertEquals(0,owner.snapshot().orElseThrow().state().work());finish(owner);assertEquals(0,device.status("s0").receive().frames());
    }
    @Test void managedMainChildAndFastTaskShareCpuButOnlyOneClaimsTheHead() {
        var device=SerialFlowHostTest.device();device.configure("s0",Direction.EAST,true);byte[] bytes=SerialRecordCodec.encode(SerialFlowHostTest.RECORD,new SerialRecordCodec.Limits(4096,1048576,SerialFlowHostTest.ARCHIVE));device.attachments().getFirst().receiver().offer(bytes);device.attachments().getFirst().receiver().offer(bytes);
        var child=new ModuleDefinition.Key("receive_child",1);var fast=new ModuleDefinition.Key("fast",1);
        var program=new FlowProgram(Map.of(),List.of(new FlowProgram.SerialReceive("main_rx","s0","got"),
            new FlowProgram.Procedure("child",child,Map.of(),Map.of("got",SerialResult.RECEIVE),Map.of("got","got")),new FlowProgram.SerialReceive("child_rx","s0","got"),new FlowProgram.EndProcedure("child_ret"),
            new FlowProgram.Procedure("fast_def",fast,Map.of(),Map.of(),Map.of()),new FlowProgram.EndProcedure("fast_ret"))).withAssembly();
        var limits=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(SerialFlowHostTest.LIMITS.hardware(),SerialFlowHostTest.LIMITS.archive(),SerialFlowHostTest.LIMITS.mailbox(),SerialFlowHostTest.LIMITS.pins(),SerialFlowHostTest.SERIAL,3,true,true);
        var owner=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{},pin->{},device);owner.installFlow(program,new DataValue.RecordValue(Map.of()),limits,true);owner.step(limits,true);
        var rxTask=owner.startFlowTask(child,new DataValue.RecordValue(Map.of()),limits,true);var fastTask=owner.startFlowTask(fast,new DataValue.RecordValue(Map.of()),limits,true);boolean fastBeforeDecode=false;
        for(int i=0;owner.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE||owner.flowTask(rxTask).orElseThrow().status()!=FlowRuntime.Status.COMPLETE;i++){
            assertTrue(i<10000);owner.step(limits,true);
            if(owner.flowTask(fastTask).orElseThrow().status()==FlowRuntime.Status.COMPLETE&&device.status("s0").receive().frames()==2)fastBeforeDecode=true;
            if(i==12){var saved=owner.write();var hardware=device.write();device.read(hardware);var restored=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{},pin->{},device);restored.read(saved);restored.tick(limits,true);assertTrue(restored.loaded(),restored.diagnostic().toString());assertEquals(owner.flowSnapshot(),restored.flowSnapshot());owner=restored;}
        }
        assertTrue(fastBeforeDecode,"Native decode never freezes other runnable tasks");assertEquals(Set.of(SerialResult.receive("received",SerialFlowHostTest.RECORD),SerialResult.receive("stale",null)),Set.of(owner.flowSnapshot().orElseThrow().state().locals().field("got"),owner.flowTask(rxTask).orElseThrow().locals().field("got")));assertEquals(1,device.status("s0").receive().frames());
        owner.reset(limits,true);owner.installFlow(new FlowProgram(Map.of(),List.of()).withAssembly(),new DataValue.RecordValue(Map.of()),limits,true);assertEquals(1,device.status("s0").receive().frames());
    }
    @Test void dirtyCallbackAfterAtomicClaimCannotReplayOrReturnTheConsumedHead() {
        var device=SerialFlowHostTest.device();device.configure("s0",Direction.EAST,true);device.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(SerialFlowHostTest.RECORD,new SerialRecordCodec.Limits(4096,1048576,SerialFlowHostTest.ARCHIVE)));
        var fail=new java.util.concurrent.atomic.AtomicBoolean();var program=new FlowProgram(Map.of(),List.of(new FlowProgram.SerialReceive("rx","s0","got"))).withAssembly();
        var owner=FlowBlockHost.installed(()->{if(fail.get())throw new IllegalStateException("dirty failed");},UUID.randomUUID(),program,new DataValue.RecordValue(Map.of()),SerialFlowHostTest.LIMITS,FlowControl.INITIAL,device);owner.setPaused(false);
        while(owner.snapshot().orElseThrow().state().assembly().orElseThrow().serial().filter(p->p.done()).isEmpty())assertEquals(1,owner.advanceRound(Map.of()));
        fail.set(true);assertThrows(IllegalStateException.class,()->owner.advanceRound(Map.of()));assertEquals(FlowRuntime.Status.COMPLETE,owner.snapshot().orElseThrow().state().status());assertEquals(0,device.status("s0").receive().frames());
        var restored=new FlowBlockHost(()->{},owner.write(),device);assertTrue(restored.synchronize(SerialFlowHostTest.LIMITS,true));assertEquals(0,restored.advanceRound(Map.of()));
    }
    @Test void everyMainChildClaimWindowReloadKeepsLoserBranchableAndUnrelatedTaskRunnable() {
        var device=SerialFlowHostTest.device();device.configure("s0",Direction.EAST,true);byte[] bytes=SerialRecordCodec.encode(SerialFlowHostTest.RECORD,new SerialRecordCodec.Limits(4096,1048576,SerialFlowHostTest.ARCHIVE));device.attachments().getFirst().receiver().offer(bytes);device.attachments().getFirst().receiver().offer(bytes);
        var rx=new ModuleDefinition.Key("rx",1);var tx=new ModuleDefinition.Key("unrelated_tx",1);
        var packet=new DataValue.NbtValue(8,new DataValue.TextValue("t".repeat(500)));
        var program=new FlowProgram(Map.of(),List.of(new FlowProgram.SerialReceive("main","s0","got"),
            new FlowProgram.Procedure("rx_def",rx,Map.of(),Map.of("got",SerialResult.RECEIVE),Map.of("got","got")),new FlowProgram.SerialReceive("child","s0","got"),new FlowProgram.EndProcedure("rx_ret"),
            new FlowProgram.Procedure("tx_def",tx,Map.of("packet",DataType.Scalar.NBT),Map.of("sent",SerialResult.SEND),Map.of("sent","sent")),new FlowProgram.SerialSend("send","s0","packet","sent"),new FlowProgram.EndProcedure("tx_ret"))).withAssembly();
        var limits=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(SerialFlowHostTest.LIMITS.hardware(),SerialFlowHostTest.LIMITS.archive(),SerialFlowHostTest.LIMITS.mailbox(),SerialFlowHostTest.LIMITS.pins(),SerialFlowHostTest.SERIAL,3,true,true);
        var owner=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{},pin->{},device);owner.installFlow(program,new DataValue.RecordValue(Map.of()),limits,true);owner.step(limits,true);
        var rxId=owner.startFlowTask(rx,new DataValue.RecordValue(Map.of()),limits,true);var txId=owner.startFlowTask(tx,new DataValue.RecordValue(Map.of("packet",packet)),limits,true);int windows=0;long unrelatedAtWindow=-1;
        for(int i=0;owner.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE||owner.flowTask(rxId).orElseThrow().status()!=FlowRuntime.Status.COMPLETE||owner.flowTask(txId).orElseThrow().status()!=FlowRuntime.Status.COMPLETE;i++){
            assertTrue(i<10000);owner.step(limits,true);var snapshot=owner.flowSnapshot().orElseThrow();var child=owner.flowTask(rxId).orElseThrow();
            boolean gap=device.status("s0").receive().frames()==1&&(!snapshot.state().locals().fields().containsKey("got")||!child.locals().fields().containsKey("got"));
            if(gap){windows++;assertEquals(FlowRuntime.Status.READY,owner.flowTask(txId).orElseThrow().status());unrelatedAtWindow=owner.flowTask(txId).orElseThrow().work();}
            long paid=snapshot.state().work()+snapshot.tasks().contexts().stream().mapToLong(FlowRuntime.Checkpoint::work).sum();var raw=owner.write();var hardware=device.write();device.read(hardware);
            var restored=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{},pin->{},device);restored.read(raw);restored.tick(limits,true);assertTrue(restored.loaded(),restored.diagnostic().toString());
            var after=restored.flowSnapshot().orElseThrow();assertEquals(paid,after.state().work()+after.tasks().contexts().stream().mapToLong(FlowRuntime.Checkpoint::work).sum(),"Load never grants parser or failure work");owner=restored;
        }
        assertTrue(windows>0,"Save in the first-claim-to-loser-result gap");assertTrue(owner.flowTask(txId).orElseThrow().work()>unrelatedAtWindow,"Unrelated task continues after gap reload");assertEquals(Set.of(SerialResult.receive("received",SerialFlowHostTest.RECORD),SerialResult.receive("stale",null)),Set.of(owner.flowSnapshot().orElseThrow().state().locals().field("got"),owner.flowTask(rxId).orElseThrow().locals().field("got")));assertEquals(1,device.status("s0").receive().frames());
    }
    @Test void rxSourceOwnerAndSnapshotCapabilitiesRejectOlderSchemasWhileTxIdsRemainUnchanged() {
        var device=SerialFlowHostTest.device();device.configure("s0",Direction.EAST,true);var owner=receiver(device,false);var saved=owner.snapshot().orElseThrow();
        var body=new com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody(saved.program().inputs(),saved.program().steps(),saved.initialInputs(),com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowBlockLayout.DEFAULT,Map.of(),FlowProgram.Execution.REGISTER_V1);
        var workspace=new com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramWorkspace();workspace.setFlow(Optional.of(body));var document=workspace.snapshot();var value=com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(30),value.field("version"));assertEquals(document,com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.decode(value));
        assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException.class,()->com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.decode(AssemblySerialHostTest.replace(value,"version",new DataValue.IntegerValue(29))));
        var raw=owner.write();assertEquals(35,raw.getInt(com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowBlockNbtCodec.MARKER));raw.putInt(com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowBlockNbtCodec.MARKER,34);
        assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException.class,()->com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowBlockNbtCodec.decode(raw,SerialFlowHostTest.ARCHIVE));
        var snapshot=com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.decode(com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowNbtCodec.encode(new com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowNbtCodec.Snapshot(saved.program(),saved.state()),SerialFlowHostTest.ARCHIVE),SerialFlowHostTest.ARCHIVE);
        assertEquals(new DataValue.IntegerValue(37),((DataValue.RecordValue)snapshot).field("version"));assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException.class,()->com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowNbtCodec.decode(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.encode(AssemblySerialHostTest.replace(snapshot,"version",new DataValue.IntegerValue(36)),SerialFlowHostTest.ARCHIVE),SerialFlowHostTest.ARCHIVE));
        assertEquals(0,device.status("s0").receive().frames());assertEquals(0,saved.state().work());
    }
}
