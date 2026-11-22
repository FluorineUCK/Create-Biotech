package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.computer.EventEntryFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.junit.jupiter.api.*;

class TerminalButtonSessionTest {
    @BeforeAll static void bootstrap() throws Exception { TerminalBehaviourTest.bootstrap(); }

    @Test void frozenPublicationAcceptsOneViewOnlyAndClickReturnsDedicatedStatus() {
        var block = new TerminalBehaviourTest.Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        computer.installDocument(document(true), ComputerRuntime.MemoryPolicy.RESET);
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce); long revision = computer.programRevision();
        var read = session.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL,
            () -> { throw new AssertionError("read has no client inputs"); }, computer).orElseThrow();
        assertEquals("ok", read.code()); assertTrue(read.snapshot().isEmpty());
        var click = session.apply(nonce, 2, revision, ComputerControlSession.Action.TERMINAL_CLICK,
            () -> click(1, "6:button"), computer).orElseThrow();
        assertEquals("ok", click.code()); assertTrue(click.snapshot().isEmpty());
        assertEquals(Set.of("terminal_click"), click.dedicatedStatus().orElseThrow().fields().keySet());
        assertEquals(integer(0), computer.snapshot().orElseThrow().machine().memory().field("n"));
        for (int i = 0; i < 100; i++) computer.step();
        assertEquals(integer(9), computer.snapshot().orElseThrow().machine().memory().field("n"));

        var replay = session.apply(nonce, 3, revision, ComputerControlSession.Action.TERMINAL_CLICK,
            () -> click(1, "6:button"), computer).orElseThrow();
        assertEquals("invalid", replay.code());
    }

    @Test void disabledStaleAndOtherControlAttemptsConsumeThePublicationWithoutExecution() {
        var computer = new TerminalBehaviourTest.Harness().getBehaviour(ComputerBehaviour.TYPE);
        computer.installDocument(document(false), ComputerRuntime.MemoryPolicy.RESET);
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce); long revision = computer.programRevision();
        session.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, computer).orElseThrow();
        var disabled = session.apply(nonce, 2, revision, ComputerControlSession.Action.TERMINAL_CLICK,
            () -> click(1, "6:button"), computer).orElseThrow();
        assertEquals("invalid", disabled.code());
        assertEquals(ComputationMachine.Status.IDLE, computer.snapshot().orElseThrow().machine().status());

        computer.installDocument(document(true), ComputerRuntime.MemoryPolicy.RESET);
        long nextRevision = computer.programRevision();
        session.apply(nonce, 3, nextRevision, ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, computer).orElseThrow();
        assertEquals("stale", session.apply(nonce, 4, revision, ComputerControlSession.Action.TERMINAL_CLICK,
            () -> click(3, "6:button"), computer).orElseThrow().code());
        session.apply(nonce, 5, nextRevision, ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, computer).orElseThrow();
        session.apply(nonce, 6, nextRevision, ComputerControlSession.Action.REFRESH, () -> EMPTY, computer).orElseThrow();
        assertEquals("invalid", session.apply(nonce, 7, nextRevision, ComputerControlSession.Action.TERMINAL_CLICK,
            () -> click(5, "6:button"), computer).orElseThrow().code());
    }

    @Test void clickWireAllowsOnlyBoundedViewAndInstanceRecord() {
        var nonce = UUID.randomUUID();
        assertDoesNotThrow(() -> new ProgramControlRequest(1, nonce, 2, 4,
            ComputerControlSession.Action.TERMINAL_CLICK, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> TerminalClick.decode(new DataValue.RecordValue(Map.of(
            "view", integer(1), "instance", new DataValue.TextValue("x"), "entry", new DataValue.TextValue("forged")))));
        assertThrows(IllegalArgumentException.class, () -> TerminalClick.decode(new DataValue.RecordValue(Map.of(
            "view", integer(-1), "instance", new DataValue.TextValue("x")))));
    }

    @Test void malformedClickConsumesTheOtherwiseValidPublication() {
        var computer = new TerminalBehaviourTest.Harness().getBehaviour(ComputerBehaviour.TYPE);
        computer.installDocument(document(true), ComputerRuntime.MemoryPolicy.RESET);
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce); long revision = computer.programRevision();
        session.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, computer).orElseThrow();
        assertEquals("invalid", session.apply(nonce, 2, revision, ComputerControlSession.Action.TERMINAL_CLICK,
            () -> new DataValue.RecordValue(Map.of("view", integer(1))), computer).orElseThrow().code());
        assertEquals("invalid", session.apply(nonce, 3, revision, ComputerControlSession.Action.TERMINAL_CLICK,
            () -> click(1, "6:button"), computer).orElseThrow().code());
        assertEquals(ComputationMachine.Status.IDLE, computer.snapshot().orElseThrow().machine().status());
    }

    @Test void failedRefreshInvalidatesThePreviousPublication() {
        var available = new AtomicBoolean(true); var block = new AvailabilityHarness(available);
        var computer = block.getBehaviour(ComputerBehaviour.TYPE); computer.installDocument(document(true), ComputerRuntime.MemoryPolicy.RESET);
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce); long revision = computer.programRevision();
        session.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, computer).orElseThrow();
        available.set(false);
        assertEquals("invalid", session.apply(nonce, 2, revision, ComputerControlSession.Action.READ_TERMINAL,
            () -> EMPTY, computer).orElseThrow().code());
        available.set(true);
        assertEquals("invalid", session.apply(nonce, 3, revision, ComputerControlSession.Action.TERMINAL_CLICK,
            () -> click(1, "6:button"), computer).orElseThrow().code());
    }

    @Test void undeliverableReadIsInvalidatedBeforeAnyClickCanUseIt() {
        var computer = new TerminalBehaviourTest.Harness().getBehaviour(ComputerBehaviour.TYPE);
        computer.installDocument(document(true), ComputerRuntime.MemoryPolicy.RESET);
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce); long revision = computer.programRevision();
        var answer = session.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, computer).orElseThrow();
        var response = ProgramStatusResponse.fromAnswer(0, nonce, 1, answer,
            new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(1, 10, 1));
        assertEquals("too_large", response.code()); session.invalidateTerminalPublication();
        assertEquals("invalid", session.apply(nonce, 2, revision, ComputerControlSession.Action.TERMINAL_CLICK,
            () -> click(1, "6:button"), computer).orElseThrow().code());
    }

    @Test void clickUsesFrozenRowEvenAfterRunningProgramReplacesMemorySource() {
        var base = program(false); var definition = base.program();
        var rowType = new DataType.RecordType(Map.of("amount", DataType.Scalar.INTEGER)); var listType = new DataType.ListType(rowType);
        var oldRows = new DataValue.ListValue(List.of(input("amount", 1))); var cells = new LinkedHashMap<>(definition.cells());
        cells.put("rows", new ProgramDefinition.Cell(listType, oldRows));
        var root = new ComputationGraph(Map.of("rows", listType), Map.of("rows", new ComputationGraph.Input("rows")), Map.of("rows", "rows"));
        var archive = new ProgramArchive(new ProgramDefinition(root, cells, List.of(new ProgramDefinition.Write("rows", "rows")), definition.sends(), definition.entries()), base.modules());
        var button = new TerminalDefinition.Button(new TerminalBinding.Literal(new DataValue.TextValue("Use")),
            new TerminalBinding.Literal(new DataValue.BooleanValue(true)), "message", new TerminalBinding.Path(new ValuePath("row", List.of())));
        var terminal = new TerminalDefinition("rows", Map.of("rows", new TerminalDefinition.Repeat(new TerminalBinding.Path(new ValuePath("memory", List.of()).key("rows")), "button"), "button", button));
        var block = new TerminalBehaviourTest.Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        computer.installDocument(new ProgramDocument(archive, Map.of(), Optional.of(terminal)), ComputerRuntime.MemoryPolicy.RESET);
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce); long revision = computer.programRevision();
        session.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, computer).orElseThrow();
        computer.begin(new DataValue.RecordValue(Map.of("rows", new DataValue.ListValue(List.of(input("amount", 99))))));
        for (int i = 0; i < 100 && computer.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) computer.step();
        assertEquals("ok", session.apply(nonce, 2, revision, ComputerControlSession.Action.TERMINAL_CLICK,
            () -> click(1, "4:rows/6:button[0]"), computer).orElseThrow().code());
        for (int i = 0; i < 100 && computer.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) computer.step();
        assertEquals(integer(1), computer.snapshot().orElseThrow().machine().memory().field("n"));
    }

    private static ProgramDocument document(boolean enabled) {
        var button = new TerminalDefinition.Button(new TerminalBinding.Literal(new DataValue.TextValue("Add")),
            new TerminalBinding.Literal(new DataValue.BooleanValue(enabled)), "message",
            new TerminalBinding.Literal(input("amount", 9)));
        return new ProgramDocument(program(false), Map.of(), Optional.of(new TerminalDefinition("button", Map.of("button", button))));
    }
    private static DataValue.RecordValue click(long view, String instance) {
        return new DataValue.RecordValue(Map.of("view", integer(view), "instance", new DataValue.TextValue(instance)));
    }
    private static final class AvailabilityHarness extends SmartBlockEntity {
        AvailabilityHarness(AtomicBoolean available) {
            super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
            this.available = available;
        }
        private final AtomicBoolean available;
        @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
            try {
                var constructor = ComputerBehaviour.class.getDeclaredConstructor(SmartBlockEntity.class, BooleanSupplier.class, Supplier.class);
                constructor.setAccessible(true);
                Supplier<HardwareSelection> hardware = () -> new HardwareSelection(HardwareSelection.Status.READY,
                    Optional.of(new ResidentHardwareIdentity(ResourceLocation.parse("minecraft:villager"), ResourceLocation.parse("minecraft:none"), 1)),
                    "balanced", LIMITS);
                behaviours.add(constructor.newInstance(this, (BooleanSupplier)(() -> available.get()), hardware));
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }
        @Override public void setChanged() { }
    }
}
