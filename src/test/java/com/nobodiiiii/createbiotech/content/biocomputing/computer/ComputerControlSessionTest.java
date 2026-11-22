package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.junit.jupiter.api.*;

class ComputerControlSessionTest {
    @Test void completedPausedEventResumePreservesCommittedSendStorageAndPeriodicState() {
        var computer = new Owner().getBehaviour(ComputerBehaviour.TYPE);
        computer.install(ComputerOutboxTest.sender(1), ComputerRuntime.MemoryPolicy.RESET);
        computer.configurePeriodic(50, ComputerBehaviourTest.EMPTY);
        UUID nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce); long revision = computer.programRevision();
        assertEquals("ok", session.apply(nonce, 1, revision, ComputerControlSession.Action.START_PAUSED,
            () -> ComputerBehaviourTest.EMPTY, computer).orElseThrow().code());
        long intent = 1;
        while (computer.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING) {
            assertTrue(intent < 100);
            assertEquals("ok", session.apply(nonce, ++intent, revision, ComputerControlSession.Action.STEP,
                () -> ComputerBehaviourTest.EMPTY, computer).orElseThrow().code());
        }
        var before = computer.snapshot().orElseThrow();
        assertEquals(ComputationMachine.Status.SUCCEEDED, before.machine().status()); assertTrue(before.machine().paused());
        assertEquals(new DataValue.IntegerValue(1), before.machine().memory().field("count")); assertTrue(before.periodic().isPresent());
        var committed = ComputerRuntime.restore(before, ComputerBehaviourTest.LIMITS.hardware(), ComputerBehaviourTest.LIMITS.mailbox()).outbound();
        assertTrue(committed.isPresent());
        assertEquals("ok", session.apply(nonce, ++intent, revision, ComputerControlSession.Action.RESUME,
            () -> { fail("Resume must not re-read inputs or begin/reset an event"); return ComputerBehaviourTest.EMPTY; }, computer).orElseThrow().code());
        var after = computer.snapshot().orElseThrow();
        assertFalse(after.machine().paused()); assertEquals(before.machine().status(), after.machine().status());
        assertEquals(before.machine().memory(), after.machine().memory()); assertEquals(before.machine().output(), after.machine().output());
        assertEquals(before.machine().workUnits(), after.machine().workUnits()); assertEquals(before.delivery(), after.delivery());
        assertEquals(before.periodic(), after.periodic()); assertEquals(before.rpc(), after.rpc()); assertEquals(before.mailbox(), after.mailbox());
        assertEquals(committed, ComputerRuntime.restore(after, ComputerBehaviourTest.LIMITS.hardware(), ComputerBehaviourTest.LIMITS.mailbox()).outbound());
    }
    @Test void ownNetworkCaptureIsAuthorizedFencedAndNeverExecutesOrMutatesTheInstalledComputer() {
        var computer = new Owner().getBehaviour(ComputerBehaviour.TYPE);
        computer.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
        computer.begin(ComputerBehaviourTest.EMPTY);
        var before = computer.snapshot();
        UUID nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce);
        var action = ComputerControlSession.Action.valueOf("READ_NETWORK");
        var captured = new DataValue.RecordValue(Map.of("network", new DataValue.RecordValue(Map.of("game_time", new DataValue.IntegerValue(42)))));
        java.util.function.Supplier<DataValue.RecordValue> forbidden = () -> { fail("Network read before authorization"); return null; };
        assertTrue(session.apply(UUID.randomUUID(), 1, computer.programRevision(), action, forbidden, computer).isEmpty());
        assertEquals("stale", session.apply(nonce, 1, 99, action, forbidden, computer).orElseThrow().code());
        var denied = session.apply(nonce, 2, computer.programRevision(), action, forbidden, computer,
            () -> { throw new com.nobodiiiii.createbiotech.content.biocomputing.network.PeerDiscovery.DiscoveryException("invalid", "access_denied"); }).orElseThrow();
        assertEquals("invalid", denied.code()); assertTrue(denied.capturedInputs().isEmpty());
        int[] authorized = {0};
        var answer = session.apply(nonce, 3, computer.programRevision(), action, () -> captured, computer, () -> authorized[0]++).orElseThrow();
        assertEquals(1, authorized[0]); assertEquals("ok", answer.code());
        assertEquals(Optional.of(captured), answer.capturedInputs());
        assertTrue(answer.snapshot().isEmpty()); assertTrue(answer.dedicatedStatus().isEmpty());
        assertEquals(before, computer.snapshot()); assertTrue(computer.endpointStatus().field("endpoint") == DataValue.NullValue.INSTANCE);
        assertTrue(session.apply(nonce, 3, computer.programRevision(), action, forbidden, computer).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramControlRequest(1, nonce, 4, 1, action, new byte[] {1}));
        assertEquals(25, ComputerControlSession.Action.SET_WIRING_ENABLED.ordinal());
        assertEquals(26, action.ordinal());
    }

    @Test void explicitReadWorksWithoutProgramAndIsFencedBeforeSupplier() throws Exception {
        var computer = new Owner().getBehaviour(ComputerBehaviour.TYPE);
        UUID nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce);
        var read = ComputerControlSession.Action.valueOf("READ_PERIPHERALS");
        var captured = new DataValue.RecordValue(Map.of("peripherals", new DataValue.RecordValue(Map.of())));
        java.util.function.Supplier<DataValue.RecordValue> forbidden = () -> { fail("capture before fence"); return null; };
        assertTrue(session.apply(UUID.randomUUID(), 1, 0, read, forbidden, computer).isEmpty());
        assertEquals("stale", session.apply(nonce, 1, 99, read, forbidden, computer).orElseThrow().code());
        assertTrue(session.apply(nonce, 1, 0, read, forbidden, computer).isEmpty());
        var answer = session.apply(nonce, 2, computer.programRevision(), read, () -> captured, computer).orElseThrow();
        assertEquals("ok", answer.code()); assertTrue(answer.snapshot().isEmpty());
        assertEquals(Optional.of(captured), answer.getClass().getMethod("capturedInputs").invoke(answer));
        var responseFactory = com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramStatusResponse.class.getMethod("fromAnswer",
            int.class, UUID.class, long.class, ComputerControlSession.Answer.class,
            com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits.class);
        var response = (com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramStatusResponse)
            responseFactory.invoke(null, 1, nonce, 2L, answer, ComputerBehaviourTest.LIMITS.archive());
        var view = (DataValue.RecordValue) com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire.decodeValue(response.status(), ComputerBehaviourTest.LIMITS.archive());
        assertEquals(captured, view.field("captured_inputs"));
        // The menu now compresses values: 40k repeated characters fit. Use incompressible content.
        byte[] noise=new byte[36_000];new java.util.Random(47).nextBytes(noise);
        var oversized = session.apply(nonce, 3, computer.programRevision(), read,
            () -> new DataValue.RecordValue(Map.of("peripherals", new DataValue.TextValue(java.util.Base64.getEncoder().encodeToString(noise)))), computer).orElseThrow();
        var rejected = (com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramStatusResponse)
            responseFactory.invoke(null, 1, nonce, 3L, oversized, ComputerBehaviourTest.LIMITS.archive());
        assertEquals("too_large", rejected.code()); assertEquals(0, rejected.status().length);
        computer.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
        computer.begin(ComputerBehaviourTest.EMPTY);
        var before = computer.snapshot();
        session.apply(nonce, 4, computer.programRevision(), read, () -> captured, computer);
        assertEquals(before, computer.snapshot());
    }
    @BeforeAll static void bootstrap() throws Exception { ComputerBehaviourTest.bootstrap(); }
    @Test void startPausedStepResumeAndResetOperateOnTheRealComputerOnly() {
        var owner = new Owner(); var computer = owner.getBehaviour(ComputerBehaviour.TYPE);
        computer.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
        UUID nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce); long revision = computer.programRevision();
        assertEquals("ok", session.apply(nonce, 1, revision, ComputerControlSession.Action.START_PAUSED, () -> ComputerBehaviourTest.EMPTY, computer).orElseThrow().code());
        owner.tick(); assertEquals(0, computer.snapshot().orElseThrow().machine().workUnits());
        session.apply(nonce, 2, revision, ComputerControlSession.Action.STEP, () -> ComputerBehaviourTest.EMPTY, computer);
        assertEquals(1, computer.snapshot().orElseThrow().machine().workUnits());
        assertTrue(computer.snapshot().orElseThrow().machine().paused());
        session.apply(nonce, 3, revision, ComputerControlSession.Action.RESUME, () -> ComputerBehaviourTest.EMPTY, computer);
        for (int i=0;i<30;i++) owner.tick();
        assertEquals(new DataValue.IntegerValue(1), computer.snapshot().orElseThrow().machine().memory().field("count"));
        session.apply(nonce, 4, revision, ComputerControlSession.Action.RESET, () -> ComputerBehaviourTest.EMPTY, computer);
        assertEquals(new DataValue.IntegerValue(0), computer.snapshot().orElseThrow().machine().memory().field("count"));
    }
    @Test void foreignReplayAndStaleControlsDoNotDecodeInputsOrMutateRuntime() {
        var owner = new Owner(); var computer = owner.getBehaviour(ComputerBehaviour.TYPE);
        computer.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
        UUID nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce);
        java.util.function.Supplier<DataValue.RecordValue> forbidden = () -> { fail("input decoding before authority check"); return null; };
        assertTrue(session.apply(UUID.randomUUID(), 1, 0, ComputerControlSession.Action.RUN, forbidden, computer).isEmpty());
        assertEquals("stale", session.apply(nonce, 1, 0, ComputerControlSession.Action.RUN, forbidden, computer).orElseThrow().code());
        assertTrue(session.apply(nonce, 1, computer.programRevision(), ComputerControlSession.Action.RUN, forbidden, computer).isEmpty());
        assertEquals(ComputationMachine.Status.IDLE, computer.snapshot().orElseThrow().machine().status());
    }
    @Test void eventInputsAreFrozenAndPausingDoesNotAffectAnotherComputer() {
        var first = new Owner(); var second = new Owner(); var a=first.getBehaviour(ComputerBehaviour.TYPE); var b=second.getBehaviour(ComputerBehaviour.TYPE);
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of("sample",DataType.Scalar.INTEGER),
            Map.of("input",new ComputationGraph.Input("sample")),Map.of("value","input")), Map.of(),List.of()),Map.of());
        a.install(archive,ComputerRuntime.MemoryPolicy.RESET); b.install(ComputerRuntimeTest.counter(1),ComputerRuntime.MemoryPolicy.RESET); b.begin(ComputerBehaviourTest.EMPTY);
        var mutable = new LinkedHashMap<String,DataValue>(); mutable.put("sample",new DataValue.IntegerValue(7));
        var input = new DataValue.RecordValue(mutable); UUID nonce=UUID.randomUUID(); var session=new ComputerControlSession(nonce);
        session.apply(nonce,1,a.programRevision(),ComputerControlSession.Action.START_PAUSED,()->input,a);
        mutable.put("sample",new DataValue.IntegerValue(99));
        for(int i=0;i<30;i++){ first.tick(); second.tick(); }
        assertEquals(0,a.snapshot().orElseThrow().machine().workUnits());
        assertEquals(ComputationMachine.Status.SUCCEEDED,b.snapshot().orElseThrow().machine().status());
        for(int i=2;i<12;i++)session.apply(nonce,i,a.programRevision(),ComputerControlSession.Action.STEP,()->input,a);
        assertEquals(new DataValue.IntegerValue(7),a.snapshot().orElseThrow().machine().output().orElseThrow().field("value"));
    }
    private static final class Owner extends SmartBlockEntity {
        Owner(){super(BlockEntityType.FURNACE,BlockPos.ZERO,Blocks.FURNACE.defaultBlockState());}
        @Override public void addBehaviours(List<BlockEntityBehaviour> list){list.add(new ComputerBehaviour(this,()->true,()->new HardwareSelection(HardwareSelection.Status.READY,Optional.empty(),"test",ComputerBehaviourTest.LIMITS)));}
        @Override public void setChanged(){}
    }
}
