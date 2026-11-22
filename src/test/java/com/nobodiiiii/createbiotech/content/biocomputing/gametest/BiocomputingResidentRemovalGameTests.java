package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.foundation.item.CBItemData;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBItems;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentLifecycle;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ResidentHost;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual registered block/mixin entry, not a simulated release callback. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid = "create_biotech", bus = net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
public final class BiocomputingResidentRemovalGameTests {
    private static net.minecraft.world.level.block.entity.BlockEntityType<ForeignHost> foreignType;

    @net.neoforged.bus.api.SubscribeEvent
    public static void registerTestHost(net.neoforged.neoforge.registries.RegisterEvent event) {
        event.register(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE, registry -> {
            foreignType = net.minecraft.world.level.block.entity.BlockEntityType.Builder.of(
                ForeignHost::new, CBBlocks.COMPUTER.get()).build(null);
            registry.register(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create_biotech", "test_foreign_resident_host"), foreignType);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void failedDestructionRebuildsMissingOwnerWithoutDestroyingItsUpperHalf(GameTestHelper helper) {
        rebuildAfterFailedDestruction(helper, false);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void failedDestructionDoesNotLoadOriginalArchiveIntoForeignOwnerType(GameTestHelper helper) {
        rebuildAfterFailedDestruction(helper, true);
    }

    private static void rebuildAfterFailedDestruction(GameTestHelper helper, boolean replaceWithForeignOwner) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var computer = BiocomputingWorldGameTests.computer(helper, pos, VillagerProfession.LIBRARIAN, 3);
        var before = computer.saveWithoutMetadata(level.registryAccess());
        var lower = level.getBlockState(pos); var upper = level.getBlockState(pos.above());
        var resident = ItemStack.parse(level.registryAccess(), before.getCompound("ComputerData").getCompound("Resident")).orElseThrow();
        var identity = CBItemData.getOrEmpty(resident).getCompound("CapturedEntity").getUUID("UUID");
        // A distinct extension-owned type valid for the same physical block. Vanilla correctly
        // refuses a CHEST type here before rollback; that would not exercise the type fence.
        var foreign = new ForeignHost(pos, lower);
        boolean removed = ComputerResidentLifecycle.releaseBeforeExternalDestruction(level, pos, lower, computer, () -> {
            level.removeBlockEntity(pos);
            if (replaceWithForeignOwner) {
                level.setBlockEntity(foreign);
                helper.assertTrue(level.getBlockEntity(pos) == foreign, "Fixture must actually replace the owner before rollback");
            }
            return false;
        });
        helper.assertValueEqual(foreign.loads, 0, "A foreign physical owner must never deserialize the original server archive");
        helper.assertTrue(!removed, "Partial failed destruction must report rollback");
        helper.assertTrue(level.getBlockEntity(pos) != computer, "The vanished owner is actually reconstructed");
        helper.assertValueEqual(level.getBlockEntity(pos).getType(), computer.getType(), "Rebuild original registered owner type");
        helper.assertValueEqual(level.getBlockEntity(pos).saveWithoutMetadata(level.registryAccess()), before, "Full server archive restored");
        helper.assertValueEqual(level.getBlockState(pos), lower, "Lower block preserved");
        helper.assertValueEqual(level.getBlockState(pos.above()), upper, "Owner recovery must not tear down its other physical half");
        helper.assertTrue(level.getEntity(identity) == null, "Reconstructed owner has no loose duplicate resident");
        helper.assertTrue(!ComputerResidentLifecycle.isRemovalActive(level, pos), "Recovery releases its guard");
        helper.runAfterDelay(3, () -> {
            helper.assertValueEqual(level.getBlockState(pos), lower, "Structure survives scheduled validation");
            helper.assertValueEqual(level.getBlockState(pos.above()), upper, "Upper half survives scheduled validation");
            helper.assertTrue(level.getEntity(identity) == null, "No deferred duplicate release");
            helper.succeed();
        });
    }

    private static final class ForeignHost extends net.minecraft.world.level.block.entity.BlockEntity implements ResidentHost {
        private int loads;
        ForeignHost(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) { super(foreignType, pos, state); }
        @Override public net.minecraft.world.level.block.entity.BlockEntity residentOwner() { return this; }
        @Override public boolean hasResidentSource() { return false; }
        @Override public ItemStack residentSnapshot() { return ItemStack.EMPTY; }
        @Override public net.minecraft.nbt.Tag rawResidentTag() { return null; }
        @Override public byte[] serializedResident(net.minecraft.core.HolderLookup.Provider registries) { return new byte[0]; }
        @Override public void clearResidentSource() { }
        @Override protected void loadAdditional(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
            loads++;
            super.loadAdditional(tag, registries);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void residentReleaseWorksWithoutAnyFactoryComputerOrProductionState(GameTestHelper helper) {
        independentHost(helper, true);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void failedDestructionRestoresIndependentHostAndRollsBackReleasedEntity(GameTestHelper helper) {
        independentHost(helper, false);
    }

    private static void independentHost(GameTestHelper helper, boolean commit) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        level.removeBlockEntity(pos);
        var owner = new IndependentHost(pos, level.getBlockState(pos)); level.setBlockEntity(owner);
        var villager = net.minecraft.world.entity.EntityType.VILLAGER.create(level);
        var identity = villager.getUUID(); var box = new ItemStack(CBItems.CARDBOARD_BOX.get());
        helper.assertTrue(CapturedEntityBoxHelper.captureEntity(box, villager), "Real captured entity, not invented resident bytes");
        owner.resident = box.copy(); owner.setChanged();
        var before = owner.saveWithoutMetadata(level.registryAccess());
        boolean removed = ComputerResidentLifecycle.releaseBeforeExternalDestruction(level, pos, level.getBlockState(pos), owner,
            () -> commit && level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
        helper.assertValueEqual(removed, commit, "Result follows actual destruction, not release alone");
        helper.assertTrue(!ComputerResidentLifecycle.isRemovalActive(level, pos), "Transaction guard is always released");
        if (commit) {
            helper.assertTrue(level.getBlockEntity(pos) == null && level.getEntity(identity) instanceof Villager, "Generic host removal publishes the original resident once");
            helper.assertTrue(!owner.hasResidentSource(), "Removed owner no longer holds a second copy");
        } else {
            helper.assertTrue(level.getBlockEntity(pos) == owner, "Restore the exact loaded physical owner when available");
            helper.assertValueEqual(owner.saveWithoutMetadata(level.registryAccess()), before, "Opaque server state is fully restored without understanding its schema");
            helper.assertTrue(level.getEntity(identity) == null, "Failed destruction does not leave a duplicate released entity");
            helper.assertTrue(owner.hasResidentSource(), "The original host retains its resident after rollback");
        }
        helper.succeed();
    }

    /** A test-owned physical host, intentionally neither subclass nor adapter of factorycluster. */
    private static final class IndependentHost extends net.minecraft.world.level.block.entity.ChestBlockEntity implements ResidentHost {
        private ItemStack resident = ItemStack.EMPTY;
        private int loads;
        IndependentHost(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) { super(pos, state); }
        @Override public net.minecraft.world.level.block.entity.BlockEntity residentOwner() { return this; }
        @Override public boolean hasResidentSource() { return !resident.isEmpty(); }
        @Override public ItemStack residentSnapshot() { return resident.copy(); }
        @Override public net.minecraft.nbt.Tag rawResidentTag() { return null; }
        @Override public byte[] serializedResident(net.minecraft.core.HolderLookup.Provider registries) {
            return resident.isEmpty() ? new byte[0] : resident.save(registries).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        @Override public void clearResidentSource() { resident = ItemStack.EMPTY; setChanged(); }
        @Override protected void saveAdditional(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            if (!resident.isEmpty()) tag.put("ResidentTest", resident.save(registries));
        }
        @Override protected void loadAdditional(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
            loads++;
            super.loadAdditional(tag, registries);
            resident = ItemStack.parse(registries, tag.getCompound("ResidentTest")).orElse(ItemStack.EMPTY);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void replacingUpperReleasesTheSameResidentAndRemovesOnlyTheOldPair(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var computer = BiocomputingWorldGameTests.computer(helper, pos, VillagerProfession.LIBRARIAN, 3);
        var saved = computer.saveWithoutMetadata(level.registryAccess());
        var resident = ItemStack.parse(level.registryAccess(), saved.getCompound("ComputerData").getCompound("Resident")).orElseThrow();
        var identity = CBItemData.getOrEmpty(resident).getCompound("CapturedEntity").getUUID("UUID");
        helper.assertTrue(level.getEntity(identity) == null, "Installed resident is not already loose in the world");
        helper.assertTrue(level.setBlock(pos.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL), "Actual upper replacement commits");
        helper.assertTrue(level.getBlockState(pos).isAir() && level.getBlockEntity(pos) == null, "Lower owner is removed, not duplicated");
        helper.assertTrue(level.getBlockState(pos.above()).is(Blocks.STONE), "Requested replacement survives pair cleanup");
        helper.assertTrue(level.getEntity(identity) instanceof Villager, "Same original resident UUID is released");
        var released = (Villager)level.getEntity(identity);
        helper.assertValueEqual(released.getVillagerData().getProfession(), VillagerProfession.LIBRARIAN, "Profession retained");
        helper.assertValueEqual(released.getVillagerData().getLevel(), 3, "Hardware resident level retained");
        helper.assertValueEqual(released.getHealth(), 1F, "Keep existing release rule, not a new balance setting");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void undecodableResidentRejectsUpperReplacementWithoutErasingItsArchive(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var computer = BiocomputingWorldGameTests.computer(helper, pos);
        var archive = computer.saveWithoutMetadata(level.registryAccess());
        archive.getCompound("ComputerData").putString("Resident", "unreadable resident archive");
        computer.loadWithComponents(archive, level.registryAccess());
        var before = computer.saveWithoutMetadata(level.registryAccess());
        var lower = level.getBlockState(pos); var upper = level.getBlockState(pos.above());
        helper.assertTrue(computer.hasResidentSource(), "Malformed data still owns a resident source");
        helper.assertTrue(!level.setBlock(pos.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL), "Failed release rejects actual upper replacement");
        helper.assertValueEqual(level.getBlockState(pos), lower, "Lower block restored");
        helper.assertValueEqual(level.getBlockState(pos.above()), upper, "Upper block never lost");
        helper.assertTrue(level.getBlockState(pos).is(CBBlocks.COMPUTER.get()), "Original registered Computer remains");
        helper.assertValueEqual(level.getBlockEntity(pos).saveWithoutMetadata(level.registryAccess()), before, "Full malformed source and all other state survive unchanged");
        helper.succeed();
    }
}
