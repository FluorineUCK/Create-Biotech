package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.*;

class PreparedProgramBehaviourTest {
    @BeforeAll static void bootstrap() throws Exception { TerminalBehaviourTest.bootstrap(); }

    @Test void realDocumentInstallRouteRetainsEditorSourceTerminalAndExactSavedOwner() {
        var source = PreparedProgramTest.source();
        var terminal = new TerminalDefinition("text", Map.of("text", new TerminalDefinition.Text(
            new TerminalBinding.Literal(new DataValue.TextValue("installed source")))));
        var workspace = new ProgramWorkspace(); var mapped = workspace.createEntry("source_mapping");
        var node = mapped.add(new ComputationGraph.Constant(new DataValue.IntegerValue(42)), 31, 47);
        mapped.output("value", node); workspace.setFlow(source.flow()); workspace.setTerminal(Optional.of(terminal));
        var document = workspace.snapshot(); assertFalse(document.positions().isEmpty());
        var limits = ComputerBehaviourTest.LIMITS;
        var wired = ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, limits.archive()), limits.archive());
        var computer = new TerminalBehaviourTest.Harness().getBehaviour(ComputerBehaviour.TYPE);
        assertEquals(ComputerRuntime.InstallResult.APPLIED, computer.installDocument(wired, ComputerRuntime.MemoryPolicy.RESET));
        assertEquals(document, computer.document().orElseThrow());
        assertTrue(computer.flowSnapshot().orElseThrow().state().paused());
        assertTrue(computer.flowSnapshot().orElseThrow().state().assembly().isPresent());
        computer.step(); var before = computer.flowSnapshot(); long revision = computer.programRevision();
        var saved = new CompoundTag(); computer.write(saved, RegistryAccess.EMPTY, false);
        assertThrows(IllegalArgumentException.class, () -> computer.installDocument(wired, ComputerRuntime.MemoryPolicy.PRESERVE));
        assertEquals(document, computer.document().orElseThrow()); assertEquals(before, computer.flowSnapshot());
        assertEquals(revision, computer.programRevision());
        var unchanged = new CompoundTag(); computer.write(unchanged, RegistryAccess.EMPTY, false); assertEquals(saved, unchanged);
        var restored = new TerminalBehaviourTest.Harness().getBehaviour(ComputerBehaviour.TYPE);
        restored.read(saved, RegistryAccess.EMPTY, false);
        assertEquals(document, restored.document().orElseThrow()); assertEquals(before, restored.flowSnapshot());
        assertEquals(Optional.of(terminal), TerminalStatusView.decode(restored.terminalStatus()).terminal());
        var roundtrip = new CompoundTag(); restored.write(roundtrip, RegistryAccess.EMPTY, false); assertEquals(saved, roundtrip);
    }

    @Test void actualDocumentRouteRejectsUnsupportedIsaWithoutChangingTheEditorOrOldPc() {
        var computer = new TerminalBehaviourTest.Harness().getBehaviour(ComputerBehaviour.TYPE);
        var source = PreparedProgramTest.source(); computer.installDocument(source, ComputerRuntime.MemoryPolicy.RESET); computer.step();
        var before = computer.flowSnapshot(); long revision = computer.programRevision();
        var saved = new CompoundTag(); computer.write(saved, RegistryAccess.EMPTY, false);
        assertEquals(ComputationException.Code.INVALID_GRAPH, assertThrows(ComputationException.class,
            () -> computer.installDocument(PreparedProgramTest.unknownInstructionDocument(), ComputerRuntime.MemoryPolicy.RESET)).code());
        assertEquals(source, computer.document().orElseThrow()); assertEquals(before, computer.flowSnapshot());
        assertEquals(revision, computer.programRevision());
        var after = new CompoundTag(); computer.write(after, RegistryAccess.EMPTY, false); assertEquals(saved, after);
    }
}
