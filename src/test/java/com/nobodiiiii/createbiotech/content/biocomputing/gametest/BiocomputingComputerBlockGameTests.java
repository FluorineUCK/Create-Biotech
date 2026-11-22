package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerPhysicalHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramMenu;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.*;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingComputerBlockGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void registeredPhysicalBlockUsesNeutralHostForTickInstallApplicationEditorAndPlayerBreak(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        var block = CBBlocks.COMPUTER.get(); var lower = block.defaultBlockState();
        level.setBlockAndUpdate(pos, lower);
        level.setBlockAndUpdate(pos.above(), lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER));
        level.removeBlockEntity(pos);
        var host = new NeutralHost(pos, lower); level.setBlockEntity(host);
        helper.assertTrue(level.getBlockEntity(pos) == host, "Real block owns an independent host, not a Factory Computer subclass");
        var ticker = Objects.requireNonNull(block.getTicker(level, lower, CBBlockEntityTypes.COMPUTER.get()));
        // Use the registered BlockEntityType's erasure, just as the chunk ticker does, with its actual independent instance.
        tick(ticker, level, pos, lower, host);
        helper.assertValueEqual(host.ticks, 1, "Physical ticker dispatches the neutral host");

        var player = new MenuPlayer(level); player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.moveTo(Vec3.atCenterOf(pos.north()));
        var hit = new BlockHitResult(Vec3.atCenterOf(pos.above()), Direction.NORTH, pos.above(), false);
        var source = Objects.requireNonNull(EntityType.VILLAGER.create(level));
        var held = new ItemStack(CBItems.CARDBOARD_BOX.get());
        helper.assertTrue(CapturedEntityBoxHelper.captureEntity(held, source), "Actual captured resident item");
        var before = held.copy(); player.setItemInHand(InteractionHand.MAIN_HAND, held);
        helper.assertValueEqual(player.gameMode.useItemOn(player, level, held, InteractionHand.MAIN_HAND, hit), InteractionResult.PASS,
            "Refusal does not claim success; vanilla then tries the non-sneaking box, which passes through");
        helper.assertValueEqual(host.installations, 1, "Upper forwards exactly one installation attempt to the lower host");
        helper.assertTrue(ItemStack.matches(before, held), "Refusal preserves the actual source");

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.assertFalse(player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit).consumesAction(),
            "Unclaimed application use retains normal pass-through");
        helper.assertValueEqual(host.applications, 1, "Application was offered the physical interaction");
        host.applicationEnabled = true;
        helper.assertTrue(player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit).consumesAction(),
            "An independent application can explicitly handle the physical interaction");
        helper.assertValueEqual(host.applications, 2, "Only one callback per use");

        player.setShiftKeyDown(true);
        helper.assertTrue(player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit).consumesAction(),
            "Upper-half empty-hand sneak use opens the real program editor");
        helper.assertTrue(player.containerMenu instanceof ComputerProgramMenu && player.containerMenu.stillValid(player),
            "The actual program menu is anchored to the independent loaded owner");
        helper.assertValueEqual(host.applications, 2, "Program use does not also invoke the application");
        helper.assertTrue(host.getBehaviour(ComputerBehaviour.TYPE).document().isEmpty(), "Opening never installs a program");
        player.containerMenu = player.inventoryMenu; player.setShiftKeyDown(false);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));
        player.gameMode.destroyBlock(pos.above());
        helper.assertTrue(level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir(),
            "Actual player upper break redirects to the lower owner through the new event subscriber");
        int drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2)).stream()
            .filter(item -> item.getItem().is(block.asItem())).mapToInt(item -> item.getItem().getCount()).sum();
        helper.assertValueEqual(drops, 1, "Survival player break recovers one Computer, not one per half");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void occupiedNeutralHostsReleaseTheirOwnResidentsOnceThroughRealMixinAndPlayerEvent(GameTestHelper helper) {
        var level = helper.getLevel();
        var professions = List.of(net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN,
            net.minecraft.world.entity.npc.VillagerProfession.FLETCHER);
        for (int index = 0; index < 2; index++) {
            var pos = helper.absolutePos(new BlockPos(2 + index * 5, 2, 2));
            var lower = CBBlocks.COMPUTER.get().defaultBlockState();
            level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(pos, lower);
            level.setBlockAndUpdate(pos.above(), lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER));
            level.removeBlockEntity(pos);
            var host = new NeutralHost(pos, lower); level.setBlockEntity(host);
            var source = Objects.requireNonNull(EntityType.VILLAGER.create(level));
            source.setBaby(false);
            source.setVillagerData(source.getVillagerData().setProfession(professions.get(index)).setLevel(index + 1));
            var identity = source.getUUID(); var box = new ItemStack(CBItems.CARDBOARD_BOX.get());
            helper.assertTrue(CapturedEntityBoxHelper.captureEntity(box, source), "Capture actual distinct resident");
            host.resident.restoreSnapshot(box);
            if (index == 0) helper.assertTrue(level.destroyBlock(pos, true), "Actual occupied lower destroy enters the mixin");
            else {
                var player = new MenuPlayer(level); player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
                player.moveTo(Vec3.atCenterOf(pos.north()));
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));
                player.gameMode.destroyBlock(pos.above());
            }
            helper.assertTrue(level.getBlockEntity(pos) == null && level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir(), "Both occupied physical halves are removed");
            var released = level.getEntity(identity);
            helper.assertTrue(released instanceof net.minecraft.world.entity.npc.Villager, "Release the original UUID, not a default resident");
            var villager = (net.minecraft.world.entity.npc.Villager) released;
            helper.assertValueEqual(villager.getVillagerData().getProfession(), professions.get(index), "Keep this member's profession");
            helper.assertValueEqual(villager.getVillagerData().getLevel(), index + 1, "Keep this member's level");
            helper.assertFalse(host.hasResidentSource(), "No remaining owned second copy");
            var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2)).stream()
                .filter(item -> item.getItem().is(CBItems.COMPUTER.get())).toList();
            helper.assertValueEqual(drops.stream().mapToInt(item -> item.getItem().getCount()).sum(), 1, "Exactly one empty Computer drop");
            helper.assertTrue(drops.stream().noneMatch(item -> item.getItem().has(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA)),
                "Resident is not duplicated in the recovered Computer");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void emptyHandOpensTheInstalledComputerEditorWithoutAProductionListener(GameTestHelper helper) {
        var level = helper.getLevel(); var lower = helper.absolutePos(new BlockPos(2, 2, 2));
        var computer = BiocomputingWorldGameTests.computer(helper, lower);
        var player = new MenuPlayer(level); player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.moveTo(Vec3.atCenterOf(lower.north()));
        for (var pos : java.util.List.of(lower, lower.above())) {
            var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false);
            var result = player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(result.consumesAction() && player.containerMenu instanceof ComputerProgramMenu
                && player.containerMenu.stillValid(player), "Both halves open this Computer's actual programming menu");
            player.containerMenu = player.inventoryMenu;
        }
        helper.succeed();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void tick(net.minecraft.world.level.block.entity.BlockEntityTicker ticker, ServerLevel level,
        BlockPos pos, BlockState state, NeutralHost host) { ticker.tick(level, pos, state, host); }

    private static final class NeutralHost extends SmartBlockEntity implements ComputerPhysicalHost {
        private final com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResident resident =
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResident();
        int ticks, installations, applications;
        boolean applicationEnabled;
        NeutralHost(BlockPos pos, BlockState state) { super(CBBlockEntityTypes.COMPUTER.get(), pos, state); }
        @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
            behaviours.add(new ComputerBehaviour(this, () -> resident.snapshot(), () -> true));
        }
        @Override public void tick() { ticks++; }
        @Override public SmartBlockEntity residentOwner() { return this; }
        @Override public boolean hasResidentSource() { return resident.hasSource(); }
        @Override public ItemStack residentSnapshot() { return resident.snapshot(); }
        @Override public Tag rawResidentTag() { return resident.raw(); }
        @Override public byte[] serializedResident(HolderLookup.Provider registries) { return resident.serialized(registries); }
        @Override public void clearResidentSource() { resident.clear(); }
        @Override protected void write(net.minecraft.nbt.CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.write(tag, registries, clientPacket);
            if (!clientPacket) resident.write(tag, registries);
        }
        @Override protected void read(net.minecraft.nbt.CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.read(tag, registries, clientPacket);
            if (!clientPacket) resident.commitLoad(resident.prepareLoad(tag.get("Resident"), registries));
        }
        @Override public ComputerInstallResult installResident(ServerPlayer player, InteractionHand hand) {
            installations++; return ComputerInstallResult.OCCUPIED;
        }
        @Override public boolean openComputerApplication(ServerPlayer player) { applications++; return applicationEnabled; }
    }
    private static final class MenuPlayer extends FakePlayer {
        private int nextMenu;
        MenuPlayer(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "physical-computer")); }
        @Override public OptionalInt openMenu(MenuProvider provider, Consumer<RegistryFriendlyByteBuf> openingData) {
            containerMenu = provider.createMenu(++nextMenu, getInventory(), this);
            var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try { openingData.accept(buffer); buffer.readUUID(); } finally { buffer.release(); }
            return OptionalInt.of(nextMenu);
        }
    }
}
