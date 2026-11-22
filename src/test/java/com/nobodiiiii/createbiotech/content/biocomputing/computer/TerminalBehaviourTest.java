package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.junit.jupiter.api.*;

public class TerminalBehaviourTest {
    @BeforeAll public static void bootstrap() throws Exception { ComputerBehaviourTest.bootstrap(); }
    public static ProgramArchive counter() { return ComputerRuntimeTest.counter(1); }
    private static ProgramDocument document(String label) {
        return new ProgramDocument(counter(), Map.of(), Optional.of(new TerminalDefinition("text", Map.of(
            "text", new TerminalDefinition.Text(new TerminalBinding.Literal(new DataValue.TextValue(label)))))));
    }
    @Test void installedFlowPublishesOnlyItsCompletedOutputAndPreservesTerminalAcrossRestore() {
        var workspace = new ProgramWorkspace();
        var expression = workspace.createEntry("calculate");
        String value = expression.add(new ComputationGraph.Constant(new DataValue.IntegerValue(42)), 0, 0);
        expression.output("value", value);
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Evaluate("calculate", "calculate")),
            ComputerBehaviourTest.EMPTY)));
        var binding = new TerminalBinding.Path(new ValuePath("output", List.of(new ValuePath.Key("value"))));
        workspace.setTerminal(Optional.of(new TerminalDefinition("result", Map.of("result", new TerminalDefinition.Text(binding)))));
        var document = workspace.snapshot();
        var computer = new Harness().getBehaviour(ComputerBehaviour.TYPE);
        assertEquals(ComputerRuntime.InstallResult.APPLIED, computer.installDocument(document, ComputerRuntime.MemoryPolicy.RESET));
        var ready = computer.flowSnapshot();
        var initial = TerminalStatusView.decode(computer.terminalStatus());
        assertEquals(document.terminal(), initial.terminal());
        assertEquals(DataValue.NullValue.INSTANCE, initial.roots().field("output"));
        assertEquals(ComputerBehaviourTest.EMPTY, initial.roots().field("memory"));
        assertEquals(ready, computer.flowSnapshot(), "Terminal observation must not spend work or advance a flow");
        for (int i = 0; i < 10 && computer.flowSnapshot().orElseThrow().state().status()
            != com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime.Status.COMPLETE; i++) computer.step();
        var completed = computer.flowSnapshot();
        var observed = TerminalStatusView.decode(computer.terminalStatus());
        assertEquals("COMPLETE", observed.phase());
        assertEquals(new DataValue.IntegerValue(42), binding.resolve(observed.roots()));
        assertEquals(completed, computer.flowSnapshot());
        var saved = new CompoundTag(); computer.write(saved, RegistryAccess.EMPTY, false);
        var restored = new Harness().getBehaviour(ComputerBehaviour.TYPE); restored.read(saved, RegistryAccess.EMPTY, false);
        assertEquals(observed, TerminalStatusView.decode(restored.terminalStatus()));
        assertEquals(document, restored.document().orElseThrow());
        assertEquals(completed, restored.flowSnapshot(), "Reopening a terminal must not reset or rerun its installed flow");
    }
    @Test void installedObservationUsesActiveNotPendingAndDelayedNbtRestoreDoesNotExecute() {
        var block = new Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        var active = document("active"); var pending = document("pending");
        computer.installDocument(active, ComputerRuntime.MemoryPolicy.RESET);
        computer.begin(ComputerBehaviourTest.EMPTY); computer.setPaused(true);
        assertEquals(ComputerRuntime.InstallResult.QUEUED, computer.installDocument(pending, ComputerRuntime.MemoryPolicy.PRESERVE));
        assertEquals(pending, computer.document().orElseThrow());
        var before = computer.snapshot(); block.samples = 0;
        var view = TerminalStatusView.decode(computer.terminalStatus());
        assertEquals(active.terminal(), view.terminal()); assertEquals(before.orElseThrow().machine().memory(), view.roots().field("memory"));
        assertEquals(before, computer.snapshot()); assertEquals(1, block.samples);
        var saved = new CompoundTag(); computer.write(saved, RegistryAccess.EMPTY, false);
        var restoredBlock = new Harness(); var restored = restoredBlock.getBehaviour(ComputerBehaviour.TYPE);
        restored.read(saved, RegistryAccess.EMPTY, false); assertTrue(restored.snapshot().isEmpty());
        assertEquals(active.terminal(), TerminalStatusView.decode(restored.terminalStatus()).terminal());
        assertEquals(pending, restored.document().orElseThrow());
        assertEquals(before.orElseThrow().machine(), restored.snapshot().orElseThrow().machine());
        restored.setPaused(false); for (int i = 0; i < 30; i++) restoredBlock.tick();
        assertEquals(pending.terminal(), TerminalStatusView.decode(restored.terminalStatus()).terminal());
        assertEquals(new DataValue.IntegerValue(1), ((DataValue.RecordValue)TerminalStatusView.decode(restored.terminalStatus()).roots().field("memory")).field("count"));
    }
    private static ProgramDocument sleepingFlow(long ticks, TerminalDefinition terminal) {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("ticks", DataType.Scalar.INTEGER), List.of(
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Sleep("sleep", "ticks")),
            new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(ticks))))));
        workspace.setTerminal(Optional.of(terminal)); return workspace.snapshot();
    }
    @Test void unfinishedOrFailedFlowHasNoSuccessfulOutputAndTerminalReadsCannotAdvanceOrDispatchIt() {
        var layout = new TerminalDefinition("text", Map.of("text", new TerminalDefinition.Text(
            new TerminalBinding.Path(new ValuePath("output", List.of())))));
        for (long ticks : List.of(5L, -1L)) {
            var computer = new Harness().getBehaviour(ComputerBehaviour.TYPE);
            computer.installDocument(sleepingFlow(ticks, layout), ComputerRuntime.MemoryPolicy.RESET); computer.step();
            var before = computer.flowSnapshot(); long revision = computer.programRevision();
            var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce);
            var read = session.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL,
                () -> { throw new AssertionError("Read must not request inputs"); }, computer).orElseThrow();
            assertEquals("ok", read.code());
            var view = TerminalStatusView.decode(read.dedicatedStatus().orElseThrow());
            assertEquals(ticks > 0 ? "SLEEPING" : "FAILED", view.phase());
            assertEquals(DataValue.NullValue.INSTANCE, view.roots().field("output"));
            assertEquals(ComputerBehaviourTest.EMPTY, view.roots().field("memory"));
            var click = session.apply(nonce, 2, revision, ComputerControlSession.Action.TERMINAL_CLICK,
                () -> new TerminalClick(1, "text").encode(), computer).orElseThrow();
            assertEquals("invalid", click.code(), "A forged click on a presentation node cannot run an event");
            assertEquals(before, computer.flowSnapshot()); assertEquals(revision, computer.programRevision());
        }
    }
    @Test void nestedFlowInteractionIsRejectedBeforeReplacingTheInstalledProgram() {
        var computer = new Harness().getBehaviour(ComputerBehaviour.TYPE);
        var original = sleepingFlow(5, new TerminalDefinition("text", Map.of("text", new TerminalDefinition.Text(
            new TerminalBinding.Literal(new DataValue.TextValue("waiting"))))));
        computer.installDocument(original, ComputerRuntime.MemoryPolicy.RESET);
        var before = computer.flowSnapshot(); long revision = computer.programRevision();
        var text = new TerminalBinding.Literal(new DataValue.TextValue("click"));
        var enabled = new TerminalBinding.Literal(new DataValue.BooleanValue(false));
        var inputs = new TerminalBinding.Literal(ComputerBehaviourTest.EMPTY);
        for (TerminalDefinition.Node node : List.of(new TerminalDefinition.Button(text, enabled, "handler", inputs),
            new TerminalDefinition.Input(text, enabled, "handler", "value", inputs))) {
            var layout = new TerminalDefinition("rows", Map.of("rows", new TerminalDefinition.Repeat(
                new TerminalBinding.Literal(new DataValue.ListValue(List.of())), "control"), "control", node));
            assertThrows(IllegalArgumentException.class, () -> computer.installDocument(sleepingFlow(5, layout), ComputerRuntime.MemoryPolicy.RESET));
            assertEquals(before, computer.flowSnapshot()); assertEquals(original, computer.document().orElseThrow());
            assertEquals(revision, computer.programRevision());
        }
    }
    @Test void unavailableFlowHardwareDoesNotPublishItsTerminalOrAdvanceIt() {
        var block = new Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        computer.installDocument(sleepingFlow(5, TerminalDefinition.empty()), ComputerRuntime.MemoryPolicy.RESET);
        var before = computer.flowSnapshot(); block.available = false;
        assertThrows(IllegalArgumentException.class, computer::terminalStatus);
        assertEquals(before, computer.flowSnapshot());
        block.available = true;
        assertEquals("READY", TerminalStatusView.decode(computer.terminalStatus()).phase());
        assertEquals(before, computer.flowSnapshot());
    }
    @Test void terminalReadAllowsStaleRevisionButRejectsNonceReplayAndInputWithoutMutatingState() {
        var computer = new Harness().getBehaviour(ComputerBehaviour.TYPE); computer.installDocument(document("active"), ComputerRuntime.MemoryPolicy.RESET);
        var before = computer.snapshot(); long revision = computer.programRevision();
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce); var action = ComputerControlSession.Action.READ_TERMINAL;
        assertTrue(session.apply(UUID.randomUUID(), 1, revision, action, () -> { throw new AssertionError("input read"); }, computer).isEmpty());
        var answer = session.apply(nonce, 1, revision - 1, action, () -> { throw new AssertionError("input read"); }, computer).orElseThrow();
        assertEquals("ok", answer.code()); assertTrue(answer.snapshot().isEmpty());
        assertEquals(document("active").terminal(), TerminalStatusView.decode(answer.dedicatedStatus().orElseThrow()).terminal());
        assertTrue(session.apply(nonce, 1, revision, action, () -> { throw new AssertionError("input read"); }, computer).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ProgramControlRequest(1, nonce, 2, revision, action, new byte[]{1}));
        assertEquals(before, computer.snapshot()); assertEquals(revision, computer.programRevision());
    }
    @Test void terminalEnvelopeRejectsScalarOutputInsteadOfAcceptingAMalformedSnapshot() {
        var good = new DataValue.RecordValue(Map.of("terminal", DataValue.NullValue.INSTANCE, "memory", new DataValue.RecordValue(Map.of()),
            "output", DataValue.NullValue.INSTANCE, "phase", new DataValue.TextValue("IDLE"), "paused", new DataValue.BooleanValue(false)));
        assertTrue(TerminalStatusView.decode(good).terminal().isEmpty());
        var bad = new LinkedHashMap<>(good.fields()); bad.put("output", new DataValue.IntegerValue(1));
        assertThrows(IllegalArgumentException.class, () -> TerminalStatusView.decode(new DataValue.RecordValue(bad)));
    }
    @Test void oversizedTerminalIsRejectedBeforeChangingTheInstalledDocumentOrRam() {
        var computer = new Harness().getBehaviour(ComputerBehaviour.TYPE); var active = document("active");
        computer.installDocument(active, ComputerRuntime.MemoryPolicy.RESET); var before = computer.snapshot(); long revision = computer.programRevision();
        assertThrows(IllegalArgumentException.class, () -> computer.installDocument(document("x".repeat(30000)), ComputerRuntime.MemoryPolicy.RESET));
        assertEquals(active, computer.document().orElseThrow()); assertEquals(before, computer.snapshot()); assertEquals(revision, computer.programRevision());
    }
    public static final class Harness extends SmartBlockEntity {
        public int samples;
        public boolean available = true;
        public Harness() { super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState()); }
        @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
            behaviours.add(new ComputerBehaviour(this, () -> available, () -> { samples++; return new HardwareSelection(HardwareSelection.Status.READY,
                Optional.of(new ResidentHardwareIdentity(ResourceLocation.parse("minecraft:villager"), ResourceLocation.parse("minecraft:cleric"), 1)), "parser", ComputerBehaviourTest.LIMITS); }));
        }
        @Override public void setChanged() { }
    }
}
