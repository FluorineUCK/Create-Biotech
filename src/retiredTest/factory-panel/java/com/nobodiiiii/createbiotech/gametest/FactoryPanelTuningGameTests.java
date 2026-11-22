package com.nobodiiiii.createbiotech.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelBlockEntity;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelBlockItem;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real registered Panel item, Create blocks, NeoForge click events and native assignment Mixin. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public class FactoryPanelTuningGameTests {
    @GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
    public static void panelTunesRetunesClearsAndEmbedsInSurvival(GameTestHelper helper) {
        exercise(helper, GameType.SURVIVAL);
    }

    @GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
    public static void panelTunesRetunesClearsAndEmbedsInCreative(GameTestHelper helper) {
        exercise(helper, GameType.CREATIVE);
    }

    @GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
    public static void nativeAssignmentCannotRetuneAnOfflineCoordinatorReplica(GameTestHelper helper) {
        var player = new TuningPlayer(helper.getLevel(), GameType.SURVIVAL);
        ItemStack stack = new ItemStack(CBBlocks.FACTORY_PANEL.get());
        CompoundTag tag = new CompoundTag();
        tag.putUUID("PanelId", UUID.randomUUID());
        tag.put("BindingState", new ClusterBinding(UUID.randomUUID(), 7,
            new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, UUID.randomUUID()),
            List.of(new LogisticsBinding(UUID.randomUUID()))).save());
        stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(tag));
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        LogisticallyLinkedBlockItem.assignFrequency(stack, player, UUID.randomUUID());
        helper.assertValueEqual(stack.get(DataComponents.BLOCK_ENTITY_DATA).copyTag(), tag,
            "Native gauge assignment must not bypass the Panel's existing offline-authority fence");
        helper.assertTrue(player.messages.contains("create_biotech.factory_cluster.binding.authority_offline"),
            "Rejected native tuning must explain its authority fence");
        helper.succeed();
    }

    private static void exercise(GameTestHelper helper, GameType mode) {
        ServerLevel level = helper.getLevel();
        var player = new TuningPlayer(level, mode);
        BlockPos linkPos = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos tickerPos = linkPos.east(2);
        BlockPos shellPos = linkPos.south(2);
        level.setBlock(linkPos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(linkPos, AllBlocks.STOCK_LINK.getDefaultState()
            .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR), Block.UPDATE_ALL);
        level.setBlock(tickerPos, AllBlocks.STOCK_TICKER.getDefaultState(), Block.UPDATE_ALL);
        level.setBlock(shellPos, CBBlocks.COMPUTER_CASING.get().defaultBlockState(), Block.UPDATE_ALL);
        player.moveTo(Vec3.atCenterOf(linkPos));
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        configureNetwork(level, linkPos, first, player);
        configureNetwork(level, tickerPos, second, player);
        try {
            ItemStack stack = new ItemStack(CBBlocks.FACTORY_PANEL.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            click(player, level, linkPos, Direction.UP);
            assertTuned(helper, player.getMainHandItem(), first);
            helper.assertTrue(player.messages.contains("create.logistically_linked.tuned"),
                "Right click must reach native tuned feedback, not silently fail in shell embedding");
            helper.assertTrue(level.getBlockState(linkPos).is(AllBlocks.STOCK_LINK.get()),
                "Capturing a network must not replace the linked component");
            helper.assertValueEqual(stack.getCount(), 1, "Tuning must not consume the Panel");

            click(player, level, tickerPos, Direction.UP);
            assertTuned(helper, stack, second);
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
            try {
                ItemStack.STREAM_CODEC.encode(buffer, stack);
                assertTuned(helper, ItemStack.STREAM_CODEC.decode(buffer), second);
            } finally { buffer.release(); }

            stack.getItem().use(level, player, InteractionHand.MAIN_HAND);
            helper.assertTrue(LogisticallyLinkedBlockItem.networkFromStack(stack) == null,
                "Native clear must remove the frequency observed by the outline renderer");
            helper.assertFalse(stack.hasFoil(), "Clear must remove foil");
            helper.assertTrue(FactoryPanelBlockItem.readBindings(stack).isEmpty(), "Clear must remove pending bindings");
            helper.assertTrue(player.messages.contains("create.logistically_linked.cleared"), "Native clear feedback");

            click(player, level, linkPos, Direction.UP);
            assertTuned(helper, stack, first);
            click(player, level, shellPos, Direction.EAST);
            helper.assertTrue(level.getBlockEntity(shellPos) instanceof FactoryPanelBlockEntity,
                "Tuned Panel must still embed in a computer casing");
            var placed = (FactoryPanelBlockEntity) level.getBlockEntity(shellPos);
            helper.assertValueEqual(placed.logisticsBindings(), List.of(new LogisticsBinding(first)),
                "Embedding must retain the native captured network in the existing binding state");
            assertTuned(helper, placed.asConfiguredStack(level.registryAccess()), first);
            helper.assertValueEqual(stack.getCount(), mode == GameType.CREATIVE ? 1 : 0,
                "Only survival embedding consumes the item");
            helper.succeed();
        } finally {
            Create.LOGISTICS.linkRemoved(first, GlobalPos.of(level.dimension(), linkPos));
            Create.LOGISTICS.linkRemoved(second, GlobalPos.of(level.dimension(), tickerPos));
        }
    }

    private static void configureNetwork(ServerLevel level, BlockPos pos, UUID network, TuningPlayer player) {
        var linked = BlockEntityBehaviour.get(level, pos, LogisticallyLinkedBehaviour.TYPE);
        if (linked == null) throw new AssertionError("Real linked behaviour missing");
        linked.freqId = network;
        Create.LOGISTICS.linkAdded(network, GlobalPos.of(level.dimension(), pos), player.getUUID());
    }

    private static void click(TuningPlayer player, ServerLevel level, BlockPos pos, Direction face) {
        player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false));
    }

    private static void assertTuned(GameTestHelper helper, ItemStack stack, UUID network) {
        helper.assertTrue(network.equals(LogisticallyLinkedBlockItem.networkFromStack(stack)),
            "Panel must capture the exact native Freq");
        helper.assertTrue(stack.getItem() instanceof LogisticallyLinkedBlockItem
            && LogisticallyLinkedBlockItem.isTuned(stack), "Create client outline eligibility");
        helper.assertTrue(stack.hasFoil(), "Synced Panel stack must render native foil");
        helper.assertValueEqual(FactoryPanelBlockItem.readBindings(stack), List.of(new LogisticsBinding(network)),
            "Native frequency and existing placement binding must agree");
    }

    private static final class TuningPlayer extends FakePlayer {
        final List<String> messages = new ArrayList<>();
        TuningPlayer(ServerLevel level, GameType mode) {
            super(level, new GameProfile(UUID.randomUUID(), "panel-tuning"));
            gameMode.changeGameModeForPlayer(mode);
        }
        @Override public void displayClientMessage(Component message, boolean actionBar) {
            if (message.getContents() instanceof TranslatableContents translated)
                messages.add(translated.getKey());
        }
    }
}
