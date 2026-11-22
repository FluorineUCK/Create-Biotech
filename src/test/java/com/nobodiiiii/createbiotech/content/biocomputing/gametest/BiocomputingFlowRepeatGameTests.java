package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingClusterXBusGameTests.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

/** Public server menus and actual configured Cluster wire. Not a rendered client acceptance test. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingFlowRepeatGameTests {
    @GameTest(template = "empty", timeoutTicks = 180)
    public static void countedMenuProgramsRetainFramesOnReloadAndStopAfterThreeRealExchanges(GameTestHelper helper) {
        var f = fixture(helper, false, false); var calculator = BiocomputingFlowFixture.calculator();
        var types = new LinkedHashMap<>(calculator.inputs()); types.put("n", DataType.Scalar.INTEGER); types.put("ticks", DataType.Scalar.INTEGER);
        var initial = new LinkedHashMap<>(BiocomputingFlowFixture.inputs(12).fields());
        initial.put("n", new DataValue.IntegerValue(3)); initial.put("ticks", new DataValue.IntegerValue(2));
        var steps = new ArrayList<FlowProgram.Step>(); steps.add(new FlowProgram.Repeat("loop", "n")); steps.addAll(calculator.steps());
        steps.add(new FlowProgram.Sleep("pace", "ticks")); steps.add(new FlowProgram.EndRepeat("end"));
        var aDoc = new ProgramDocument(calculator.expressions(), Map.of(), Optional.empty(),
            Optional.of(new FlowDocumentBody(types, steps, new DataValue.RecordValue(initial))));
        var receiver = new FlowProgram(Map.of("n", DataType.Scalar.INTEGER), List.of(new FlowProgram.Repeat("loop", "n"),
            new FlowProgram.Read("read", "x1", "packet", BiocomputingFlowFixture.PACKET), new FlowProgram.EndRepeat("end")));
        var bDoc = new ProgramDocument(receiver.expressions(), Map.of(), Optional.empty(), Optional.of(
            new FlowDocumentBody(receiver.inputs(), receiver.steps(), new DataValue.RecordValue(Map.of("n", new DataValue.IntegerValue(3))))));
        installThroughMenu(helper, f, "A", aDoc); installThroughMenu(helper, f, "B", bDoc);
        f.player().moveTo(Vec3.atCenterOf(f.controller().getBlockPos().west())); configureThroughMenu(helper, f.controller(), f.player(), f.config());
        f.a().step(); f.a().step(); f.b().step(); f.b().step();
        var a = f.a().flowSnapshot().orElseThrow(); var b = f.b().flowSnapshot().orElseThrow();
        helper.assertValueEqual(a.state().repeats().frames().getFirst().remaining(), 3L, "Captured count exists beside the pure subframe");
        helper.assertValueEqual(a.state().status(), FlowRuntime.Status.COMPUTING, "Paused actual host holds a pure calculation in the loop");
        helper.assertValueEqual(b.state().status(), FlowRuntime.Status.WAITING_READ, "Other Computer reached a real rendezvous wait");
        var restoredA = reloadComputer(helper, f.config().members().get("A").position());
        var restoredB = reloadComputer(helper, f.config().members().get("B").position());
        restoredA.document(); restoredB.document();
        helper.runAfterDelay(3, () -> {
            helper.assertValueEqual(restoredA.flowSnapshot().orElseThrow(), a, "Paused count and pure frame survive actual BE reload");
            helper.assertValueEqual(restoredB.flowSnapshot().orElseThrow(), b, "Paused count and read wait survive actual BE reload");
            restoredA.setPaused(false); restoredB.setPaused(false);
            helper.runAfterDelay(35, () -> {
                var done = restoredA.flowSnapshot().orElseThrow(); var received = restoredB.flowSnapshot().orElseThrow();
                helper.assertValueEqual(done.state().status(), FlowRuntime.Status.COMPLETE, "Counted sender terminates");
                helper.assertValueEqual(received.state().status(), FlowRuntime.Status.COMPLETE, "Counted receiver terminates");
                helper.assertValueEqual(done.receipts().size(), 3, "Exactly three transfers, not a reset or forever loop");
                helper.assertValueEqual(done.receipts(), received.receipts(), "Both owners preserve matching real wire receipts");
                helper.assertValueEqual(done.receipts().getLast().payload().field("value"), new DataValue.IntegerValue(33), "Three public +7 computations");
                helper.assertTrue(done.state().repeats().frames().isEmpty() && received.state().repeats().frames().isEmpty(), "Exited groups free their counts");
                helper.assertValueEqual(restoredA.document().orElseThrow(), aDoc, "Loop is still the player's installed document");
                helper.succeed();
            });
        });
    }
}
