package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;

class LegacyBroadcastQuestionTest {
    static final ArchiveLimits ARCHIVE=new ArchiveLimits(10000,100000,1000000);
    static final ComputerHostLimits STOCK = new ComputerHostLimits(new ResourceLimits(256,65536,8),ARCHIVE,
        MailboxLimits.DEFAULT,HardwarePins.DEFAULT,SerialHardware.NONE,4,true,true);
    static class Host extends SmartBlockEntity {
        Host(){ super(BlockEntityType.FURNACE,BlockPos.ZERO,Blocks.FURNACE.defaultBlockState()); }
        @Override public void addBehaviours(List<BlockEntityBehaviour> all){ all.add(new ComputerBehaviour(this,()->true,
            ()->new HardwareSelection(HardwareSelection.Status.READY,Optional.empty(),"parser",STOCK))); }
        @Override public void setChanged() {}
    }
    @BeforeAll static void boot() throws Exception { TerminalBehaviourTest.bootstrap(); }
    static FlowBlockHost.Saved peek(ComputerBehaviour c){ return c.flowSnapshot().orElseThrow(); }
    static long score(ComputerBehaviour c){ return ((DataValue.IntegerValue)peek(c).memory().cells().field("score")).value(); }
    static void tick(ComputerBehaviour c){ for(int i=0;i<64;i++) c.tick(); }
    static DataValue.RecordValue empty(){ return new DataValue.RecordValue(Map.of()); }

    // Runtime, active source and installation fields extracted from a real v46 Computer (not a regenerated program).
    @Test void oldBroadcastAndQuestionResumeOnceThroughTheInstalledTerminal() throws Exception {
        var c=new Host().getBehaviour(ComputerBehaviour.TYPE);
        try(var bytes=Objects.requireNonNull(getClass().getResourceAsStream("/biocomputing/owner46-terminal-ask.nbt.gz"))) {
            c.read(net.minecraft.nbt.NbtIo.readCompressed(bytes,net.minecraft.nbt.NbtAccounter.create(16*1024*1024)),null,false);
        }
        tick(c);
        assertTrue(c.diagnostic().isEmpty(),()->String.valueOf(c.diagnostic()));
        var doc=c.document().orElseThrow();
        assertEquals(doc,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(doc)), "Editing round-trip retains terminal hats and ask");
        assertEquals("ping",doc.flow().orElseThrow().canvas().terminalHats().get("button_hat"));
        var saved=peek(c);var state=saved.state();assertEquals(FlowRuntime.Status.WAITING_TASK,state.status());
        assertEquals(UUID.fromString("b7831e04-1da1-41b9-81d3-42e864d2722d"),state.flow());assertEquals(1L,state.work());
        assertEquals(30L,score(c));var menuId=UUID.randomUUID();var menu=new ComputerControlSession(menuId);long rev=c.programRevision();
        var stale=menu.apply(menuId,1,rev,ComputerControlSession.Action.TERMINAL_SUBMIT,()->new TerminalSubmit(1,TerminalSubmit.QUESTION,"stale").encode(),c).orElseThrow();
        assertEquals("invalid",stale.code());assertEquals(state,peek(c).state());
        var view=menu.apply(menuId,2,rev,ComputerControlSession.Action.READ_TERMINAL,()->empty(),c).orElseThrow();
        assertEquals("ok",view.code(),view.detail());assertEquals("Which batch?",TerminalStatusView.decode(view.dedicatedStatus().orElseThrow()).question().orElseThrow().text());
        var ok=menu.apply(menuId,3,rev,ComputerControlSession.Action.TERMINAL_SUBMIT,()->new TerminalSubmit(2,TerminalSubmit.QUESTION,"batch-71").encode(),c).orElseThrow();
        assertEquals("ok",ok.code(),ok.detail());
        var duplicate=menu.apply(menuId,4,rev,ComputerControlSession.Action.TERMINAL_SUBMIT,()->new TerminalSubmit(2,TerminalSubmit.QUESTION,"repeat").encode(),c).orElseThrow();
        assertEquals("invalid",duplicate.code());
        tick(c); // This player-edited document adds 90 in main_done, not the template's 100.
        assertEquals(121L,score(c));assertEquals(state.flow(),peek(c).state().flow());
        assertEquals(FlowRuntime.Status.COMPLETE,peek(c).state().status());
        assertEquals(new DataValue.TextValue("batch-71"),peek(c).prompts().answer());
        assertTrue(TerminalStatusView.decode(c.terminalStatus()).question().isEmpty());
    }
}
