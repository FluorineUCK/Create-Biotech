package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.util.FakePlayer;

final class AuthoredLibraryWorldFixture {
    static ComputerBehaviour host(ComputerBlockEntity b) {return Objects.requireNonNull(b.getBehaviour(ComputerBehaviour.TYPE));}
    static ComputerBlockEntity computer(GameTestHelper h,BlockPos pos,VillagerProfession profession,int rank) {
        var l=h.getLevel();l.setBlock(pos.below(),Blocks.STONE.defaultBlockState(),3);var lower=CBBlocks.COMPUTER.get().defaultBlockState().setValue(ComputerBlock.HALF,DoubleBlockHalf.LOWER);l.setBlock(pos,lower,3);l.setBlock(pos.above(),lower.setValue(ComputerBlock.HALF,DoubleBlockHalf.UPPER),3);
        var b=(ComputerBlockEntity)l.getBlockEntity(pos);var villager=Objects.requireNonNull(EntityType.VILLAGER.create(l));villager.setBaby(false);villager.setVillagerData(new VillagerData(VillagerType.PLAINS,profession,rank));var box=new ItemStack(CBItems.CARDBOARD_BOX.get());h.assertTrue(CapturedEntityBoxHelper.captureEntity(box,villager),"Capture actual default resident");var player=new FakePlayer(l,new GameProfile(UUID.randomUUID(),"read-resident"));player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);player.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));player.setItemInHand(InteractionHand.MAIN_HAND,box);h.assertValueEqual(b.installResident(player,InteractionHand.MAIN_HAND),ComputerInstallResult.SUCCESS,"Normal resident install");return b;
    }
    static BlockEntity reconstruct(ServerLevel level,BlockEntity before) {var pos=before.getBlockPos();var state=before.getBlockState();var tag=before.saveWithFullMetadata(level.registryAccess());level.removeBlockEntity(pos);var next=Objects.requireNonNull(BlockEntity.loadStatic(pos,state,tag,level.registryAccess()));level.setBlockEntity(next);return next;}
}
