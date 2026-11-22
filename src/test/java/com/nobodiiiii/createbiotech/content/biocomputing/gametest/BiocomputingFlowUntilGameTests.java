package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingClusterXBusGameTests.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

/** Actual hosts, public server install/configure menus and a real wire; no rendered input-editor claim. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingFlowUntilGameTests {
    @GameTest(template = "empty", timeoutTicks = 180)
    public static void untilNestedConditionCommitSurvivesReloadAndStopsAfterThreeRealTransfers(GameTestHelper helper) {
        var f = fixture(helper, false, false); var calculator = BiocomputingFlowFixture.calculator();
        var key = new ModuleDefinition.Key("at_limit", 1);
        var module = new ModuleDefinition(key, new ComputationGraph(Map.of("value", DataType.Scalar.INTEGER, "limit", DataType.Scalar.INTEGER),
            Map.of("value", new ComputationGraph.Input("value"), "limit", new ComputationGraph.Input("limit"),
                "equal", new ComputationGraph.Binary(ComputationGraph.BinaryOp.EQUAL_INTEGER, "value", "limit")), Map.of("done", "equal")));
        var graph = new ComputationGraph(Map.of("packet", BiocomputingFlowFixture.PACKET, "limit", DataType.Scalar.INTEGER), Map.of(
            "packet", new ComputationGraph.Input("packet"), "value", new ComputationGraph.Field("packet", "value"),
            "limit", new ComputationGraph.Input("limit"), "call", new ComputationGraph.Call(key, Map.of("value", "value", "limit", "limit")),
            "done", new ComputationGraph.Field("call", "done"), "unused", new ComputationGraph.Constant(BiocomputingFlowFixture.inputs(999).field("packet"))),
            Map.of("done", "done", "packet", "unused"));
        var entries = new LinkedHashMap<>(calculator.expressions().program().entries());
        entries.put("condition", new ProgramDefinition.Entry(graph, List.of(), List.of()));
        var archive = new ProgramArchive(new ProgramDefinition(calculator.expressions().program().graph(), Map.of(), List.of(), List.of(), entries), Map.of(key, module));
        var types = new LinkedHashMap<>(calculator.inputs()); types.put("limit", DataType.Scalar.INTEGER); types.put("ticks", DataType.Scalar.INTEGER);
        var initial = new LinkedHashMap<>(BiocomputingFlowFixture.inputs(12).fields());
        initial.put("limit", new DataValue.IntegerValue(33)); initial.put("ticks", new DataValue.IntegerValue(2));
        var steps = new ArrayList<FlowProgram.Step>(); steps.add(new FlowProgram.Until("until", new FlowInput.Expression("condition", "done")));
        steps.addAll(calculator.steps()); steps.add(new FlowProgram.Sleep("pace", "ticks")); steps.add(new FlowProgram.EndUntil("end"));
        var aDoc = new ProgramDocument(archive, Map.of(), Optional.empty(), Optional.of(new FlowDocumentBody(types, steps, new DataValue.RecordValue(initial))));
        var reader = new FlowProgram(Map.of(), List.of(new FlowProgram.Repeat("repeat", new FlowInput.Literal(new DataValue.IntegerValue(3))),
            new FlowProgram.Read("read", "x1", "packet", BiocomputingFlowFixture.PACKET), new FlowProgram.EndRepeat("end")));
        var bDoc = new ProgramDocument(reader.expressions(), Map.of(), Optional.empty(), Optional.of(
            new FlowDocumentBody(reader.inputs(), reader.steps(), new DataValue.RecordValue(Map.of()))));
        installThroughMenu(helper, f, "A", aDoc); installThroughMenu(helper, f, "B", bDoc);
        f.player().moveTo(Vec3.atCenterOf(f.controller().getBlockPos().west())); configureThroughMenu(helper, f.controller(), f.player(), f.config());
        for (int i = 0; i < 50; i++) {
            f.a().step();
            if (f.a().flowSnapshot().orElseThrow().state().expression().filter(pure -> pure.frames().getFirst() instanceof MachineCheckpoint.CommitState).isPresent()) break;
        }
        f.b().step(); f.b().step();
        var a = f.a().flowSnapshot().orElseThrow(); var b = f.b().flowSnapshot().orElseThrow();
        helper.assertValueEqual(a.state().cursor(), 0, "Until is still computing its first condition, not its body");
        helper.assertTrue(a.state().expression().orElseThrow().frames().getFirst() instanceof MachineCheckpoint.CommitState, "Real host holds the final condition commit frame");
        helper.assertValueEqual(a.state().inputInvocation().orElseThrow().inputs().field("packet"), BiocomputingFlowFixture.inputs(12).field("packet"), "Original call parameter is frozen");
        helper.assertValueEqual(b.state().status(), FlowRuntime.Status.WAITING_READ, "Literal-count receiver reached the actual read");
        var restoredA = reloadComputer(helper, f.config().members().get("A").position());
        var restoredB = reloadComputer(helper, f.config().members().get("B").position());
        restoredA.document(); restoredB.document();
        helper.runAfterDelay(3, () -> {
            helper.assertValueEqual(restoredA.flowSnapshot().orElseThrow(), a, "Paused condition commit and invocation survive actual owner reload");
            helper.assertValueEqual(restoredB.flowSnapshot().orElseThrow(), b, "Captured literal count and read wait survive reload");
            restoredA.setPaused(false); restoredB.setPaused(false);
            helper.runAfterDelay(45, () -> {
                var done = restoredA.flowSnapshot().orElseThrow(); var received = restoredB.flowSnapshot().orElseThrow();
                helper.assertValueEqual(done.state().status(), FlowRuntime.Status.COMPLETE, "Until reevaluates current packet and exits at the limit");
                helper.assertValueEqual(received.state().status(), FlowRuntime.Status.COMPLETE, "Literal count stops the receiver");
                helper.assertValueEqual(done.receipts().size(), 3, "Exactly three rendezvous, no hidden or extra message");
                helper.assertValueEqual(done.receipts(), received.receipts(), "Both owners persist the matching transfers");
                helper.assertValueEqual(done.receipts().getLast().payload().field("value"), new DataValue.IntegerValue(33), "Three +7 calculations; unused 999 output never overwrites locals");
                helper.assertTrue(done.state().inputInvocation().isEmpty() && received.state().repeats().frames().isEmpty(), "Completed loops release their control state");
                helper.assertValueEqual(restoredA.document().orElseThrow(), aDoc, "Editable nested-call document is preserved");
                var completed = reloadComputer(helper, f.config().members().get("A").position()); completed.document();
                helper.runAfterDelay(3, () -> {
                    helper.assertValueEqual(completed.flowSnapshot().orElseThrow(), done, "Completed Until cannot restart or send again after reload");
                    helper.succeed();
                });
            });
        });
    }
}
