package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowEventIsaTest {
    private static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());
    private static final ModuleDefinition.Key SENDER = new ModuleDefinition.Key("send", 1);
    private static final ModuleDefinition.Key UNRELATED = new ModuleDefinition.Key("unrelated", 1);
    private static final ComputerHostLimits LIMITS = limits(3);
    private static ComputerHostLimits limits(int slots) {
        return ParserHardwareFixture.withIsa1a(baseLimits(slots));
    }
    private static ComputerHostLimits baseLimits(int slots) {
        return new ComputerHostLimits(new ResourceLimits(4096, 64_000, 1), FlowArchiveTest.ARCHIVE,
            MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SerialHardware.NONE, slots);
    }
    private static FlowProgram program(String opcode) {
        var cells = Map.of("count", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0)));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), cells, List.of()), Map.of());
        var steps = new ArrayList<FlowProgram.Step>();
        steps.add(new FlowProgram.Invoke("main", SENDER, new FlowCallBindings(Map.of(), Map.of())));
        steps.add(new FlowProgram.Procedure("send", SENDER, Map.of(), Map.of(), Map.of(), FlowBlockRules.NONE,
            Optional.of(new AssemblySource(opcode + " refresh\nRET"))));
        steps.add(new FlowProgram.EndProcedure("end_send"));
        steps.add(new FlowProgram.Procedure("unrelated", UNRELATED, Map.of(), Map.of(), Map.of(), FlowBlockRules.NONE,
            Optional.of(new AssemblySource("REG delay INTEGER\nCONST delay 1000\nSLEEP delay\nRET"))));
        steps.add(new FlowProgram.EndProcedure("unrelated_end"));
        for (int i = 1; i <= 3; i++) {
            String id = "receiver_" + i;
            steps.add(new FlowProgram.Procedure(id, new ModuleDefinition.Key(id, 1), Map.of(), Map.of(), Map.of(),
                FlowBlockRules.NONE, Optional.empty(), Optional.of(i == 3 ? "other" : "refresh")));
            steps.add(new FlowProgram.Shared(id + "_add", "count", new FlowInput.Literal(new DataValue.IntegerValue(i)), true));
            steps.add(new FlowProgram.EndProcedure(id + "_end"));
        }
        return new FlowProgram(Map.of(), steps, archive).withAssembly();
    }
    private static FlowBlockHost.Saved state(ComputerBlockHost owner) { return owner.flowSnapshot().orElseThrow(); }
    private static ComputerBlockHost install(String opcode, ComputerHostLimits limits) {
        var owner = new ComputerBlockHost(() -> {});
        owner.installFlow(program(opcode), EMPTY, limits, true);
        return owner;
    }
    @Test void nativeOpcodeRequiresParserButScratchCanChooseSoftwareEvenWithoutReceivers() {
        var basic = baseLimits(3);
        var masterWithoutParser = new ComputerHostLimits(basic.hardware(), basic.archive(), basic.mailbox(), basic.pins(),
            basic.serial(), basic.taskSlots(), false, true);
        for (boolean wait : List.of(false, true)) {
            var authored = program(wait ? "EVENT.POST.WAIT" : "EVENT.POST");
            var scratch = new FlowProgram(Map.of(), List.of(new FlowProgram.Broadcast("emit", "refresh", wait))).withAssembly();
            assertDoesNotThrow(()->FlowPins.validate(scratch.forBroadcastHardware(false),basic));
            for (var candidate : List.of(authored, scratch.forBroadcastHardware(true))) {
                for (var hardware : List.of(basic, masterWithoutParser))
                    assertTrue(assertThrows(FlowPins.Unavailable.class, () -> FlowPins.validate(candidate, hardware))
                        .getMessage().contains("ISA-1a requires Parser hardware"));
                assertDoesNotThrow(() -> FlowPins.validate(candidate, LIMITS));
            }
        }
    }

    private static FlowProgram scratchGroup(boolean await) {
        var source=program("EVENT.POST");
        var steps=new ArrayList<FlowProgram.Step>();steps.add(new FlowProgram.Broadcast("emit","refresh",await));
        steps.addAll(source.steps().subList(3,source.steps().size()));
        return new FlowProgram(Map.of(),steps,source.expressions()).withAssembly();
    }
    @Test void softwareStagesTargetsBeforePublishingAndRestoresAcrossBothBoundaries(){
        var basic=baseLimits(4);
        for(boolean wait:List.of(false,true)){
            var owner=new ComputerBlockHost(()->{});owner.installFlow(scratchGroup(wait),EMPTY,basic,true);
            var original=state(owner).program();
            var base=FlowXBus.image(original);
            assertTrue(base.code().stream().noneMatch(c->c.instruction() instanceof RegisterEvent));
            assertTrue(base.code().stream().anyMatch(c->c.instruction() instanceof RegisterTaskGroup));
            assertTrue(FlowXBus.image(scratchGroup(wait)).code().stream().anyMatch(c->c.instruction() instanceof RegisterEvent));
            for(int i=0;i<2;i++){
                owner.step(basic,true);assertTrue(state(owner).tasks().contexts().isEmpty(),"Staging is not delivery");
                var next=new ComputerBlockHost(()->{});next.read(owner.write());next.tick(basic,true);owner=next;
                assertTrue(owner.loaded(),owner.diagnostic().toString());
            }
            owner.step(basic,true);assertEquals(2,state(owner).tasks().contexts().size());
            assertEquals(wait,state(owner).state().status()==FlowRuntime.Status.WAITING_TASK);
            var restored=new ComputerBlockHost(()->{});restored.read(owner.write());restored.tick(basic,true);
            for(int i=0;i<100 && (state(restored).state().status()!=FlowRuntime.Status.COMPLETE || !state(restored).tasks().contexts().isEmpty());i++)restored.step(basic,true);
            assertEquals(new DataValue.IntegerValue(3),state(restored).memory().cells().field("count"));
            assertEquals(FlowRuntime.Status.COMPLETE,state(restored).state().status());
            assertEquals(original,state(restored).program());
        }
    }
    @Test void tooFewSlotsRejectTheWholeSoftwareBroadcast(){
        var basic=baseLimits(2);var host=new ComputerBlockHost(()->{});host.installFlow(scratchGroup(false),EMPTY,basic,true);
        for(int i=0;i<6 && state(host).state().status()!=FlowRuntime.Status.FAILED;i++)host.step(basic,true);
        assertEquals(FlowRuntime.Status.FAILED,state(host).state().status());assertTrue(state(host).tasks().contexts().isEmpty());
        assertEquals(new DataValue.IntegerValue(0),state(host).memory().cells().field("count"));
    }
    @Test void savedBroadcastWaitIsNotRunOrRewrittenWithoutParser() {
        var owner = install("EVENT.POST.WAIT", LIMITS);
        for (int i = 0; i < 10 && state(owner).state().status() != FlowRuntime.Status.WAITING_TASK; i++) owner.step(LIMITS, true);
        var before = state(owner);
        assertEquals(FlowRuntime.Status.WAITING_TASK, before.state().status());
        var saved = owner.write();
        var restored = new ComputerBlockHost(() -> {});
        restored.read(saved);
        restored.tick(baseLimits(3), true);
        assertFalse(restored.loaded());
        assertEquals(ComputerBlockHost.Code.HARDWARE_MISMATCH, restored.diagnostic().orElseThrow().code());
        assertEquals(saved, restored.write());
        restored.tick(LIMITS, true);
        assertTrue(restored.loaded(), restored.diagnostic().toString());
        assertEquals(before, state(restored));
    }

    @Test void authoredBroadcastWaitAdmitsBothMatchingTasksAndResumesOnceAfterSave() {
        var limits = limits(4);
        var owner = install("EVENT.POST.WAIT", limits);
        UUID unrelated = owner.startFlowTask(UNRELATED, EMPTY, limits, true);
        for (int i = 0; i < 10 && state(owner).state().status() != FlowRuntime.Status.WAITING_TASK; i++) owner.step(limits, true);
        var waiting = state(owner);
        assertEquals(FlowRuntime.Status.WAITING_TASK, waiting.state().status());
        assertEquals(3, waiting.tasks().contexts().size());
        assertEquals(new DataValue.TextValue("waiting_broadcast"),
            com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerStatusView
                .flow(Optional.of(waiting), true, Optional.empty()).field("wait_reason"));
        var saved = owner.write();
        var restored = new ComputerBlockHost(() -> {}); restored.read(saved); restored.tick(limits, true);
        assertTrue(restored.loaded(), restored.diagnostic().toString());
        assertEquals(waiting, state(restored));
        for (int i = 0; i < 24; i++) restored.step(limits, true);
        assertEquals(FlowRuntime.Status.COMPLETE, state(restored).state().status());
        assertEquals(new DataValue.IntegerValue(3), state(restored).memory().cells().field("count"));
        assertEquals(List.of(unrelated), state(restored).tasks().contexts().stream().map(FlowRuntime.Checkpoint::flow).toList());
        assertEquals(FlowRuntime.Status.SLEEPING, restored.flowTask(unrelated).orElseThrow().status(),
            "A broadcast wait does not join unrelated tasks");
    }

    @Test void postWithoutWaitAdvancesTheSenderBeforeEitherReceiverRuns() {
        var owner = install("EVENT.POST", LIMITS);
        for (int i = 0; i < 10 && state(owner).tasks().contexts().isEmpty(); i++) owner.step(LIMITS, true);
        assertEquals(2, state(owner).tasks().contexts().size());
        assertEquals(FlowRuntime.Status.READY, state(owner).state().status());
        assertEquals(new DataValue.IntegerValue(0), state(owner).memory().cells().field("count"));
        for (int i = 0; i < 24; i++) owner.step(LIMITS, true);
        assertEquals(new DataValue.IntegerValue(3), state(owner).memory().cells().field("count"));
        assertTrue(state(owner).tasks().contexts().isEmpty());
    }

    @Test void insufficientSlotsDoNotStartAPartialBroadcast() {
        var tight = limits(2);
        var owner = install("EVENT.POST", tight);
        for (int i = 0; i < 16; i++) owner.step(tight, true);
        assertEquals(FlowRuntime.Status.FAILED, state(owner).state().status());
        assertEquals(ComputationException.Code.BUSY, state(owner).state().diagnostic().orElseThrow().code());
        assertEquals(new DataValue.IntegerValue(0), state(owner).memory().cells().field("count"));
        assertTrue(state(owner).tasks().slots().entries().isEmpty());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(FlowProgram.Compilation.class)
    void repeatedBroadcastsRetireTheirContextsAndSurviveEveryWaitingCheckpoint(FlowProgram.Compilation policy) {
        var base = program("EVENT.POST.WAIT");
        var steps = new ArrayList<FlowProgram.Step>();
        steps.add(new FlowProgram.Repeat("repeat", new FlowInput.Literal(new DataValue.IntegerValue(12))));
        steps.add(new FlowProgram.Broadcast("dispatch", "refresh", true));
        steps.add(new FlowProgram.EndRepeat("repeat_end"));
        steps.addAll(base.steps().subList(1, base.steps().size()));
        var p = new FlowProgram(Map.of(), steps, base.expressions(), FlowProgram.Execution.REGISTER_V1, policy);
        var owner = new ComputerBlockHost(() -> {});
        owner.installFlow(p, EMPTY, LIMITS, true);
        var initial = owner.flowSnapshot().orElseThrow();
        long restoredWork = -1;
        int restoredBatches = 0;
        for (int i = 0; i < 1200 && state(owner).state().status() != FlowRuntime.Status.COMPLETE; i++) {
            owner.step(LIMITS, true);
            var now = state(owner);
            assertNotEquals(FlowRuntime.Status.FAILED, now.state().status(), now.state().diagnostic().toString());
            assertTrue(now.tasks().slots().entries().size() <= 2);
            if (now.state().status() == FlowRuntime.Status.WAITING_TASK && now.state().work() != restoredWork) {
                restoredWork = now.state().work(); restoredBatches++;
                var restored = new ComputerBlockHost(() -> {});
                restored.read(owner.write()); restored.tick(LIMITS,true);
                assertTrue(restored.loaded(), restored.diagnostic().toString());
                assertEquals(now,state(restored)); owner=restored;
            }
        }
        assertEquals(12,restoredBatches);
        assertEquals(FlowRuntime.Status.COMPLETE,state(owner).state().status());
        assertEquals(new DataValue.IntegerValue(36),state(owner).memory().cells().field("count"));
        assertTrue(state(owner).tasks().contexts().isEmpty());
        assertTrue(state(owner).tasks().slots().entries().stream().allMatch(FlowTaskSlots.Slot::retired));
        assertEquals(initial.program(),state(owner).program());
    }

    @Test void failedReceiverRemainsInspectableButDoesNotHoldItsSenderForever() {
        var key = new ModuleDefinition.Key("failing_receiver",1);
        var p = new FlowProgram(Map.of(),List.of(new FlowProgram.Broadcast("post","failure",true),
            new FlowProgram.Procedure("receiver",key,Map.of(),Map.of(),Map.of(),FlowBlockRules.NONE,Optional.empty(),Optional.of("failure")),
            new FlowProgram.Sleep("invalid_sleep",new FlowInput.Literal(new DataValue.IntegerValue(-1))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var owner = new ComputerBlockHost(() -> {}); owner.installFlow(p,EMPTY,LIMITS,true);
        for(int i=0;i<12 && state(owner).state().status()!=FlowRuntime.Status.COMPLETE;i++)owner.step(LIMITS,true);
        assertEquals(FlowRuntime.Status.COMPLETE,state(owner).state().status());
        assertEquals(1,state(owner).tasks().contexts().size());
        assertEquals(FlowRuntime.Status.FAILED,state(owner).tasks().contexts().getFirst().status());
        var restored = new ComputerBlockHost(() -> {}); restored.read(owner.write()); restored.tick(LIMITS,true);
        assertTrue(restored.loaded(),restored.diagnostic().toString()); assertEquals(state(owner),state(restored));
    }

    @Test void noReceiverFinishesWithoutAHiddenTaskAndAtomicBroadcastIsRejected() {
        var owner=new ComputerBlockHost(() -> {});
        owner.installFlow(new FlowProgram(Map.of(),List.of(new FlowProgram.Broadcast("post","absent",true))).withAssembly(),EMPTY,LIMITS,true);
        owner.step(LIMITS,true);
        assertEquals(FlowRuntime.Status.COMPLETE,state(owner).state().status());
        assertTrue(state(owner).tasks().slots().entries().isEmpty());
        for(boolean wait:List.of(false,true)) {
            var bad=new FlowProgram(Map.of(),List.of(new FlowProgram.Atomic("begin","result"),
                new FlowProgram.Broadcast("post","absent",wait),new FlowProgram.EndAtomic("end"))).withAssembly();
            assertThrows(ComputationException.class,()->bad.validate(LIMITS.hardware()));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(FlowProgram.Compilation.class)
    void eventDocumentsRoundTripWithoutTurningReceiversIntoOrdinaryProcedures(FlowProgram.Compilation policy) {
        var p=program("EVENT.POST.WAIT");
        var body=com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody.fromProgram(
            new FlowProgram(p.inputs(),p.steps(),p.expressions(),FlowProgram.Execution.REGISTER_V1,policy),EMPTY);
        var document=new com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocument(p.expressions(),Map.of(),Optional.empty(),Optional.of(body));
        assertEquals(document,com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.decode(
            com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.encode(document)));
    }

}
