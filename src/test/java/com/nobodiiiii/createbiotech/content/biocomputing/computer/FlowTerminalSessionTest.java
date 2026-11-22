package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.junit.jupiter.api.*;

class FlowTerminalSessionTest {
    static final ModuleDefinition.Key COPY = new ModuleDefinition.Key("wait_copy", 1);
    static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());
    static final ComputerHostLimits LIMITS = new ComputerHostLimits(new ResourceLimits(1000, 100_000, 4),
        new ArchiveLimits(100000, 1000000, 10000000), com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT,
        HardwarePins.DEFAULT, SerialHardware.NONE, 2);
    @BeforeAll static void bootstrap() throws Exception { TerminalBehaviourTest.bootstrap(); }
    static DataValue.RecordValue value(long value) { return new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(value))); }
    static ProgramDocument document() {
        var types = Map.<String, DataType>of("value", DataType.Scalar.INTEGER);
        var p = new FlowProgram(types, List.of(new FlowProgram.Sleep("main_wait", "value"),
            new FlowProgram.Procedure("definition", COPY, types, Map.of("result", DataType.Scalar.INTEGER), Map.of("result", "value")),
            new FlowProgram.Sleep("task_wait", "value"), new FlowProgram.EndProcedure("return")));
        var button = new TerminalDefinition.Button(new TerminalBinding.Literal(new DataValue.TextValue("Start")),
            new TerminalBinding.Literal(new DataValue.BooleanValue(true)), new TerminalTarget.Task(COPY), new TerminalBinding.Literal(value(1)));
        return new ProgramDocument(p.expressions(), Map.of(), Optional.of(new TerminalDefinition("start", Map.of("start", button))),
            Optional.of(FlowDocumentBody.fromProgram(p, value(100))));
    }
    static ProgramDocument document(FlowProgram.Execution execution) {
        var source = document(); var body = source.flow().orElseThrow();
        return new ProgramDocument(source.archive(), Map.of(), source.terminal(), Optional.of(new FlowDocumentBody(
            body.inputs(), body.steps(), body.initial(), body.canvas(), body.inline(), execution)));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(FlowProgram.Execution.class)
    void aPublishedTerminalButtonStartsAPinnedFlowTaskWithoutReplacingOrWakingMain(FlowProgram.Execution execution) {
        var computer = new Harness().getBehaviour(ComputerBehaviour.TYPE);
        var document = document(execution); computer.installDocument(document, ComputerRuntime.MemoryPolicy.RESET);
        computer.step(); computer.setPaused(false); var sleeping = computer.flowSnapshot().orElseThrow().state();
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce); long revision = computer.programRevision();
        var read = session.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, computer).orElseThrow();
        assertEquals("ok", read.code());
        var click = session.apply(nonce, 2, revision, ComputerControlSession.Action.TERMINAL_CLICK,
            () -> new TerminalClick(1, "5:start").encode(), computer).orElseThrow();
        assertEquals("ok", click.code()); assertEquals(sleeping, computer.flowSnapshot().orElseThrow().state());
        assertTrue(computer.snapshot().isEmpty(), "No legacy event runtime is enabled alongside Flow");
        assertEquals(1, computer.flowSnapshot().orElseThrow().tasks().contexts().size());
        assertEquals(Optional.of(COPY), computer.flowSnapshot().orElseThrow().tasks().contexts().getFirst().entry());
        assertEquals("invalid", session.apply(nonce, 3, revision, ComputerControlSession.Action.TERMINAL_CLICK,
            () -> new TerminalClick(1, "5:start").encode(), computer).orElseThrow().code());
        computer.tick(); computer.tick(); computer.tick();
        assertEquals(FlowRuntime.Status.COMPLETE, computer.flowSnapshot().orElseThrow().tasks().contexts().getFirst().status());
        assertEquals(FlowRuntime.Status.SLEEPING, computer.flowSnapshot().orElseThrow().state().status());
        assertEquals(document, computer.document().orElseThrow());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(FlowProgram.Execution.class)
    void taskResultsAreObservableWithoutBeingConsumedAndFullSlotsReturnExplicitFeedback(FlowProgram.Execution execution) {
        var computer = new Harness().getBehaviour(ComputerBehaviour.TYPE); computer.installDocument(document(execution), ComputerRuntime.MemoryPolicy.RESET);
        computer.step(); computer.setPaused(false); var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce); long revision = computer.programRevision();
        session.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, computer);
        session.apply(nonce, 2, revision, ComputerControlSession.Action.TERMINAL_CLICK, () -> new TerminalClick(1, "5:start").encode(), computer);
        computer.tick(); computer.tick(); computer.tick();
        var before = computer.flowSnapshot().orElseThrow();
        var read = session.apply(nonce, 3, revision, ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, computer).orElseThrow();
        var observed = TerminalStatusView.decode(read.dedicatedStatus().orElseThrow());
        var tasks = (DataValue.ListValue)observed.roots().field("tasks"); assertEquals(1, tasks.values().size());
        var row = (DataValue.RecordValue)tasks.values().getFirst();
        assertEquals(new DataValue.TextValue("COMPLETE"), row.field("phase"));
        assertEquals(new DataValue.RecordValue(Map.of("result", new DataValue.IntegerValue(1))), row.field("result"));
        assertFalse(row.fields().containsKey("locals")); assertFalse(row.fields().containsKey("calls"));
        assertEquals(before, computer.flowSnapshot().orElseThrow(), "Reading a terminal does not claim a result or free its slot");
        var busy = session.apply(nonce, 4, revision, ComputerControlSession.Action.TERMINAL_CLICK,
            () -> new TerminalClick(3, "5:start").encode(), computer).orElseThrow();
        assertEquals("busy", busy.code()); assertEquals(before, computer.flowSnapshot().orElseThrow());
        assertDoesNotThrow(() -> ProgramStatusResponse.fromAnswer(0, nonce, 4, busy, LIMITS.archive()));
    }
    @Test void fixedTargetAndTerminalSchemaCannotBeDowngradedOrResolvedByAnImplicitMatchingName() {
        var document = document(); var encoded = ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(21), encoded.field("version")); assertEquals(document, ProgramDocumentCodec.decode(encoded));
        var old = new LinkedHashMap<>(encoded.fields()); old.put("version", new DataValue.IntegerValue(20));
        assertThrows(ArchiveException.class, () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(old)));
        var terminal = TerminalCodec.encode(document.terminal().orElseThrow()); assertEquals(new DataValue.IntegerValue(4), terminal.field("version"));
        var down = new LinkedHashMap<>(terminal.fields()); down.put("version", new DataValue.IntegerValue(3));
        assertThrows(ArchiveException.class, () -> TerminalCodec.decode(new DataValue.RecordValue(down)));
        var button = (TerminalDefinition.Button)document.terminal().orElseThrow().nodes().get("start");
        for (var target : List.of(new TerminalTarget.Event(COPY.id()), new TerminalTarget.Task(new ModuleDefinition.Key(COPY.id(), 2)))) {
            var wrong = new TerminalDefinition("start", Map.of("start", new TerminalDefinition.Button(button.title(), button.enabled(), target, button.inputs())));
            assertThrows(IllegalArgumentException.class, () -> new ProgramDocument(document.archive(), Map.of(), Optional.of(wrong), document.flow()).validate(LIMITS.hardware()));
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(FlowProgram.Execution.class)
    void claimingAResultUsesThePublishedLocalRowNotAnEditableUuidAndCannotReplayAgainstANewTask(FlowProgram.Execution execution) {
        var computer = new Harness().getBehaviour(ComputerBehaviour.TYPE); computer.installDocument(document(execution), ComputerRuntime.MemoryPolicy.RESET);
        computer.step(); computer.setPaused(false); var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce); long revision = computer.programRevision();
        session.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, computer);
        session.apply(nonce, 2, revision, ComputerControlSession.Action.TERMINAL_CLICK, () -> new TerminalClick(1, "5:start").encode(), computer);
        computer.tick(); computer.tick(); computer.tick();
        session.apply(nonce, 3, revision, ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, computer);
        assertEquals("ok", session.apply(nonce, 4, revision, ComputerControlSession.Action.TERMINAL_CLAIM,
            () -> new TerminalTaskClaim(3, 0).encode(), computer).orElseThrow().code());
        assertTrue(computer.flowSnapshot().orElseThrow().tasks().contexts().isEmpty());
        session.apply(nonce, 5, revision, ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, computer);
        session.apply(nonce, 6, revision, ComputerControlSession.Action.TERMINAL_CLICK, () -> new TerminalClick(5, "5:start").encode(), computer);
        var next = computer.flowSnapshot().orElseThrow();
        assertEquals("invalid", session.apply(nonce, 7, revision, ComputerControlSession.Action.TERMINAL_CLAIM,
            () -> new TerminalTaskClaim(3, 0).encode(), computer).orElseThrow().code());
        assertEquals(next, computer.flowSnapshot().orElseThrow());
    }
    @Test void pauseAndArgumentTypeFailuresRejectWithoutChangingEitherExecution() {
        for(boolean paused:List.of(true,false)) {
            var document=document();
            if(!paused) {
                var original=(TerminalDefinition.Button)document.terminal().orElseThrow().nodes().get("start");
                var bad=new TerminalDefinition.Button(original.title(),original.enabled(),original.target(),
                    new TerminalBinding.Literal(new DataValue.RecordValue(Map.of("value",new DataValue.TextValue("1")))));
                document=new ProgramDocument(document.archive(),Map.of(),Optional.of(new TerminalDefinition("start",Map.of("start",bad))),document.flow());
            }
            var computer=new Harness().getBehaviour(ComputerBehaviour.TYPE); computer.installDocument(document,ComputerRuntime.MemoryPolicy.RESET);
            computer.step(); computer.setPaused(paused); var before=computer.flowSnapshot().orElseThrow();
            var nonce=UUID.randomUUID(); var session=new ComputerControlSession(nonce); long revision=computer.programRevision();
            session.apply(nonce,1,revision,ComputerControlSession.Action.READ_TERMINAL,()->EMPTY,computer);
            assertEquals(paused?"busy":"type_mismatch",session.apply(nonce,2,revision,ComputerControlSession.Action.TERMINAL_CLICK,
                ()->new TerminalClick(1,"5:start").encode(),computer).orElseThrow().code());
            assertEquals(before,computer.flowSnapshot().orElseThrow()); assertTrue(computer.diagnostic().isEmpty());
        }
    }
    @Test void submittedTextIsBoundToAnExplicitTaskArgumentAndDoesNotOverwriteMainLocals() {
        var types=Map.<String,DataType>of("query",DataType.Scalar.TEXT); var key=new ModuleDefinition.Key("echo",1);
        var mainTypes=Map.<String,DataType>of("value",DataType.Scalar.INTEGER);
        var program=new FlowProgram(mainTypes,List.of(new FlowProgram.Sleep("main","value"),
            new FlowProgram.Procedure("definition",key,types,types,Map.of("query","query")),new FlowProgram.EndProcedure("return")));
        var input=new TerminalDefinition.Input(new TerminalBinding.Literal(new DataValue.TextValue("")),
            new TerminalBinding.Literal(new DataValue.BooleanValue(true)),new TerminalTarget.Task(key),"query",new TerminalBinding.Literal(EMPTY));
        var document=new ProgramDocument(program.expressions(),Map.of(),Optional.of(new TerminalDefinition("input",Map.of("input",input))),Optional.of(FlowDocumentBody.fromProgram(program,value(100))));
        var computer=new Harness().getBehaviour(ComputerBehaviour.TYPE); computer.installDocument(document,ComputerRuntime.MemoryPolicy.RESET);
        computer.step(); computer.setPaused(false); var nonce=UUID.randomUUID(); var session=new ComputerControlSession(nonce);
        session.apply(nonce,1,computer.programRevision(),ComputerControlSession.Action.READ_TERMINAL,()->EMPTY,computer);
        assertEquals("ok",session.apply(nonce,2,computer.programRevision(),ComputerControlSession.Action.TERMINAL_SUBMIT,
            ()->new TerminalSubmit(1,"5:input","中文 input").encode(),computer).orElseThrow().code());
        computer.tick(); var saved=computer.flowSnapshot().orElseThrow();
        assertEquals(value(100),saved.state().locals());
        assertEquals(new DataValue.RecordValue(Map.of("query",new DataValue.TextValue("中文 input"))),saved.tasks().contexts().getFirst().locals());
        assertEquals(FlowRuntime.Status.COMPLETE,saved.tasks().contexts().getFirst().status());
    }
    static ProgramDocument taskStartWithoutLayout() {
        var types = Map.<String, DataType>of("value", DataType.Scalar.INTEGER);
        var program = new FlowProgram(types, List.of(
            new FlowProgram.TaskStart("launch", COPY, Map.of("value", "value"), "ticket"),
            new FlowProgram.Procedure("definition", COPY, types, Map.of("result", DataType.Scalar.INTEGER), Map.of("result", "value")),
            new FlowProgram.Sleep("task_wait", "value"), new FlowProgram.EndProcedure("return"))).withAssembly();
        return new ProgramDocument(program.expressions(), Map.of(), Optional.empty(),
            Optional.of(FlowDocumentBody.fromProgram(program, value(0))));
    }
    static ComputerBehaviour completedTaskWithoutLayout() {
        var computer = new Harness().getBehaviour(ComputerBehaviour.TYPE);
        computer.installDocument(taskStartWithoutLayout(), ComputerRuntime.MemoryPolicy.RESET);
        for (int i = 0; i < 12; i++) computer.step();
        assertEquals(FlowRuntime.Status.COMPLETE, computer.flowSnapshot().orElseThrow().tasks().contexts().getFirst().status());
        return computer;
    }
    @Test void scratchTaskStartResultCanBeClaimedFromAPublishedTaskListWithoutTerminalLayout() {
        var computer = completedTaskWithoutLayout();
        var before = computer.flowSnapshot().orElseThrow();
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce); long revision = computer.programRevision();
        var read = session.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, computer).orElseThrow();
        assertEquals("ok", read.code());
        var status = TerminalStatusView.decode(read.dedicatedStatus().orElseThrow());
        assertTrue(status.terminal().isEmpty());
        var rows = (DataValue.ListValue) status.roots().field("tasks"); assertEquals(1, rows.values().size());
        assertEquals(new DataValue.TextValue("COMPLETE"), ((DataValue.RecordValue) rows.values().getFirst()).field("phase"));
        assertEquals(before, computer.flowSnapshot().orElseThrow(), "Observing a result does not release it");
        var claim = session.apply(nonce, 2, revision, ComputerControlSession.Action.TERMINAL_CLAIM,
            () -> new TerminalTaskClaim(1, 0).encode(), computer).orElseThrow();
        assertEquals("ok", claim.code(), claim.detail());
        var after = computer.flowSnapshot().orElseThrow();
        assertTrue(after.tasks().contexts().isEmpty()); assertEquals(before.state(), after.state());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"nonce", "intent", "view", "refresh", "revision", "program", "index", "negative_index", "forged_uuid", "invalidated", "click", "submit"})
    void layoutFreeTaskPublicationKeepsItsFencesAndGrantsNoUndefinedControls(String rejection) {
        var computer = completedTaskWithoutLayout();
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce); long revision = computer.programRevision();
        session.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, computer).orElseThrow();
        if (rejection.equals("refresh")) session.apply(nonce, 2, revision, ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, computer).orElseThrow();
        if (rejection.equals("program")) {
            computer.reset();
            computer.installDocument(taskStartWithoutLayout(), ComputerRuntime.MemoryPolicy.RESET);
            for (int i = 0; i < 12; i++) computer.step();
            assertNotEquals(revision, computer.programRevision());
        }
        if (rejection.equals("invalidated")) session.invalidateTerminalPublication();
        var before = computer.flowSnapshot().orElseThrow();
        var payload = new LinkedHashMap<>(new TerminalTaskClaim(rejection.equals("view") ? 99 : 1, rejection.equals("index") ? 1 : 0).encode().fields());
        if (rejection.equals("negative_index")) payload.put("index", new DataValue.IntegerValue(-1));
        if (rejection.equals("forged_uuid")) payload.put("handle", new DataValue.TextValue(UUID.randomUUID().toString()));
        var action = rejection.equals("click") ? ComputerControlSession.Action.TERMINAL_CLICK
            : rejection.equals("submit") ? ComputerControlSession.Action.TERMINAL_SUBMIT : ComputerControlSession.Action.TERMINAL_CLAIM;
        var input = rejection.equals("click") ? new TerminalClick(1, "5:start").encode()
            : rejection.equals("submit") ? new TerminalSubmit(1, "5:input", "forged").encode() : new DataValue.RecordValue(payload);
        boolean ignored = rejection.equals("nonce") || rejection.equals("intent");
        var answer = session.apply(rejection.equals("nonce") ? UUID.randomUUID() : nonce, rejection.equals("intent") ? 1 : 3,
            rejection.equals("revision") ? revision - 1 : computer.programRevision(), action,
            () -> { if (ignored || rejection.equals("revision")) fail("Rejected envelope must not decode inputs"); return input; }, computer);
        if (ignored) assertTrue(answer.isEmpty());
        else assertEquals(rejection.equals("revision") || rejection.equals("program") ? "stale" : "invalid", answer.orElseThrow().code());
        assertEquals(before, computer.flowSnapshot().orElseThrow());
        var retry = session.apply(nonce, 4, computer.programRevision(), ComputerControlSession.Action.TERMINAL_CLAIM,
            () -> new TerminalTaskClaim(1, 0).encode(), computer).orElseThrow();
        assertEquals(ignored ? "ok" : "invalid", retry.code(), "Accepted attempts consume even a rejected publication; foreign/replayed envelopes do not");
        if (!ignored) {
            assertEquals(before, computer.flowSnapshot().orElseThrow());
            session.apply(nonce, 5, computer.programRevision(), ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, computer).orElseThrow();
            assertEquals("ok", session.apply(nonce, 6, computer.programRevision(), ComputerControlSession.Action.TERMINAL_CLAIM,
                () -> new TerminalTaskClaim(5, 0).encode(), computer).orElseThrow().code());
        }
        assertTrue(computer.flowSnapshot().orElseThrow().tasks().contexts().isEmpty());
        assertEquals("invalid", session.apply(nonce, 7, computer.programRevision(), ComputerControlSession.Action.TERMINAL_CLAIM,
            () -> new TerminalTaskClaim(5, 0).encode(), computer).orElseThrow().code());
    }
    @Test void layoutFreeClaimStillRequiresAvailableComputerAndConsumesDeniedPublication() {
        var harness = new Harness(); var computer = harness.getBehaviour(ComputerBehaviour.TYPE);
        computer.installDocument(taskStartWithoutLayout(), ComputerRuntime.MemoryPolicy.RESET);
        for (int i = 0; i < 12; i++) computer.step();
        var before = computer.flowSnapshot().orElseThrow();
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce); long revision = computer.programRevision();
        session.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, computer).orElseThrow();
        harness.available = false;
        assertEquals("unavailable", session.apply(nonce, 2, revision, ComputerControlSession.Action.TERMINAL_CLAIM,
            () -> new TerminalTaskClaim(1, 0).encode(), computer).orElseThrow().code());
        harness.available = true;
        assertEquals(before, computer.flowSnapshot().orElseThrow());
        assertEquals("invalid", session.apply(nonce, 3, revision, ComputerControlSession.Action.TERMINAL_CLAIM,
            () -> new TerminalTaskClaim(1, 0).encode(), computer).orElseThrow().code());
        session.apply(nonce, 4, revision, ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, computer).orElseThrow();
        assertEquals("ok", session.apply(nonce, 5, revision, ComputerControlSession.Action.TERMINAL_CLAIM,
            () -> new TerminalTaskClaim(4, 0).encode(), computer).orElseThrow().code());
    }
    @Test void keyIntentCannotUseAnotherMenusPublicationOrConsumeItTwice() {
        var computer = new Harness().getBehaviour(ComputerBehaviour.TYPE);
        computer.installDocument(document(FlowProgram.Execution.REGISTER_V1), ComputerRuntime.MemoryPolicy.RESET);
        computer.setPaused(false);
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce);
        var action = ComputerControlSession.Action.TERMINAL_KEY;
        long revision = computer.programRevision();
        var key = new DataValue.RecordValue(Map.of("view", new DataValue.IntegerValue(2), "key", new DataValue.TextValue("a")));
        assertEquals("invalid", session.apply(nonce, 1, revision, action, () -> key, computer).orElseThrow().code());
        session.apply(nonce, 2, revision, ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, computer);
        var missing = session.apply(nonce, 3, revision, action, () -> key, computer).orElseThrow();
        assertEquals("no_listener", ProgramStatusResponse.fromAnswer(0, nonce, 3, missing, LIMITS.archive()).code());
        assertEquals("invalid", session.apply(nonce, 4, revision, action, () -> key, computer).orElseThrow().code());
        assertTrue(computer.flowSnapshot().orElseThrow().tasks().contexts().isEmpty());
    }
    @Test void exactAndAnyKeyHatsAreAdmittedTogetherAndDoNotRestartOnAnotherPress() {
        var exact = new ModuleDefinition.Key("exact_key", 1);
        var any = new ModuleDefinition.Key("any_key", 1);
        var p = new FlowProgram(Map.of(), List.of(
            new FlowProgram.Sleep("main_wait", new FlowInput.Literal(new DataValue.IntegerValue(1000))),
            FlowProgram.Procedure.keyHat("a_hat", exact, "a"),
            new FlowProgram.Sleep("a_wait", new FlowInput.Literal(new DataValue.IntegerValue(1000))),
            new FlowProgram.EndProcedure("a_end"),
            FlowProgram.Procedure.keyHat("any_hat", any, "any"),
            new FlowProgram.EndProcedure("any_end"))).withAssembly();
        var document = new ProgramDocument(p.expressions(), Map.of(), Optional.empty(), Optional.of(FlowDocumentBody.fromProgram(p, EMPTY)));
        assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        var harness = new Harness(); harness.taskSlots = 3;
        var computer = harness.getBehaviour(ComputerBehaviour.TYPE);
        computer.installDocument(document, ComputerRuntime.MemoryPolicy.RESET);
        assertThrows(ComputationException.class, () -> computer.pressTerminalKey("a"));
        computer.setPaused(false); computer.tick();
        var main = computer.flowSnapshot().orElseThrow().state();
        assertTrue(TerminalStatusView.decode(computer.terminalStatus()).keyboard());
        assertEquals("ok", computer.pressTerminalKey("a"));
        var launched = computer.flowSnapshot().orElseThrow();
        assertEquals(main, launched.state());
        assertEquals(Set.of(exact, any), launched.tasks().contexts().stream()
            .map(task -> task.entry().orElseThrow()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(launched, FlowBlockNbtCodec.decode(FlowBlockNbtCodec.encode(launched, LIMITS.archive()), LIMITS.archive()));
        assertEquals("already_running", computer.pressTerminalKey("a"));
        assertEquals(launched, computer.flowSnapshot().orElseThrow());
        for (int i = 0; i < 20; i++) computer.tick();
        assertEquals(List.of(Optional.of(exact)), computer.flowSnapshot().orElseThrow().tasks().contexts().stream().map(FlowRuntime.Checkpoint::entry).toList());
        assertEquals("ok", computer.pressTerminalKey("b"));
        assertEquals(Optional.of(any), computer.flowSnapshot().orElseThrow().tasks().contexts().getLast().entry());
        var small = new Harness().getBehaviour(ComputerBehaviour.TYPE);
        small.installDocument(document, ComputerRuntime.MemoryPolicy.RESET); small.setPaused(false);
        var before = small.flowSnapshot().orElseThrow();
        assertEquals(ComputationException.Code.BUSY, assertThrows(ComputationException.class, () -> small.pressTerminalKey("a")).code());
        assertEquals(before, small.flowSnapshot().orElseThrow());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"paused","unavailable","revision","view","extra","any","nonce","intent"})
    void rejectedKeyCannotLaunchTasksAndAcceptedEnvelopeConsumesPublication(String rejection) {
        var harness=new Harness();harness.taskSlots=3;
        var computer=harness.getBehaviour(ComputerBehaviour.TYPE);
        var p=new FlowProgram(Map.of(),List.of(FlowProgram.Procedure.keyHat("hat",new ModuleDefinition.Key("key_test",1),"a"),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        computer.installDocument(new ProgramDocument(p.expressions(),Map.of(),Optional.empty(),Optional.of(FlowDocumentBody.fromProgram(p,EMPTY))),ComputerRuntime.MemoryPolicy.RESET);
        computer.setPaused(false);
        var nonce=UUID.randomUUID();var session=new ComputerControlSession(nonce);long revision=computer.programRevision();
        session.apply(nonce,1,revision,ComputerControlSession.Action.READ_TERMINAL,()->EMPTY,computer).orElseThrow();
        if(rejection.equals("paused"))computer.setPaused(true);
        if(rejection.equals("unavailable"))harness.available=false;
        var before=computer.flowSnapshot().orElseThrow();
        var fields=new LinkedHashMap<String,DataValue>();fields.put("view",new DataValue.IntegerValue(rejection.equals("view")?99:1));
        fields.put("key",new DataValue.TextValue(rejection.equals("any")?"any":"a"));
        if(rejection.equals("extra"))fields.put("entry",new DataValue.TextValue("forged"));
        boolean ignored=rejection.equals("nonce")||rejection.equals("intent");
        var answer=session.apply(rejection.equals("nonce")?UUID.randomUUID():nonce,rejection.equals("intent")?1:2,
            rejection.equals("revision")?revision-1:revision,ComputerControlSession.Action.TERMINAL_KEY,
            ()->{if(ignored||rejection.equals("revision"))fail("Rejected envelope must not decode");return new DataValue.RecordValue(fields);},computer);
        if(ignored)assertTrue(answer.isEmpty());
        else assertEquals(switch(rejection){case "paused"->"busy";case "unavailable"->"unavailable";case "revision"->"stale";default->"invalid";},answer.orElseThrow().code());
        assertEquals(before,computer.flowSnapshot().orElseThrow());
        harness.available=true;computer.setPaused(false);
        var key=new DataValue.RecordValue(Map.of("view",new DataValue.IntegerValue(1),"key",new DataValue.TextValue("a")));
        assertEquals(ignored?"ok":"invalid",session.apply(nonce,3,revision,ComputerControlSession.Action.TERMINAL_KEY,()->key,computer).orElseThrow().code());
        assertEquals(ignored?1:0,computer.flowSnapshot().orElseThrow().tasks().contexts().size());
    }
    static final class Harness extends SmartBlockEntity {
        boolean available = true;
        int taskSlots = LIMITS.taskSlots();
        Harness() { super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState()); }
        @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
            behaviours.add(new ComputerBehaviour(this, () -> available, () -> new HardwareSelection(HardwareSelection.Status.READY,
                Optional.empty(), "test", new ComputerHostLimits(LIMITS.hardware(),LIMITS.archive(),LIMITS.mailbox(),LIMITS.pins(),LIMITS.serial(),taskSlots))));
        }
        @Override public void setChanged() {}
    }
}
