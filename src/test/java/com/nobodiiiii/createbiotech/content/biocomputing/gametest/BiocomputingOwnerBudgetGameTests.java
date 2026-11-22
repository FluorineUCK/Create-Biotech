package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocument;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

/** Real ServerLevel activity identity, installed through the public program menu. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingOwnerBudgetGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void repeatedPhysicalTicksDoNotMultiplyPeriodicTimeOrEventWork(GameTestHelper helper) {
        var fixture = BiocomputingClusterXBusGameTests.fixture(helper, false, false);
        var graph = new ComputationGraph(Map.of(), Map.of("read", new ComputationGraph.ReadMemory("count"),
            "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "read", "one")), Map.of("next", "sum"));
        var archive = new ProgramArchive(new ProgramDefinition(graph,
            Map.of("count", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0))),
            List.of(new ProgramDefinition.Write("count", "next"))), Map.of());
        BiocomputingClusterXBusGameTests.installThroughMenu(helper, fixture, "A", ProgramDocument.of(archive));
        var computer = fixture.a();
        computer.configurePeriodic(2, new DataValue.RecordValue(Map.of())); computer.setPaused(false);
        computer.tick();
        var first = computer.snapshot().orElseThrow();
        helper.assertValueEqual(first.periodic().orElseThrow().remainingTicks(), 1L, "One physical tick advances one timer tick");
        computer.tick(); computer.tick();
        helper.assertValueEqual(computer.snapshot().orElseThrow(), first, "Duplicate ServerLevel tick cannot start the event early");
        helper.runAfterDelay(1, () -> {
            computer.tick();
            var second = computer.snapshot().orElseThrow();
            helper.assertTrue(second.machine().workUnits() > 0, "Next world tick starts the installed event");
            helper.assertTrue(second.machine().workUnits() <= computer.hardware().limits().hardware().workPerSlice(),
                "Event work fits the resident's configured allowance");
            computer.tick(); computer.tick();
            helper.assertValueEqual(computer.snapshot().orElseThrow(), second, "The actual block behaviour spends at most one allowance per world tick");
            helper.succeed();
        });
    }
}
