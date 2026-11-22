package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import java.util.Optional;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.PanelSlot;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingNativeGaugeGameTests {
    private static final String AGENT = "CreateBiotechAgent", PROVIDER = "CreateBiotechFactoryProvider";

    private static FactoryPanelBlockEntity gauge(GameTestHelper helper, int x) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(x, 2, 2));
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos, AllBlocks.FACTORY_GAUGE.getDefaultState()
            .setValue(FactoryPanelBlock.FACE, AttachFace.FLOOR).setValue(FactoryPanelBlock.FACING, Direction.NORTH));
        var panel = (FactoryPanelBlockEntity) level.getBlockEntity(pos);
        panel.addPanel(PanelSlot.BOTTOM_LEFT, UUID.randomUUID());
        var slot = panel.panels.get(PanelSlot.BOTTOM_LEFT);
        slot.recipeAddress = "workbench";
        slot.setFilter(new ItemStack(Items.IRON_INGOT));
        return panel;
    }

    private static FactoryPanelBlockEntity reload(GameTestHelper helper, FactoryPanelBlockEntity original, CompoundTag disk) {
        var level = helper.getLevel();
        var pos = original.getBlockPos();
        var state = original.getBlockState();
        level.removeBlockEntity(pos);
        var restored = (FactoryPanelBlockEntity) BlockEntity.loadStatic(pos, state, disk, level.registryAccess());
        level.setBlockEntity(restored);
        return restored;
    }

    private static InteractionResult shiftClick(GameTestHelper h, FakePlayer player, FactoryPanelBlockEntity gauge) {
        var pos = gauge.getBlockPos();
        player.setPos(pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5);
        player.setShiftKeyDown(true);
        return player.gameMode.useItemOn(player, h.getLevel(), ItemStack.EMPTY, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void oldAgentCannotInterceptNativeGaugeInteraction(GameTestHelper h) {
        var plain = gauge(h, 2);
        var old = gauge(h, 5);
        var registries = h.getLevel().registryAccess();
        var disk = old.saveWithFullMetadata(registries);
        var configuration = new FactoryPanelAgentAccess.Configuration(UUID.randomUUID(), UUID.randomUUID(), 4,
            AgentEndpointRole.INPUT, "workbench", 3, CommunicationFrequency.of("7"), AgentCompletionMode.TARGET,
            Optional.empty(), AgentEndpointState.OutputMode.WAREHOUSE_ALL, 0, new CreateRouteKey(UUID.randomUUID(), "warehouse"));
        FactoryPanelAgentAccess.writeConfiguration(disk, configuration);
        h.assertValueEqual(FactoryPanelAgentAccess.readConfiguration(disk), Optional.of(configuration), "Known original Agent format");
        old = reload(h, old, disk);
        var player = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "gauge-test"));
        var nativeResult = shiftClick(h, player, plain);
        h.assertTrue(nativeResult != InteractionResult.FAIL, "Clean gauge reaches normal Create interaction");
        h.assertValueEqual(shiftClick(h, player, old), nativeResult, "Frozen Agent must not veto a normal Create interaction");
        h.assertValueEqual(old.saveWithFullMetadata(registries).get(AGENT), disk.get(AGENT), "Gesture does not reset the archive");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nativeGaugePreservesOpaqueDiskOnlyTagsAcrossTicksAndReload(GameTestHelper h) {
        var panel = gauge(h, 2);
        var registries = h.getLevel().registryAccess();
        var disk = panel.saveWithFullMetadata(registries);
        h.assertTrue(!disk.contains(AGENT) && !disk.contains(PROVIDER), "No legacy configuration manufactured");
        disk.putIntArray(AGENT, new int[]{3, -1, Integer.MAX_VALUE});
        var provider = new CompoundTag(); provider.putLongArray("Future", new long[]{42L});
        disk.put(PROVIDER, provider);
        var restored = reload(h, panel, disk);
        h.runAfterDelay(30, () -> {
            var saved = restored.saveWithFullMetadata(registries);
            h.assertValueEqual(saved.get(AGENT), disk.get(AGENT), "Malformed Agent survives without reinterpretation");
            h.assertValueEqual(saved.get(PROVIDER), provider, "Future provider survives without normalization");
            var packet = restored.getUpdateTag(registries);
            h.assertTrue(!packet.contains(AGENT) && !packet.contains(PROVIDER)
                && !packet.contains("NormalGoggles") && !packet.contains("AdvancedGoggleTarget"), "No retired data in native sync");
            packet.putString(AGENT, "ignore client value");
            restored.handleUpdateTag(packet, registries);
            h.assertValueEqual(restored.saveWithFullMetadata(registries).get(AGENT), disk.get(AGENT),
                "Display sync cannot replace disk evidence");
            var again = reload(h, restored, saved);
            var slot = again.panels.get(PanelSlot.BOTTOM_LEFT);
            h.assertTrue(slot.isActive() && slot.getFilter().is(Items.IRON_INGOT) && "workbench".equals(slot.recipeAddress),
                "Native filter, slot and recipe address survive two reconstructions");
            h.assertTrue(!FactoryPanelAgentAccess.class.isInstance(again), "No runtime agent interface on the actual Create block");
            var clean = saved.copy(); clean.remove(AGENT); clean.remove(PROVIDER);
            again.loadWithComponents(clean, registries);
            var cleared = again.saveWithFullMetadata(registries);
            h.assertTrue(!cleared.contains(AGENT) && !cleared.contains(PROVIDER), "Missing fields clear previous evidence on re-read");
            h.succeed();
        });
    }
}
