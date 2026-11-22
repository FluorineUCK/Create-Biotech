package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
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

public class NativeLibraryTerminalSessionTest {
    public static final ModuleDefinition.Key WRITER = new ModuleDefinition.Key("library_write_a", 1);
    public static final String INSTANCE = "4:root/5:input";
    @BeforeAll public static void bootstrap() throws Exception { TerminalBehaviourTest.bootstrap(); }
    public static ProgramDocument published() throws Exception {
        try (var input = NativeLibraryTerminalSessionTest.class.getResourceAsStream("/assets/create_biotech/biocomputing/examples/library_terminal_a.bcp")) {
            assertNotNull(input);
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(input.readAllBytes()).trim()), NativeLibraryOwnersTest.limits("cleric",5).archive());
        }
    }
    /** Test-only physical adapter uses an unmodified default program5 owner. Reflection exposes only its owner to the wire fixture. */
    public static final class Block extends SmartBlockEntity {
        public Block() { super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState()); }
        @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
            behaviours.add(new ComputerBehaviour(this, () -> true, () -> new HardwareSelection(HardwareSelection.Status.READY,
                Optional.of(new ResidentHardwareIdentity(ResourceLocation.parse("minecraft:villager"),ResourceLocation.parse("minecraft:cleric"),5)),
                "parser", NativeLibraryOwnersTest.limits("cleric",5))));
        }
        @Override public void setChanged() {}
    }
    public static ComputerBlockHost host(ComputerBehaviour behaviour) throws Exception {
        var field = ComputerBehaviour.class.getDeclaredField("host"); field.setAccessible(true); return (ComputerBlockHost)field.get(behaviour);
    }
    public static final class Fixture {
        public ComputerBehaviour computer;
        public ComputerBlockHost owner;
        public final UUID nonce = UUID.randomUUID();
        public ComputerControlSession session = new ComputerControlSession(nonce);
        public long intent;
        public Fixture() throws Exception {
            computer = new Block().getBehaviour(ComputerBehaviour.TYPE);
            computer.installDocument(published(), ComputerRuntime.MemoryPolicy.RESET);
            owner = host(computer);
        }
        public ComputerControlSession.Answer apply(ComputerControlSession.Action action, DataValue.RecordValue payload) {
            return session.apply(nonce, ++intent, computer.programRevision(), action, () -> payload, computer).orElseThrow();
        }
        public TerminalStatusView read() {
            return TerminalStatusView.decode(apply(ComputerControlSession.Action.READ_TERMINAL, new DataValue.RecordValue(Map.of())).dedicatedStatus().orElseThrow());
        }
        public void start() {
            assertEquals("ok", apply(ComputerControlSession.Action.RESUME,new DataValue.RecordValue(Map.of())).code());
            for (int i=0;i<20 && computer.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;i++) computer.step();
            assertEquals(FlowRuntime.Status.COMPLETE,computer.flowSnapshot().orElseThrow().state().status());
            assertTrue(computer.flowSnapshot().orElseThrow().tasks().contexts().isEmpty());
        }
        DataValue.RecordValue publishedInitial() { return computer.document().orElseThrow().flow().orElseThrow().initial(); }
        public String submit(String text) { long view = intent; return apply(ComputerControlSession.Action.TERMINAL_SUBMIT,new TerminalSubmit(view,INSTANCE,text).encode()).code(); }
        public void restore() throws Exception {
            var before=computer.flowSnapshot().orElseThrow(); var tag=new CompoundTag(); computer.write(tag,RegistryAccess.EMPTY,false);
            var next = new Block().getBehaviour(ComputerBehaviour.TYPE); next.read(tag,RegistryAccess.EMPTY,false);
            assertEquals(published(),next.document().orElseThrow()); next.terminalStatus();
            assertEquals(before,next.flowSnapshot().orElseThrow(),"Restore cannot reset, reinstall, replay, or drop task/control/history");
            computer=next; owner=host(next); session=new ComputerControlSession(nonce);
        }
    }
    @Test void frozenPublicSubmissionAdmitsExactlyOneTextTaskWithoutMovingMainAndRestoreKeepsItWaiting() throws Exception {
        var f=new Fixture(); var installed=f.computer.flowSnapshot(); f.read();
        assertEquals(installed,f.computer.flowSnapshot()); assertNotEquals("ok",f.submit("not yet published"));
        assertEquals(installed,f.computer.flowSnapshot());
        f.start(); f.computer.setPaused(true); f.read(); var paused=f.computer.flowSnapshot();
        assertEquals("busy",f.submit("paused")); assertEquals(paused,f.computer.flowSnapshot());
        f.computer.setPaused(false); var main=f.computer.flowSnapshot().orElseThrow().state();
        assertEquals(f.publishedInitial(),f.read().roots().field("output"));
        assertEquals("ok",f.submit("{\"literal\":42}"));
        var admitted=f.computer.flowSnapshot().orElseThrow(); assertEquals(1,admitted.tasks().contexts().size()); assertEquals(main,admitted.state());
        assertEquals(new DataValue.TextValue("{\"literal\":42}"),admitted.tasks().contexts().getFirst().locals().field("replacement"));
        assertEquals("invalid",f.submit("stale publication"));
        var revision=f.computer.programRevision();
        assertTrue(f.session.apply(f.nonce,f.intent,revision,ComputerControlSession.Action.TERMINAL_SUBMIT,()->{throw new AssertionError("Replay decoded");},f.computer).isEmpty());
        for(int i=0;i<100;i++)f.owner.tick(NativeLibraryOwnersTest.limits("cleric",5),true);
        var waiting=f.computer.flowSnapshot().orElseThrow(); assertEquals(main,waiting.state());
        assertEquals(FlowRuntime.Status.WAITING_WRITE,waiting.tasks().contexts().getFirst().status());
        var observed=f.read(); var row=(DataValue.RecordValue)((DataValue.ListValue)observed.roots().field("tasks")).values().getFirst();
        assertEquals(DataValue.NullValue.INSTANCE,row.field("result")); assertEquals(DataValue.NullValue.INSTANCE,row.field("failure"));
        assertEquals(waiting,f.computer.flowSnapshot().orElseThrow()); f.restore();
        assertEquals(waiting,f.computer.flowSnapshot().orElseThrow()); f.read(); assertEquals(waiting,f.computer.flowSnapshot().orElseThrow());
    }
    @Test void wrongArgumentsAndActualRamAndSlotRejectionsDoNotMutateOwner() throws Exception {
        var f=new Fixture(); f.start(); var limits=NativeLibraryOwnersTest.limits("cleric",5); var before=f.owner.write();
        assertThrows(RuntimeException.class,()->f.computer.startFlowTask(WRITER,new DataValue.RecordValue(Map.of("wrong",new DataValue.IntegerValue(1)))));
        assertEquals(before,f.owner.write());
        // Literal TEXT through the published control, large enough for actual owner aggregate RAM rejection (not a UI parser).
        f.read(); assertEquals("ok",f.submit("x".repeat(24000))); var occupied=f.owner.write();
        f.read(); assertEquals("memory_limit",f.submit("x".repeat(24000))); assertEquals(occupied,f.owner.write());
        var slots=new Fixture(); slots.start();
        for(int i=1;i<limits.taskSlots();i++){ slots.read(); assertEquals("ok",slots.submit("one operation requires player coordination")); }
        var full=slots.owner.write(); slots.read(); assertEquals("busy",slots.submit("full")); assertEquals(full,slots.owner.write());
        assertEquals(limits.taskSlots()-1,slots.computer.flowSnapshot().orElseThrow().tasks().contexts().size());
        System.out.println("terminal A actual limits="+limits+" retained slots="+(limits.taskSlots()-1));
    }
}
