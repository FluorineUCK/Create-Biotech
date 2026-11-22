package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingClusterXBusGameTests.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

/** Public menu installation and real configured Cluster ticks, not a rendered player/client test. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingFlowForeverGameTests {
    @GameTest(template = "empty", timeoutTicks = 160)
    public static void playerDocumentsRepeatOnAnActualWireAndBothPausedOwnersResumeAfterReload(GameTestHelper helper) {
        var f = fixture(helper, false, false); var calculator = BiocomputingFlowFixture.calculator();
        var inputs = new LinkedHashMap<>(calculator.inputs()); inputs.put("ticks", DataType.Scalar.INTEGER);
        var steps = new ArrayList<FlowProgram.Step>(); steps.add(new FlowProgram.Forever("loop")); steps.addAll(calculator.steps());
        steps.add(new FlowProgram.Sleep("pace", "ticks")); steps.add(new FlowProgram.EndForever("end"));
        var initial = new LinkedHashMap<>(BiocomputingFlowFixture.inputs(12).fields()); initial.put("ticks", new DataValue.IntegerValue(3));
        var aDoc = new ProgramDocument(calculator.expressions(), Map.of(), Optional.empty(), Optional.of(new FlowDocumentBody(inputs, steps, new DataValue.RecordValue(initial))));
        var receiver = new FlowProgram(Map.of(), List.of(new FlowProgram.Forever("loop"),
            new FlowProgram.Read("read", "x1", "packet", BiocomputingFlowFixture.PACKET), new FlowProgram.EndForever("end")));
        var bDoc = new ProgramDocument(receiver.expressions(), Map.of(), Optional.empty(), Optional.of(new FlowDocumentBody(Map.of(), receiver.steps(), new DataValue.RecordValue(Map.of()))));
        installThroughMenu(helper, f, "A", aDoc); installThroughMenu(helper, f, "B", bDoc);
        f.player().moveTo(Vec3.atCenterOf(f.controller().getBlockPos().west())); configureThroughMenu(helper, f.controller(), f.player(), f.config());
        f.a().setPaused(false); f.b().setPaused(false);
        helper.runAfterDelay(20, () -> {
            f.a().setPaused(true); f.b().setPaused(true);
            var a = f.a().flowSnapshot().orElseThrow(); var b = f.b().flowSnapshot().orElseThrow();
            helper.assertTrue(a.receipts().size() >= 3, "Configured wire must perform several loop iterations without reset");
            helper.assertValueEqual(a.receipts(), b.receipts(), "Both real owners retain the same consumed records");
            helper.assertTrue(a.state().operation() > a.state().cursor(), "Actual back-edges use independent operations");
            long result = ((DataValue.IntegerValue)a.receipts().getLast().payload().field("value")).value();
            helper.assertTrue(result >= 33, "Ordinary addition expression must compute each new value");
            var restoredA = reloadComputer(helper, f.config().members().get("A").position());
            var restoredB = reloadComputer(helper, f.config().members().get("B").position());
            helper.assertTrue(restoredA.xbusPort("left", "x0").isPresent() && restoredB.xbusPort("right", "x1").isPresent(), "Cold owners revalidate their public ports");
            helper.assertValueEqual(restoredA.document().orElseThrow(), aDoc, "Installed loop document survives actual block reload");
            helper.runAfterDelay(4, () -> {
                helper.assertTrue(restoredA.flowSnapshot().orElseThrow().equals(a), "Paused writer retains exact continuation");
                helper.assertTrue(restoredB.flowSnapshot().orElseThrow().equals(b), "Paused reader retains exact continuation");
                restoredA.setPaused(false); restoredB.setPaused(false);
                helper.runAfterDelay(12, () -> {
                    var resumed = restoredA.flowSnapshot().orElseThrow();
                    helper.assertTrue(resumed.receipts().size() > a.receipts().size(), "Existing physical route continues after explicit resume");
                    helper.assertValueEqual(resumed.state().flow(), a.state().flow(), "Resume is not reinstall/reset");
                    helper.assertTrue(f.controller().routingState().orElseThrow().mailbox().queued().isEmpty(), "No legacy inbox receives the loop records");
                    helper.succeed();
                });
            });
        });
    }
}
