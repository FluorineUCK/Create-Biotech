package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceState;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.*;

/** Real chunk lifecycle, not a manual BE replacement or a non-ticking-only assertion. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingLibraryLifecycleGameTests {
    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void librarianWorkFreezesAtDormancyAndResumesAfterRealChunkReload(GameTestHelper helper) {
        var level = helper.getLevel(); var far = helper.absolutePos(new BlockPos(24576, 1, 1));
        var pos = new BlockPos((far.getX() & ~15) + 8, far.getY(), (far.getZ() & ~15) + 8);
        var chunk = new ChunkPos(pos); var anchor = new ChunkPos(chunk.x - 2, chunk.z);
        level.setChunkForced(anchor.x, anchor.z, true); level.setChunkForced(chunk.x, chunk.z, true);
        var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.EAST);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos, state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), 3);
        level.setBlock(pos.above(), state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var core = (LibraryBlockEntity)level.getBlockEntity(pos); core.initialize();
        var computer = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, pos.west(4)));
        var control = core.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryControlBehaviour.TYPE);
        var drive = core.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryDriveBehaviour.TYPE);
        for (int x = 1; x <= 3; x++) level.setBlock(pos.west(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        for (int z = 1; z <= 4; z++) level.setBlock(pos.north(z), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), 3);
        var retained = new net.minecraft.nbt.Tag[1]; var remaining = new long[1]; var resumeTick = new long[1];
        var program = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime.Snapshot[1];
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(level.shouldTickBlocksAt(chunk.toLong()) && core.libraryIdentityReady(), "Library actually active"))
        .thenExecute(() -> {
            computer.install(BiocomputingWorldGameTests.counter(), com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime.MemoryPolicy.RESET);
            computer.setPaused(true); program[0] = computer.snapshot().orElseThrow();
            var actor = new net.neoforged.neoforge.common.util.FakePlayer(level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "librarian-reload"));
            actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
            helper.assertValueEqual(control.configure(actor, 0, Optional.of(control.discover(actor).getFirst())), "configured", "Explicit nearby controller");
            drive.seek(computer, LibraryTrackAddress.from(pos, Direction.EAST, pos.north(4)));
            level.setChunkForced(chunk.x, chunk.z, false);
        }).thenWaitUntil(() -> helper.assertTrue(!level.shouldTickBlocksAt(chunk.toLong()) && level.getChunkSource().getChunkNow(chunk.x, chunk.z) != null,
            "Ticket boundary keeps core loaded but non-ticking"))
        .thenExecute(() -> {
            retained[0] = drive.archive().orElseThrow(); remaining[0] = drive.snapshot().orElseThrow().pending().orElseThrow().remainingTicks();
            helper.assertTrue(remaining[0] > 0, "Unfinished work exists at dormancy");
        }).thenIdle(20).thenExecute(() -> {
            drive.tick(); // Explicit accidental call must also respect the real ticking boundary.
            helper.assertValueEqual(drive.archive().orElseThrow(), retained[0], "Dormancy preserves the exact pending checkpoint");
            level.setChunkForced(anchor.x, anchor.z, false);
        }).thenWaitUntil(() -> helper.assertTrue(level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null && core.isRemoved() && computer.blockEntity.isRemoved(),
            "Actual chunk unload retires both owners, not a manual replacement"))
        .thenIdle(100).thenExecute(() -> {
            helper.assertValueEqual(drive.archive().orElseThrow(), retained[0], "Unloaded old host cannot consume time");
            resumeTick[0] = level.getGameTime(); level.setChunkForced(chunk.x, chunk.z, true);
        }).thenWaitUntil(() -> {
            var loaded = level.getChunkSource().getChunkNow(chunk.x, chunk.z);
            helper.assertTrue(loaded != null && level.shouldTickBlocksAt(chunk.toLong()) && loaded.getBlockEntity(pos) instanceof LibraryBlockEntity restored
                && restored != core && restored.libraryIdentityReady(), "Normal reload creates a new active Library");
        }).thenExecute(() -> {
            var loaded = level.getChunkSource().getChunkNow(chunk.x, chunk.z);
            var restored = (LibraryBlockEntity)loaded.getBlockEntity(pos);
            var work = restored.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryDriveBehaviour.TYPE);
            var resumed = work.snapshot().orElseThrow().pending().orElseThrow().remainingTicks();
            helper.assertTrue(resumed <= remaining[0] && remaining[0] - resumed <= level.getGameTime() - resumeTick[0] + 1,
                "At most the newly active ticks elapsed; 100 absent ticks do not catch up");
            var restoredComputer = ((com.simibubi.create.foundation.blockEntity.SmartBlockEntity)loaded.getBlockEntity(pos.west(4)))
                .getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour.TYPE);
            helper.assertValueEqual(restoredComputer.snapshot().orElseThrow(), program[0], "Paused controller's independent program/RAM survive");
            helper.assertValueEqual(restored.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryControlBehaviour.TYPE).status().field("code"),
                new DataValue.TextValue("ready"), "Existing delegate identity remains valid without reinstallation");
            try { work.seek(computer, LibraryTrackAddress.from(pos, Direction.EAST, pos.north(4))); helper.fail("Retired Computer object is not authority"); }
            catch (LibraryBookAccess.Failure error) { helper.assertValueEqual(error.code(), "member_changed", "Old live reference rejected"); }
        }).thenWaitUntil(() -> {
            var loaded = level.getChunkSource().getChunkNow(chunk.x, chunk.z);
            var work = ((LibraryBlockEntity)loaded.getBlockEntity(pos)).getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryDriveBehaviour.TYPE);
            helper.assertTrue(work.snapshot().orElseThrow().result().isPresent(), "Remaining work finishes on subsequent real device ticks");
            helper.assertValueEqual(work.snapshot().orElseThrow().sequence(), 1L, "No duplicate request after reload");
            helper.assertValueEqual(work.snapshot().orElseThrow().result().orElseThrow().code(), "positioned", "Original positioning result");
        }).thenExecute(() -> level.setChunkForced(chunk.x, chunk.z, false)).thenSucceed();
    }
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void loadedButNonTickingLibraryCannotAdvertiseOrAdmitRequests(GameTestHelper helper) {
        var level = helper.getLevel();
        var far = helper.absolutePos(new BlockPos(16384, 1, 1));
        var pos = new BlockPos((far.getX() & ~15) + 4, far.getY(), (far.getZ() & ~15) + 4);
        var chunk = new ChunkPos(pos); var anchor = new ChunkPos(chunk.x - 2, chunk.z);
        level.setChunkForced(anchor.x, anchor.z, true); level.setChunkForced(chunk.x, chunk.z, true);
        var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState();
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
        level.setBlock(pos.above(), state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        var core = (LibraryBlockEntity)level.getBlockEntity(pos); core.initialize();
        var service = core.getBehaviour(LibraryServiceBehaviour.TYPE);
        var configuration = new LibraryServiceConfiguration(Optional.of(new EndpointConfiguration("disk", true)), Map.of());
        var source = UUID.randomUUID(); var event = UUID.randomUUID();
        var request = new MessageEnvelope(source, 0, event, 0,
            new DataValue.RecordValue(Map.of("op", new DataValue.TextValue("volumes"))),
            new MessageProtocol.Request(new RequestContext(new RequestCorrelation(source, event, 0), "absent")));
        var retained = new net.minecraft.nbt.CompoundTag[1];
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(level.shouldTickBlocksAt(chunk.toLong()) && core.libraryIdentityReady(), "Library initially ticks");
        }).thenExecute(() -> {
            helper.assertValueEqual(service.configure(0, configuration), "configured", "Explicitly open only after real ticking begins");
            helper.assertTrue(service.advertisement().isPresent(), "Active configured Library advertises normally");
            retained[0] = core.saveWithFullMetadata(level.registryAccess()).getCompound(LibraryServiceBehaviour.ROOT).copy();
            level.setChunkForced(chunk.x, chunk.z, false);
        }).thenWaitUntil(() -> {
            helper.assertTrue(!level.shouldTickBlocksAt(chunk.toLong()) && level.getChunkSource().getChunkNow(chunk.x, chunk.z) != null,
                "Real ticket boundary leaves the Library loaded but non-ticking");
        }).thenExecute(() -> {
            helper.assertTrue(!core.isRemoved(), "Non-ticking is not reported as actual unloading");
            helper.assertTrue(service.advertisement().isEmpty(), "A non-ticking Library must not advertise storage availability");
            helper.assertValueEqual(service.receive(request), AdmissionStatus.UNAVAILABLE,
                "An unavailable device cannot acknowledge a request for later hidden execution");
        }).thenIdle(10).thenExecute(() -> {
            helper.assertValueEqual(core.saveWithFullMetadata(level.registryAccess()).getCompound(LibraryServiceBehaviour.ROOT), retained[0],
                "Neither admission nor global end-tick service mutates the dormant Library");
            level.setChunkForced(chunk.x, chunk.z, true);
        }).thenWaitUntil(() -> {
            helper.assertTrue(level.shouldTickBlocksAt(chunk.toLong()) && service.advertisement().isPresent(), "Real activity restores availability");
        }).thenExecute(() -> {
            helper.assertValueEqual(service.status().field("queued"), new DataValue.IntegerValue(0), "Rejected request did not become hidden queued work");
            helper.assertValueEqual(service.status().field("configuration"), configuration.value(), "Resume keeps explicit grants, not an implicit new configuration");
            level.setChunkForced(anchor.x, anchor.z, false); level.setChunkForced(chunk.x, chunk.z, false);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void serviceObservationCannotKeepLibraryLoadedAndBooksSurviveRealReload(GameTestHelper helper) {
        var level = helper.getLevel();
        var far = helper.absolutePos(new BlockPos(8192, 1, 1));
        var pos = new BlockPos((far.getX() & ~15) + 4, far.getY(), (far.getZ() & ~15) + 4);
        var chunk = new ChunkPos(pos);
        level.setChunkForced(chunk.x, chunk.z, true);
        var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.EAST);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
        level.setBlock(pos.above(), state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        var core = (LibraryBlockEntity)level.getBlockEntity(pos); core.initialize();
        var service = core.getBehaviour(LibraryServiceBehaviour.TYPE);
        var configuration = new LibraryServiceConfiguration(Optional.of(new EndpointConfiguration("disk", true)),
            Map.of("journal", LibraryServiceConfiguration.Access.READ_ONLY));
        var closed = new LibraryServiceConfiguration(Optional.of(new EndpointConfiguration("disk", false)), configuration.grants());
        var shelfPos = pos.north(); level.setBlock(shelfPos, Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
        var shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(shelfPos);
        var media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), CBConfigs.SERVER.biocomputing.current(true).archive());
        var volumes = new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get());
        var book = volumes.rename(media.put(new ItemStack(Items.WRITABLE_BOOK), "reading",
            new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(43))), "journal");
        shelf.setItem(2, book.copy());
        var identity = core.libraryId();
        var source = UUID.randomUUID(); var event = UUID.randomUUID();
        var request = new MessageEnvelope(source, 0, event, 0, new DataValue.RecordValue(Map.of(
            "op", new DataValue.TextValue("read"), "volume", new DataValue.TextValue("journal"), "entry", new DataValue.TextValue("reading"))),
            new MessageProtocol.Request(new RequestContext(new RequestCorrelation(source, event, 0), "absent")));
        var retained = new net.minecraft.nbt.CompoundTag[1];
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(level.shouldTickBlocksAt(chunk.toLong()) && core.libraryIdentityReady(), "Library is initially active");
        }).thenExecute(() -> {
            helper.assertValueEqual(service.configure(0, configuration), "configured", "Fixture explicitly grants one volume");
            helper.assertValueEqual(service.receive(request), AdmissionStatus.ACCEPTED, "An active Library admits the original request");
            helper.assertValueEqual(service.configure(1, closed), "configured", "Close before execution without discarding admitted work");
            retained[0] = core.saveWithFullMetadata(level.registryAccess()).getCompound(LibraryServiceBehaviour.ROOT).copy();
        }).thenIdle(5).thenExecute(() -> level.setChunkForced(chunk.x, chunk.z, false)).thenWaitUntil(() -> {
            helper.assertTrue(level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null && core.isRemoved() && shelf.isRemoved(),
                "Library service must not renew loading tickets: require real chunk removal and retired core/shelf instances");
        }).thenExecute(() -> {
            helper.assertTrue(service.advertisement().isEmpty(), "An unloaded service must not advertise");
            level.setChunkForced(chunk.x, chunk.z, true);
        }).thenWaitUntil(() -> {
            var loaded = level.getChunkSource().getChunkNow(chunk.x, chunk.z);
            helper.assertTrue(loaded != null && level.shouldTickBlocksAt(chunk.toLong()) && loaded.getBlockEntity(pos) instanceof LibraryBlockEntity restored
                && restored != core && restored.libraryIdentityReady(), "Normal reload constructs and initializes a fresh Library");
        }).thenExecute(() -> {
            var loaded = level.getChunkSource().getChunkNow(chunk.x, chunk.z);
            var restored = (LibraryBlockEntity)loaded.getBlockEntity(pos);
            var restoredShelf = (ChiseledBookShelfBlockEntity)loaded.getBlockEntity(shelfPos);
            helper.assertTrue(restoredShelf != shelf, "Shelf is also a fresh instance, not a held live object");
            helper.assertValueEqual(restored.libraryId(), identity, "Physical identity survives normal save/load");
            helper.assertValueEqual(restored.getBehaviour(LibraryServiceBehaviour.TYPE).status().field("configuration"),
                closed.value(), "Explicit volume authorization and closed endpoint survive, without opening other volumes");
            helper.assertValueEqual(restored.saveWithFullMetadata(level.registryAccess()).getCompound(LibraryServiceBehaviour.ROOT), retained[0],
                "Original correlation and queued work survive actual unload, without executing during absence or reopening on load");
            helper.assertTrue(ItemStack.matches(restoredShelf.getItem(2), book), "Book keeps exact data, type, volume and version");
            helper.assertValueEqual(media.read(restoredShelf.getItem(2)).get("reading").value(), new DataValue.IntegerValue(43),
                "The actual reloaded medium, not a cached Library table, owns the original value");
            helper.assertTrue(service.advertisement().isEmpty(), "Old service cannot become live when a replacement occupies its position");
            helper.assertValueEqual(restored.getBehaviour(LibraryServiceBehaviour.TYPE).configure(2, configuration), "configured",
                "Only explicit reopening resumes the previously admitted work");
        }).thenWaitUntil(() -> {
            var loaded = level.getChunkSource().getChunkNow(chunk.x, chunk.z);
            var restored = (LibraryBlockEntity)loaded.getBlockEntity(pos);
            var saved = LibraryServiceState.decode(ValueNbtCodec.decode(
                restored.saveWithFullMetadata(level.registryAccess()).getCompound(LibraryServiceBehaviour.ROOT), CBConfigs.SERVER.biocomputing.current(true).archive()));
            helper.assertTrue(saved.reply().isPresent(), "Original pending request resumes after explicit opening");
            helper.assertValueEqual(saved.mailbox().queued(), List.of(request), "Retained exact request, not a new or duplicated correlation");
            helper.assertValueEqual(saved.reply().orElseThrow().field("code"), new DataValue.TextValue("ok"), "Read succeeds from the reloaded book");
            var data = (DataValue.RecordValue)saved.reply().orElseThrow().field("data");
            helper.assertValueEqual(data.field("value"), new DataValue.IntegerValue(43), "The pending read gets the original typed value");
            helper.assertValueEqual(saved.nextSequence(), 1L, "One reply is generated for the one pending request");
        }).thenExecute(() -> {
            level.setChunkForced(chunk.x, chunk.z, false);
        }).thenSucceed();
    }
}
