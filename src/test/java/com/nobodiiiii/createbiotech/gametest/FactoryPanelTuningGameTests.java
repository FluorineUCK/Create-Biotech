package com.nobodiiiii.createbiotech.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBindingCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.MaterialRequestPortBlockEntity;
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
    public static void nativeSelectionDoesNotModifyArchivedCoordinatorOrPort(GameTestHelper helper) {
        var player = new TuningPlayer(helper.getLevel(), GameType.SURVIVAL);
        ItemStack stack = new ItemStack(CBBlocks.MATERIAL_REQUEST_PORT.get());
        CompoundTag tag = new CompoundTag();
        tag.putUUID("PanelId", UUID.randomUUID());
        tag.put("BindingState", ProductionBindingCodec.save(new ProductionBinding(UUID.randomUUID(), 7,
            new ProductionBinding.Authority(ProductionBinding.Role.COORDINATOR, UUID.randomUUID()),
            List.of(UUID.randomUUID()))));
        stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(tag));
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        tag.putString("id", "create_biotech:biocomputing_material_request_port");
        var port = new CompoundTag(); port.putUUID("Identity", UUID.randomUUID());
        tag.put("BiocomputingProductionPort", port);
        stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(tag));
        var selected = UUID.randomUUID();
        LogisticallyLinkedBlockItem.assignFrequency(stack, player, selected);
        helper.assertValueEqual(LogisticallyLinkedBlockItem.networkFromStack(stack), selected,
            "An old coordinator replica is inert; a player can still select a native network");
        player.getAbilities().mayBuild = false;
        LogisticallyLinkedBlockItem.assignFrequency(stack, player, UUID.randomUUID());
        helper.assertValueEqual(LogisticallyLinkedBlockItem.networkFromStack(stack), selected, "Assignment still checks the player");
        player.getAbilities().mayBuild = true;
        stack.getItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertValueEqual(stack.get(DataComponents.BLOCK_ENTITY_DATA).copyTag(), tag,
            "Clearing the native selection preserves the legacy replica and exact portable material service");
        helper.assertTrue(player.messages.contains("create.logistically_linked.cleared"), "Use the normal Create clear interaction");
        helper.succeed();
    }

    @GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
    public static void foreignPortComponentCannotReplaceTheCasing(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var casing = CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState();
        level.setBlock(pos, casing, Block.UPDATE_ALL);
        var player = new TuningPlayer(level, GameType.SURVIVAL); player.moveTo(Vec3.atCenterOf(pos));
        var stack = new ItemStack(CBBlocks.MATERIAL_REQUEST_PORT.get());
        var foreign = new CompoundTag(); foreign.putString("id", "minecraft:chest");
        stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(foreign)); player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        click(player, level, pos, Direction.WEST);
        helper.assertValueEqual(level.getBlockState(pos), casing, "Foreign device data cannot replace the shell");
        helper.assertValueEqual(stack.getCount(), 1, "A rejected mount consumes no item");
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
            ItemStack stack = new ItemStack(CBBlocks.MATERIAL_REQUEST_PORT.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            click(player, level, linkPos, Direction.UP);
            assertTuned(helper, player.getMainHandItem(), first);
            helper.assertTrue(com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool.BoundLogisticsTooltipData
                .read(stack).flatMap(names -> names.find(first)).isPresent(),
                "Tuning a real stock link must show the selected network without the retired production scanner");
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
            helper.assertFalse(stack.getOrDefault(DataComponents.BLOCK_ENTITY_DATA, CustomData.EMPTY).copyTag().contains("LogisticsBindings"),
                "Selecting a native frequency never creates a legacy production binding");
            helper.assertTrue(player.messages.contains("create.logistically_linked.cleared"), "Native clear feedback");

            click(player, level, linkPos, Direction.UP);
            assertTuned(helper, stack, first);
            click(player, level, shellPos, Direction.EAST);
            helper.assertTrue(level.getBlockEntity(shellPos) instanceof MaterialRequestPortBlockEntity,
                "Tuned Panel must still embed in a computer casing");
            var placed = (MaterialRequestPortBlockEntity) level.getBlockEntity(shellPos);
            helper.assertTrue(!placed.saveWithoutMetadata(level.registryAccess()).contains("LogisticsBindings"), "An embedded native network selection is not a legacy cluster binding");
            var service = placed.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionPortBehaviour.TYPE);
            helper.assertTrue(service.revision() == 0 && service.status().field("binding") == com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.NullValue.INSTANCE,
                "Embedding does not grant the Computer access");
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
        helper.assertFalse(stack.getOrDefault(DataComponents.BLOCK_ENTITY_DATA, CustomData.EMPTY).copyTag().contains("LogisticsBindings"),
            "Native Freq never publishes a legacy logistics binding");
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
