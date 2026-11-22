package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionRuntimeServices;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingWorldGameTests.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberIndex;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.EpochFault;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.EpochReformControl;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingEpochTransactionsGameTests {
    @GameTest(template = "empty", timeoutTicks = 240)
    public static void realEpochStartCloseAndReformPreserveIndependentPausedComputers(GameTestHelper helper) {
        var origin = helper.absolutePos(new BlockPos(1, 1, 1));
        var a = computer(helper, origin.offset(1, 1, 1), VillagerProfession.LIBRARIAN, 1);
        var b = computer(helper, origin.offset(3, 1, 1), VillagerProfession.FLETCHER, 2);
        var computers = List.of(a, b);
        for (int i = 0; i < computers.size(); i++) {
            var base = counter().program();
            var program = new ProgramArchive(new ProgramDefinition(base.graph(), Map.of("count",
                new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(i * 10L))), base.writes()), Map.of());
            var host = host(computers.get(i));
            helper.assertValueEqual(host.install(program, ComputerRuntime.MemoryPolicy.RESET),
                ComputerRuntime.InstallResult.APPLIED, "Install each private counter");
            host.setPaused(true);
            host.begin(new DataValue.RecordValue(Map.of()));
        }
        var savedPrograms = computers.stream().map(c -> host(c).snapshot().orElseThrow()).toList();
        var hardware = computers.stream().map(c -> host(c).hardware()).toList();
        var profiles = computers.stream().map(c -> c.production().installedProfile().orElseThrow()).toList();
        var ids = computers.stream().map(c -> c.computerId().orElseThrow()).toList();
        for (int x = 0; x < 5; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 3; z++)
            if (x == 0 || x == 4 || y == 0 || y == 3 || z == 0 || z == 2)
                helper.getLevel().setBlock(origin.offset(x, y, z), CBBlocks.COMPUTER_CASING.get().defaultBlockState(), Block.UPDATE_ALL);
        // The real embedded production panel drives the existing checked first-epoch bootstrap.
        helper.getLevel().setBlock(origin.offset(0, 1, 1), CBBlocks.FACTORY_PANEL.get().defaultBlockState(), Block.UPDATE_ALL);
        var restored = new ArrayList<ComputerBlockEntity>();
        helper.startSequence().thenWaitUntil(() -> {
            for (var c : computers) helper.assertTrue(c.production().epoch().isPresent(), "Natural shell + panel startup freezes the real epoch: " + c.production().availabilityReason());
            var epoch = a.production().epoch().orElseThrow();
            var observed = ProductionRuntimeServices.get(helper.getLevel().getServer()).snapshot(epoch.groupId()).orElse(null);
            helper.assertTrue(observed != null && observed.members().size() == computers.size(),
                "Natural production ticks publish every heterogeneous member to the real service");
            for (int i = 0; i < computers.size(); i++) {
                var member = observed.member(ids.get(i));
                helper.assertTrue(member != null && member.loaded(), "Each physical Computer is sampled as loaded");
                helper.assertValueEqual(member.profile(), profiles.get(i), "Sampling preserves each member's own production profile");
            }
        }).thenExecute(() -> {
            var epoch = a.production().epoch().orElseThrow();
            var owner = computers.stream().filter(c -> c.computerId().orElseThrow().equals(epoch.coordinatorId())).findFirst().orElseThrow();
            var structureId = owner.production().computerStructureMemberId();
            helper.assertTrue(!ClusterMemberIndex.conflicts(helper.getLevel().getServer(), epoch.groupId()).blocksNewTasks(),
                "The real heterogeneous production members share a valid registered binding");
            var panel = (com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelBlockEntity)
                helper.getLevel().getBlockEntity(origin.offset(0, 1, 1));
            var beforeBinding = panel.bindingState();
            var player = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "binding-transaction"));
            helper.assertValueEqual(com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService.replaceFormedBindings(
                player, panel, beforeBinding.logisticsBindings()),
                com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService.BindResult.OK,
                "The real player entry updates the formed production binding through its narrow logistics transaction");
            helper.assertValueEqual(panel.bindingState().revision(), beforeBinding.revision() + 1,
                "Successful transaction advances one binding revision without replacing the production epoch");
            for (int i = 0; i < computers.size(); i++) {
                var c = computers.get(i);
                helper.assertValueEqual(c.production().coordinatorMember.bindingState(), panel.bindingState(), "Every exact Computer replica receives the same binding");
                helper.assertValueEqual(c.production().epoch().orElseThrow(), epoch, "Logistics update does not replace the active epoch");
                helper.assertValueEqual(host(c).hardware(), hardware.get(i), "Logistics transaction preserves different hardware budgets");
                helper.assertValueEqual(host(c).snapshot().orElseThrow(), savedPrograms.get(i), "Logistics transaction never alters private computation");
            }
            var libraryPos = origin.offset(6, 1, 1);
            helper.getLevel().setBlock(libraryPos.below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            var libraryState = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState();
            helper.getLevel().setBlock(libraryPos, libraryState.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
            helper.getLevel().setBlock(libraryPos.above(), libraryState.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
            var library = (com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity)
                helper.getLevel().getBlockEntity(libraryPos);
            var libraryId = UUID.randomUUID();
            helper.assertTrue(library.applyPlacementData(com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreItemData.configured(
                com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency.of("binding-entry-native"), 0, libraryId, 0)),
                "New physical Library has its own identity and no production binding");
            var bindingBeforeLibrary = panel.bindingState();
            helper.assertValueEqual(com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService.bind(player, panel, library),
                com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService.BindResult.OK,
                "First attachment uses the actual checked player service rather than rebinding the active Computer group");
            helper.assertValueEqual(library.memberId(), libraryId, "Attaching production does not replace the Library identity");
            helper.assertValueEqual(library.bindingState(), bindingBeforeLibrary, "Only the new Library receives the current production binding");
            helper.assertValueEqual(panel.bindingState(), bindingBeforeLibrary, "First attachment cannot revise the source binding");
            for (int i = 0; i < computers.size(); i++) {
                var c = computers.get(i);
                helper.assertValueEqual(c.production().coordinatorMember.bindingState(), bindingBeforeLibrary, "Existing Computer binding remains unchanged");
                helper.assertValueEqual(c.production().epoch().orElseThrow(), epoch, "First Library attachment cannot restart the production epoch");
                helper.assertValueEqual(host(c).hardware(), hardware.get(i), "Library attachment keeps each Computer's hardware");
                helper.assertValueEqual(host(c).snapshot().orElseThrow(), savedPrograms.get(i), "Library attachment cannot edit private programs or RAM");
            }
            var control = new VerifiedIdleProduction(computers);
            helper.assertValueEqual(owner.production().closeIdleEpoch(epoch.epochId(), control), com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputer.EpochCloseResult.CLOSED,
                "Close only after consulting actual empty production runtimes");
            for (int i = 0; i < computers.size(); i++) {
                var c = computers.get(i);
                helper.assertTrue(c.production().epoch().isEmpty(), "Close clears every production replica");
                helper.assertValueEqual(c.production().installedProfile().orElseThrow(), profiles.get(i), "Closing production keeps each installed profile");
                helper.assertValueEqual(host(c).hardware(), hardware.get(i), "Closing production cannot pool or reset hardware budgets");
                helper.assertValueEqual(host(c).snapshot().orElseThrow(), savedPrograms.get(i), "Close leaves the private paused event and RAM intact");
            }
            var publication = owner.production().publishedCoordinatorMember().orElseThrow();
            var acceptedBinding = publication.bindingState();
            helper.assertValueEqual(publication.prepareClusterBinding(acceptedBinding),
                com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingPreparation.READY,
                "Prepare an idempotent binding through the actual published coordinator");
            publication.commitClusterBinding(acceptedBinding);
            for (int i = 0; i < computers.size(); i++) {
                var c = computers.get(i);
                helper.assertValueEqual(c.production().coordinatorMember.bindingState(), acceptedBinding, "Real indexed commit reaches every exact replica");
                helper.assertValueEqual(c.production().installedProfile().orElseThrow(), profiles.get(i), "Binding preserves each profession profile");
                helper.assertValueEqual(host(c).hardware(), hardware.get(i), "Binding preserves each hardware budget");
                helper.assertValueEqual(host(c).snapshot().orElseThrow(), savedPrograms.get(i), "Binding never edits private program or RAM");
            }
            helper.assertValueEqual(owner.production().startEpoch(epoch.groupId()), com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputer.EpochStartResult.STARTED,
                "Explicit start uses the real binding and current physical shell");
            var restarted = owner.production().epoch().orElseThrow();
            helper.assertValueEqual(owner.production().latchEpochFault(restarted.epochId(), EpochFault.WIDTH), com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputer.FaultLatchResult.LATCHED,
                "Latch a production-only fault");
            var follower = computers.stream().filter(c -> c != owner).findFirst().orElseThrow();
            helper.assertValueEqual(follower.production().latchEpochFault(restarted.epochId(), EpochFault.WIDTH), com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputer.FaultLatchResult.ALREADY_LATCHED,
                "A frozen follower can repeat a fault without opening another epoch");
            for (int i = 0; i < computers.size(); i++) {
                var c = computers.get(i);
                helper.assertValueEqual(c.production().latchedEpochFaults(), Set.of(EpochFault.WIDTH), "All loaded production replicas retain the fault");
                helper.assertValueEqual(c.production().epoch().orElseThrow(), restarted, "Fault latching does not revise frozen profiles or identity");
                helper.assertValueEqual(host(c).snapshot().orElseThrow(), savedPrograms.get(i), "Fault latching cannot alter the private paused event or RAM");
                helper.assertValueEqual(host(c).hardware(), hardware.get(i), "Fault latching keeps each member's own hardware budget");
                var faulted = (ComputerBlockEntity) Objects.requireNonNull(BlockEntity.loadStatic(c.getBlockPos(), c.getBlockState(),
                    c.saveWithFullMetadata(helper.getLevel().registryAccess()), helper.getLevel().registryAccess()));
                helper.assertValueEqual(faulted.production().latchedEpochFaults(), Set.of(EpochFault.WIDTH), "Faults survive actual BE archive decoding");
                helper.assertValueEqual(faulted.production().epoch().orElseThrow(), restarted, "Decoding keeps the faulted epoch and each frozen profile");
            }
            helper.assertValueEqual(owner.production().stopAllAndReform(restarted.epochId(), control), com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputer.ReformResult.REFORMED,
                "Reform an already verified idle production application");
            var replacement = owner.production().epoch().orElseThrow();
            helper.assertTrue(!replacement.epochId().equals(restarted.epochId()), "Reform produces a new frozen epoch");
            helper.assertValueEqual(owner.production().stopAllAndReform(restarted.epochId(), control), com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputer.ReformResult.EPOCH_MISMATCH,
                "A stale production request cannot reform the replacement epoch a second time");
            for (int i = 0; i < computers.size(); i++) {
                var c = computers.get(i);
                helper.assertValueEqual(c.production().computerStructureMemberId(), structureId, "Keep physical production structure identity");
                helper.assertValueEqual(c.production().epoch().orElseThrow(), replacement, "All members receive the same production epoch");
                helper.assertTrue(c.production().latchedEpochFaults().isEmpty(), "Verified reform clears production faults");
                helper.assertValueEqual(replacement.layout().members().stream().filter(n -> n.computerId().equals(c.computerId().orElseThrow()))
                    .findFirst().orElseThrow().profile(), profiles.get(i), "Freeze the member's own profile, not a pooled/default profile");
                helper.assertValueEqual(host(c).snapshot().orElseThrow(), savedPrograms.get(i), "Production start/close/reform cannot alter private computation");
                var saved = c.saveWithFullMetadata(helper.getLevel().registryAccess());
                var pos = c.getBlockPos();
                helper.getLevel().removeBlockEntity(pos);
                helper.assertTrue(c.production().publishedCoordinatorMember().isEmpty(), "Removed instances withdraw their publication");
                helper.assertTrue(ClusterMemberIndex.members(helper.getLevel().getServer(), replacement.groupId(),
                    ClusterMemberType.COMPUTER_COORDINATOR).stream().noneMatch(member -> member == c.production().coordinatorMember),
                    "Actual server index no longer retains this exact removed adapter");
                var next = (ComputerBlockEntity) Objects.requireNonNull(BlockEntity.loadStatic(pos, c.getBlockState(), saved, helper.getLevel().registryAccess()));
                helper.assertTrue(next.production().publishedCoordinatorMember().isEmpty(), "Publication is not restored from an archive");
                helper.getLevel().setBlockEntity(next);
                restored.add(next);
                host(next).document();
                helper.assertValueEqual(host(next).snapshot().orElseThrow(), savedPrograms.get(i), "Reload retains the original paused event and RAM");
                helper.assertValueEqual(host(next).hardware(), hardware.get(i), "Reload retains per-member hardware limits");
                helper.assertValueEqual(next.computerId().orElseThrow(), ids.get(i), "Keep each resident Computer identity");
            }
            helper.assertTrue(hardware.get(0).limits().hardware().workPerSlice() < hardware.get(1).limits().hardware().workPerSlice(),
                "The two machines retain different execution budgets");
            restored.forEach(c -> host(c).setPaused(false));
        }).thenWaitUntil(() -> {
            var epoch = restored.getFirst().production().epoch().orElseThrow();
            var coordinator = restored.stream().filter(c -> c.computerId().orElseThrow().equals(epoch.coordinatorId())).findFirst().orElseThrow();
            var publication = coordinator.production().publishedCoordinatorMember().orElse(null);
            helper.assertTrue(publication != null, "Natural scan publishes the replacement coordinator");
            var indexed = ClusterMemberIndex.members(helper.getLevel().getServer(), epoch.groupId(), ClusterMemberType.COMPUTER_COORDINATOR);
            helper.assertTrue(indexed.stream().anyMatch(member -> member == publication), "The actual index contains the new exact adapter");
            helper.assertTrue(indexed.stream().noneMatch(member -> computers.stream().anyMatch(c -> member == c.production().coordinatorMember)),
                "Neither removed adapter reappears after the new publication");
            helper.assertTrue(!ClusterMemberIndex.conflicts(helper.getLevel().getServer(), epoch.groupId()).blocksNewTasks(),
                "Replacement registration retains coherent production bindings, not a stale duplicate authority");
            helper.assertValueEqual(memory(host(restored.get(0)), "count"), new DataValue.IntegerValue(1), "First original event completes once");
            helper.assertValueEqual(memory(host(restored.get(1)), "count"), new DataValue.IntegerValue(11), "Second original event uses separate RAM");
        }).thenIdle(5).thenExecute(() -> {
            helper.assertValueEqual(memory(host(restored.get(0)), "count"), new DataValue.IntegerValue(1), "No replay after restart");
            helper.assertValueEqual(memory(host(restored.get(1)), "count"), new DataValue.IntegerValue(11), "No cross-member writes");
        }).thenSucceed();
    }

    /** This fixture accepts only already idle real production runtimes; it does not simulate draining live orders. */
    private record VerifiedIdleProduction(List<ComputerBlockEntity> members) implements EpochReformControl {
        public boolean allRootsStopped() { return members.stream().allMatch(c -> c.production().runtime().allRootsStopped()); }
        private ComputerBlockEntity member(UUID id) { return members.stream().filter(c -> c.computerId().filter(id::equals).isPresent()).findFirst().orElseThrow(); }
        public boolean nodeIdle(UUID id) { return member(id).production().runtime().nodeIdle(id); }
        public boolean nodeRootFree(UUID id) { return member(id).production().runtime().nodeRootFree(id); }
        public boolean nodeMailboxEmpty(UUID id) { return member(id).production().runtime().nodeMailboxEmpty(id); }
        public boolean stopAllAndClear() {
            return allRootsStopped() && members.stream().allMatch(c -> {
                var id = c.computerId().orElseThrow();
                return nodeIdle(id) && nodeRootFree(id) && nodeMailboxEmpty(id);
            });
        }
    }
}
