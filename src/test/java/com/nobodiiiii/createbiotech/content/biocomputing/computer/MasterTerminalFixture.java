package com.nobodiiiii.createbiotech.content.biocomputing.computer;
import java.util.*;
    /** Only the published native ACK test needs Master; other terminal tests keep their original fixture. */
public final class MasterTerminalFixture extends com.simibubi.create.foundation.blockEntity.SmartBlockEntity {
        public MasterTerminalFixture(){super(net.minecraft.world.level.block.entity.BlockEntityType.FURNACE,net.minecraft.core.BlockPos.ZERO,net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState());}
        @Override public void addBehaviours(List<com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour> behaviours){
            behaviours.add(new ComputerBehaviour(this,()->true,()->new com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection(
                com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection.Status.READY,
                Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentHardwareIdentity(net.minecraft.resources.ResourceLocation.parse("minecraft:villager"),net.minecraft.resources.ResourceLocation.parse("minecraft:cleric"),5)),
                "master",com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.NativeLibraryOwnersTest.limits("cleric",5))));
        }
        @Override public void setChanged(){}
    }
