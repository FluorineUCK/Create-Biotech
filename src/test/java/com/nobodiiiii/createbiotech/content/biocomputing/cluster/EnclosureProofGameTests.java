package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlock;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class EnclosureProofGameTests {
    @GameTest(template="empty")
    public static void guardedWindowStillSeesSameTickChangesAcrossChunkColumns(GameTestHelper h) {
        var level=h.getLevel();
        var base=h.absolutePos(new BlockPos(1,2,1));
        // Cross the next X chunk boundary with the shell itself.
        var min=new BlockPos((base.getX() & ~15)+14,base.getY(),base.getZ()); var max=min.offset(4,4,4);
        var door=min.offset(0,1,2);
        var casing=CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState();
        for(var pos:BlockPos.betweenClosed(min,max)) {
            boolean shell=pos.getX()==min.getX()||pos.getX()==max.getX()||pos.getY()==min.getY()||
                pos.getY()==max.getY()||pos.getZ()==min.getZ()||pos.getZ()==max.getZ();
            level.setBlock(pos,shell?casing:Blocks.AIR.defaultBlockState(),3);
        }
        level.setBlock(door,CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState()
            .setValue(ClusterControllerBlock.FACING,Direction.WEST),3);
        var member = min.offset(1,1,2);
        var lower = CBBlocks.COMPUTER.get().defaultBlockState();
        level.setBlock(member,lower,2);
        level.setBlock(member.above(),lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER),2);
        var limits=new EnclosureLimits(3,7,32);
        var world=new WorldEnclosureView(level,door);
        var guard=new ClusterStructureGuard();
        h.assertValueEqual(guard.current(world,door,limits).status(),EnclosureScanner.Status.READY,"Intact shell");
        var proof=guard.proof().orElseThrow();
        // Delegating the observations selects the original one-world-read-per-position path.
        var reference=new EnclosureScanner.View() {
            public boolean loaded(BlockPos pos){return world.loaded(pos);}
            public boolean sameSpace(BlockPos a,BlockPos b){return world.sameSpace(a,b);}
            public EnclosureScanner.Cell cell(BlockPos pos){return world.cell(pos);}
            public EnclosureScanner.Cell observe(BlockPos a,BlockPos b){return world.observe(a,b);}
        };
        for(var v:java.util.List.of(world,reference)){
            h.assertTrue(proof.unchanged(v,door,limits),"Initial agreement");
        }
        for(var changed:java.util.List.of(max,min.offset(2,1,2),door.west(2))){
            var previous=level.getBlockState(changed);
            level.setBlock(changed,previous.isAir()?casing:Blocks.AIR.defaultBlockState(),3);
            h.assertFalse(proof.unchanged(world,door,limits),"Cached geometry must notice "+changed);
            h.assertFalse(proof.unchanged(reference,door,limits),"Reference must notice "+changed);
            level.setBlock(changed,previous,3);
            h.assertTrue(proof.unchanged(world,door,limits),"Exact repair");
        }
        // An alternate valid shell state can match through the fallback. But rotating a member
        // invalidates the old proof, even before a neighbor event or a new game tick.
        level.setBlock(max,CBBlocks.COMPUTER_CASING.get().defaultBlockState(),2);
        h.assertTrue(proof.unchanged(world,door,limits),"Opaque and transparent are both shell");
        level.setBlock(member,lower.setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST),2);
        h.assertFalse(proof.unchanged(world,door,limits),"Same computer block, different facing");
        level.setBlock(member,lower,2);
        h.assertTrue(proof.unchanged(world,door,limits),"Repair is visible in the same tick");
        // Measurement is observational, not a timing assertion or a simulation speed override.
        for(int i=0;i<32;i++){proof.unchanged(world,door,limits); proof.unchanged(reference,door,limits);}
        long grouped=0,scalar=0;
        for(int n=0;n<4;n++){
            for(int k=0;k<2;k++){
                boolean fast=((n+k)&1)==0;
                var v=fast?world:reference;
                long start=System.nanoTime();
                for(int i=0;i<128;i++) h.assertTrue(proof.unchanged(v,door,limits),"Fresh evidence");
                long elapsed=System.nanoTime()-start;
                if(fast) grouped+=elapsed; else scalar+=elapsed;
            }
        }
        System.out.println("LOGISTICS_ENCLOSURE sample=512 grouped_ns="+grouped+" scalar_ns="+scalar+" section_path="+world.canReadSection(door));
        h.succeed();
    }
}
