package com.nobodiiiii.createbiotech.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpoch;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserFilter;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserPage;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import java.util.Objects;
import java.util.List;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberIndex;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.simibubi.create.Create;
import net.minecraft.core.GlobalPos;
import java.util.UUID;


import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelBlockEntity;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelMenu;


@GameTestHolder(CreateBiotech.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FactoryPlayerEntryGameTests {
    private FactoryPlayerEntryGameTests() {}

    @GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 120)
    public static void ordinaryUnpublishedPanelClicksDoNotScanOrStartOffCadence(GameTestHelper helper) {
        var built = build(helper, false, true);
        var level = helper.getLevel();
        var probe = new ScanProbeComputer(built.computer().getBlockPos(), built.computer().getBlockState());
        probe.loadWithComponents(built.computer().saveWithoutMetadata(level.registryAccess()), level.registryAccess());
        level.setBlockEntity(probe);
        helper.runAfterDelay(35, () -> {
            helper.assertTrue(probe.production().computerStructureMemberId().isPresent(),
                "Scheduled formation must observe the unready structure before the click probe");
            built.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            probe.scanIdentityReads = 0;
            for (int click = 0; click < 3; click++)
                built.player().gameMode.useItemOn(built.player(), level, ItemStack.EMPTY,
                    InteractionHand.MAIN_HAND, hit(built.panel().getBlockPos()));
            helper.assertValueEqual(probe.scanIdentityReads, 0,
                "Ordinary unpublished Panel clicks must not reread Computer identities through off-cadence topology scans");
            helper.assertTrue(probe.production().epoch().isEmpty(), "Repeated empty Panel clicks cannot activate an unready structure");
            var villager = Objects.requireNonNull(EntityType.VILLAGER.create(level));
            villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.LIBRARIAN).setLevel(3));
            var box = new ItemStack(CBItems.CARDBOARD_BOX.get());
            helper.assertTrue(CapturedEntityBoxHelper.captureEntity(box, villager), "Capture must succeed");
            built.player().setItemInHand(InteractionHand.MAIN_HAND, box);
            helper.assertTrue(built.player().gameMode.useItemOn(built.player(), level, box,
                InteractionHand.MAIN_HAND, hit(probe.getBlockPos())).consumesAction(), "Actual resident install must succeed");
            built.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            probe.scanIdentityReads = 0;
            built.player().gameMode.useItemOn(built.player(), level, ItemStack.EMPTY,
                InteractionHand.MAIN_HAND, hit(built.panel().getBlockPos()));
            helper.assertValueEqual(probe.scanIdentityReads, 0, "A newly ready plain Panel must still wait for the scheduled observation");
            helper.assertTrue(probe.production().epoch().isEmpty(), "A menu click cannot start the newly ready epoch off cadence");
            helper.runAfterDelay(25, () -> {
                helper.assertTrue(probe.production().epoch().isPresent() && built.panel().isFormedClusterMember(),
                    "The next scheduled observation must bootstrap the same ordinary Panel");
                built.player().gameMode.useItemOn(built.player(), level, ItemStack.EMPTY,
                    InteractionHand.MAIN_HAND, hit(built.panel().getBlockPos()));
                helper.assertTrue(built.player().containerMenu instanceof FactoryPanelMenu,
                    "The scheduled Panel must subsequently open through the normal player interaction");
                built.player().closeContainer();
                helper.succeed();
            });
        });
    }

    private static final class ScanProbeComputer extends ComputerBlockEntity {
        int scanIdentityReads;
        ScanProbeComputer(BlockPos pos, BlockState state) { super(pos, state); }
        @Override public java.util.Optional<UUID> computerId() {
            scanIdentityReads++;
            return super.computerId();
        }
    }

    @GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 130)
    public static void laterInstalledFirstPanelStartsWithoutPrematureEmptyEpoch(GameTestHelper helper) {
        var fixture = build(helper, true, true, 1, ItemStack.EMPTY);
        var level = helper.getLevel();
        var panelPos = fixture.computer().getBlockPos().west();
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(fixture.computer().production().computerStructureMemberId().isPresent(),
                "A Panel is not a requirement for legal structure formation");
            helper.assertTrue(fixture.computer().production().epoch().isEmpty(),
                "Automatic startup must wait for the first Panel instead of freezing an empty Panel epoch");
            var item = new ItemStack(CBItems.FACTORY_PANEL.get());
            fixture.player().setItemInHand(InteractionHand.MAIN_HAND, item);
            helper.assertTrue(fixture.player().gameMode.useItemOn(fixture.player(), level, item,
                InteractionHand.MAIN_HAND, hit(panelPos)).consumesAction(), "Actual late Panel item placement must succeed");
            helper.runAfterDelay(35, () -> {
                var panel = (FactoryPanelBlockEntity) level.getBlockEntity(panelPos);
                helper.assertTrue(fixture.computer().production().epoch().isPresent() && panel.isFormedClusterMember(),
                    "The next legal scan must publish the later installed first Panel");
                fixture.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                fixture.player().gameMode.useItemOn(fixture.player(), level, ItemStack.EMPTY,
                    InteractionHand.MAIN_HAND, hit(panelPos));
                helper.assertTrue(fixture.player().containerMenu instanceof FactoryPanelMenu,
                    "The later installed first Panel must open through actual player use");
                fixture.player().closeContainer();
                helper.succeed();
            });
        });
    }

    @GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 140)
    public static void playerBuiltReadyClusterOpensUnboundPanel(GameTestHelper helper) {
        Fixture built = build(helper, true, true);
        Fixture fixture = new Fixture(built.computer(), built.panel(), menuPlayer(helper, built.panel().getBlockPos()));
        helper.runAfterDelay(35, () -> {
            helper.assertTrue(fixture.computer().production().epoch().isPresent(),
                "Player-built ready structure must bootstrap itself");
            helper.assertTrue(fixture.panel().isFormedClusterMember(),
                "Embedded Panel must be published by that epoch");
            helper.assertTrue(fixture.panel().logisticsBindings().isEmpty(),
                "UI must work before logistics binding");
            UUID cluster = fixture.panel().clusterId();
            UUID epoch = fixture.computer().production().epoch().orElseThrow().epochId();
            fixture.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            fixture.player().setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            fixture.player().gameMode.useItemOn(fixture.player(), helper.getLevel(),
                ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit(fixture.panel().getBlockPos()));
            helper.assertTrue(fixture.player().containerMenu instanceof FactoryPanelMenu,
                "Actual player right click must open Cluster UI");
            helper.runAfterDelay(45, () -> {
                helper.assertValueEqual(fixture.panel().clusterId(), cluster,
                    "Repeated scheduled refresh must preserve Cluster identity");
                helper.assertValueEqual(fixture.computer().production().epoch().orElseThrow().epochId(), epoch,
                    "Repeated scheduled refresh must preserve the first epoch");
                fixture.player().closeContainer();
                helper.succeed();
            });
        });
    }

    @GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
    public static void playerBuiltMissingResidentDoesNotPublish(GameTestHelper helper) {
        assertNotPublished(helper, build(helper, false, true));
    }

    @GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
    public static void playerBuiltOpenShellDoesNotPublish(GameTestHelper helper) {
        assertNotPublished(helper, build(helper, true, false));
    }

    @GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 160)
    public static void configuredPanelItemRestoresNetworksIntoNewLocalClusterOnPlayerUse(GameTestHelper helper) {
        Fixture source = build(helper, true, true);
        UUID network = UUID.randomUUID();
        GlobalPos link = GlobalPos.of(helper.getLevel().dimension(), source.panel().getBlockPos().above(5));
        Create.LOGISTICS.linkAdded(network, link, null);
        helper.runAfterDelay(35, () -> {
            helper.assertTrue(source.panel().isFormedClusterMember(), "Source must auto-form");
            helper.assertValueEqual(ClusterBindingService.replaceFormedBindings(source.player(), source.panel(),
                List.of(new LogisticsBinding(network))), ClusterBindingService.BindResult.OK,
                "Source configured networks must come from the authorized formed binding service");
            var oldBinding = source.panel().bindingState();
            ItemStack configured = source.panel().asConfiguredStack(helper.getLevel().registryAccess());
            ((com.simibubi.create.content.equipment.wrench.IWrenchable) source.panel().getBlockState().getBlock())
                .onSneakWrenched(source.panel().getBlockState(), new UseOnContext(source.player(),
                    InteractionHand.MAIN_HAND, hit(source.panel().getBlockPos())));
            Fixture built = build(helper, true, true, 6, configured);
            Fixture target = new Fixture(built.computer(), built.panel(), menuPlayer(helper, built.panel().getBlockPos()));
            helper.runAfterDelay(35, () -> {
                helper.assertTrue(target.computer().production().epoch().isEmpty(), "Configured item must await real player authorization");
                helper.assertValueEqual(target.panel().logisticsBindings(), oldBinding.logisticsBindings(),
                    "Scheduled formation must not erase captured item configuration");
                target.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                target.player().setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                target.player().gameMode.useItemOn(target.player(), helper.getLevel(), ItemStack.EMPTY,
                    InteractionHand.MAIN_HAND, hit(target.panel().getBlockPos()));
                helper.assertTrue(target.player().containerMenu instanceof FactoryPanelMenu,
                    "Actual player interaction must restore configuration and open the menu");
                helper.assertValueEqual(target.panel().logisticsBindings(), oldBinding.logisticsBindings(),
                    "Imported logistics configuration must survive activation");
                helper.assertValueEqual(target.panel().memberId(), source.panel().memberId(),
                    "Physical Panel identity must survive configured item transfer");
                helper.assertFalse(target.panel().clusterId().equals(oldBinding.clusterId()),
                    "Import must not revive or impersonate the source Cluster");
                helper.assertValueEqual(source.computer().production().coordinatorMember.bindingState(), oldBinding,
                    "Import must leave the source Cluster unchanged");
                target.player().closeContainer();
                Create.LOGISTICS.linkRemoved(network, link);
                helper.succeed();
            });
        });
    }

    @GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 100)
    public static void configuredItemPermissionDenialPreservesData(GameTestHelper helper) {
        UUID network = UUID.randomUUID();
        GlobalPos link = GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(1, 8, 1)));
        Create.LOGISTICS.linkAdded(network, link, UUID.randomUUID());
        ItemStack configured = new ItemStack(CBItems.FACTORY_PANEL.get());
        net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA,
            configured, tag -> {
                var list = new net.minecraft.nbt.ListTag();
                list.add(new LogisticsBinding(network).save());
                tag.put("LogisticsBindings", list);
                net.minecraft.world.level.block.entity.BlockEntity.addEntityType(tag,
                    com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes.FACTORY_PANEL.get());
            });
        Fixture target = build(helper, true, true, 1, configured);
        helper.runAfterDelay(35, () -> {
            var binding = target.panel().bindingState();
            target.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            target.player().gameMode.useItemOn(target.player(), helper.getLevel(), ItemStack.EMPTY,
                InteractionHand.MAIN_HAND, hit(target.panel().getBlockPos()));
            helper.assertTrue(target.computer().production().epoch().isEmpty(), "Unauthorized import cannot start an epoch");
            helper.assertTrue(target.computer().production().coordinatorMember.bindingState() == null,
                "Unauthorized import cannot claim target binding");
            helper.assertValueEqual(target.panel().bindingState(), binding, "Denied import must preserve original item data");
            helper.assertFalse(target.player().containerMenu instanceof FactoryPanelMenu, "Unauthorized import must not open UI");
            helper.assertTrue(((RecordingPlayer) target.player()).feedback.getContents()
                instanceof net.minecraft.network.chat.contents.TranslatableContents translated
                && translated.getKey().equals("create_biotech.factory_cluster.binding.permission"),
                "Unauthorized import must explain permission denial");
            Create.LOGISTICS.linkRemoved(network, link);
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 120)
    public static void failedPartialPublicationRollsBackAndNextCycleRetriesStableIdentity(GameTestHelper helper) {
        var level = helper.getLevel();
        UUID network = UUID.randomUUID();
        GlobalPos link = GlobalPos.of(level.dimension(), helper.absolutePos(new BlockPos(1, 8, 1)));
        Create.LOGISTICS.linkAdded(network, link, null);
        Fixture fixture = build(helper, true, true, 1, configuredPanelItem(network));
        BlockPos secondPos = fixture.panel().getBlockPos().east(2);
        ItemStack item = configuredPanelItem(network);
        fixture.player().setItemInHand(InteractionHand.MAIN_HAND, item);
        item.getItem().useOn(new UseOnContext(fixture.player(), InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(secondPos), Direction.EAST, secondPos, false)));
        var second = (FactoryPanelBlockEntity) level.getBlockEntity(secondPos);
        var later = fixture.panel().memberId().compareTo(second.memberId()) > 0 ? fixture.panel() : second;
        var earlier = later == second ? fixture.panel() : second;
        var saved = later.saveWithoutMetadata(level.registryAccess());
        var rejected = new PublicationGatePanel(later.getBlockPos(), later.getBlockState());
        rejected.loadWithComponents(saved, level.registryAccess());
        level.setBlockEntity(rejected);
        helper.runAfterDelay(35, () -> {
            var earlierBefore = earlier.saveWithoutMetadata(level.registryAccess());
            var rejectedBefore = rejected.saveWithoutMetadata(level.registryAccess());
            helper.assertValueEqual(earlier.logisticsBindings(), List.of(new LogisticsBinding(network)),
                "Configured publication failure must exercise native network data");
            helper.assertValueEqual(rejected.selectedNetwork(), network,
                "Configured publication failure must exercise native selectedNetwork data");
            fixture.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            fixture.player().setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            fixture.player().gameMode.useItemOn(fixture.player(), level, ItemStack.EMPTY,
                InteractionHand.MAIN_HAND, hit(earlier.getBlockPos()));
            UUID stable = fixture.computer().production().computerStructureMemberId().orElseThrow();
            helper.assertTrue(rejected.attempts > 0, "Test must reach the real second Panel publication boundary");
            helper.assertTrue(fixture.computer().production().epoch().isEmpty(), "Failed publication must roll back epoch");
            helper.assertTrue(fixture.computer().production().coordinatorMember.bindingState() == null,
                "Failed publication must roll back coordinator binding for retry");
            helper.assertFalse(earlier.isFormedClusterMember(), "Earlier partial Panel must be withdrawn");
            helper.assertTrue(ClusterMemberIndex.members(level.getServer(), stable, ClusterMemberType.PANEL).isEmpty(),
                "Failed publication cannot leave a visible Panel service");
            helper.assertTrue(ClusterMemberIndex.members(level.getServer(), stable, ClusterMemberType.COMPUTER_COORDINATOR).isEmpty(),
                "Failed publication cannot leave a visible bound coordinator service");
            helper.assertValueEqual(earlier.saveWithoutMetadata(level.registryAccess()), earlierBefore,
                "Failed configured publication must preserve complete earlier Panel NBT");
            helper.assertValueEqual(rejected.saveWithoutMetadata(level.registryAccess()), rejectedBefore,
                "Failed configured publication must preserve complete rejected Panel NBT");
            helper.assertValueEqual(earlier.selectedNetwork(), network,
                "Failed configured publication must preserve the earlier selected native network");
            helper.assertValueEqual(rejected.selectedNetwork(), network,
                "Failed configured publication must preserve the rejected selected native network");
            rejected.allow = true;
            fixture.player().gameMode.useItemOn(fixture.player(), level, ItemStack.EMPTY,
                InteractionHand.MAIN_HAND, hit(earlier.getBlockPos()));
            helper.runAfterDelay(30, () -> {
                helper.assertTrue(earlier.isFormedClusterMember() && rejected.isFormedClusterMember(),
                    "Next authorized lifecycle attempt must retry both exact Panels");
                helper.assertValueEqual(fixture.computer().production().epoch().orElseThrow().groupId(), stable,
                    "Retry must reuse the already persisted local identity");
                helper.assertValueEqual(ClusterMemberIndex.members(level.getServer(), stable, ClusterMemberType.PANEL).size(), 2,
                    "Successful retry must expose exactly two Panel services");
                helper.assertValueEqual(earlier.logisticsBindings(), List.of(new LogisticsBinding(network)),
                    "Successful retry must retain the configured native network list");
                helper.assertValueEqual(rejected.selectedNetwork(), network,
                    "Successful retry must retain the selected native network");
                Create.LOGISTICS.linkRemoved(network, link);
                helper.succeed();
            });
        });
    }

    private static final class PublicationGatePanel extends FactoryPanelBlockEntity {
        boolean allow;
        int attempts;
        PublicationGatePanel(BlockPos pos, BlockState state) { super(pos, state); }
        @Override public boolean publishForComputerEpoch(com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding binding,
            com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpoch epoch) {
            attempts++;
            return allow && super.publishForComputerEpoch(binding, epoch);
        }
    }

    @GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 100)
    public static void activeClusterAttachesOnlyUnboundLibraryWithoutChangingEpoch(GameTestHelper helper) {
        Fixture fixture = build(helper, true, true);
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(6, 3, 1));
        var core = unboundLibrary(helper, pos);
        helper.runAfterDelay(35, () -> {
            var binding = fixture.panel().bindingState();
            var epoch = fixture.computer().production().epoch().orElseThrow();
            ItemStack tool = new ItemStack(CBItems.LOGISTICS_CONFIGURATION_TOOL.get());
            fixture.player().setItemInHand(InteractionHand.MAIN_HAND, tool);
            fixture.player().setShiftKeyDown(true);
            helper.assertTrue(fixture.player().gameMode.useItemOn(fixture.player(), level, tool,
                InteractionHand.MAIN_HAND, hit(fixture.panel().getBlockPos())).consumesAction(),
                "Shift tool interaction must select the exact formed Cluster");
            fixture.player().setShiftKeyDown(false);
            fixture.player().moveTo(Vec3.atCenterOf(pos.west()));
            helper.assertTrue(fixture.player().gameMode.useItemOn(fixture.player(), level, tool,
                InteractionHand.MAIN_HAND, hit(pos)).consumesAction(),
                "Actual tool interaction must attach the selected Cluster to the unbound Library");
            helper.assertValueEqual(core.bindingState(), binding, "Only the new Library receives the existing exact binding");
            helper.assertValueEqual(fixture.panel().bindingState(), binding, "Library attach cannot revise Panel binding");
            helper.assertValueEqual(fixture.computer().production().coordinatorMember.bindingState(), binding, "Library attach cannot revise coordinator binding");
            helper.assertValueEqual(fixture.computer().production().epoch().orElseThrow(), epoch, "Library attach cannot rebuild the frozen epoch");
            helper.assertFalse(ClusterBindingService.bind(fixture.player(), fixture.panel(), core).succeeded(),
                "The narrow attach path must reject an already-bound target");
            helper.succeed();
        });
    }

    private static com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity unboundLibrary(
        GameTestHelper helper, BlockPos pos) {
        var level = helper.getLevel();
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState()
            .setValue(com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlock(pos, state, Block.UPDATE_ALL);
        level.setBlock(pos.above(), state.setValue(com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock.HALF,
            DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        var core = (com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity) level.getBlockEntity(pos);
        helper.assertTrue(core.applyPlacementData(com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreItemData.configured(
            com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency.of("entry-library"),
            0, UUID.randomUUID(), 0)), "Library fixture must own a real configured identity claim");
        return core;
    }

    @GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 100)
    public static void activeLibraryAttachDeniesUnauthorizedPlayerWithoutChangingSource(GameTestHelper helper) {
        var fixture = build(helper, true, true);
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(6, 3, 1));
        var core = unboundLibrary(helper, pos);
        UUID network = UUID.randomUUID();
        var link = GlobalPos.of(level.dimension(), pos.above(5));
        Create.LOGISTICS.linkAdded(network, link, fixture.player().getUUID());
        helper.runAfterDelay(35, () -> {
            helper.assertValueEqual(ClusterBindingService.replaceFormedBindings(fixture.player(), fixture.panel(),
                List.of(new LogisticsBinding(network))), ClusterBindingService.BindResult.OK,
                "Network owner must configure the real formed Cluster");
            var binding = fixture.panel().bindingState();
            var epoch = fixture.computer().production().epoch().orElseThrow();
            var unauthorized = menuPlayer(helper, fixture.panel().getBlockPos());
            var tool = new ItemStack(CBItems.LOGISTICS_CONFIGURATION_TOOL.get());
            unauthorized.setItemInHand(InteractionHand.MAIN_HAND, tool);
            unauthorized.setShiftKeyDown(true);
            unauthorized.gameMode.useItemOn(unauthorized, level, tool, InteractionHand.MAIN_HAND,
                hit(fixture.panel().getBlockPos()));
            unauthorized.setShiftKeyDown(false);
            unauthorized.moveTo(Vec3.atCenterOf(pos.west()));
            unauthorized.gameMode.useItemOn(unauthorized, level, tool, InteractionHand.MAIN_HAND, hit(pos));
            helper.assertTrue(core.bindingState() == null, "Unauthorized attach must leave the Library unbound");
            helper.assertValueEqual(fixture.panel().bindingState(), binding, "Denied attach must preserve Panel binding");
            helper.assertValueEqual(fixture.computer().production().coordinatorMember.bindingState(), binding,
                "Denied attach must preserve coordinator binding");
            helper.assertValueEqual(fixture.computer().production().epoch().orElseThrow(), epoch,
                "Denied attach must preserve the frozen epoch");
            helper.assertTrue(((RecordingPlayer) unauthorized).feedback.getContents()
                instanceof net.minecraft.network.chat.contents.TranslatableContents translated
                && translated.getKey().equals("create_biotech.factory_cluster.binding.permission"),
                "Actual tool attach must explain the logistics permission denial");
            Create.LOGISTICS.linkRemoved(network, link);
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 100)
    public static void conflictingCapturedPanelConfigurationsAreNotSilentlyMerged(GameTestHelper helper) {
        var fixture = build(helper, true, true, 1, configuredPanelItem(UUID.randomUUID()));
        var level = helper.getLevel();
        var secondPos = fixture.panel().getBlockPos().east(2);
        var item = configuredPanelItem(UUID.randomUUID());
        fixture.player().setItemInHand(InteractionHand.MAIN_HAND, item);
        item.getItem().useOn(new UseOnContext(fixture.player(), InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(secondPos), Direction.EAST, secondPos, false)));
        var second = (FactoryPanelBlockEntity) level.getBlockEntity(secondPos);
        var firstBinding = fixture.panel().bindingState();
        var secondBinding = second.bindingState();
        helper.runAfterDelay(35, () -> {
            fixture.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            fixture.player().gameMode.useItemOn(fixture.player(), level, ItemStack.EMPTY,
                InteractionHand.MAIN_HAND, hit(fixture.panel().getBlockPos()));
            helper.assertTrue(fixture.computer().production().epoch().isEmpty(), "Conflicting saved configurations must not start");
            helper.assertTrue(fixture.computer().production().coordinatorMember.bindingState() == null, "Conflict cannot claim local authority");
            helper.assertValueEqual(fixture.panel().bindingState(), firstBinding, "Conflict must preserve first configuration");
            helper.assertValueEqual(second.bindingState(), secondBinding, "Conflict must preserve second configuration");
            helper.assertFalse(fixture.player().containerMenu instanceof FactoryPanelMenu, "Conflict must not expose UI");
            helper.succeed();
        });
    }

    private static ItemStack configuredPanelItem(UUID network) {
        ItemStack item = new ItemStack(CBItems.FACTORY_PANEL.get());
        net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA,
            item, tag -> {
                var list = new net.minecraft.nbt.ListTag();
                list.add(new LogisticsBinding(network).save());
                tag.put("LogisticsBindings", list);
                tag.putUUID("SelectedNetwork", network);
                net.minecraft.world.level.block.entity.BlockEntity.addEntityType(tag,
                    com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes.FACTORY_PANEL.get());
            });
        return item;
    }

    private static void assertNotPublished(GameTestHelper helper, Fixture fixture) {
        helper.runAfterDelay(35, () -> {
            helper.assertTrue(fixture.computer().production().epoch().isEmpty(), "Unready structure cannot start");
            helper.assertFalse(fixture.panel().isFormedClusterMember(), "Unready Panel cannot publish");
            fixture.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            fixture.player().setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            fixture.player().gameMode.useItemOn(fixture.player(), helper.getLevel(), ItemStack.EMPTY,
                InteractionHand.MAIN_HAND, hit(fixture.panel().getBlockPos()));
            helper.assertFalse(fixture.player().containerMenu instanceof FactoryPanelMenu,
                "Unready structure cannot expose the Cluster UI");
            helper.assertTrue(fixture.panel().normalGoggles().rows().stream()
                .anyMatch(row -> row.key().equals("formation") && row.values().equals(List.of("@NOT_PUBLISHED"))),
                "Unpublished Panel goggles must explain admission, not display a provisional operational identity");
            helper.assertTrue(((RecordingPlayer) fixture.player()).feedback != null,
                "Failed right click must provide a localized explanation");
            helper.succeed();
        });
    }

    private static Fixture build(GameTestHelper helper, boolean resident, boolean closed) {
        return build(helper, resident, closed, 1, new ItemStack(CBItems.FACTORY_PANEL.get()));
    }

    private static Fixture build(GameTestHelper helper, boolean resident, boolean closed, int xOffset, ItemStack panelItem) {
        ServerLevel level = helper.getLevel();
        BlockPos min = helper.absolutePos(new BlockPos(xOffset, 2, 1));
        for (int x = 0; x < 3; x++)
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 3; z++) {
                    boolean shell = x == 0 || x == 2 || y == 0 || y == 3 || z == 0 || z == 2;
                    BlockState casing = (y == 0 ? CBBlocks.COMPUTER_CASING.get()
                        : CBBlocks.TRANSPARENT_COMPUTER_CASING.get()).defaultBlockState();
                    level.setBlock(min.offset(x, y, z), shell ? casing : Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL);
                }
        BlockPos lower = min.offset(1, 1, 1);
        BlockState state = CBBlocks.COMPUTER.get().defaultBlockState()
            .setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlock(lower, state, Block.UPDATE_ALL);
        level.setBlock(lower.above(), state.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        ServerPlayer player = new RecordingPlayer(level);
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.moveTo(Vec3.atCenterOf(min.offset(-1, 1, 1)));
        if (resident) {
            Villager villager = Objects.requireNonNull(EntityType.VILLAGER.create(level));
            villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.LIBRARIAN).setLevel(3));
            ItemStack box = new ItemStack(CBItems.CARDBOARD_BOX.get());
            helper.assertTrue(CapturedEntityBoxHelper.captureEntity(box, villager), "Resident capture must succeed");
            player.setItemInHand(InteractionHand.MAIN_HAND, box);
            helper.assertTrue(player.gameMode.useItemOn(player, level, box, InteractionHand.MAIN_HAND,
                hit(lower)).consumesAction(), "Actual resident install interaction must succeed");
        }
        BlockPos panelPos = min.offset(0, 1, 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, panelItem);
        if (!panelItem.isEmpty())
            helper.assertTrue(panelItem.getItem().useOn(new UseOnContext(player,
                InteractionHand.MAIN_HAND, hit(panelPos))).consumesAction(), "Registered Panel item must embed");
        if (!closed) level.setBlock(min.offset(2, 2, 1), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        return new Fixture((ComputerBlockEntity) level.getBlockEntity(lower),
            (FactoryPanelBlockEntity) level.getBlockEntity(panelPos), player);
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.WEST, pos, false);
    }
    private static ServerPlayer menuPlayer(GameTestHelper helper, BlockPos pos) {
        var player = new RecordingPlayer(helper.getLevel());
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.moveTo(Vec3.atCenterOf(pos.west()));
        return player;
    }

    @GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 100)
    public static void bothCoreHalvesOpenRegisteredBrowserWithoutClusterOrRecipes(GameTestHelper helper) {
        var pos = helper.absolutePos(new BlockPos(2, 3, 2));
        var core = unboundLibrary(helper, pos);
        var player = (RecordingPlayer) menuPlayer(helper, pos);
        var before = core.saveWithoutMetadata(helper.getLevel().registryAccess());
        com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserRequestPacket previousRequest = null;
        for (var clicked : List.of(pos, pos.above())) {
            player.containerMenu = player.inventoryMenu;
            helper.assertTrue(player.gameMode.useItemOn(player, helper.getLevel(), ItemStack.EMPTY,
                InteractionHand.MAIN_HAND, hit(clicked)).consumesAction(), "Physical Core click must open the browser");
            helper.assertTrue(player.containerMenu instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu,
                "Either half must open the canonical registered browser");
            helper.assertTrue(player.clientMenu instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu,
                "Opening bytes must construct the registered client menu");
            var menu = (com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu) player.containerMenu;
            var physicalAddress = core.memberAddress();
            helper.assertValueEqual(menu.coreAddress(), new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation(
                physicalAddress.dimension(), physicalAddress.subLevelId(), physicalAddress.localPos()),
                "Upper half resolves to lower physical address");
            helper.assertValueEqual(menu.libraryId(), core.memberId(), "Opening preserves exact Library identity");
            var client = (com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu) player.clientMenu;
            if (previousRequest != null) {
                helper.assertFalse(menu.enqueue(player, previousRequest), "Reopened menu rejects previous random session despite container-id reuse");
            } else {
                var request = client.browser().request(com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserFilter.EMPTY, 0, java.util.Optional.empty());
                var wire = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), levelRegistries(helper));
                try {
                    com.nobodiiiii.createbiotech.network.CBPackets.beginPanelDeliveryObservationForTest(player);
                    com.nobodiiiii.createbiotech.network.CBPackets.roundTripPanelForTest(request, wire).handle(player);
                    menu.broadcastChanges();
                    var payload = com.nobodiiiii.createbiotech.network.CBPackets.takePanelDeliveryForTest(player).orElseThrow();
                    helper.assertTrue(payload instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserResponsePacket,
                        "Registered browser request must deliver its actual bounded response");
                    wire.clear();
                    var response = com.nobodiiiii.createbiotech.network.CBPackets.roundTripPanelForTest(
                        (com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserResponsePacket) payload, wire);
                    helper.assertTrue(client.browser().accept(response), "Registered response must apply to exact client opening and intent");
                    helper.assertTrue(response.page().entries().isEmpty(), "Empty unbound Core still has an operational browser");
                    helper.assertValueEqual(response.page().status(), com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserPage.Status.OK,
                        "Empty browser is OK, not an unopened placeholder");
                    previousRequest = request;
                } finally { wire.release(); }
            }
        }
        helper.assertValueEqual(core.saveWithoutMetadata(helper.getLevel().registryAccess()), before,
            "Browser opening cannot spend resources, create queries or mutate persisted Core state");
        helper.assertTrue(core.bindingState() == null, "Browser opening must not bind an unbound Library");
        helper.succeed();
    }

    private static net.minecraft.core.RegistryAccess levelRegistries(GameTestHelper helper) { return helper.getLevel().registryAccess(); }

    @GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 100)
    public static void freshCorePlacementUsesRealMenuRegisteredCodecVanillaSyncThenSecondClick(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 3, 2));
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        var player = (RecordingPlayer) menuPlayer(helper, pos);
        var item = new ItemStack(CBItems.PATTERN_STORAGE_CORE.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        var placeHit = new BlockHitResult(Vec3.atCenterOf(pos.below()), Direction.UP, pos.below(), false);
        helper.assertTrue(player.gameMode.useItemOn(player, level, item, InteractionHand.MAIN_HAND, placeHit).consumesAction(),
            "Fresh registered item must enter placement form on first physical useOn");
        helper.assertTrue(level.getBlockState(pos).isAir(), "First use opens editor, not an unconfigured Core");
        helper.assertTrue(player.clientMenu instanceof com.nobodiiiii.createbiotech.content.biocomputing.library.menu.LibraryCorePlacementMenu,
            "Actual opening data must construct the registered placement menu");
        var client = (com.nobodiiiii.createbiotech.content.biocomputing.library.menu.LibraryCorePlacementMenu) player.clientMenu;
        var synchronizedItems = new java.util.ArrayList<ItemStack>();
        player.inventoryMenu.setSynchronizer(new net.minecraft.world.inventory.ContainerSynchronizer() {
            @Override public void sendInitialData(net.minecraft.world.inventory.AbstractContainerMenu menu, net.minecraft.core.NonNullList<ItemStack> items, ItemStack carried, int[] data) {}
            @Override public void sendSlotChange(net.minecraft.world.inventory.AbstractContainerMenu menu, int slot, ItemStack changed) { synchronizedItems.add(changed.copy()); }
            @Override public void sendCarriedChange(net.minecraft.world.inventory.AbstractContainerMenu menu, ItemStack stack) {}
            @Override public void sendDataChange(net.minecraft.world.inventory.AbstractContainerMenu menu, int id, int value) {}
        });
        dispatchRegisteredLegacyPacket(player, client.placementPacket("Test Frequency", "-17"));
        player.closeContainer();
        player.inventoryMenu.broadcastChanges();
        helper.assertTrue(synchronizedItems.stream().anyMatch(stack ->
            com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreItemData.read(stack)
                .filter(data -> data.frequency().value().equals("Test Frequency") && data.libraryPriority() == -17).isPresent()),
            "Vanilla inventory synchronization must expose configured exact held-stack data");
        player.setShiftKeyDown(true);
        helper.assertTrue(item.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction(),
            "Configured unplaced Core remains editable using sneak-use");
        player.closeContainer(); player.setShiftKeyDown(false);
        helper.assertTrue(player.gameMode.useItemOn(player, level, item, InteractionHand.MAIN_HAND, placeHit).consumesAction(),
            "Second physical click must place the configured Core");
        helper.assertTrue(level.getBlockEntity(pos) instanceof com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity,
            "Second click must create the real lower block entity");
        var core = (com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity) level.getBlockEntity(pos);
        helper.assertValueEqual(core.routingConfiguration().frequency().value(), "Test Frequency", "Placed Core must inherit literal configured frequency");
        helper.assertValueEqual(core.routingConfiguration().priority(), -17, "Placed Core must inherit configured priority");
        helper.succeed();
    }

    /** Only the headless transport is substituted; both encode/decode and dispatch are the registered production union. */
    @SuppressWarnings("unchecked")
    private static void dispatchRegisteredLegacyPacket(ServerPlayer player, Object packet) {
        var wire = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), player.registryAccess());
        try {
            Class<?> payload = Class.forName("com.nobodiiiii.createbiotech.network.CBPackets$ServerPayload");
            var constructor = payload.getDeclaredConstructor(Object.class); constructor.setAccessible(true);
            var field = payload.getDeclaredField("STREAM_CODEC"); field.setAccessible(true);
            var codec = (net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, Object>) field.get(null);
            codec.encode(wire, constructor.newInstance(packet));
            ((net.createmod.catnip.net.base.ServerboundPacketPayload) codec.decode(wire)).handle(player);
        } catch (ReflectiveOperationException failure) { throw new AssertionError("registered placement packet unavailable", failure); }
        finally { wire.release(); }
    }

    private static final class RecordingPlayer extends FakePlayer {
        net.minecraft.world.inventory.AbstractContainerMenu clientMenu;
        net.minecraft.network.chat.Component feedback;
        RecordingPlayer(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "player-entry")); }
        @Override public java.util.OptionalInt openMenu(net.minecraft.world.MenuProvider provider,
            java.util.function.Consumer<net.minecraft.network.RegistryFriendlyByteBuf> writer) {
            var menu = provider.createMenu(1, getInventory(), this);
            if (menu == null) return java.util.OptionalInt.empty();
            containerMenu = menu;
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try {
                if (writer != null) writer.accept(buffer);
                if (menu instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu)
                    clientMenu = com.nobodiiiii.createbiotech.registry.CBMenuTypes.PATTERN_STORAGE_CORE.get().create(1, getInventory(), buffer);
                else if (menu instanceof com.nobodiiiii.createbiotech.content.biocomputing.library.menu.LibraryCorePlacementMenu)
                    clientMenu = com.nobodiiiii.createbiotech.registry.CBMenuTypes.PATTERN_CORE_PLACEMENT.get().create(1, getInventory(), buffer);
            } finally { buffer.release(); }
            return java.util.OptionalInt.of(1);
        }
        @Override public void displayClientMessage(net.minecraft.network.chat.Component component, boolean overlay) {
            feedback = component;
        }
    }
    private record Fixture(ComputerBlockEntity computer, FactoryPanelBlockEntity panel, ServerPlayer player) {}
}
