package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationNetworkLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
class AssemblySerialHostTest {
    @Test void radioFixtureUsesTheUsualIsaSendAndExplicitReceiveWithoutAWiredSegment() {
        var tx = SerialFlowHostTest.device();
        var rx = SerialFlowHostTest.device();
        tx.configure("s0", Direction.EAST, true);
        rx.configure("s0", Direction.WEST, true);
        var record = SerialFlowHostTest.RECORD;
        var sender = sender(tx, record);
        finish(sender);
        assertEquals(SerialResult.send("queued"), sender.snapshot().orElseThrow().state().locals().field("sent"));
        assertTrue(sender.snapshot().orElseThrow().state().work() > record.storageUnits(), "The sender still pays encoding work");
        var radio = new RadioSerialNetwork(new SharedSerialChannel.Limits(2, 4096, 128, 8),
            (a, b, carrier, band) -> 90, (coding, wanted, unwanted) -> wanted > unwanted);
        var band = new RadioSerialNetwork.Band(100, 8);
        var coding = new RadioSerialNetwork.Coding("qa-isa", 32, 1);
        var radios = List.of(new RadioSerialNetwork.Radio(tx, "s0", BlockPos.ZERO, band, band, coding, coding.format()),
            new RadioSerialNetwork.Radio(rx, "s0", new BlockPos(10, 0, 0), band, band, coding, coding.format()));
        for (int tick = 0; rx.status("s0").receive().frames() == 0; tick++) {
            assertTrue(tick < 200, "Timed out waiting for the test radio");
            assertTrue(radio.tick(radios).fault().isEmpty());
        }
        var receiver = FlowBlockHost.installed(() -> {}, UUID.randomUUID(), new FlowProgram(Map.of(), List.of(
                new FlowProgram.SerialReceive("receive", "s0", "got"))).withAssembly(),
            new DataValue.RecordValue(Map.of()), SerialFlowHostTest.LIMITS, FlowControl.INITIAL, rx);
        finish(receiver);
        assertEquals(SerialResult.receive("received", record), receiver.snapshot().orElseThrow().state().locals().field("got"));
        assertEquals(0, rx.status("s0").receive().frames());
    }

    @Test void theSameIsaSendWorksOnEitherMediumWithoutMakingCpuOrThePortFaster() {
        var a = BlockPos.ZERO;
        var cable = a.east();
        var b = cable.east();
        var packet = SerialFlowHostTest.RECORD;
        var codec = new SerialRecordCodec.Limits(4096, 1048576, SerialFlowHostTest.ARCHIVE);
        int encodedBits = SerialRecordCodec.encode(packet, codec).length * 8 + 8;
        var occupied = new ArrayList<Integer>();
        var cpu = new ArrayList<Long>();
        var base = new SharedSerialChannel.Limits(2, 4096, 32, 8);
        for (var medium : List.of(SerialWireMedium.shared(base),
            SerialWireMedium.shared(new SharedSerialChannel.Limits(2, 4096, 512, 8)))) {
            var tx = SerialFlowHostTest.device();
            var rx = SerialFlowHostTest.device();
            tx.configure("s0", Direction.EAST, true);
            rx.configure("s0", Direction.WEST, true);
            var owner = sender(tx, packet);
            finish(owner);
            var state = owner.snapshot().orElseThrow().state();
            assertEquals(SerialResult.send("queued"), state.locals().field("sent"));
            cpu.add(state.work());

            var world = new CableDiscovery.World() {
                public boolean loaded(BlockPos position) { return true; }
                public CableDiscovery.Cell cell(BlockPos position) {
                    return new CableDiscovery.Cell(position.equals(cable) ? CableDiscovery.Kind.CABLE
                        : position.equals(a) || position.equals(b) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
                }
            };
            var network = new SerialNetwork();
            long rate = Math.min(medium.limits().bitsPerStep(), SerialFlowHostTest.SERIAL.ports().get("s0").bitsPerTick());
            int expected = (int)((encodedBits + rate - 1) / rate);
            for (int tick = 1; tick <= expected; tick++) {
                network.tickWired(world, Map.of(a, tx, b, rx), new CommunicationNetworkLimits(64, 4), base,
                    ignored -> medium, (pos, port) -> new CableDiscovery.Connection(pos, port.side()));
                assertEquals(tick == expected ? Optional.of(packet) : Optional.empty(), rx.poll("s0"));
            }
            occupied.add(expected);
        }
        System.out.println("Same encoded ISA frame (" + encodedBits + " bits): base " + occupied.get(0)
            + " ticks, fast " + occupied.get(1) + " ticks; CPU work " + cpu);
        assertEquals(cpu.getFirst(), cpu.getLast());
        assertTrue(occupied.getLast() < occupied.getFirst());
    }

    @Test void nativeOwnerEncodesThenQueuesOneFrameAndLegacyPublicReceiverReadsIt() {
        var tx = SerialFlowHostTest.device(); var rx = SerialFlowHostTest.device();
        tx.configure("s0", Direction.EAST, true); rx.configure("s0", Direction.WEST, true);
        var packet = new DataValue.RecordValue(Map.of("text", new DataValue.TextValue("bounded-你好")));
        var sender = FlowBlockHost.installed(() -> {}, UUID.randomUUID(), new FlowProgram(Map.of("packet",DataType.typeOf(packet)), List.of(
            new FlowProgram.SerialSend("send","s0","packet","sent"), new FlowProgram.SerialStatus("status","s0","view"))).withAssembly(),
            new DataValue.RecordValue(Map.of("packet",packet)), SerialFlowHostTest.LIMITS, FlowControl.INITIAL, tx);
        sender.step(Map.of()); assertEquals("idle", tx.status("s0").transmit());
        assertNull(sender.snapshot().orElseThrow().state().locals().fields().get("sent"));
        sender.setPaused(false);
        int rounds=0;
        while(sender.snapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE) {
            assertTrue(rounds++<2000); assertTrue(sender.advanceRound(Map.of())>0);
        }
        assertEquals(SerialResult.send("queued"), sender.snapshot().orElseThrow().state().locals().field("sent"));
        assertEquals(packet,sender.snapshot().orElseThrow().state().locals().field("packet"));
        assertTrue(sender.snapshot().orElseThrow().state().work()>packet.storageUnits());
        var network=new SerialNetwork();
        var a=new net.minecraft.core.BlockPos(0,0,0); var b=new net.minecraft.core.BlockPos(2,0,0);
        var cable=new net.minecraft.core.BlockPos(1,0,0); var devices=Map.of(a,tx,b,rx);
        var world=new com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery.World() {
            public boolean loaded(net.minecraft.core.BlockPos pos) { return true; }
            public com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery.Cell cell(net.minecraft.core.BlockPos pos) {
                return new com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery.Cell(pos.equals(cable)?com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery.Kind.CABLE:
                    devices.containsKey(pos)?com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery.Kind.ENDPOINT:
                    com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery.Kind.EMPTY,null);
            }
        };
        for(int i=0;i<100;i++) network.tick(world,devices,new com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationNetworkLimits(64,4),new SharedSerialChannel.Limits(2,4096,128,8));
        assertEquals(1,rx.status("s0").receive().frames());
        var receiver=FlowBlockHost.installed(() -> {},UUID.randomUUID(),new FlowProgram(Map.of(),List.of(new FlowProgram.SerialReceive("receive","s0","got"))),
            new DataValue.RecordValue(Map.of()),SerialFlowHostTest.LIMITS,FlowControl.INITIAL,rx);
        receiver.step(Map.of());
        assertEquals(SerialResult.receive("received",packet),receiver.snapshot().orElseThrow().state().locals().field("got"));
        assertEquals(0,rx.status("s0").receive().frames());
        assertEquals(0,sender.advanceRound(Map.of()));
    }

    @Test void actualComputerCallEncodingSurvivesEveryPaidStepAndCompletionRestoreDoesNotResend() {
        var device=SerialFlowHostTest.device();device.configure("s0",Direction.EAST,true);
        var shared=new DataValue.RecordValue(Map.of("x",new DataValue.TextValue("save-你好-😀")));
        var packet=new DataValue.RecordValue(Map.of("a",shared,"b",shared));
        var key=new ModuleDefinition.Key("transmit",1);var type=DataType.typeOf(packet);
        var program=new FlowProgram(Map.of("packet",type),List.of(
            new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of("packet","packet"),Map.of("sent","sent"))),
            new FlowProgram.SerialStatus("view","s0","view"),
            new FlowProgram.Procedure("def",key,Map.of("packet",type),Map.of("sent",SerialResult.SEND),Map.of("sent","sent")),
            new FlowProgram.SerialSend("send","s0","packet","sent"),new FlowProgram.EndProcedure("return"))).withAssembly();
        var inputs=new DataValue.RecordValue(Map.of("packet",packet));
        var owner=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{},pin->{},device);
        owner.installFlow(program,inputs,SerialFlowHostTest.LIMITS,true);
        boolean queued=false;int steps=0;
        while(owner.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE){
            assertTrue(steps++<2000);var before=owner.flowSnapshot().orElseThrow();
            owner.step(SerialFlowHostTest.LIMITS,true);var after=owner.flowSnapshot().orElseThrow();
            assertEquals(before.state().work()+1,after.state().work());
            var progress=after.state().assembly().orElseThrow().serial();
            if(progress.isPresent()) {assertEquals("idle",device.status("s0").transmit());if(!progress.orElseThrow().done())assertTrue(progress.orElseThrow().ownedUnits()>=4096);}
            queued|=device.status("s0").transmit().equals("queued");
            var saved=owner.write();var hardware=device.write();owner.unload();
            if(!queued)device.read(hardware);
            owner=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{},pin->{},device);
            owner.read(saved);owner.tick(SerialFlowHostTest.LIMITS,true);
            assertEquals(after,owner.flowSnapshot().orElseThrow());assertEquals(hardware,device.write());
        }
        assertTrue(queued);var result=owner.flowSnapshot().orElseThrow().state();
        assertEquals(SerialResult.send("queued"),result.locals().field("sent"));assertEquals(packet,result.locals().field("packet"));
        assertTrue(result.assembly().orElseThrow().calls().orElseThrow().frames().isEmpty());
        long work=result.work();owner.tick(SerialFlowHostTest.LIMITS,true);assertEquals(work,owner.flowSnapshot().orElseThrow().state().work());
    }
    @Test void reservedEncodingBuffersAndFaultClaimsCannotBypassRamOrArchiveAdmission() {
        for(boolean claim:List.of(false,true))for(boolean archive:List.of(false,true)){
            var device=SerialFlowHostTest.device();device.configure("s0",Direction.EAST,true);device.attachments().getFirst().receiver().offer(new byte[0]);
            var packet=SerialFlowHostTest.RECORD;var input=claim?new DataValue.RecordValue(Map.of()):new DataValue.RecordValue(Map.of("packet",packet));
            var program=new FlowProgram(claim?Map.of():Map.of("packet",DataType.typeOf(packet)),claim?List.of(new FlowProgram.SerialStatus("claim","s0","claimed",true)):List.of(new FlowProgram.SerialSend("send","s0","packet","sent"))).withAssembly();
            var owner=FlowBlockHost.installed(()->{},UUID.randomUUID(),program,input,SerialFlowHostTest.LIMITS,FlowControl.INITIAL,device);owner.setPaused(false);
            var snapshot=owner.snapshot().orElseThrow();
            long base=FlowRuntime.restore(program,snapshot.state(),SerialFlowHostTest.LIMITS.hardware()).retainedUnits()+input.storageUnits()+snapshot.control().storageUnits()+2;
            long save=com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.decode(owner.write().getCompound("Data"),SerialFlowHostTest.ARCHIVE).storageUnits();
            var tight=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(new ResourceLimits(1000,archive?1000000:base+1,8),
                archive?new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(100000,1000000,save+1):SerialFlowHostTest.ARCHIVE,
                SerialFlowHostTest.LIMITS.mailbox(),SerialFlowHostTest.LIMITS.pins(),SerialFlowHostTest.SERIAL,SerialFlowHostTest.LIMITS.taskSlots(),SerialFlowHostTest.LIMITS.isa1a(),SerialFlowHostTest.LIMITS.isa1b());
            assertTrue(owner.synchronize(tight,true),()->"claim="+claim+" archive="+archive+" "+owner.diagnostic());var before=owner.write();var beforeDevice=device.write();
            assertEquals(0,owner.advanceRound(Map.of()));assertEquals(before,owner.write());assertEquals(beforeDevice,device.write());
            assertTrue(owner.synchronize(SerialFlowHostTest.LIMITS,true));assertEquals(1,owner.advanceRound(Map.of()));
            if(!claim){assertEquals("idle",device.status("s0").transmit());assertFalse(device.status("s0").receive().faults().isEmpty());}
            else assertTrue(device.status("s0").receive().faults().isEmpty());
        }
    }

    static FlowBlockHost sender(SerialDevice device,DataValue packet) {
        var program=new FlowProgram(Map.of("packet",DataType.typeOf(packet)),List.of(new FlowProgram.SerialSend("send","s0","packet","sent"),new FlowProgram.SerialStatus("status","s0","view"))).withAssembly();
        return FlowBlockHost.installed(()->{},UUID.randomUUID(),program,new DataValue.RecordValue(Map.of("packet",packet)),SerialFlowHostTest.LIMITS,FlowControl.INITIAL,device);
    }
    static void finish(FlowBlockHost owner) {owner.setPaused(false);for(int i=0;owner.snapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;i++){assertTrue(i<10000,()->owner.diagnostic().toString());assertEquals(1,owner.advanceRound(Map.of()),()->owner.diagnostic().toString());}}
    @Test void finalAdmissionRechecksBusyClosedAndExplicitConfigurationWithoutRetry() {
        for(String change:List.of("busy","closed","configuration_changed")) {
            var device=SerialFlowHostTest.device();device.configure("s0",Direction.EAST,true);
            var owner=sender(device,SerialFlowHostTest.RECORD);owner.step(Map.of());
            var progress=owner.snapshot().orElseThrow().state().assembly().orElseThrow().serial().orElseThrow();
            owner.setPaused(true);assertSame(progress,owner.snapshot().orElseThrow().state().assembly().orElseThrow().serial().orElseThrow(),"Owner candidate must retain live codec witness");
            if(change.equals("busy"))assertEquals("queued",device.send("s0",SerialFlowHostTest.RECORD));
            else device.configure("s0",Direction.WEST,!change.equals("closed"));
            finish(owner);assertEquals(SerialResult.send(change),owner.snapshot().orElseThrow().state().locals().field("sent"));
            var raw=owner.write();assertEquals(0,owner.advanceRound(Map.of()));assertEquals(raw,owner.write());
        }
    }
    @Test void twoManagedIsaTasksCompeteAtFinalQueueAdmissionAndSaveTheirSeparatePaidCursors() {
        var device=SerialFlowHostTest.device();device.configure("s0",Direction.EAST,true);
        var limits=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(SerialFlowHostTest.LIMITS.hardware(),SerialFlowHostTest.LIMITS.archive(),SerialFlowHostTest.LIMITS.mailbox(),SerialFlowHostTest.LIMITS.pins(),SerialFlowHostTest.SERIAL,3,true,false);
        var key=new ModuleDefinition.Key("tx",1);var packet=SerialFlowHostTest.RECORD;
        var program=new FlowProgram(Map.of("delay",DataType.Scalar.INTEGER),List.of(new FlowProgram.Sleep("pause_main","delay"),
            new FlowProgram.Procedure("def",key,Map.of("packet",DataType.typeOf(packet)),Map.of("sent",SerialResult.SEND),Map.of("sent","sent")),
            new FlowProgram.SerialSend("send","s0","packet","sent"),new FlowProgram.EndProcedure("ret"))).withAssembly();
        var owner=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{},pin->{},device);
        owner.installFlow(program,new DataValue.RecordValue(Map.of("delay",new DataValue.IntegerValue(100000))),limits,true);owner.step(limits,true);
        var args=new DataValue.RecordValue(Map.of("packet",packet));var a=owner.startFlowTask(key,args,limits,true);var b=owner.startFlowTask(key,args,limits,true);
        for(int i=0;owner.flowTask(a).orElseThrow().status()!=FlowRuntime.Status.COMPLETE || owner.flowTask(b).orElseThrow().status()!=FlowRuntime.Status.COMPLETE;i++) {
            assertTrue(i<2000);owner.step(limits,true);
            if(i%31==0){var raw=owner.write();var next=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{},pin->{},device);next.read(raw);next.tick(limits,true);assertTrue(next.loaded(),()->next.diagnostic().toString());owner=next;}
        }
        var outcomes=Set.of(owner.flowTask(a).orElseThrow().locals().field("sent"),owner.flowTask(b).orElseThrow().locals().field("sent"));
        assertEquals(Set.of(SerialResult.send("queued"),SerialResult.send("busy")),outcomes);assertEquals("queued",device.status("s0").transmit());
        assertEquals(FlowRuntime.Status.SLEEPING,owner.flowSnapshot().orElseThrow().state().status());
    }
    @Test void publicMemoryProgramsStillDispatchSerialAtTheActualPc() {
        var device=SerialFlowHostTest.device();device.configure("s0",Direction.EAST,true);
        var packet=SerialFlowHostTest.RECORD;
        var archive=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),
            Map.of("counter",new ProgramDefinition.Cell(DataType.Scalar.INTEGER,new DataValue.IntegerValue(0))),List.of()),Map.of());
        var program=new FlowProgram(Map.of("packet",DataType.typeOf(packet)),List.of(new FlowProgram.Shared("increment","counter",new FlowInput.Literal(new DataValue.IntegerValue(1)),true),
            new FlowProgram.SerialSend("send","s0","packet","sent"),new FlowProgram.SerialStatus("status","s0","view")),archive).withAssembly();
        var owner=FlowBlockHost.installed(()->{},UUID.randomUUID(),program,new DataValue.RecordValue(Map.of("packet",packet)),SerialFlowHostTest.LIMITS,FlowControl.INITIAL,device);
        finish(owner);assertEquals(SerialResult.send("queued"),owner.snapshot().orElseThrow().state().locals().field("sent"));
        var restored=new FlowBlockHost(()->{},owner.write(),device);assertTrue(restored.synchronize(SerialFlowHostTest.LIMITS,true));assertEquals(owner.snapshot(),restored.snapshot());
    }
    @Test void scratchDocumentSerialGateAndSmallRecordAndNbtDemonstrationsHaveRealCosts() {
        var nativePacket=new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of("reading",new DataValue.NbtValue(2,new DataValue.IntegerValue(37)))));
        for(var packet:List.of(SerialFlowHostTest.RECORD,nativePacket)){
            var device=SerialFlowHostTest.device();device.configure("s0",Direction.EAST,true);var owner=sender(device,packet);
            var program=owner.snapshot().orElseThrow().program();var body=new com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody(program.inputs(),program.steps(),owner.snapshot().orElseThrow().initialInputs(),
                com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowBlockLayout.DEFAULT,Map.of(),FlowProgram.Execution.REGISTER_V1);
            var workspace=new com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramWorkspace();workspace.setFlow(Optional.of(body));var document=workspace.snapshot();
            var value=(DataValue.RecordValue)com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.encode(document);assertEquals(new DataValue.IntegerValue(29),value.field("version"));
            assertEquals(document,com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.decode(value));
            var old=new LinkedHashMap<>(value.fields());old.put("version",new DataValue.IntegerValue(28));
            assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException.class,()->com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.decode(new DataValue.RecordValue(old)));
            long peak=0;owner.setPaused(false);
            while(owner.snapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE){assertEquals(1,owner.advanceRound(Map.of()));peak=Math.max(peak,FlowRuntime.restore(program,owner.snapshot().orElseThrow().state(),SerialFlowHostTest.LIMITS.hardware()).retainedUnits());}
            System.out.println("SERIAL_DEMO kind="+(packet instanceof DataValue.NbtValue?"NBT":"record")+" codewords="+program.usage(SerialFlowHostTest.LIMITS.hardware()).programUnits()+" cycles="+owner.snapshot().orElseThrow().state().work()+" runtimeRAMpeak="+peak);
            assertTrue(program.usage(SerialFlowHostTest.LIMITS.hardware()).programUnits()<=32);
        }
    }

    static DataValue.RecordValue replace(DataValue value,String key,DataValue replacement) {var map=new LinkedHashMap<>(((DataValue.RecordValue)value).fields());map.put(key,replacement);return new DataValue.RecordValue(map);}
    @Test void malformedFutureWrongImageAndHugeSavedSerialProgressNeverAllocatesOrQueuesOnRestore() {
        var device=SerialFlowHostTest.device();device.configure("s0",Direction.EAST,true);var owner=sender(device,SerialFlowHostTest.RECORD);owner.step(Map.of());
        var good=owner.write();var beforeDevice=device.write();
        var value=(DataValue.RecordValue)com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.decode(good.getCompound("Data"),SerialFlowHostTest.ARCHIVE);
        var flow=(DataValue.RecordValue)value.field("flow");var state=(DataValue.RecordValue)flow.field("state");var assembly=(DataValue.RecordValue)state.field("assembly");var progress=(DataValue.RecordValue)assembly.field("serial");
        for(String corrupt:List.of("version","paid","bytes","image","pc","truncated")) {
            DataValue altered=corrupt.equals("image")?replace(assembly,"image",new DataValue.TextValue("0".repeat(64))):corrupt.equals("pc")?replace(assembly,"pc",new DataValue.IntegerValue(1)):
                replace(assembly,"serial",corrupt.equals("truncated")?new DataValue.RecordValue(Map.of("version",new DataValue.IntegerValue(1))):
                    replace(progress,corrupt,new DataValue.IntegerValue(corrupt.equals("version")?2:corrupt.equals("paid")?Long.MAX_VALUE:Integer.MAX_VALUE)));
            var changed=replace(value,"flow",replace(flow,"state",replace(state,"assembly",altered)));
            var bad=good.copy();bad.put("Data",com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.encode(changed,SerialFlowHostTest.ARCHIVE));
            var restored=new FlowBlockHost(()->{},bad,device);assertFalse(restored.synchronize(SerialFlowHostTest.LIMITS,true),corrupt);
            assertEquals(bad,restored.write());assertEquals(beforeDevice,device.write());assertEquals(0,restored.advanceRound(Map.of()));
        }
    }

    @Test void restoredSerialProgressRejectsWrongOrOutOfBoundsSourceProjectionWithoutThrowing() {
        var device=SerialFlowHostTest.device();device.configure("s0",Direction.EAST,true);var owner=sender(device,SerialFlowHostTest.RECORD);owner.step(Map.of());
        var raw=owner.write();var beforeDevice=device.write();
        var value=(DataValue.RecordValue)com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.decode(raw.getCompound("Data"),SerialFlowHostTest.ARCHIVE);
        var flow=(DataValue.RecordValue)value.field("flow");var state=(DataValue.RecordValue)flow.field("state");
        for(int cursor:List.of(1,2,100)) {
            var altered=replace(value,"flow",replace(flow,"state",replace(state,"cursor",new DataValue.IntegerValue(cursor))));
            var bad=raw.copy();bad.put("Data",com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.encode(altered,SerialFlowHostTest.ARCHIVE));
            var restored=new FlowBlockHost(()->{},bad,device);
            assertFalse(restored.synchronize(SerialFlowHostTest.LIMITS,true));
            assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost.Code.INVALID_SAVE,restored.diagnostic().orElseThrow().code());
            assertEquals(bad,restored.write());assertEquals(beforeDevice,device.write());
        }
    }
    @Test void anyTypedSerialInputDefersRecordOrNbtValidationToRuntimeResultWithoutQueueingOtherKinds() {
        var master = com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture.withMasterParser(SerialFlowHostTest.LIMITS);
        for(DataValue packet:List.of(SerialFlowHostTest.RECORD,new DataValue.NbtValue(2,new DataValue.IntegerValue(37)),new DataValue.IntegerValue(37))) {
            var device=SerialFlowHostTest.device();device.configure("s0",Direction.EAST,true);
            var program=new FlowProgram(Map.of("packet",DataType.Scalar.ANY),List.of(new FlowProgram.SerialSend("send","s0","packet","sent"))).withAssembly();
            var owner=FlowBlockHost.installed(()->{},UUID.randomUUID(),program,new DataValue.RecordValue(Map.of("packet",packet)),master,FlowControl.INITIAL,device);
            finish(owner);boolean valid=packet instanceof DataValue.RecordValue || packet instanceof DataValue.NbtValue;
            assertEquals(SerialResult.send(valid?"queued":"invalid_record"),owner.snapshot().orElseThrow().state().locals().field("sent"));
            assertEquals(valid?"queued":"idle",device.status("s0").transmit());assertEquals(packet,owner.snapshot().orElseThrow().state().locals().field("packet"));
        }
    }
    @Test void restoreUnderSmallerArchiveEnvelopeChargesReconstructedTemporaryEncodingState() {
        var device=SerialFlowHostTest.device();device.configure("s0",Direction.EAST,true);var owner=sender(device,SerialFlowHostTest.RECORD);owner.step(Map.of());
        var raw=owner.write();var beforeDevice=device.write();
        long serialized=com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.decode(raw.getCompound("Data"),SerialFlowHostTest.ARCHIVE).storageUnits();
        long temporary=owner.snapshot().orElseThrow().state().assembly().orElseThrow().serial().orElseThrow().ownedUnits();
        var limits=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(SerialFlowHostTest.LIMITS.hardware(),
            new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(100000,1000000,serialized+temporary-1),
            SerialFlowHostTest.LIMITS.mailbox(),SerialFlowHostTest.LIMITS.pins(),SerialFlowHostTest.SERIAL,SerialFlowHostTest.LIMITS.taskSlots(),SerialFlowHostTest.LIMITS.isa1a(),SerialFlowHostTest.LIMITS.isa1b());
        var restored=new FlowBlockHost(()->{},raw,device);assertFalse(restored.synchronize(limits,true));
        assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost.Code.RESOURCE_LIMIT,restored.diagnostic().orElseThrow().code());
        assertEquals(raw,restored.write());assertEquals(beforeDevice,device.write());assertEquals(0,restored.advanceRound(Map.of()));
        assertTrue(restored.synchronize(SerialFlowHostTest.LIMITS,true));assertEquals(owner.snapshot(),restored.snapshot());
    }
}
