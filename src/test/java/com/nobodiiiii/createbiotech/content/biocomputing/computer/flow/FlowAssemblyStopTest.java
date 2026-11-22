package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowAssemblyTasksTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult;
import java.util.*;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class FlowAssemblyStopTest {
    @Test void stoppingOthersCancelsAnAdmittedWaitWithoutChargingTheVictimOrStoppingTheCaller() {
        var owner = owner(List.of(new FlowProgram.Sleep("give_worker_turn", "value"),
            new FlowProgram.StopScript("cancel_worker", FlowProgram.StopMode.OTHERS), new FlowProgram.StopScript("done")));
        var worker = owner.startFlowTask(COPY, value(100), LIMITS, true);
        owner.step(LIMITS, true); owner.step(LIMITS, true);
        assertEquals(FlowRuntime.Status.SLEEPING, owner.flowTask(worker).orElseThrow().status());
        owner.setPaused(false, LIMITS, true);
        owner.tick(1,LIMITS,true,Map.of());
        owner.setPaused(true,LIMITS,true);
        owner = restored(owner);
        var cancelled = owner.flowTask(worker).orElseThrow();
        assertTrue(cancelled.cancelled());
        assertEquals(1, cancelled.work(), "Cancel does not execute another victim cycle");
        assertEquals(2, owner.flowSnapshot().orElseThrow().state().work());
        owner.step(LIMITS, true);
        assertEquals(FlowRuntime.Status.COMPLETE, owner.flowSnapshot().orElseThrow().state().status());
        assertEquals(cancelled, owner.claimFlowTask(worker,LIMITS,true));
    }

    @Test void stopAllSuspendsDispatchAndCancelsRunnableContextsWithoutPretendingTheyRan() {
        var owner = owner(List.of(new FlowProgram.StopScript("stop_all", FlowProgram.StopMode.ALL), new FlowProgram.Sleep("unreachable", "value")));
        var worker = owner.startFlowTask(COPY, value(100), LIMITS, true);
        owner.step(LIMITS,true);
        owner = restored(owner);
        var main = owner.flowSnapshot().orElseThrow().state();
        assertTrue(main.cancelled()); assertTrue(main.paused()); assertEquals(1,main.work());
        var cancelled = owner.flowTask(worker).orElseThrow();
        assertTrue(cancelled.cancelled()); assertEquals(0,cancelled.work());
    }
    @Test void cancellationReleasesThePinButCannotUndoAnAlreadyPublishedXBusReceipt() {
        var shape = new DataType.RecordType(Map.of("number", DataType.Scalar.INTEGER));
        var payload = new DataValue.RecordValue(Map.of("number", new DataValue.IntegerValue(12)));
        var writerKey = new ModuleDefinition.Key("writer",1);
        var writer = new ComputerBlockHost(() -> {});
        writer.installFlow(new FlowProgram(Map.of("value", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Sleep("turn", "value"), new FlowProgram.StopScript("stop", FlowProgram.StopMode.OTHERS),
            new FlowProgram.Procedure("writer_def",writerKey,Map.of("payload",shape,"delay",DataType.Scalar.INTEGER),Map.of(),Map.of()),
            new FlowProgram.Write("offer","x0","payload"), new FlowProgram.Sleep("after_offer","delay"),
            new FlowProgram.EndProcedure("writer_return"))).withAssembly(), value(1), LIMITS, true);
        var inputs = new DataValue.RecordValue(Map.of("payload",payload,"delay",new DataValue.IntegerValue(100)));
        var first = writer.startFlowTask(writerKey,inputs,LIMITS,true);
        var second = writer.startFlowTask(writerKey,inputs,LIMITS,true);
        writer.step(LIMITS,true); writer.step(LIMITS,true); writer.step(LIMITS,true);
        assertEquals(FlowRuntime.Status.WAITING_WRITE, writer.flowTask(first).orElseThrow().status());
        assertEquals(FlowRuntime.Status.WAITING_WRITE, writer.flowTask(second).orElseThrow().status());
        var reader = new ComputerBlockHost(() -> {});
        reader.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("receive", "x0", "payload", shape))).withAssembly(),
            new DataValue.RecordValue(Map.of()), LIMITS, true);
        reader.setPaused(false,LIMITS,true); reader.tick(1,LIMITS,true,Map.of());
        writer.setPaused(false,LIMITS,true);
        assertEquals(XBusExchange.Status.EXCHANGED, writer.exchange("x0",LIMITS,true,reader,"x0",LIMITS,true));
        var receipts = writer.flowSnapshot().orElseThrow().receipts();
        assertFalse(receipts.isEmpty());
        writer.tick(1,LIMITS,true,Map.of()); // The resumed first writer gets its turn before main.
        writer.tick(2,LIMITS,true,Map.of());
        writer.setPaused(true,LIMITS,true);
        writer = restored(writer);
        var stopped = writer.flowSnapshot().orElseThrow();
        assertTrue(stopped.pinClaims().owner("x0").isEmpty());
        assertEquals(receipts,stopped.receipts(), "Cancellation never erases consumption history");
        assertEquals(payload,reader.flowSnapshot().orElseThrow().state().locals().field("payload"));
        assertTrue(writer.flowTask(first).orElseThrow().cancelled());
        assertTrue(writer.flowTask(second).orElseThrow().cancelled());
    }

    @Test void otherScriptCanTakeTheSerialFrameAfterStoppingAnUncommittedReceiveAndReloading() {
        var limits=serialTaskLimits();
        var device=SerialFlowHostTest.device();
        device.configure("s0",Direction.EAST,true);
        var frame=SerialFlowHostTest.RECORD;
        var bytes=SerialRecordCodec.encode(frame,new SerialRecordCodec.Limits(4096,1048576,SerialFlowHostTest.ARCHIVE));
        device.attachments().getFirst().receiver().offer(bytes);
        var reader=new ModuleDefinition.Key("reader",1);
        var program=new FlowProgram(Map.of("delay",DataType.Scalar.INTEGER),List.of(
            new FlowProgram.Sleep("yield", "delay"),
            new FlowProgram.StopScript("stop",FlowProgram.StopMode.OTHERS),
            new FlowProgram.SerialReceive("read_remaining","s0","received"),
            new FlowProgram.SerialReceive("verify_empty","s0","remaining"),
            new FlowProgram.Procedure("reader_definition",reader,Map.of(),Map.of(),Map.of()),
            new FlowProgram.SerialReceive("background_receive","s0","background"),
            new FlowProgram.EndProcedure("return"))).withAssembly();
        var owner=new ComputerBlockHost(()->{},pin->{},device);
        owner.installFlow(program,new DataValue.RecordValue(Map.of("delay",new DataValue.IntegerValue(1))),limits,true);
        var task=owner.startFlowTask(reader,new DataValue.RecordValue(Map.of()),limits,true);
        owner.step(limits,true); owner.step(limits,true);
        var decoding=owner.flowTask(task).orElseThrow();
        assertTrue(decoding.assembly().orElseThrow().serial().isPresent(),"The background task must begin the incremental receive");
        assertEquals(1,device.status("s0").receive().frames());

        owner.setPaused(false,limits,true);
        owner.tick(1,limits,true,Map.of()); owner.setPaused(true,limits,true);
        var stopped=owner.flowTask(task).orElseThrow();
        assertTrue(stopped.cancelled(),"The receive must not finish as a side effect of stopping it");
        assertEquals(decoding.work(),stopped.work());
        assertEquals(1,device.status("s0").receive().frames());
        var checkpoint=owner.write();
        var hardware=device.write();
        owner.unload(); device.read(hardware);
        owner=new ComputerBlockHost(()->{},pin->{},device);
        owner.read(checkpoint); owner.tick(limits,true);
        assertEquals(stopped,owner.flowTask(task).orElseThrow());
        assertEquals(1,device.status("s0").receive().frames());

        for (int i=0; owner.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE; i++) {
            assertTrue(i<1000,"The surviving script must be able to reclaim the frame");
            owner.step(limits,true);
        }
        var result=owner.flowSnapshot().orElseThrow().state();
        assertEquals(SerialResult.receive("received",frame),result.locals().field("received"));
        assertEquals(SerialResult.receive("empty",null),result.locals().field("remaining"));
        assertEquals(0,device.status("s0").receive().frames());
        var finalRaw=owner.write();
        owner.unload(); device.read(device.write());
        var cold=new ComputerBlockHost(()->{},pin->{},device);
        cold.read(finalRaw); cold.tick(limits,true);
        assertEquals(result,cold.flowSnapshot().orElseThrow().state());
        assertTrue(cold.flowTask(task).orElseThrow().cancelled());
        assertEquals(0,device.status("s0").receive().frames());
    }

    @Test void stoppingOtherScriptsDoesNotRetractOrResendAnAcceptedSerialTransmission() {
        var limits=serialTaskLimits();
        var device=SerialFlowHostTest.device();
        device.configure("s0",Direction.EAST,true);
        var frame=SerialFlowHostTest.RECORD;
        var packetType=DataType.typeOf(frame);
        var writer=new ModuleDefinition.Key("writer",1);
        var program=new FlowProgram(Map.of("delay",DataType.Scalar.INTEGER),List.of(
            new FlowProgram.Sleep("yield", "delay"),
            new FlowProgram.StopScript("stop",FlowProgram.StopMode.OTHERS),
            new FlowProgram.Procedure("writer_definition",writer,Map.of("packet",packetType,"hold",DataType.Scalar.INTEGER),Map.of(),Map.of()),
            new FlowProgram.SerialSend("send_once","s0","packet","sent"),
            new FlowProgram.Sleep("wait_after_send","hold"),new FlowProgram.EndProcedure("return"))).withAssembly();
        var owner=new ComputerBlockHost(()->{},pin->{},device);
        owner.installFlow(program,new DataValue.RecordValue(Map.of("delay",new DataValue.IntegerValue(1))),limits,true);
        var task=owner.startFlowTask(writer,new DataValue.RecordValue(Map.of("packet",frame,"hold",new DataValue.IntegerValue(100))),limits,true);
        owner.step(limits,true); // The root task sleeps; manual instruction steps do not advance that clock.
        for (int i=0; owner.flowTask(task).orElseThrow().status()!=FlowRuntime.Status.SLEEPING; i++) {
            assertTrue(i<1000,"The background task must queue its frame and yield");
            owner.step(limits,true);
        }
        var sending=owner.flowTask(task).orElseThrow();
        assertEquals(SerialResult.send("queued"),sending.locals().field("sent"));
        assertEquals("queued",device.status("s0").transmit());
        var admitted=device.write();

        owner.setPaused(false,limits,true);
        owner.tick(1,limits,true,Map.of()); owner.setPaused(true,limits,true);
        var stopped=owner.flowTask(task).orElseThrow();
        assertTrue(stopped.cancelled()); assertEquals(sending.work(),stopped.work());
        assertEquals(admitted,device.write(),"Stopping tasks cannot retract bytes already accepted by the device");

        var checkpoint=owner.write();
        owner.unload(); device.read(admitted);
        var cold=new ComputerBlockHost(()->{},pin->{},device);
        cold.read(checkpoint); cold.tick(limits,true);
        assertEquals(stopped,cold.flowTask(task).orElseThrow());
        assertEquals("interrupted",device.status("s0").transmit());
        assertEquals("reloaded",device.status("s0").failure());
        cold.setPaused(false,limits,true);
        for (int i=2;i<5;i++) cold.tick(i,limits,true,Map.of());
        assertEquals("interrupted",device.status("s0").transmit(),"Reload must not silently send a replacement");
        assertEquals(stopped.work(),cold.flowTask(task).orElseThrow().work());
    }

    private static ComputerHostLimits serialTaskLimits() {
        var limits=SerialFlowHostTest.LIMITS;
        return new ComputerHostLimits(new ResourceLimits(1000, 1_000_000, 1), limits.archive(),
            limits.mailbox(), limits.pins(), limits.serial(), 3, true);
    }

    private static ComputerBlockHost owner(List<FlowProgram.Step> main) {
        var fixture = program();
        var steps = new ArrayList<>(main); steps.addAll(fixture.steps().subList(1, fixture.steps().size()));
        var owner = new ComputerBlockHost(() -> {});
        owner.installFlow(new FlowProgram(fixture.inputs(), steps, fixture.expressions()).withAssembly(), value(1), LIMITS, true);
        return owner;
    }
}
