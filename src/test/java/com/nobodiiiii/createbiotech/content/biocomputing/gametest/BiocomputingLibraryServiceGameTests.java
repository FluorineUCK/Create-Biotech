package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.*;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingLibraryServiceGameTests {
    /** Physical completion capability only: no claim that a public slow-write command or competition host exists yet. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void positionedDeviceWriteReceiptSurvivesRealShelfReloadAndBookCarry(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(8, 2, 3));
        var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.EAST);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
        level.setBlock(pos.above(), state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        var core = (LibraryBlockEntity)level.getBlockEntity(pos); core.initialize();
        var shelfPos = pos.north(); level.setBlock(shelfPos, Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
        var shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(shelfPos);
        var archive = CBConfigs.SERVER.biocomputing.current(true).archive();
        var media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), archive, CBConfigs.SERVER.biocomputing.libraryBookBytes(true));
        var volumes = new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get());
        var pages = new net.minecraft.world.item.component.WritableBookContent(List.of(
            net.minecraft.server.network.Filterable.passThrough("original human notes"),
            net.minecraft.server.network.Filterable.passThrough("original recipe page")));
        var book = new ItemStack(Items.WRITABLE_BOOK); book.set(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT, pages);
        book = volumes.rename(media.put(book, "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(43))), "日志");
        shelf.setItem(2, book);
        var service = core.getBehaviour(LibraryServiceBehaviour.TYPE);
        helper.assertValueEqual(service.configure(0, new LibraryServiceConfiguration(Optional.empty(),
            Map.of("日志", LibraryServiceConfiguration.Access.READ_WRITE))), "configured", "Explicit grant, legacy endpoint still closed");
        java.util.function.Supplier<LibraryStructureScanner.ScanResult> scan = () -> LibraryStructureScanner.scan(
            new LibraryStructureWorldView(level), pos, 32, 16);
        try (var world = new LibraryServiceWorldSource(level, pos, core::libraryIdentityReady, scan, archive,
            CBConfigs.SERVER.biocomputing.libraryBookBytes(true))) {
            var writer = world.positionedWriter(track -> track.resolve(pos, Direction.EAST, scan.get()), service::volumeAccess);
            var track = LibraryTrackAddress.from(pos, Direction.EAST, shelfPos);
            var operation = new LibraryDeviceWrite(UUID.randomUUID(), 3, new LibraryMutation("日志", "reading",
                LibraryMutation.Version.decode(media.stat(book, "reading").field("version")),
                Optional.of(new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(44)))));
            var prepared = writer.prepare(track, 2, operation);
            helper.assertTrue(ItemStack.matches(book, shelf.getItem(2)), "Preparation does not write the actual shelf");
            helper.assertValueEqual(service.configure(1, new LibraryServiceConfiguration(Optional.empty(),
                Map.of("日志", LibraryServiceConfiguration.Access.READ_ONLY))), "configured", "Revoke write before physical commit");
            try { prepared.commit().run(); helper.fail("Revoked candidate must not commit"); }
            catch (LibraryBookAccess.Failure failure) { helper.assertValueEqual(failure.code(), "readonly", "Live permission rechecked"); }
            helper.assertTrue(ItemStack.matches(book, shelf.getItem(2)), "Denied write leaves every component intact");
            helper.assertValueEqual(service.configure(2, new LibraryServiceConfiguration(Optional.empty(),
                Map.of("日志", LibraryServiceConfiguration.Access.READ_WRITE))), "configured", "Explicit reauthorization");
            prepared.commit().run(); var written = shelf.getItem(2).copy();
            helper.assertValueEqual(media.read(written).get("reading").value(), new DataValue.IntegerValue(44), "Single physical update");
            var saved = shelf.saveWithFullMetadata(level.registryAccess()); level.removeBlockEntity(shelfPos);
            var restored = (ChiseledBookShelfBlockEntity)BlockEntity.loadStatic(shelfPos, level.getBlockState(shelfPos), saved, level.registryAccess());
            level.setBlockEntity(restored);
            var replay = writer.prepare(track, 2, LibraryDeviceWrite.decode(operation.value()));
            helper.assertTrue(replay.replay(), "Registered component restores the original device receipt");
            helper.assertValueEqual(replay.reply(), prepared.reply(), "Recovery returns the original reply"); replay.commit().run();
            helper.assertTrue(ItemStack.matches(written, restored.getItem(2)), "Replay cannot increment the version");
            var taken = restored.removeItem(2, 1);
            var carried = ItemStack.parse(level.registryAccess(), taken.save(level.registryAccess())).orElseThrow();
            restored.setItem(5, carried);
            try { writer.prepare(track, 2, operation); helper.fail("Physical addressing must not follow a moved book"); }
            catch (LibraryBookAccess.Failure failure) { helper.assertValueEqual(failure.code(), "missing_medium", "Old slot is unavailable"); }
            helper.assertTrue(writer.prepare(track, 5, operation).replay(), "Only explicit new positioning can see the carried receipt");
            writer.release(track, 5, operation);
            helper.assertValueEqual(media.stat(restored.getItem(5), "reading"), media.stat(written, "reading"), "Cleanup preserves the entry/version");
            helper.assertValueEqual(restored.getItem(5).get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT), pages, "Original pages survive write/reload/carry/cleanup");
            helper.assertTrue(service.advertisements().isEmpty(), "No old RPC endpoint is needed");
        }
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 220)
    public static void librarianFinishesAcceptedWorkOnItsOwnTicksWhileTheControllerIsPaused(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(8, 2, 3));
        var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.EAST);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos, state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), 3);
        level.setBlock(pos.above(), state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var core = (LibraryBlockEntity)level.getBlockEntity(pos);
        helper.assertTrue(core.applyPlacementData(LibraryCoreItemData.configured(CommunicationFrequency.of("librarian-work"), 0, UUID.randomUUID(), 0)), "Configured physical item fixture");
        core.initialize();
        var computer = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, pos.west(4)));
        computer.install(BiocomputingWorldGameTests.counter(), ComputerRuntime.MemoryPolicy.RESET); computer.setPaused(true);
        var before = computer.snapshot().orElseThrow();
        for (int x = 1; x <= 3; x++) level.setBlock(pos.west(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "librarian-clock"));
        actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        var control = core.getBehaviour(LibraryControlBehaviour.TYPE);
        helper.assertValueEqual(control.configure(actor, 0, Optional.of(control.discover(actor).getFirst())), "configured", "Explicit controller");
        level.setBlock(pos.north(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), 3);
        var shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(pos.north());
        var media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), CBConfigs.SERVER.biocomputing.current(true).archive());
        var volumes = new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get());
        var book = volumes.rename(media.put(new ItemStack(Items.WRITABLE_BOOK), "reading",
            new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(43))), "日志");
        shelf.setItem(2, book.copy());
        var service = core.getBehaviour(LibraryServiceBehaviour.TYPE);
        helper.assertValueEqual(service.configure(0, new LibraryServiceConfiguration(Optional.empty(),
            Map.of("日志", LibraryServiceConfiguration.Access.READ_ONLY))), "configured", "Grant does not open the legacy RPC endpoint");
        var drive = core.getBehaviour(LibraryDriveBehaviour.TYPE);
        helper.assertTrue(drive != null, "Registered Library owns an actual mechanical host");
        var track = LibraryTrackAddress.from(pos, Direction.EAST, pos.north());
        long seek = drive.seek(computer, track);
        helper.assertTrue(drive.snapshot().orElseThrow().pending().isPresent(), "Seek is not an immediate result");
        var carried = LibraryServiceItemData.decode(core.asConfiguredStack().get(CBDataComponents.BIOCOMPUTING_LIBRARY_SERVICE.get()));
        helper.assertValueEqual(carried.drive().orElseThrow(), drive.archive().orElseThrow(), "Actual core item carries all remaining work");
        var clientTag = new net.minecraft.nbt.CompoundTag(); drive.write(clientTag, level.registryAccess(), true);
        helper.assertTrue(!clientTag.contains(LibraryDriveBehaviour.ROOT), "Private data is not placed in generic BE updates");
        LibraryControlBehaviour.duringRemoval(core, () -> {
            var held = drive.archive().orElseThrow(); drive.tick();
            helper.assertValueEqual(drive.archive().orElseThrow(), held, "Synchronous removal cannot advance already captured work");
            return true;
        });
        helper.assertValueEqual(control.configure(actor, 1, Optional.empty()), "busy", "Changing delegates cannot orphan accepted work");
        level.setBlock(pos.west(2), Blocks.AIR.defaultBlockState(), 3);
        helper.runAfterDelay(40, () -> {
            helper.assertValueEqual(drive.snapshot().orElseThrow().result().orElseThrow().code(), "positioned", "Actual world ticks finish despite a paused/disconnected caller");
            var held = drive.archive().orElseThrow();
            try { drive.result(computer, seek); helper.fail("Disconnected caller must not retrieve a result"); }
            catch (LibraryBookAccess.Failure failure) { helper.assertValueEqual(failure.code(), "disconnected", "No hidden direct access"); }
            helper.assertValueEqual(drive.archive().orElseThrow(), held, "Failed retrieval retains the result");
            level.setBlock(pos.west(2), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
            helper.assertValueEqual(drive.result(computer, seek).code(), "positioned", "Explicit retrieval observes the old result");
            drive.acknowledge(computer, seek);
            long select = drive.select(computer, 2);
            helper.runAfterDelay(10, () -> {
                helper.assertValueEqual(drive.result(computer, select).code(), "selected", "Select uses device ticks");
                drive.acknowledge(computer, select);
                long read = drive.readEntry(computer, "reading");
                helper.assertTrue(drive.snapshot().orElseThrow().pending().isPresent(), "Reading consumes actual medium time");
                long wait = drive.snapshot().orElseThrow().pending().orElseThrow().remainingTicks() + 2;
                helper.runAfterDelay((int)wait, () -> {
                    var completed = drive.result(computer, read);
                    helper.assertValueEqual(completed.code(), "read", "Granted physical read completes");
                    helper.assertValueEqual(((DataValue.RecordValue)completed.value()).field("value"), new DataValue.IntegerValue(43), "Typed entry returned");
                    helper.assertTrue(ItemStack.matches(book, shelf.getItem(2)), "No write to source book/pages");
                    helper.assertValueEqual(computer.snapshot().orElseThrow(), before, "Mechanical work never advances paused Computer RAM, program or budget");
                    var retained = drive.archive().orElseThrow();
                    helper.runAfterDelay(5, () -> {
                        helper.assertValueEqual(drive.archive().orElseThrow(), retained, "Unclaimed result remains bounded and stable");
                        helper.assertValueEqual(control.configure(actor, 1, Optional.empty()), "result_pending", "Do not discard an unclaimed result on rebind");
                        drive.acknowledge(computer, read);
                        helper.assertValueEqual(control.configure(actor, 1, Optional.empty()), "configured", "Explicitly consumed work permits release");
                        helper.succeed();
                    });
                });
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void librarianControlRequiresAnExplicitReachableComputerAndKeepsItsIndependentState(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(8, 2, 3));
        var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.EAST);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
        level.setBlock(pos.above(), state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        var core = (LibraryBlockEntity)level.getBlockEntity(pos);
        helper.assertTrue(core.applyPlacementData(LibraryCoreItemData.configured(CommunicationFrequency.of("librarian-local"), 0, UUID.randomUUID(), 0)),
            "Physical fixture claims the configured item before first initialization");
        core.initialize();
        var computer = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, pos.west(4)));
        computer.install(BiocomputingWorldGameTests.counter(), ComputerRuntime.MemoryPolicy.RESET); computer.setPaused(true);
        var before = computer.snapshot().orElseThrow();
        for (int x = 1; x <= 3; x++) level.setBlock(pos.west(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "librarian-control"));
        actor.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        var control = core.getBehaviour(LibraryControlBehaviour.TYPE);
        helper.assertValueEqual(control.status().field("code"), text("unbound"), "No automatic control binding");
        var choices = control.discover(actor);
        helper.assertTrue(choices.size() == 1, "Physical cable discovers the actual Computer, without an open message endpoint");
        helper.assertValueEqual(control.configure(actor, 0, Optional.of(choices.getFirst())), "configured", "Explicit binding");
        helper.assertValueEqual(control.status().field("code"), text("ready"), "A paused program is still the designated controller");
        var saved = new net.minecraft.nbt.CompoundTag(); control.write(saved, level.registryAccess(), false);
        var carried = LibraryServiceItemData.decode(core.asConfiguredStack().get(CBDataComponents.BIOCOMPUTING_LIBRARY_SERVICE.get()));
        helper.assertValueEqual(carried.control().orElseThrow(), saved.get(LibraryControlBehaviour.ROOT), "Actual core item carries its private control binding");
        helper.assertTrue(carried.archive() == null, "An unused legacy message service is not initialized by local control");
        LibraryControlBehaviour.duringRemoval(core, () -> {
            helper.assertValueEqual(control.status().field("code"), text("unavailable"), "Removal handoff cannot accept a late configuration");
            try { control.configure(actor, 1, Optional.empty()); helper.fail("Removal must reject changes after capturing the carried state"); }
            catch (LibraryBookAccess.Failure failure) { helper.assertValueEqual(failure.code(), "unavailable", "Explicit removal boundary"); }
            return true;
        });
        var client = new net.minecraft.nbt.CompoundTag(); control.write(client, level.registryAccess(), true);
        helper.assertTrue(!client.contains(LibraryControlBehaviour.ROOT), "No private binding in generic BE updates");
        level.setBlock(pos.west(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertValueEqual(control.status().field("code"), text("disconnected"), "Broken cable is not an unbound or empty device");
        helper.assertValueEqual(control.configure(actor, 0, Optional.empty()), "changed", "Stale menu cannot clear a newer binding");
        var same = new net.minecraft.nbt.CompoundTag(); control.write(same, level.registryAccess(), false);
        helper.assertValueEqual(same, saved, "Observation preserves disconnected binding for explicit recovery");
        level.setBlock(pos.west(2), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
        control.read(saved, level.registryAccess(), false);
        helper.assertValueEqual(control.status().field("code"), text("ready"), "Stored identity and actual route recover");
        helper.assertValueEqual(computer.snapshot().orElseThrow(), before, "Binding never installs, runs or merges Computer state");
        helper.assertValueEqual(control.configure(actor, 1, Optional.empty()), "configured", "Explicit release");
        helper.assertValueEqual(control.status().field("code"), text("unbound"), "No implicit replacement owner");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void librarianSelectsAnActualClusterMemberWithoutSharingResidentResources(GameTestHelper helper) {
        var level = helper.getLevel(); var min = helper.absolutePos(new BlockPos(4, 1, 9)); var max = min.offset(4, 3, 4);
        var gatewayPos = min.offset(0, 1, 1); var memberPos = gatewayPos.east(); var otherPos = memberPos.south(2);
        var first = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, memberPos,
            net.minecraft.world.entity.npc.VillagerProfession.NONE, 1));
        var second = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, otherPos,
            net.minecraft.world.entity.npc.VillagerProfession.CLERIC, 5));
        for (var computer : List.of(first, second)) { computer.install(BiocomputingWorldGameTests.counter(), ComputerRuntime.MemoryPolicy.RESET); computer.setPaused(true); }
        var before = List.of(first.snapshot().orElseThrow(), second.snapshot().orElseThrow());
        var hardware = List.of(first.hardware(), second.hardware());
        helper.assertTrue(!hardware.get(0).equals(hardware.get(1)), "Actual different residents and hardware");
        for (var part : BlockPos.betweenClosed(min, max))
            if (part.getX() == min.getX() || part.getX() == max.getX() || part.getY() == min.getY() || part.getY() == max.getY()
                || part.getZ() == min.getZ() || part.getZ() == max.getZ()) level.setBlock(part, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), 3);
        level.setBlock(gatewayPos, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState()
            .setValue(com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlock.FACING, Direction.WEST), 3);
        var pos = gatewayPos.west(3);
        var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.WEST);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos, state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), 3);
        level.setBlock(pos.above(), state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var core = (LibraryBlockEntity)level.getBlockEntity(pos); core.initialize();
        level.setBlock(pos.east(), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(pos.east(2), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "librarian-cluster")); actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        var control = core.getBehaviour(LibraryControlBehaviour.TYPE); var choices = control.discover(actor);
        helper.assertTrue(choices.size() == 2 && choices.stream().allMatch(c -> c.controller().isPresent()), "Explicit candidates, not automatic member routing");
        var target = choices.stream().filter(c -> c.member().equals(otherPos)).findFirst().orElseThrow();
        helper.assertValueEqual(control.configure(actor, 0, Optional.of(target)), "configured", "One member selected");
        helper.assertValueEqual(control.status().field("code"), text("ready"), "Closed casing with internal air is valid");
        level.setBlock(min.above(), Blocks.AIR.defaultBlockState(), 3);
        helper.assertValueEqual(control.status().field("code"), text("gateway_unformed"), "Broken outer shell disables the designated path");
        level.setBlock(min.above(), CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), 3);
        helper.assertValueEqual(control.status().field("code"), text("ready"), "Repair restores the same chosen member");
        helper.assertValueEqual(List.of(first.snapshot().orElseThrow(), second.snapshot().orElseThrow()), before, "No program or memory changes");
        helper.assertValueEqual(List.of(first.hardware(), second.hardware()), hardware, "No resident-level or budget aggregation");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void explicitAddressUsesTheRealSelectedShelfAndRejectsReplacedBooks(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(8, 2, 3));
        var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.EAST);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
        level.setBlock(pos.above(), state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        var core = (LibraryBlockEntity)level.getBlockEntity(pos); core.initialize();
        var shelfPos = pos.north(); level.setBlock(shelfPos, Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
        var shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(shelfPos);
        var media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), CBConfigs.SERVER.biocomputing.current(true).archive());
        var volumes = new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get());
        var book = volumes.rename(media.put(new ItemStack(Items.WRITABLE_BOOK), "reading",
            new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(43))), "日志");
        shelf.setItem(2, book);
        java.util.function.Supplier<LibraryStructureScanner.ScanResult> scan = () -> LibraryStructureScanner.scan(
            new LibraryStructureWorldView(level), pos, 32, 16);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "disk-address-reader"));
        try (var world = new LibraryBookWorldAccess(level, pos, actor, core::libraryIdentityReady, () -> true,
                () -> scan.get().snapshot().mediaShelves())) {
            var access = new LibraryBookAccess(world, media, volumes);
            var track = new LibraryTrackAddress(-1, 0, 0);
            var handle = access.at(track.resolve(pos, Direction.EAST, scan.get()), 2);
            helper.assertValueEqual(world.address(handle), obj("track", track.value(), "slot", new DataValue.IntegerValue(2)),
                "Management address uses the real core orientation and zero-based physical slot");
            helper.assertValueEqual(access.read(handle).get("reading").value(), new DataValue.IntegerValue(43), "Selected real medium is readable");
            level.setBlock(pos.south(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertValueEqual(track.resolve(pos, Direction.EAST, scan.get()), shelfPos, "Adding a shelf cannot renumber the selected track");
            var before = shelf.getItem(2).copy();
            helper.assertTrue(ItemStack.matches(before, book), "Address projection did not alter pages, values or volume");
            // Native physical-source proof only. Explicit test ticks do not claim production host/clock integration.
            var limits = CBConfigs.SERVER.biocomputing.libraryDrive(true);
            var source = new LibraryPositionedReadSource(access, address -> address.resolve(pos, Direction.EAST, scan.get()),
                volume -> volume.equals("日志"));
            var head = LibraryReadHead.seek(LibraryReadHead.State.empty(), track, source, limits);
            head = advanceReadHead(helper, head, source, limits);
            head = LibraryReadHead.take(head, head.sequence(), limits).state();
            head = advanceReadHead(helper, LibraryReadHead.select(head, 2, source, limits), source, limits);
            head = LibraryReadHead.take(head, head.sequence(), limits).state();
            head = LibraryReadHead.read(head, "reading", source, limits);
            helper.assertTrue(head.result().isEmpty(), "Real book does not bypass the slow read");
            head = LibraryReadHead.advance(head, source, limits);
            var savedHead = LibraryReadHeadCodec.encode(head, limits);
            var remaining = head.pending().orElseThrow().remainingTicks();
            head = LibraryReadHeadCodec.decode(savedHead, limits);
            helper.assertTrue(head.pending().orElseThrow().remainingTicks() == remaining, "Restoration keeps mechanical work");
            head = advanceReadHead(helper, head, source, limits);
            helper.assertValueEqual(head.result().orElseThrow().code(), "read", "Selected real shelf completed");
            helper.assertValueEqual(((DataValue.RecordValue)head.result().orElseThrow().value()).field("value"),
                new DataValue.IntegerValue(43), "Typed stored value, not a world reference");
            helper.assertTrue(ItemStack.matches(before, shelf.getItem(2)), "Delayed read did not change the real book");
            head = LibraryReadHead.take(head, head.sequence(), limits).state();
            head = LibraryReadHead.read(head, "reading", source, limits);
            shelf.setItem(2, media.put(book, "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(44))));
            head = advanceReadHead(helper, LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(head, limits), limits), source, limits);
            helper.assertValueEqual(head.result().orElseThrow().code(), "changed", "Restored read cannot follow a changed real book");
            helper.assertValueEqual(head.result().orElseThrow().value(), DataValue.NullValue.INSTANCE, "Failed read does not leak its sampled value");
            try { access.read(handle); helper.fail("Old physical selection must not follow a changed book"); }
            catch (LibraryBookAccess.Failure changed) { helper.assertValueEqual(changed.code(), "changed", "Explicit reselection is required"); }
            helper.assertValueEqual(media.read(shelf.getItem(2)).get("reading").value(), new DataValue.IntegerValue(44), "Failure did not modify replacement");
        }
        helper.succeed();
    }
    private static LibraryReadHead.State advanceReadHead(GameTestHelper helper, LibraryReadHead.State state,
        LibraryReadHead.Source source, LibraryDriveLimits limits) {
        for (int ticks = 0; state.pending().isPresent() && ticks < 10000; ticks++) state = LibraryReadHead.advance(state, source, limits);
        helper.assertTrue(state.pending().isEmpty(), "Bounded native fixture completed its explicitly supplied activity ticks");
        return state;
    }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void closedLibraryAndComputerRemainUndiscoverableButHaveSpecificDeliveryDiagnosis(GameTestHelper helper) {
        var level = helper.getLevel();
        var source = BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(3, 2, 3)));
        var target = BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(3, 2, 6)));
        var computer = BiocomputingWorldGameTests.host(target);
        computer.configureEndpoint(new EndpointConfiguration("sleeping", false));
        var pos = helper.absolutePos(new BlockPos(8, 2, 3));
        var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.EAST);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
        level.setBlock(pos.above(), state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        var core = (LibraryBlockEntity)level.getBlockEntity(pos);
        core.initialize(); // Raw world fixture must enter the physical identity lifecycle before serving.
        var service = core.getBehaviour(LibraryServiceBehaviour.TYPE);
        helper.assertValueEqual(service.configure(0, new LibraryServiceConfiguration(
            Optional.of(new EndpointConfiguration("books", false)), Map.of())), "configured", "Explicitly closed Library");
        for (int x = 4; x <= 7; x++)
            level.setBlock(helper.absolutePos(new BlockPos(x, 2, 3)), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
        for (int z = 4; z <= 5; z++)
            level.setBlock(helper.absolutePos(new BlockPos(3, 2, z)), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
        var envelope = new com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageEnvelope(
            UUID.randomUUID(), 0, UUID.randomUUID(), 0, obj("value", new DataValue.IntegerValue(7)));
        helper.assertValueEqual(EndpointTransport.deliverEnvelope(source, EnumSet.allOf(Direction.class), "books", envelope).code(), "closed", "Closed Library is not missing");
        helper.assertValueEqual(EndpointTransport.deliverEnvelope(source, EnumSet.allOf(Direction.class), "sleeping", envelope).code(), "closed", "Closed Computer is not missing");
        helper.assertTrue(service.advertisement().isEmpty() && computer.advertisement().isEmpty(), "Closed endpoints grant no singular open advertisement");
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "closed-reader"));
        actor.setPos(source.getBlockPos().getX() + .5, source.getBlockPos().getY(), source.getBlockPos().getZ() + .5);
        helper.assertTrue(((DataValue.RecordValue)PeerDiscovery.discover(source, actor).field("peers")).fields().isEmpty(), "Player discovery still hides closed names");
        helper.assertValueEqual(service.status().field("queued"), new DataValue.IntegerValue(0), "No Library admission");
        helper.assertTrue(computer.snapshot().isEmpty(), "No Computer RAM restoration or execution");
        helper.assertValueEqual(EndpointTransport.deliverEnvelope(source, EnumSet.allOf(Direction.class), "missing", envelope).code(), "not_found", "Unknown name remains distinct");
        helper.succeed();
    }
    static DataValue.TextValue text(String s) { return new DataValue.TextValue(s); }
    static DataValue.RecordValue obj(Object... pairs) {
        var map = new LinkedHashMap<String, DataValue>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String)pairs[i], pairs[i+1] instanceof String s ? text(s) : (DataValue)pairs[i+1]);
        return new DataValue.RecordValue(map);
    }
    @GameTest(template = "empty", timeoutTicks = 160)
    public static void computerReadsGrantedPhysicalBookThroughRealCableAndReceivesTypedErrors(GameTestHelper helper) {
        var level = helper.getLevel();
        var computer = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(3, 2, 3))));
        var pos = helper.absolutePos(new BlockPos(8, 2, 3));
        var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.EAST);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
        level.setBlock(pos.above(), state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        var core = (LibraryBlockEntity)level.getBlockEntity(pos);
        core.initialize(); // Raw world fixture must enter the physical identity lifecycle before serving.
        var service = core.getBehaviour(LibraryServiceBehaviour.TYPE);
        helper.assertTrue(level.getCapability(CommunicationEndpoint.CAPABILITY, pos, Direction.WEST) == service, "Registered real rear capability");
        helper.assertTrue(level.getCapability(CommunicationEndpoint.CAPABILITY, pos, Direction.EAST) == null, "No implicit front connector");
        helper.assertTrue(service.advertisement().isEmpty(), "New service starts closed");
        var config = new LibraryServiceConfiguration(Optional.of(new EndpointConfiguration("books", true)),
            Map.of("日志", LibraryServiceConfiguration.Access.READ_ONLY));
        helper.assertValueEqual(service.configure(0, config), "configured", "Explicit volume grant");
        level.setBlock(pos.north(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
        var shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(pos.north());
        var media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(),
            CBConfigs.SERVER.biocomputing.current(true).archive());
        var volumes = new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get());
        var book = volumes.rename(media.put(new ItemStack(Items.WRITABLE_BOOK), "reading",
            new LibraryEntry(LibraryEntry.Kind.DATA, obj("count", new DataValue.IntegerValue(43)))), "日志");
        shelf.setItem(0, book);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "library-reader"));
        var capture = BlockSnapshotReader.read(level, pos, Direction.WEST, actor, CBConfigs.SERVER.biocomputing.peripherals(true).snapshot());
        helper.assertTrue(capture.fields().get("error") instanceof DataValue.ErrorValue, "Generic readers must not expose private Library service queues or grants");
        for (int x = 4; x <= 7; x++) if (x != 5)
            level.setBlock(helper.absolutePos(new BlockPos(x, 2, 3)), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
        computer.configureEndpoint(new EndpointConfiguration("client", true));
        helper.assertValueEqual(computer.install(caller(), ComputerRuntime.MemoryPolicy.RESET), ComputerRuntime.InstallResult.APPLIED, "Install public-node RPC program");
        computer.begin(obj());
        helper.runAfterDelay(20, () -> {
            helper.assertValueEqual(BiocomputingWorldGameTests.memory(computer, "calls"), new DataValue.IntegerValue(0), "Cable gap prevents service access");
            level.setBlock(helper.absolutePos(new BlockPos(5, 2, 3)), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
            helper.runAfterDelay(20, () -> {
                assertReply(helper, computer, "ok", 1);
                helper.assertValueEqual(BiocomputingWorldGameTests.memory(computer, "result"), obj("kind", "DATA", "value", obj("count", new DataValue.IntegerValue(43))), "Read original typed entry");
                var saved = core.saveWithFullMetadata(level.registryAccess());
                level.removeBlockEntity(pos);
                var restored = (LibraryBlockEntity)BlockEntity.loadStatic(pos, state, saved, level.registryAccess());
                level.setBlockEntity(restored); restored.initialize();
                helper.assertValueEqual(restored.getBehaviour(LibraryServiceBehaviour.TYPE).status().field("configuration"), config.value(), "Grant and service identity survive actual BE reload");
                var taken = shelf.removeItem(0, 1);
                computer.begin(obj());
                helper.runAfterDelay(20, () -> {
                    assertReply(helper, computer, "missing_volume", 2);
                    helper.assertValueEqual(BiocomputingWorldGameTests.memory(computer, "result"), DataValue.NullValue.INSTANCE, "Failure uses same reply schema, not empty success");
                    shelf.setItem(0, taken); shelf.setItem(1, taken.copy());
                    computer.begin(obj());
                    helper.runAfterDelay(20, () -> {
                        assertReply(helper, computer, "duplicate_volume", 3);
                        shelf.removeItem(1, 1);
                        helper.assertValueEqual(computer.install(caller("stat"), ComputerRuntime.MemoryPolicy.RESET), ComputerRuntime.InstallResult.APPLIED, "Install public stat request");
                        computer.begin(obj());
                        helper.runAfterDelay(20, () -> {
                            assertReply(helper, computer, "ok", 1);
                            helper.assertValueEqual(BiocomputingWorldGameTests.memory(computer, "result"), media.stat(taken, "reading"), "Real cable stat returns matching typed value and medium-owned version");
                            helper.succeed();
                        });
                    });
                });
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void queuedWriteCannotModifyReplacementMediumAfterDeviceReload(GameTestHelper helper) {
        var level = helper.getLevel();
        var sourceBlock = BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(3, 2, 3)));
        var computer = BiocomputingWorldGameTests.host(sourceBlock);
        computer.configureEndpoint(new EndpointConfiguration("client", true));
        var pos = helper.absolutePos(new BlockPos(8, 2, 3));
        var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.EAST);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
        level.setBlock(pos.above(), state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        var core = (LibraryBlockEntity)level.getBlockEntity(pos);
        core.initialize(); // Raw world fixture must enter the physical identity lifecycle before serving.
        var config = new LibraryServiceConfiguration(Optional.of(new EndpointConfiguration("books", true)),
            Map.of("日志", LibraryServiceConfiguration.Access.READ_WRITE));
        helper.assertValueEqual(core.getBehaviour(LibraryServiceBehaviour.TYPE).configure(0, config), "configured", "Explicit writable volume");
        level.setBlock(pos.north(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
        var shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(pos.north());
        var media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), CBConfigs.SERVER.biocomputing.current(true).archive());
        var volumes = new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get());
        var pages = new net.minecraft.world.item.component.WritableBookContent(List.of(
            net.minecraft.server.network.Filterable.passThrough("Protected original recipe page")));
        var blank = new ItemStack(Items.WRITABLE_BOOK);
        blank.set(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT, pages);
        var original = volumes.rename(media.put(blank, "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(43))), "日志");
        var replacement = volumes.rename(media.put(blank, "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(51))), "日志");
        var oldVersion = LibraryMutation.Version.decode(media.stat(original, "reading").field("version"));
        var newVersion = LibraryMutation.Version.decode(media.stat(replacement, "reading").field("version"));
        helper.assertValueEqual(oldVersion.revision(), newVersion.revision(), "Revision equality alone must not authorize the other book");
        helper.assertTrue(!oldVersion.medium().equals(newVersion.medium()), "The fixture contains two distinct physical media, not a clone");
        shelf.setItem(0, original.copy());
        for (int x = 4; x <= 7; x++) if (x != 5)
            level.setBlock(helper.absolutePos(new BlockPos(x, 2, 3)), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
        var request = obj("op", "put", "volume", "日志", "entry", "reading", "expected", oldVersion.value(),
            "kind", "DATA", "value", new DataValue.IntegerValue(44));
        helper.assertValueEqual(computer.install(caller(request), ComputerRuntime.MemoryPolicy.RESET), ComputerRuntime.InstallResult.APPLIED, "Install ordinary public Request program");
        computer.begin(obj());
        helper.runAfterDelay(20, () -> {
            var committed = computer.snapshot().orElseThrow();
            helper.assertValueEqual(committed.machine().status(), ComputationMachine.Status.SUCCEEDED, "Request event has committed");
            helper.assertValueEqual(committed.delivery().orElseThrow().acknowledged(), 0, "Cable gap retains unacknowledged request");
            helper.assertValueEqual(committed.rpc().pending().size(), 1, "Original correlation exists before reload");
            helper.assertValueEqual(computer.transportResult().orElseThrow().code(), "not_found", "Real transport observed cable gap");
            var sourceSave = sourceBlock.saveWithFullMetadata(level.registryAccess());
            level.removeBlockEntity(sourceBlock.getBlockPos());
            var reloadedBlock = (com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity)
                BlockEntity.loadStatic(sourceBlock.getBlockPos(), sourceBlock.getBlockState(), sourceSave, level.registryAccess());
            level.setBlockEntity(reloadedBlock);
            var restored = BiocomputingWorldGameTests.host(reloadedBlock);
            restored.document();
            helper.assertValueEqual(restored.snapshot().orElseThrow().id(), committed.id(), "Computer identity retained");
            helper.assertValueEqual(restored.snapshot().orElseThrow().delivery(), committed.delivery(), "Same committed event, sequence and ACK cursor");
            helper.assertValueEqual(restored.snapshot().orElseThrow().rpc(), committed.rpc(), "No new request replaces saved correlation");
            var coreSave = core.saveWithFullMetadata(level.registryAccess());
            level.removeBlockEntity(pos);
            var loadedCore = (LibraryBlockEntity)BlockEntity.loadStatic(pos, core.getBlockState(), coreSave, level.registryAccess());
            level.setBlockEntity(loadedCore); loadedCore.initialize();
            helper.assertValueEqual(loadedCore.getBehaviour(LibraryServiceBehaviour.TYPE).status().field("configuration"), config.value(), "Library grant survives recreation");
            var carried = shelf.removeItem(0, 1);
            helper.assertTrue(ItemStack.matches(original, carried), "Original medium removed without mutation");
            shelf.setItem(0, replacement.copy());
            level.setBlock(helper.absolutePos(new BlockPos(5, 2, 3)), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
            helper.runAfterDelay(25, () -> {
                assertReply(helper, restored, "medium_changed", 1);
                helper.assertValueEqual(BiocomputingWorldGameTests.memory(restored, "result"), DataValue.NullValue.INSTANCE, "Failure is not a successful empty value");
                helper.assertTrue(ItemStack.matches(replacement, shelf.getItem(0)), "Saved request cannot change replacement data, revision, receipt or pages");
                helper.assertTrue(ItemStack.matches(original, carried), "Carried original remains unchanged");
                var returnedReplacement = shelf.removeItem(0, 1);
                shelf.setItem(0, carried);
                // A new event is necessary; a failed request must not wake up and mutate the returned book.
                helper.runAfterDelay(10, () -> {
                    assertReply(helper, restored, "medium_changed", 1);
                    helper.assertTrue(ItemStack.matches(original, shelf.getItem(0)), "Reinsertion does not replay completed failure");
                    restored.begin(obj());
                    helper.runAfterDelay(25, () -> {
                        assertReply(helper, restored, "ok", 2);
                        helper.assertValueEqual(media.read(shelf.getItem(0)).get("reading").value(), new DataValue.IntegerValue(44), "Already installed program can write original medium on a new event");
                        helper.assertValueEqual(shelf.getItem(0).get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT), pages, "Source pages preserved");
                        helper.assertTrue(ItemStack.matches(replacement, returnedReplacement), "Other medium remains unchanged after recovery");
                        helper.succeed();
                    });
                });
            });
        });
    }
    static void assertReply(GameTestHelper helper, ComputerBehaviour computer, String code, int count) {
        helper.assertValueEqual(BiocomputingWorldGameTests.memory(computer, "code"), text(code), "Service result code");
        helper.assertValueEqual(BiocomputingWorldGameTests.memory(computer, "calls"), new DataValue.IntegerValue(count), "One event per correlated reply");
        helper.assertTrue(computer.snapshot().orElseThrow().rpc().pending().isEmpty(), "Pending correlation completes");
    }
    static ProgramArchive caller() {
        return caller("read");
    }
    static ProgramArchive caller(String operation) {
        return caller(obj("op", operation, "volume", "日志", "entry", "reading"));
    }
    static ProgramArchive caller(DataValue.RecordValue payload) {
        var graph = new ComputationGraph(Map.of(), Map.of("target", new ComputationGraph.Constant(text("books")),
            "payload", new ComputationGraph.Constant(payload)),
            Map.of("target", "target", "payload", "payload"));
        var reply = new ComputationGraph(Map.of("code", DataType.Scalar.TEXT, "data", DataType.Scalar.ANY),
            Map.of("code", new ComputationGraph.Input("code"), "data", new ComputationGraph.Input("data"),
                "old", new ComputationGraph.ReadMemory("calls"), "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
                "next", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "old", "one")),
            Map.of("code", "code", "data", "data", "calls", "next"));
        return new ProgramArchive(new ProgramDefinition(graph, Map.of(
            "code", new ProgramDefinition.Cell(DataType.Scalar.TEXT, text("")),
            "result", new ProgramDefinition.Cell(DataType.Scalar.ANY, DataValue.NullValue.INSTANCE),
            "calls", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0))), List.of(),
            List.of(ProgramDefinition.Send.request("target", "payload", "lookup", "reply")),
            Map.of("reply", new ProgramDefinition.Entry(reply, List.of(new ProgramDefinition.Write("code", "code"),
                new ProgramDefinition.Write("result", "data"), new ProgramDefinition.Write("calls", "calls")), List.of()))), Map.of());
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void twoComputersConflictThenWriteAndDeleteThroughRealLibraryPort(GameTestHelper helper) {
        var level = helper.getLevel();
        var a = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(3, 2, 3))));
        var b = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(3, 2, 4))));
        a.configureEndpoint(new EndpointConfiguration("client-a", true)); b.configureEndpoint(new EndpointConfiguration("client-b", true));
        for (int x = 4; x <= 7; x++) level.setBlock(helper.absolutePos(new BlockPos(x, 2, 3)), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(helper.absolutePos(new BlockPos(4, 2, 4)), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
        var pos = helper.absolutePos(new BlockPos(8, 2, 3));
        var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.EAST);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
        level.setBlock(pos.above(), state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        var core = (LibraryBlockEntity)level.getBlockEntity(pos);
        core.initialize(); // Raw world fixture must enter the physical identity lifecycle before serving.
        var service = core.getBehaviour(LibraryServiceBehaviour.TYPE);
        var config = new LibraryServiceConfiguration(Optional.of(new EndpointConfiguration("books", true)), Map.of("日志", LibraryServiceConfiguration.Access.READ_WRITE));
        helper.assertValueEqual(service.configure(0, config), "configured", "Explicit writable grant");
        level.setBlock(pos.north(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
        var shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(pos.north());
        var media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), CBConfigs.SERVER.biocomputing.current(true).archive());
        var volumes = new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get());
        var original = new ItemStack(Items.WRITABLE_BOOK);
        original.set(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT,
            new net.minecraft.world.item.component.WritableBookContent(List.of(net.minecraft.server.network.Filterable.passThrough("Untouched source page"))));
        var book = volumes.rename(media.put(original, "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(43))), "日志");
        shelf.setItem(0, book);
        var request = obj("op", "put", "volume", "日志", "entry", "reading", "expected", media.stat(book, "reading").field("version"),
            "kind", "DATA", "value", new DataValue.IntegerValue(44));
        a.install(caller(request), ComputerRuntime.MemoryPolicy.RESET); b.install(caller(request), ComputerRuntime.MemoryPolicy.RESET);
        a.begin(obj()); b.begin(obj());
        helper.runAfterDelay(25, () -> {
            assertReply(helper, a, "conflict", 1); assertReply(helper, b, "conflict", 1);
            helper.assertTrue(ItemStack.matches(book, shelf.getItem(0)), "Neither contender writes the real book");
            a.begin(obj());
            helper.runAfterDelay(25, () -> {
                assertReply(helper, a, "ok", 2);
                helper.assertValueEqual(media.read(shelf.getItem(0)).get("reading").value(), new DataValue.IntegerValue(44), "Single public write applied");
                helper.assertValueEqual(shelf.getItem(0).get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT), original.get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT), "Original pages protected");
                var saved = core.saveWithFullMetadata(level.registryAccess());
                level.removeBlockEntity(pos);
                var restored = (LibraryBlockEntity)BlockEntity.loadStatic(pos, state, saved, level.registryAccess()); level.setBlockEntity(restored); restored.initialize();
                var shelfSave = shelf.saveWithFullMetadata(level.registryAccess()); level.removeBlockEntity(pos.north());
                var loadedShelf = (ChiseledBookShelfBlockEntity)BlockEntity.loadStatic(pos.north(), shelf.getBlockState(), shelfSave, level.registryAccess()); level.setBlockEntity(loadedShelf);
                LibraryShelfUpdates.changed(level, pos.north());
                helper.assertValueEqual(media.read(loadedShelf.getItem(0)).get("reading").value(), new DataValue.IntegerValue(44), "Storage survives actual BE reload");
                var oldDelete = obj("op", "delete", "volume", "日志", "entry", "reading", "expected", media.stat(loadedShelf.getItem(0), "reading").field("version"));
                a.install(caller(oldDelete), ComputerRuntime.MemoryPolicy.RESET);
                a.begin(obj());
                helper.runAfterDelay(25, () -> {
                    assertReply(helper, a, "ok", 1);
                    helper.assertTrue(media.read(loadedShelf.getItem(0)).isEmpty(), "Public delete applied to entry, not physical book/pages");
                    helper.assertValueEqual(loadedShelf.getItem(0).get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT), original.get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT), "Delete keeps pages");
                    a.install(caller("stat"), ComputerRuntime.MemoryPolicy.RESET);
                    a.begin(obj());
                    helper.runAfterDelay(25, () -> {
                        assertReply(helper, a, "ok", 1);
                        var absent = (DataValue.RecordValue) BiocomputingWorldGameTests.memory(a, "result");
                        helper.assertValueEqual(absent.field("present"), new DataValue.BooleanValue(false), "Public stat reports deletion, not a missing book");
                        helper.assertValueEqual(absent.field("value"), DataValue.NullValue.INSTANCE, "Deleted value is absent");
                        helper.assertTrue(!absent.field("version").equals(oldDelete.field("expected")), "Deletion advances the medium-owned tombstone");
                        a.install(caller(obj("op", "put", "volume", "日志", "entry", "reading", "expected", absent.field("version"),
                            "kind", "DATA", "value", new DataValue.IntegerValue(99))), ComputerRuntime.MemoryPolicy.RESET);
                        a.begin(obj());
                        helper.runAfterDelay(25, () -> {
                            assertReply(helper, a, "ok", 1);
                            helper.assertValueEqual(media.read(loadedShelf.getItem(0)).get("reading").value(), new DataValue.IntegerValue(99), "New data recreated via public request");
                            a.install(caller(oldDelete), ComputerRuntime.MemoryPolicy.RESET);
                            a.begin(obj());
                            helper.runAfterDelay(25, () -> {
                                assertReply(helper, a, "stale_version", 1);
                                helper.assertValueEqual(media.read(loadedShelf.getItem(0)).get("reading").value(), new DataValue.IntegerValue(99), "Old delete cannot remove recreated entry");
                                helper.assertValueEqual(loadedShelf.getItem(0).get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT),
                                    original.get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT), "Delete/recreate cannot edit protected source pages");
                                helper.succeed();
                            });
                        });
                    });
                });
            });
        });
    }
}
