package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.*;

/** Real ownership/continuation proof; not a two-device exchange or a player block-editor acceptance test. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingFlowOwnerGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void preparedLocalTransactionSurvivesPhysicalReloadAndPublishesToItsOwnTerminal(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var block = BiocomputingWorldGameTests.computer(helper, pos); var host = BiocomputingWorldGameTests.host(block);
        var neighbor = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(5, 2, 2))));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("counter", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0))), List.of()), Map.of());
        var steps = List.<FlowProgram.Step>of(new FlowProgram.Memory("begin", FlowProgram.MemoryKind.BEGIN, "", ""),
            new FlowProgram.Memory("write", FlowProgram.MemoryKind.WRITE, "counter", "value"),
            new FlowProgram.Memory("commit", FlowProgram.MemoryKind.COMMIT, "", "result"),
            new FlowProgram.Memory("again", FlowProgram.MemoryKind.BEGIN, "", ""),
            new FlowProgram.Memory("read", FlowProgram.MemoryKind.READ, "counter", "current"),
            new FlowProgram.Memory("end", FlowProgram.MemoryKind.ROLLBACK, "", ""));
        var program = new FlowProgram(Map.of("value", DataType.Scalar.INTEGER), steps, archive).withAssembly();
        var inputs = new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(42)));
        host.installFlow(program, inputs); neighbor.installFlow(program, inputs);
        var hardware = host.hardware().limits(); var other = neighbor.flowSnapshot().orElseThrow();
        for (int i = 0; i < 4; i++) host.step();
        var checkpoint = host.flowSnapshot().orElseThrow();
        helper.assertTrue(checkpoint.memory().preparing(checkpoint.state().flow()), "Prepared commit is still private and paid stepwise");
        helper.assertValueEqual(checkpoint.memory().cells().field("counter"), new DataValue.IntegerValue(0), "No staged value leaks before publish");
        var nbt = block.saveWithFullMetadata(level.registryAccess()); var state = block.getBlockState(); level.removeBlockEntity(pos);
        var reloadedBlock = (ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos, state, nbt, level.registryAccess()));
        level.setBlockEntity(reloadedBlock); var reloaded = BiocomputingWorldGameTests.host(reloadedBlock);
        helper.runAfterDelay(3, () -> {
            helper.assertValueEqual(reloaded.flowSnapshot().orElseThrow(), checkpoint, "Pause preserves the precise transaction and instruction");
            helper.assertValueEqual(reloaded.hardware().limits(), hardware, "No hidden task or Cluster RAM is added");
            reloaded.setPaused(false);
            helper.runAfterDelay(4, () -> {
                var result = reloaded.flowSnapshot().orElseThrow();
                helper.assertValueEqual(result.state().status(), FlowRuntime.Status.COMPLETE, "Physical coordinator completes the ISA program");
                helper.assertValueEqual(result.state().locals().field("current"), new DataValue.IntegerValue(42), "The next transaction sees the committed value");
                helper.assertValueEqual(TerminalStatusView.decode(reloaded.terminalStatus()).roots().field("memory"), result.memory().cells(), "Public terminal binding sees only the committed bank");
                helper.assertValueEqual(neighbor.flowSnapshot().orElseThrow(), other, "Neighbor program, RAM, pause and budget stay independent");
                helper.assertTrue(result.memory().owners().isEmpty(), "No transaction remains after completion"); helper.succeed();
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void structuredAssemblyTextScanResumesAfterPhysicalReloadWithoutAdvancingAnotherResident(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var block = BiocomputingWorldGameTests.computer(helper, pos); var host = BiocomputingWorldGameTests.host(block);
        var neighbor = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(5, 2, 2)),
            net.minecraft.world.entity.npc.VillagerProfession.FLETCHER, 3));
        var item = new DataType.RecordType(Map.of("name", DataType.Scalar.TEXT));
        var sample = new DataType.RecordType(Map.of("items", new DataType.ListType(item)));
        var namedItem = new DataValue.RecordValue(Map.of("name", new DataValue.TextValue("A😀".repeat(10))));
        var input = new DataValue.RecordValue(Map.of("sample", new DataValue.RecordValue(Map.of("items", new DataValue.ListValue(List.of(namedItem))))));
        var graph = new ComputationGraph(Map.of("source", sample), Map.of(
            "source", new ComputationGraph.Input("source"), "items", new ComputationGraph.Field("source", "items"),
            "first", new ComputationGraph.Index("items", 0), "name", new ComputationGraph.Field("first", "name"),
            "length", new ComputationGraph.Text(TextOperation.LENGTH, Map.of("text", "name"))), Map.of("value", "length"));
        var body = new FlowDocumentBody(Map.of("sample", sample), List.of(new FlowProgram.Evaluate("measure", "measure",
            new FlowCallBindings(Map.of("source", "sample"), Map.of("value", "length")))), input,
            FlowBlockLayout.DEFAULT, Map.of("measure", graph), FlowDocumentBody.emptyAssembly().execution());
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(body)); var document = workspace.snapshot();
        var nonce = UUID.randomUUID(); var session = new ProgramEditSession(nonce);
        helper.assertValueEqual(session.submit(nonce, 1, host.programRevision(), host::programRevision,
            () -> host.installDocument(document, ComputerRuntime.MemoryPolicy.RESET)).orElseThrow().code(), "applied", "Public installation accepts typed data");
        neighbor.installDocument(document, ComputerRuntime.MemoryPolicy.RESET); var other = neighbor.flowSnapshot().orElseThrow();
        var hardware = host.hardware().limits();
        helper.assertTrue(hardware.hardware().workPerSlice() != neighbor.hardware().limits().hardware().workPerSlice(), "Residents retain different cycle budgets");
        for (int i = 0; i < 6; i++) host.step();
        var halfway = host.flowSnapshot().orElseThrow();
        helper.assertValueEqual(halfway.state().assembly().orElseThrow().scan().orElseThrow().points(), 2L, "Two code points, including the surrogate pair");
        helper.assertTrue(!halfway.state().locals().fields().containsKey("length"), "No partial result published");
        var saved = block.saveWithFullMetadata(level.registryAccess()); var state = block.getBlockState(); level.removeBlockEntity(pos);
        var replacement = (ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(replacement); var restored = BiocomputingWorldGameTests.host(replacement);
        helper.runAfterDelay(3, () -> {
            helper.assertValueEqual(restored.flowSnapshot().orElseThrow(), halfway, "Paused scan resumes exactly without restart");
            helper.assertValueEqual(restored.hardware().limits(), hardware, "Hardware belongs to this resident");
            for (int i = 0; i < 40 && restored.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.READY; i++) restored.step();
            var done = restored.flowSnapshot().orElseThrow();
            helper.assertValueEqual(done.state().status(), FlowRuntime.Status.COMPLETE, "Data program completed");
            helper.assertValueEqual(done.state().locals().field("length"), new DataValue.IntegerValue(20), "Unicode length result");
            helper.assertValueEqual(done.state().work(), 26L, "Every scan step charged");
            helper.assertValueEqual(done.initialInputs(), input, "Frozen source is unchanged");
            helper.assertValueEqual(neighbor.flowSnapshot().orElseThrow(), other, "Neighbor RAM and progress are independent");
            helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void scratchAssemblyRepeatAndUntilResumeInsideALoopAfterPhysicalReload(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var block = BiocomputingWorldGameTests.computer(helper, pos, net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN, 2);
        var host = BiocomputingWorldGameTests.host(block);
        var increment = new ComputationGraph(Map.of("value", DataType.Scalar.INTEGER), Map.of(
            "input", new ComputationGraph.Input("value"), "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "input", "one")), Map.of("value", "sum"));
        var finished = new ComputationGraph(Map.of("value", DataType.Scalar.INTEGER), Map.of(
            "input", new ComputationGraph.Input("value"), "four", new ComputationGraph.Constant(new DataValue.IntegerValue(4)),
            "equal", new ComputationGraph.Scalar(ScalarOperation.EQUAL_INTEGER, Map.of("left", "input", "right", "four"))), Map.of("value", "equal"));
        var binding = new FlowCallBindings(Map.of("value", "counter"), Map.of("value", "counter"));
        var body = new FlowDocumentBody(Map.of("counter", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Repeat("repeat", new FlowInput.Literal(new DataValue.IntegerValue(2))),
            new FlowProgram.Evaluate("first", "first", binding), new FlowProgram.EndRepeat("repeat_end"),
            new FlowProgram.Until("until", new FlowInput.Expression("until", "value", Map.of("value", "counter"))),
            new FlowProgram.Evaluate("second", "second", binding), new FlowProgram.EndUntil("until_end")),
            new DataValue.RecordValue(Map.of("counter", new DataValue.IntegerValue(0))), FlowBlockLayout.DEFAULT,
            Map.of("first", increment, "second", increment, "until", finished), FlowProgram.Execution.REGISTER_V1);
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(body));
        var nonce = UUID.randomUUID(); var session = new ProgramEditSession(nonce);
        helper.assertValueEqual(session.submit(nonce, 1, host.programRevision(), host::programRevision,
            () -> host.installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET)).orElseThrow().code(), "applied", "Install compiled loops through the document owner");
        for (int i = 0; i < 30 && host.flowSnapshot().orElseThrow().state().locals().field("counter").equals(new DataValue.IntegerValue(0)); i++) host.step();
        var halfway = host.flowSnapshot().orElseThrow(); var hardware = host.hardware();
        helper.assertValueEqual(halfway.state().locals().field("counter"), new DataValue.IntegerValue(1), "Save after the first body, not at the end");
        helper.assertValueEqual(halfway.state().status(), FlowRuntime.Status.READY, "The Repeat is still active");
        helper.assertTrue(halfway.state().expression().isEmpty() && halfway.state().repeats().equals(FlowRepeatState.EMPTY), "Loop is only register instructions");
        var saved = block.saveWithFullMetadata(level.registryAccess()); var state = block.getBlockState(); level.removeBlockEntity(pos);
        var restoredBlock = (ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(restoredBlock); var restored = BiocomputingWorldGameTests.host(restoredBlock);
        helper.runAfterDelay(3, () -> {
            helper.assertValueEqual(restored.flowSnapshot().orElseThrow(), halfway, "Unloaded paused loop preserves its precise register continuation");
            helper.assertValueEqual(restored.hardware(), hardware, "Resident and hardware remain the owner of this loop");
            for (int i = 0; i < 120 && restored.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.READY; i++) restored.step();
            var done = restored.flowSnapshot().orElseThrow().state();
            helper.assertValueEqual(done.status(), FlowRuntime.Status.COMPLETE, "Both loops terminate after resuming");
            helper.assertValueEqual(done.locals().field("counter"), new DataValue.IntegerValue(4), "Repeat and Until both keep their semantics");
            helper.assertValueEqual(restored.flowStatus().orElseThrow().field("cycles"), new DataValue.IntegerValue(done.work()), "Debugger shows actual instruction cost");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void scratchAssemblyDocumentInstallsStepsAndReloadsWithIndependentResidentResources(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var block = BiocomputingWorldGameTests.computer(helper, pos); var host = BiocomputingWorldGameTests.host(block);
        var neighborBlock = BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(5, 2, 2)),
            net.minecraft.world.entity.npc.VillagerProfession.FLETCHER, 3);
        var neighbor = BiocomputingWorldGameTests.host(neighborBlock);
        var graph = new ComputationGraph(Map.of("input", DataType.Scalar.INTEGER), Map.of(
            "input", new ComputationGraph.Input("input"), "seven", new ComputationGraph.Constant(new DataValue.IntegerValue(7)),
            "sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "input", "seven")), Map.of("value", "sum"));
        var initial = new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(3), "answer", new DataValue.IntegerValue(0)));
        var body = new FlowDocumentBody(Map.of("x", DataType.Scalar.INTEGER, "answer", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.If("choose", new FlowInput.Literal(new DataValue.BooleanValue(true))),
            new FlowProgram.Evaluate("calculate", "calculate", new FlowCallBindings(Map.of("input", "x"), Map.of("value", "answer"))),
            new FlowProgram.EndIf("end")), initial, FlowBlockLayout.DEFAULT, Map.of("calculate", graph), FlowDocumentBody.emptyAssembly().execution());
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(body)); var document = workspace.snapshot();
        var archiveLimits = host.hardware().limits().archive();
        var decoded = ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, archiveLimits), archiveLimits);
        var nonce = UUID.randomUUID(); var session = new ProgramEditSession(nonce);
        helper.assertValueEqual(session.submit(nonce, 1, host.programRevision(), host::programRevision,
            () -> host.installDocument(decoded, ComputerRuntime.MemoryPolicy.RESET)).orElseThrow().code(), "applied", "Public document installation accepted");
        neighbor.installDocument(decoded, ComputerRuntime.MemoryPolicy.RESET);
        var other = neighbor.flowSnapshot().orElseThrow(); var hardware = host.hardware().limits();
        helper.assertTrue(hardware.hardware().workPerSlice() != neighbor.hardware().limits().hardware().workPerSlice(), "Resident profiles retain independent instruction allowances");
        helper.assertTrue(!other.state().computer().equals(host.flowSnapshot().orElseThrow().state().computer()), "Separate owner identities");
        helper.assertTrue(host.flowSnapshot().orElseThrow().state().paused(), "Installation does not execute");
        host.step(); var checkpoint = host.flowSnapshot().orElseThrow();
        helper.assertValueEqual(checkpoint.state().work(), 1L, "Step executes one register instruction");
        helper.assertTrue(checkpoint.state().assembly().isPresent() && checkpoint.state().expression().isEmpty(), "No hidden graph execution frame");
        var saved = block.saveWithFullMetadata(level.registryAccess()); var state = block.getBlockState(); level.removeBlockEntity(pos);
        var restoredBlock = (ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(restoredBlock); var restored = BiocomputingWorldGameTests.host(restoredBlock);
        helper.runAfterDelay(3, () -> {
            helper.assertValueEqual(restored.document().orElseThrow(), decoded, "Source document retains its assembly target");
            helper.assertValueEqual(restored.flowSnapshot().orElseThrow(), checkpoint, "Paused physical reload does not execute or restart");
            helper.assertValueEqual(restored.hardware().limits(), hardware, "Reload does not pool or change resident hardware");
            for (int i = 0; i < 20 && restored.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) restored.step();
            var complete = restored.flowSnapshot().orElseThrow().state();
            helper.assertValueEqual(complete.status(), FlowRuntime.Status.COMPLETE, "Assembly reaches completion");
            helper.assertValueEqual(complete.locals().field("answer"), new DataValue.IntegerValue(10), "Native register result is correct");
            helper.assertValueEqual(restored.flowStatus().orElseThrow().field("cycles"), new DataValue.IntegerValue(complete.work()), "Public debugger exposes actual cycles");
            helper.assertValueEqual(neighbor.flowSnapshot().orElseThrow(), other, "Stepping one resident does not advance another or modify its RAM");
            helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void oppositePinTaskFailsLocallyAndReloadKeepsTheOriginalReader(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var block = BiocomputingWorldGameTests.computer(helper, pos); var host = BiocomputingWorldGameTests.host(block);
        var packet = new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER));
        var key = new ModuleDefinition.Key("send", 1); var empty = new DataValue.RecordValue(Map.of());
        var program = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("main_read", "x0", "received", packet),
            new FlowProgram.Procedure("sender", key, Map.of("packet", packet), Map.of(), Map.of()),
            new FlowProgram.Write("task_send", "x0", "packet"), new FlowProgram.EndProcedure("return")));
        host.installFlow(program, empty); host.step(); var main = host.flowSnapshot().orElseThrow().state();
        var hardware = host.hardware().limits();
        var child = host.startFlowTask(key, new DataValue.RecordValue(Map.of("packet", new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(42))))));
        host.step(); var snapshot = host.flowSnapshot().orElseThrow();
        helper.assertValueEqual(snapshot.state(), main, "Conflicting task cannot change the existing receive");
        helper.assertValueEqual(host.flowTask(child).orElseThrow().status(), FlowRuntime.Status.FAILED, "Reverse request is not silently queued");
        helper.assertValueEqual(host.flowTask(child).orElseThrow().diagnostic().orElseThrow().code(), ComputationException.Code.WRITE_CONFLICT, "Conflict remains explicit");
        helper.assertValueEqual(snapshot.pinClaims().entries().size(), 1, "Only the original direction owns this physical pin");
        var saved = block.saveWithFullMetadata(level.registryAccess()); var state = block.getBlockState(); level.removeBlockEntity(pos);
        var restoredBlock = (ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(restoredBlock); var restored = BiocomputingWorldGameTests.host(restoredBlock);
        helper.runAfterDelay(3, () -> {
            helper.assertValueEqual(restored.flowSnapshot().orElseThrow(), snapshot, "Real save/reload preserves failure and original wait, no retry");
            helper.assertValueEqual(restored.hardware().limits(), hardware, "Task conflict grants no extra resident resources");
            helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void terminalDispatchAndClaimSurviveRealComputerReloadWhileMainSleeps(GameTestHelper helper) {
        terminalTask(helper, false);
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void assemblyTerminalTaskAndResultSurvivePhysicalReload(GameTestHelper helper) {
        terminalTask(helper, true);
    }
    private static void terminalTask(GameTestHelper helper, boolean assembly) {
        var level=helper.getLevel(); var pos=helper.absolutePos(new BlockPos(2,2,2));
        var block=BiocomputingWorldGameTests.computer(helper,pos); var host=BiocomputingWorldGameTests.host(block);
        var type=Map.<String,DataType>of("ticks",DataType.Scalar.INTEGER); var key=new ModuleDefinition.Key("terminal_wait",1);
        var program=new FlowProgram(type,List.of(new FlowProgram.Sleep("main_wait","ticks"),
            new FlowProgram.Procedure("definition",key,type,Map.of("result",DataType.Scalar.INTEGER),Map.of("result","ticks")),
            new FlowProgram.Sleep("child_wait","ticks"),new FlowProgram.EndProcedure("return")));
        if (assembly) program = program.withAssembly();
        var button=new TerminalDefinition.Button(
            new TerminalBinding.Literal(new DataValue.TextValue("Start")),
            new TerminalBinding.Literal(new DataValue.BooleanValue(true)),
            new TerminalTarget.Task(key),
            new TerminalBinding.Literal(new DataValue.RecordValue(Map.of("ticks",new DataValue.IntegerValue(2)))));
        var terminal=new TerminalDefinition("start",Map.of("start",button));
        var document=new ProgramDocument(program.expressions(),Map.of(),Optional.of(terminal),
            Optional.of(FlowDocumentBody.fromProgram(program,new DataValue.RecordValue(Map.of("ticks",new DataValue.IntegerValue(80))))));
        host.installDocument(document,ComputerRuntime.MemoryPolicy.RESET); host.step(); host.setPaused(false);
        var nonce=UUID.randomUUID(); var session=new ComputerControlSession(nonce);
        var empty=new DataValue.RecordValue(Map.of()); long revision=host.programRevision();
        session.apply(nonce,1,revision,ComputerControlSession.Action.READ_TERMINAL,()->empty,host);
        helper.assertValueEqual(session.apply(nonce,2,revision,ComputerControlSession.Action.TERMINAL_CLICK,
            ()->new TerminalClick(1,"5:start").encode(),host).orElseThrow().code(),"ok","Published terminal starts the procedure task");
        host.setPaused(true); var checkpoint=host.flowSnapshot().orElseThrow();
        var saved=block.saveWithFullMetadata(level.registryAccess()); var state=block.getBlockState(); level.removeBlockEntity(pos);
        var restoredBlock=(ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos,state,saved,level.registryAccess()));
        level.setBlockEntity(restoredBlock); var restored=BiocomputingWorldGameTests.host(restoredBlock);
        helper.runAfterDelay(3,()->{
            helper.assertValueEqual(restored.document().orElseThrow(),document,"Task target and editable definition survive together");
            helper.assertValueEqual(restored.flowSnapshot().orElseThrow(),checkpoint,"A paused terminal task does not advance on reload");
            restored.setPaused(false);
            helper.runAfterDelay(8,()->{
                var observed=TerminalStatusView.decode(restored.terminalStatus());
                var row=(DataValue.RecordValue)((DataValue.ListValue)observed.roots().field("tasks")).values().getFirst();
                helper.assertValueEqual(row.field("result"),new DataValue.RecordValue(Map.of("result",new DataValue.IntegerValue(2))),"Real tick result is publicly observable");
                helper.assertValueEqual(restored.flowSnapshot().orElseThrow().state().status(),FlowRuntime.Status.SLEEPING,"Main was not replaced by the terminal event");
                var fresh=UUID.randomUUID(); var menu=new ComputerControlSession(fresh);
                menu.apply(fresh,1,restored.programRevision(),ComputerControlSession.Action.READ_TERMINAL,()->empty,restored);
                helper.assertValueEqual(menu.apply(fresh,2,restored.programRevision(),ComputerControlSession.Action.TERMINAL_CLAIM,
                    ()->new TerminalTaskClaim(1,0).encode(),restored).orElseThrow().code(),"ok","New menu explicitly releases restored result");
                helper.assertTrue(restored.flowSnapshot().orElseThrow().tasks().contexts().isEmpty(),"Finished slot reclaimed without resetting main"); helper.succeed();
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void independentTaskRunsWhileMainSleepsAndReloadRetainsBothUnderTheSameResident(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var block = BiocomputingWorldGameTests.computer(helper, pos); var host = BiocomputingWorldGameTests.host(block);
        var neighbor = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(5, 2, 2))));
        var initial = new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(50)));
        var type = Map.<String, DataType>of("ticks", DataType.Scalar.INTEGER); var key = new ModuleDefinition.Key("task_wait", 1);
        var program = new FlowProgram(type, List.of(new FlowProgram.Sleep("main_wait", "ticks"),
            new FlowProgram.Procedure("definition", key, type, Map.of("result", DataType.Scalar.INTEGER), Map.of("result", "ticks")),
            new FlowProgram.Sleep("child_wait", "ticks"), new FlowProgram.EndProcedure("return")));
        host.installFlow(program, initial); neighbor.installFlow(program, initial);
        var other = neighbor.flowSnapshot().orElseThrow(); var hardware = host.hardware().limits();
        helper.assertTrue(hardware.taskSlots() >= 2, "An ordinary actual resident supports a main and child context");
        host.step();
        UUID child = host.startFlowTask(key, new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(1))));
        host.step(); var paused = host.flowSnapshot().orElseThrow();
        helper.assertValueEqual(paused.tasks().contexts().getFirst().status(), FlowRuntime.Status.SLEEPING, "Child waits independently");
        var saved = block.saveWithFullMetadata(level.registryAccess()); var state = block.getBlockState(); level.removeBlockEntity(pos);
        var restoredBlock = (ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(restoredBlock); var restored = BiocomputingWorldGameTests.host(restoredBlock);
        helper.runAfterDelay(4, () -> {
            helper.assertValueEqual(restored.flowSnapshot().orElseThrow(), paused, "Pause and BE restore preserve all local contexts and scheduler cursor");
            helper.assertValueEqual(restored.hardware().limits(), hardware, "No hidden per-task hardware allocation");
            restored.setPaused(false);
            helper.runAfterDelay(6, () -> {
                var result = restored.flowSnapshot().orElseThrow();
                helper.assertValueEqual(result.state().status(), FlowRuntime.Status.SLEEPING, "Main remains asleep while another task executes");
                var task = restored.flowTask(child).orElseThrow();
                helper.assertValueEqual(task.status(), FlowRuntime.Status.COMPLETE, "Actual world cycle schedules the child");
                helper.assertValueEqual(task.locals().field("result"), new DataValue.IntegerValue(1), "Task returns its own argument copy");
                helper.assertValueEqual(result.state().work() + task.work(), 3L, "Waiting ticks do not create work or extra cores");
                helper.assertValueEqual(neighbor.flowSnapshot().orElseThrow(), other, "Neighbor RAM, program, pause and clock remain independent");
                helper.assertValueEqual(restored.claimFlowTask(child), task, "Explicit claim releases a retained result");
                helper.assertTrue(restored.flowSnapshot().orElseThrow().tasks().contexts().isEmpty(), "Claim releases the child slot");
                helper.succeed();
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void procedureCallerAndPhysicalSignalSurviveSleepingComputerReloadWithoutAdvancingItsNeighbor(GameTestHelper helper) {
        procedureCallerReload(helper, false);
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void assemblyCallerAndSignalResumeFromPrivateFrameAfterPhysicalReload(GameTestHelper helper) {
        procedureCallerReload(helper, true);
    }
    private static void procedureCallerReload(GameTestHelper helper, boolean assembly) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var block = assembly ? BiocomputingWorldGameTests.computer(helper, pos, net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN, 2)
            : BiocomputingWorldGameTests.computer(helper, pos); var host = BiocomputingWorldGameTests.host(block);
        var neighbor = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(5, 2, 2))));
        var initial = new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(3), "level", new DataValue.IntegerValue(300)));
        var types = ((DataType.RecordType)DataType.typeOf(initial)).fields(); var key = new ModuleDefinition.Key("drive_wait_sample", 1);
        var program = new FlowProgram(types, List.of(
            new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of("ticks", "ticks", "level", "level"), Map.of("reading", "answer"))),
            new FlowProgram.Procedure("definition", key, types, Map.of("reading", DataType.Scalar.INTEGER), Map.of("reading", "sample")),
            new FlowProgram.SignalWrite("drive", "p0", "level"), new FlowProgram.Sleep("delay", "ticks"),
            new FlowProgram.SignalRead("sample", "p0", "sample"), new FlowProgram.EndProcedure("return")));
        if (assembly) program = program.withAssembly();
        neighbor.installFlow(new FlowProgram(types, List.of(new FlowProgram.Sleep("other_delay", "ticks"))), initial);
        var neighborBefore = neighbor.flowSnapshot().orElseThrow(); var neighborHardware = neighbor.hardware().limits();
        if (assembly) {
            var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program, initial)));
            var nonce = UUID.randomUUID(); var session = new ProgramEditSession(nonce);
            helper.assertValueEqual(session.submit(nonce, 1, host.programRevision(), host::programRevision,
                () -> host.installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET)).orElseThrow().code(), "applied", "Install the Scratch definition and explicit call through the document owner");
        } else host.installFlow(program, initial);
        var recoveredDocument = host.document().orElseThrow();
        helper.assertValueEqual(recoveredDocument.flow().orElseThrow().canvas().detached().size(), 1,
            "Opening a runtime-only program recovers its definition as a separate editor hat");
        helper.assertValueEqual(recoveredDocument.flow().orElseThrow().program(recoveredDocument.archive()), program,
            "Recovered editor document retains the same definition and explicit call");
        for (int i = 0; i < 12 && host.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.SLEEPING; i++) host.step();
        var sleeping = host.flowSnapshot().orElseThrow(); var hardware = host.hardware().limits();
        helper.assertValueEqual(sleeping.state().status(), FlowRuntime.Status.SLEEPING, "Save inside the admitted sleep");
        helper.assertValueEqual(assembly ? sleeping.state().assembly().orElseThrow().calls().orElseThrow().frames().size()
            : sleeping.state().calls().frames().size(), 1, "The sleeping procedure owns one same-machine caller frame");
        helper.assertValueEqual(sleeping.state().signals().outputs(), Map.of("p0", 15), "A procedure uses the actual member's physical output");
        var saved = block.saveWithFullMetadata(level.registryAccess()); var state = block.getBlockState(); level.removeBlockEntity(pos);
        var restoredBlock = (ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(restoredBlock); var restored = BiocomputingWorldGameTests.host(restoredBlock);
        helper.runAfterDelay(4, () -> {
            helper.assertValueEqual(restored.flowSnapshot().orElseThrow(), sleeping, "Paused BE reload retains caller, local clock and output");
            helper.assertValueEqual(restored.hardware().limits(), hardware, "Procedure calls do not replace the member's hardware");
            restored.setPaused(false);
            helper.runAfterDelay(8, () -> {
                var result = restored.flowSnapshot().orElseThrow().state();
                helper.assertValueEqual(result.status(), FlowRuntime.Status.COMPLETE, "Ordinary server ticks return to the caller");
                helper.assertValueEqual(result.locals().field("answer"), new DataValue.IntegerValue(0), "Explicit return receives the real unwired sample");
                helper.assertValueEqual(result.locals().field("level"), initial.field("level"), "Caller input remains unchanged");
                helper.assertTrue(result.calls().frames().isEmpty(), "Returning releases the caller frame");
                helper.assertValueEqual(result.work(), assembly ? 13L : 6L, "Calls and returns share the same work counter, sleep ticks are not instructions");
                if (assembly) helper.assertTrue(result.assembly().orElseThrow().calls().orElseThrow().frames().isEmpty(), "RET releases private ISA caller frames");
                helper.assertValueEqual(neighbor.flowSnapshot().orElseThrow(), neighborBefore, "Another Computer's program, RAM, clock and execution stay independent");
                helper.assertValueEqual(neighbor.hardware().limits(), neighborHardware, "No hardware pooling between members");
                helper.succeed();
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void standaloneSignalComputerRetainsDriveThroughSleepAndReloadThenReadsAnUnwiredZero(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var block = BiocomputingWorldGameTests.computer(helper, pos); var host = BiocomputingWorldGameTests.host(block);
        var program = new FlowProgram(Map.of("level", DataType.Scalar.INTEGER, "ticks", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.SignalWrite("drive", "p0", "level"), new FlowProgram.Sleep("delay", "ticks"),
            new FlowProgram.SignalRead("sample", "p0", "reading")));
        host.installFlow(program, new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(300), "ticks", new DataValue.IntegerValue(3))));
        helper.assertValueEqual(host.hardware().limits().pins().simpleIo(), List.of("p0", "p1"), "Real resident hardware supplies independent pins");
        host.step(); host.step(); var sleeping = host.flowSnapshot().orElseThrow();
        helper.assertValueEqual(sleeping.state().signals().outputs(), Map.of("p0", 15), "Physical host clamps output without editing its input");
        var saved = block.saveWithFullMetadata(level.registryAccess()); var state = block.getBlockState(); level.removeBlockEntity(pos);
        var restoredBlock = (ComputerBlockEntity) Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(restoredBlock); var restored = BiocomputingWorldGameTests.host(restoredBlock);
        helper.runAfterDelay(4, () -> {
            helper.assertValueEqual(restored.flowSnapshot().orElseThrow(), sleeping, "Paused world ticks and BE reload preserve drive and remaining time");
            restored.step(); helper.assertValueEqual(restored.flowSnapshot().orElseThrow().state().sleepTicks(), 2L, "Sleep step consumes only one local tick");
            restored.setPaused(false);
            helper.runAfterDelay(6, () -> {
                var result = restored.flowSnapshot().orElseThrow().state();
                helper.assertValueEqual(result.status(), FlowRuntime.Status.COMPLETE, "Actual server ticks execute signal read");
                helper.assertValueEqual(result.locals().field("reading"), new DataValue.IntegerValue(0), "Unwired input excludes this pin's own output");
                helper.assertValueEqual(result.locals().field("level"), new DataValue.IntegerValue(300), "Limiting the output did not modify the input value");
                helper.assertValueEqual(result.signals(), SimpleIoState.EMPTY, "Successful read returns pin to input mode");
                helper.assertValueEqual(result.work(), 3L, "Sleep does not spend instruction rounds"); helper.succeed();
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void sleepingRegisteredComputerKeepsLocalTimeAcrossPauseAndReload(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var block = BiocomputingWorldGameTests.computer(helper, pos); var host = BiocomputingWorldGameTests.host(block);
        var program = new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER), List.of(new FlowProgram.Sleep("delay", "ticks"),
            new FlowProgram.AwaitReadable("wait", "x0")));
        host.installFlow(program, new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(3)))); host.step();
        var sleeping = host.flowSnapshot().orElseThrow();
        helper.assertValueEqual(sleeping.state().sleepTicks(), 3L, "Entering sleep does not consume time on that tick");
        var saved = block.saveWithFullMetadata(level.registryAccess()); var state = block.getBlockState(); level.removeBlockEntity(pos);
        var restoredBlock = (ComputerBlockEntity) Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(restoredBlock); var restored = BiocomputingWorldGameTests.host(restoredBlock);
        helper.runAfterDelay(4, () -> {
            helper.assertValueEqual(restored.flowSnapshot().orElseThrow(), sleeping, "Paused actual world ticks do not consume sleep time");
            restored.step(); helper.assertValueEqual(restored.flowSnapshot().orElseThrow().state().sleepTicks(), 2L, "Step advances one sleeping tick");
            restored.setPaused(false);
            helper.runAfterDelay(6, () -> {
                var waiting = restored.flowSnapshot().orElseThrow().state();
                helper.assertValueEqual(waiting.status(), FlowRuntime.Status.WAITING_READABLE, "Actual ticks wake the flow, not a legacy event");
                helper.assertValueEqual(waiting.work(), 2L, "Sleeping ticks do not charge repeated instruction execution");
                helper.assertValueEqual(waiting.sleepTicks(), 0L, "Sleep is cleared without making XBus readable"); helper.succeed();
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void registeredComputerOwnsOneFlowAndResumesItsPureSubframeAfterReload(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var block = BiocomputingWorldGameTests.computer(helper, pos); var host = BiocomputingWorldGameTests.host(block);
        host.installFlow(BiocomputingFlowFixture.calculator(), BiocomputingFlowFixture.inputs(5));
        host.configureEndpoint(new EndpointConfiguration("flow", true));
        helper.assertTrue(host.snapshot().isEmpty(), "A flow does not run a second legacy event program");
        host.step(); var checkpoint = host.flowSnapshot().orElseThrow();
        helper.assertTrue(checkpoint.state().paused(), "Single step preserves pause");
        helper.assertValueEqual(checkpoint.state().status(), FlowRuntime.Status.COMPUTING, "Step starts a pure subframe without completing it");
        var saved = block.saveWithFullMetadata(level.registryAccess()); var state = block.getBlockState();
        level.removeBlockEntity(pos);
        var restoredBlock = (ComputerBlockEntity) Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(restoredBlock); var restored = BiocomputingWorldGameTests.host(restoredBlock);
        var endpoint = Objects.requireNonNull(level.getCapability(CommunicationEndpoint.CAPABILITY, pos, Direction.WEST));
        helper.assertValueEqual(endpoint.xbusPort("flow", "x0").orElseThrow().mode(), XBusPortState.Mode.PAUSED, "Restored actual capability remains paused");
        helper.assertValueEqual(restored.flowSnapshot().orElseThrow(), checkpoint, "Full pure continuation and identity survive BE reload");
        restored.setPaused(false);
        helper.runAfterDelay(20, () -> {
            var done = restored.flowSnapshot().orElseThrow();
            helper.assertValueEqual(done.state().locals(), BiocomputingFlowFixture.inputs(12), "Actual ticks run the ordinary addition graph");
            helper.assertValueEqual(endpoint.xbusPort("flow", "x0").orElseThrow().mode(), XBusPortState.Mode.WRITE, "No reader means waiting at write");
            helper.assertValueEqual(done.state().cursor(), 1, "Write successor has not run");
            restored.reset(); var reset = restored.flowSnapshot().orElseThrow();
            helper.assertValueEqual(reset.state().locals(), BiocomputingFlowFixture.inputs(5), "Reset restores original installed input, not computed data");
            helper.assertValueEqual(reset.state().computer(), checkpoint.state().computer(), "Reset retains Computer identity");
            helper.assertTrue(!reset.state().flow().equals(checkpoint.state().flow()), "Reset invalidates the old flow identity");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void declaredReadIsNotReadyBeforeExecutionAndLegacyMessagesCannotCompleteIt(GameTestHelper helper) {
        var pos = helper.absolutePos(new BlockPos(2, 2, 2)); var block = BiocomputingWorldGameTests.computer(helper, pos);
        var host = BiocomputingWorldGameTests.host(block); var empty = new DataValue.RecordValue(Map.of());
        host.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("receive", "x0", "packet", BiocomputingFlowFixture.PACKET))), empty);
        host.configureEndpoint(new EndpointConfiguration("flow", true)); host.setPaused(false);
        var endpoint = Objects.requireNonNull(helper.getLevel().getCapability(CommunicationEndpoint.CAPABILITY, pos, Direction.WEST));
        helper.assertValueEqual(endpoint.xbusPort("flow", "x0").orElseThrow().mode(), XBusPortState.Mode.IDLE, "Declaration does not execute read");
        helper.runAfterDelay(4, () -> {
            helper.assertValueEqual(endpoint.xbusPort("flow", "x0").orElseThrow().mode(), XBusPortState.Mode.READ, "Own server tick reaches read");
            var before = host.flowSnapshot().orElseThrow();
            var message = new MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0, BiocomputingFlowFixture.inputs(12));
            helper.assertValueEqual(endpoint.receive("flow", message), AdmissionStatus.UNSUPPORTED, "No legacy inbox fallback");
            helper.assertValueEqual(endpoint.receiveReady("flow", before.state().computer(), message), ReceptionStatus.UNSUPPORTED, "Event receiver is not an XBus operation");
            helper.assertValueEqual(host.flowSnapshot().orElseThrow(), before, "Rejected protocols do not consume the read");
            host.disableEndpoint(); helper.assertTrue(endpoint.xbusPort("flow", "x0").isEmpty(), "Closed device does not expose pin state");
            helper.succeed();
        });
    }
}
