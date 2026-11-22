package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.EnclosureScanner;
import com.simibubi.create.AllItems;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingShellGameTests {
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void mixedEnclosureKeepsAirAndWrenchBreakRepairAcrossBothCasings(GameTestHelper helper) {
        var level = helper.getLevel(); var min = helper.absolutePos(new BlockPos(3, 1, 1)); var max = min.offset(4, 3, 2);
        var member = BiocomputingWorldGameTests.computer(helper, min.offset(1, 1, 1));
        for (var pos : BlockPos.betweenClosed(min, max)) {
            if (pos.getX() == min.getX() || pos.getX() == max.getX() || pos.getY() == min.getY() || pos.getY() == max.getY()
                    || pos.getZ() == min.getZ() || pos.getZ() == max.getZ()) {
                var block = (pos.getX() - min.getX()) % 2 == 0 ? CBBlocks.COMPUTER_CASING.get() : CBBlocks.TRANSPARENT_COMPUTER_CASING.get();
                level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        var controllerPos = min.offset(0, 1, 1);
        level.setBlock(controllerPos, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING, Direction.WEST), Block.UPDATE_ALL);
        var controller = (ClusterControllerBlockEntity)level.getBlockEntity(controllerPos);
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "shell-wrench"));
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL); player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
        player.setShiftKeyDown(true); player.moveTo(Vec3.atCenterOf(min.west()));
        helper.assertTrue(level.getBlockState(min.offset(2, 1, 1)).isAir(), "Interior air is not filled to form a Cluster");
        helper.assertValueEqual(controller.enclosure().status(), EnclosureScanner.Status.READY, "Mixed registered casings form one closed cuboid");
        for (var pos : List.of(min, min.east())) {
            var state = level.getBlockState(pos);
            helper.assertTrue(player.getInventory().countItem(state.getBlock().asItem()) == 0, "No preloaded casing drops");
            helper.assertTrue(wrench(player, pos).consumesAction(), "Public Create wrench item accepts sneak dismantling");
            helper.assertTrue(level.getBlockState(pos).isAir(), "Both physical casing implementations dismantle normally");
            helper.assertValueEqual(player.getInventory().countItem(state.getBlock().asItem()), 1, "Original Create fallback returns one casing");
            helper.assertTrue(controller.enclosure().status() != EnclosureScanner.Status.READY, "Broken boundary is not a formed Cluster");
            level.setBlock(pos, state, Block.UPDATE_ALL);
            helper.assertValueEqual(controller.enclosure().status(), EnclosureScanner.Status.READY, "Repair restores same geometry without reconfiguring");
        }
        helper.assertTrue(level.getBlockEntity(member.getBlockPos()) == member, "Dismantling shell must not replace the independent Computer");
        helper.succeed();
    }

    private static InteractionResult wrench(FakePlayer player, BlockPos pos) {
        var context = new UseOnContext(player, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        return AllItems.WRENCH.get().useOn(context);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void registeredCasingsHaveNewOwnersAndStableSavedIdentities(GameTestHelper helper) {
        for (var block : List.of(CBBlocks.COMPUTER_CASING.get(), CBBlocks.TRANSPARENT_COMPUTER_CASING.get())) {
            helper.assertValueEqual(block.getClass().getPackageName(),
                "com.nobodiiiii.createbiotech.content.biocomputing.block", "Actual registry must use new physical owners");
            var state = block.defaultBlockState();
            var material = ((ComputerShellBlock)block).shellMaterial(state);
            String id = material == ComputerShellMaterial.OPAQUE ? "computer_casing" : "transparent_computer_casing";
            helper.assertValueEqual(BuiltInRegistries.BLOCK.getKey(block).toString(), "create_biotech:" + id, "Stable registry identity");
            var encoded = BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
            helper.assertValueEqual(BlockState.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow(), state, "Existing world block state still resolves");
            helper.assertValueEqual(material.getSerializedName(), material == ComputerShellMaterial.OPAQUE ? "opaque" : "transparent", "Stable material property");
            var saved = CurrentSchemaStateNbt.save(material, helper.getLevel().registryAccess());
            helper.assertTrue(saved.toString().contains("factorycluster.computer.ComputerShellMaterial"), "Historical schema identity is exact");
            helper.assertValueEqual(CurrentSchemaStateNbt.load(saved, helper.getLevel().registryAccess(), ComputerShellMaterial.class).orElseThrow(), material, "Historical enum restores");
        }
        helper.succeed();
    }
}
