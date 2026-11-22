package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import static org.junit.jupiter.api.Assertions.*;

/** Headless physical lifecycle seam, not a terminal or application executor. */
public final class ReaderSelectionTerminalFixture {
    public final ComputerBehaviour computer;
    private final ComputerHostLimits limits;
    public ReaderSelectionTerminalFixture(ComputerHostLimits limits)throws Exception{
        TerminalBehaviourTest.bootstrap();this.limits=limits;computer=new Harness(limits).getBehaviour(ComputerBehaviour.TYPE);
    }
    public void install(ProgramDocument document){assertEquals(ComputerRuntime.InstallResult.APPLIED,computer.installDocument(document,ComputerRuntime.MemoryPolicy.RESET));}
    /** Observe the same actual host for the existing test network; never inject its state or outputs. */
    public ComputerBlockHost host(){try{var field=ComputerBehaviour.class.getDeclaredField("host");field.setAccessible(true);return (ComputerBlockHost)field.get(computer);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    public ReaderSelectionTerminalFixture cold()throws Exception{return cold(limits);}
    public ReaderSelectionTerminalFixture cold(ComputerHostLimits newLimits)throws Exception{
        computer.setPaused(true);var before=computer.flowSnapshot();var document=computer.document();var tag=new CompoundTag();computer.write(tag,RegistryAccess.EMPTY,false);host().unload();
        var next=new ReaderSelectionTerminalFixture(newLimits);next.computer.read(tag,RegistryAccess.EMPTY,false);next.computer.terminalStatus();assertEquals(before,next.computer.flowSnapshot());assertEquals(document,next.computer.document());next.computer.setPaused(false);return next;
    }
    private static final class Harness extends SmartBlockEntity {
        private ComputerHostLimits limits;
        Harness(ComputerHostLimits limits){super(BlockEntityType.FURNACE,BlockPos.ZERO,Blocks.FURNACE.defaultBlockState());this.limits=limits;}
        @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours){behaviours.add(new ComputerBehaviour(this,()->true,()->new HardwareSelection(HardwareSelection.Status.READY,Optional.empty(),"default-master-cleric",limits)));}
        @Override public void setChanged(){}
    }
}
