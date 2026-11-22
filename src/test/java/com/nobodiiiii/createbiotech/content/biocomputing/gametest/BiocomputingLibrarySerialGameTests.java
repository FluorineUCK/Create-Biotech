package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import java.util.function.Consumer;
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
public final class BiocomputingLibrarySerialGameTests {
    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void publicDirectoryDriverReturnsOneMetadataPageAndAcknowledgesOverRealSerial(GameTestHelper helper) {
        positionedDriver(helper, LibraryServiceConfiguration.Access.READ_ONLY, true, "library_list_driver", "unused");
    }
    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void publicDirectoryDriverCannotListAnUnopenedVolume(GameTestHelper helper) {
        positionedDriver(helper, LibraryServiceConfiguration.Access.CLOSED, false, "library_list_driver", "unused");
    }
    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void publicDirectoryRecoveryDriverClaimsRetainedMetadataAfterLostAcceptanceAndDeviceReload(GameTestHelper helper) {
        positionedDriver(helper, LibraryServiceConfiguration.Access.READ_ONLY, true, "library_list_driver", "unused", true);
    }
    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void singleInstalledPublicDriverReadsTheAddressedBookAndAcknowledgesWithoutJavaOrchestration(GameTestHelper helper) {
        positionedDriver(helper, LibraryServiceConfiguration.Access.READ_ONLY, true);
    }
    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void publicDriverStopsOnDeniedReadWithoutStartingUnauthorizedWorkOrChangingTheBook(GameTestHelper helper) {
        positionedDriver(helper, LibraryServiceConfiguration.Access.CLOSED, false);
    }
    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void publicStatDriverReturnsAbsentEntryVersionOverRealSerialAndAcknowledges(GameTestHelper helper) {
        positionedDriver(helper, LibraryServiceConfiguration.Access.READ_ONLY, true, "library_stat_driver", "new_entry");
    }
    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void publicStatDriverReturnsExistingValueAndVersionWithoutChangingTheBook(GameTestHelper helper) {
        positionedDriver(helper, LibraryServiceConfiguration.Access.READ_ONLY, true, "library_stat_driver", "reading");
    }
    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void publicStatDriverCannotQueryAnUnopenedVolume(GameTestHelper helper) {
        positionedDriver(helper, LibraryServiceConfiguration.Access.CLOSED, false, "library_stat_driver", "new_entry");
    }
    private static void positionedDriver(GameTestHelper helper, LibraryServiceConfiguration.Access access, boolean success) {
        positionedDriver(helper, access, success, "library_read_driver", "reading");
    }
    @GameTest(template = "empty", timeoutTicks = 9000)
    public static void publicWriteDriverQueriesVersionWritesAndReadsBackThePhysicalBookWithoutLegacyRpc(GameTestHelper helper) {
        positionedDriver(helper, LibraryServiceConfiguration.Access.READ_WRITE, true, "library_write_driver", "reading");
    }
    @GameTest(template = "empty", timeoutTicks = 9000)
    public static void publicWriteDriverCannotTurnAReadOnlyVersionQueryIntoWriteAuthority(GameTestHelper helper) {
        positionedDriver(helper, LibraryServiceConfiguration.Access.READ_ONLY, false, "library_write_driver", "reading");
    }
    @GameTest(template = "empty", timeoutTicks = 9000)
    public static void publicDeleteDriverDeletesOnlyTheAddressedEntryAndConfirmsItsVersionedAbsence(GameTestHelper helper) {
        positionedDriver(helper, LibraryServiceConfiguration.Access.READ_WRITE, true, "library_delete_driver", "reading");
    }
    @GameTest(template = "empty", timeoutTicks = 9000)
    public static void publicDeleteDriverCannotDeleteThroughReadOnlyAuthorization(GameTestHelper helper) {
        positionedDriver(helper, LibraryServiceConfiguration.Access.READ_ONLY, false, "library_delete_driver", "reading");
    }
    @GameTest(template = "empty", timeoutTicks = 9000)
    public static void publicDeleteDriverReportsMissingEntryWithoutPretendingAnOperationSucceeded(GameTestHelper helper) {
        positionedDriver(helper, LibraryServiceConfiguration.Access.READ_WRITE, false, "library_delete_driver", "not_present");
    }
    @GameTest(template = "empty", timeoutTicks = 9000)
    public static void serialCommissionedWriteSurvivesDeviceUnloadAndFinishesWhileItsComputerStaysPaused(GameTestHelper helper) {
        positionedDriver(helper, LibraryServiceConfiguration.Access.READ_WRITE, true, "library_write_driver", "reading", true);
    }
    @GameTest(template = "empty", timeoutTicks = 12000)
    public static void publicRecoveryDriverClaimsTheInterruptedWriteAfterCableReconnectWithoutWritingAgain(GameTestHelper helper) {
        positionedDriver(helper, LibraryServiceConfiguration.Access.READ_WRITE, true, "library_write_driver", "reading", true, true);
    }
    private static void positionedDriver(GameTestHelper helper, LibraryServiceConfiguration.Access access, boolean success,
        String exampleId, String entry) {
        positionedDriver(helper, access, success, exampleId, entry, false);
    }
    private static void positionedDriver(GameTestHelper helper, LibraryServiceConfiguration.Access access, boolean success,
        String exampleId, String entry, boolean lifecycle) {
        positionedDriver(helper, access, success, exampleId, entry, lifecycle, false);
    }
    private static void positionedDriver(GameTestHelper helper, LibraryServiceConfiguration.Access access, boolean success,
        String exampleId, String entry, boolean lifecycle, boolean recover) {
        boolean deleting = exampleId.equals("library_delete_driver");
        boolean writing = deleting || exampleId.equals("library_write_driver");
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(8, 2, 3));
        var block = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.EAST);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos, block.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), 3);
        level.setBlock(pos.above(), block.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var core = (LibraryBlockEntity)level.getBlockEntity(pos);
        core.applyPlacementData(LibraryCoreItemData.configured(CommunicationFrequency.of("positioned-driver"), 0, UUID.randomUUID(), 0)); core.initialize();
        var computer = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, pos.west(4),
            net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN, writing ? 5 : 4));
        computer.installFlow(new FlowProgram(Map.of(), List.of()), new DataValue.RecordValue(Map.of()));
        for (int x = 1; x <= 3; x++) level.setBlock(pos.west(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "positioned-driver")); actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        var control = core.getBehaviour(LibraryControlBehaviour.TYPE);
        control.configure(actor, 0, Optional.of(control.discover(actor).getFirst()));
        control.configureSerial(actor, 1, true); computer.serialDevice().configure("s0", Direction.EAST, true);
        level.setBlock(pos.north(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), 3);
        var shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(pos.north());
        var media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), CBConfigs.SERVER.biocomputing.current(true).archive());
        var sourceBook = media.put(new ItemStack(Items.WRITABLE_BOOK), "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(43)));
        if (deleting || exampleId.equals("library_list_driver")) {
            sourceBook = media.put(sourceBook, "keep", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.TextValue("not selected")));
            sourceBook.set(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT, new net.minecraft.world.item.component.WritableBookContent(
                List.of(net.minecraft.server.network.Filterable.passThrough("original notes"), net.minecraft.server.network.Filterable.passThrough("original recipe page"))));
        }
        var book = new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get()).rename(sourceBook, "日志"); shelf.setItem(2, book.copy());
        core.getBehaviour(LibraryServiceBehaviour.TYPE).configure(0, new LibraryServiceConfiguration(Optional.empty(), Map.of("日志", access)));
        java.util.function.Function<net.minecraft.resources.ResourceLocation, java.io.InputStream> open = id ->
            BiocomputingLibrarySerialGameTests.class.getClassLoader().getResourceAsStream("assets/" + id.getNamespace() + "/" + id.getPath());
        var example = ProgramExampleCatalog.load(open).stream().filter(e -> e.id().equals(exampleId)).findFirst().orElseThrow();
        var document = ProgramExampleCatalog.preview(example, open, CBConfigs.SERVER.biocomputing.current(true).archive());
        var body = document.flow().orElseThrow(); var values = new LinkedHashMap<>(body.initial().fields());
        values.put("target", new LibraryTrackAddress(pos.getX(), pos.getY(), pos.getZ()).value());
        var arguments = new LinkedHashMap<>(((DataValue.RecordValue)values.get("arguments")).fields());
        arguments.put("track", LibraryTrackAddress.from(pos, Direction.EAST, pos.north()).value());
        arguments.put("entry", new DataValue.TextValue(entry));
        if (exampleId.equals("library_list_driver")) {
            arguments.remove("entry");
            arguments.put("page", new DataValue.RecordValue(Map.of("after", DataValue.NullValue.INSTANCE, "count", new DataValue.IntegerValue(1))));
        }
        values.put("arguments", new DataValue.RecordValue(arguments));
        var configured = new ProgramDocument(document.archive(), document.positions(), document.terminal(),
            Optional.of(new FlowDocumentBody(body.inputs(), body.steps(), new DataValue.RecordValue(values))));
        computer.installDocument(configured, ComputerRuntime.MemoryPolicy.RESET);
        computer.setPaused(false); // Only installation/start: every later command is chosen by the public program.
        Consumer<FlowRuntime.Checkpoint> verify = state -> {
            helper.assertValueEqual(state.locals().field("done"), new DataValue.BooleanValue(true), "Driver ends explicitly");
            helper.assertValueEqual(state.locals().field("success"), new DataValue.BooleanValue(success), "Expected outcome; locals=" + state.locals());
            helper.assertTrue(state.locals().field("request") instanceof DataValue.TextValue, "System-generated ordinary correlation");
            if (!writing || !success) helper.assertTrue(ItemStack.matches(book, shelf.getItem(2)), "Read or denied write leaves source book intact");
            else {
                if (deleting) {
                    helper.assertTrue(!media.read(shelf.getItem(2)).containsKey(entry), "Actual entry is deleted, not overwritten with null");
                    var remaining = new LinkedHashMap<>(media.read(book)); remaining.remove(entry);
                    helper.assertValueEqual(media.read(shelf.getItem(2)), remaining, "All other named entries are preserved");
                    var previous = LibraryMutation.Version.decode(media.stat(book, entry).field("version"));
                    var current = LibraryMutation.Version.decode(media.stat(shelf.getItem(2), entry).field("version"));
                    helper.assertValueEqual(current.medium(), previous.medium(), "Deletion preserves physical medium identity");
                    helper.assertTrue(current.revision() > previous.revision(), "Versioned tombstone prevents stale recreation");
                } else helper.assertValueEqual(media.read(shelf.getItem(2)).get("reading").value(), new DataValue.IntegerValue(44), "Public driver changes the physical component");
                helper.assertValueEqual(shelf.getItem(2).get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT),
                    book.get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT), "Original book pages are preserved");
                var stored = (DataValue.RecordValue)com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.decode(
                    shelf.getItem(2).get(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get()).copyTag(), CBConfigs.SERVER.biocomputing.current(true).archive());
                helper.assertTrue(((DataValue.RecordValue)stored.field("receipts")).fields().isEmpty(), "Explicit ack releases only this completed write receipt");
            }
            helper.assertTrue(computer.advertisement().isEmpty(), "No legacy service endpoint is installed");
            var head = ((LibraryBlockEntity)level.getBlockEntity(pos)).getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow();
            helper.assertValueEqual(head.sequence(), writing ? success ? 5L : 3L : success ? 3L : 2L, "Unauthorized operations are rejected before admission");
            helper.assertTrue(head.pending().isEmpty(), "Mechanical work is finished");
            if (success) {
                if (exampleId.equals("library_list_driver")) {
                    var fields = new LinkedHashMap<>(media.directorySnapshot(book, new LibraryDirectoryQuery(Optional.empty(), 1)).value().fields());
                    fields.put("volume", new DataValue.TextValue("日志"));
                    helper.assertValueEqual(state.locals().field("read_result"), new DataValue.RecordValue(fields), "Exactly one metadata page, including the continuation and version");
                    helper.assertValueEqual(fields.get("next"), new DataValue.TextValue("keep"), "The driver does not secretly retrieve the next page");
                    helper.assertTrue(core.getBehaviour(LibraryServiceBehaviour.TYPE).advertisements().isEmpty(), "No instant service fallback");
                } else if (exampleId.equals("library_stat_driver") || deleting) {
                    var stat = (DataValue.RecordValue)state.locals().field("read_result");
                    helper.assertValueEqual(stat, media.stat(deleting ? shelf.getItem(2) : book, entry), "The complete physical medium version, presence and value");
                    helper.assertValueEqual(stat.field("present"), new DataValue.BooleanValue(!deleting && entry.equals("reading")), "Absent and existing entries are distinct");
                    helper.assertTrue(core.getBehaviour(LibraryServiceBehaviour.TYPE).advertisements().isEmpty(),
                        "The new driver never falls back to the old service");
                } else helper.assertValueEqual(((DataValue.RecordValue)state.locals().field("read_result")).field("value"),
                    new DataValue.IntegerValue(writing ? 44 : 43), "Returned physical book data");
                helper.assertTrue(head.result().isEmpty(), "The driver explicitly acknowledges after copying");
                if (deleting) {
                    var deletedBook = shelf.getItem(2).copy();
                    var savedShelf = shelf.saveWithFullMetadata(level.registryAccess()); level.removeBlockEntity(shelf.getBlockPos());
                    var restored = (ChiseledBookShelfBlockEntity)BlockEntity.loadStatic(shelf.getBlockPos(), shelf.getBlockState(), savedShelf, level.registryAccess());
                    level.setBlockEntity(restored);
                    helper.assertTrue(ItemStack.matches(deletedBook, restored.getItem(2)), "The registered book component, tombstone and untouched pages survive shelf reload");
                    var carried = ItemStack.parse(level.registryAccess(), restored.getItem(2).save(level.registryAccess())).orElseThrow();
                    helper.assertValueEqual(media.stat(carried, entry), state.locals().field("read_result"), "Carrying the book retains the versioned absence");
                }
            } else {
                helper.assertTrue(head.result().isEmpty(), "No read operation was admitted");
                helper.assertTrue(state.locals().field("error") instanceof DataValue.RecordValue, "Failure remains inspectable");
                helper.assertValueEqual(state.locals().field("index"), new DataValue.IntegerValue(writing ? 10 : 7), "Stop on rejected operation; no result, ack or retry follows");
                if (deleting) helper.assertValueEqual(((DataValue.RecordValue)state.locals().field("error")).field("code"),
                    new DataValue.TextValue(access == LibraryServiceConfiguration.Access.READ_ONLY ? "readonly" : "missing_entry"), "Specific refusal survives the public driver");
            }
            helper.succeed();
        };
        if (lifecycle && exampleId.equals("library_list_driver"))
            pauseAndReloadAdmittedDirectory(helper, computer, core, () -> {
                var restored = (LibraryBlockEntity)level.getBlockEntity(pos);
                var retained = restored.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow();
                helper.assertValueEqual(computer.flowSnapshot().orElseThrow().state().locals().field("index"), new DataValue.IntegerValue(7),
                    "The interrupted acceptance is not silently recreated or claimed by the old run");
                var recovery = ProgramExampleCatalog.load(open).stream().filter(e -> e.id().equals("library_query_recovery_driver")).findFirst().orElseThrow();
                var source = ProgramExampleCatalog.preview(recovery, open, CBConfigs.SERVER.biocomputing.current(true).archive());
                var flow = source.flow().orElseThrow(); var initial = new LinkedHashMap<>(flow.initial().fields());
                initial.put("target", values.get("target"));
                computer.reset(); // The player explicitly abandons the waiting local program, not the retained Library result.
                helper.assertValueEqual(restored.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow(), retained,
                    "Reset cannot consume or replay device work");
                computer.installDocument(new ProgramDocument(source.archive(), source.positions(), source.terminal(),
                    Optional.of(new FlowDocumentBody(flow.inputs(), flow.steps(), new DataValue.RecordValue(initial)))), ComputerRuntime.MemoryPolicy.RESET);
                computer.setPaused(false);
                awaitDriver(helper, computer, recovered -> {
                    helper.assertValueEqual(recovered.locals().field("index"), new DataValue.IntegerValue(3), "Only status/result/ack, no second list");
                    helper.assertValueEqual(recovered.locals().field("read_result"), retained.result().orElseThrow().value(), "Claims the original frozen page");
                    verify.accept(recovered);
                });
            });
        else if (lifecycle)
            pauseAndReloadAdmittedWrite(helper, computer, core, recover, () -> {
                helper.assertValueEqual(media.read(shelf.getItem(2)).get("reading").value(), new DataValue.IntegerValue(44), "Committed physical result");
                if (!recover) { helper.succeed(); return; }
                var restored = (LibraryBlockEntity)level.getBlockEntity(pos);
                var completed = restored.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow();
                var version = media.stat(shelf.getItem(2), "reading").field("version");
                helper.assertValueEqual(completed.sequence(), 4L, "Exactly one admitted write, before the old driver's readback");
                level.setBlock(pos.west(2), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
                helper.runAfterDelay(4, () -> {
                    var recovery = ProgramExampleCatalog.load(open).stream().filter(e -> e.id().equals("library_write_recovery_driver")).findFirst().orElseThrow();
                    var source = ProgramExampleCatalog.preview(recovery, open, CBConfigs.SERVER.biocomputing.current(true).archive());
                    var flow = source.flow().orElseThrow(); var initial = new LinkedHashMap<>(flow.initial().fields());
                    initial.put("target", values.get("target"));
                    computer.reset(); // Explicit player action, not an implicit cancellation performed by the driver.
                    helper.assertValueEqual(restored.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow(), completed,
                        "Resetting the Computer cannot cancel or consume an accepted Library operation");
                    computer.installDocument(new ProgramDocument(source.archive(), source.positions(), source.terminal(),
                        Optional.of(new FlowDocumentBody(flow.inputs(), flow.steps(), new DataValue.RecordValue(initial)))), ComputerRuntime.MemoryPolicy.RESET);
                    computer.setPaused(false);
                    awaitDriver(helper, computer, recovered -> {
                        helper.assertValueEqual(recovered.locals().field("success"), new DataValue.BooleanValue(true), "Public recovery flow: " + recovered.locals());
                        helper.assertValueEqual(((DataValue.RecordValue)recovered.locals().field("read_result")).field("version"), version, "Copies the retained write receipt, not book contents");
                        var after = restored.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow();
                        helper.assertValueEqual(after.sequence(), completed.sequence(), "Recovery never admits or resends mechanical work");
                        helper.assertTrue(after.pending().isEmpty() && after.writing().isEmpty() && after.result().isEmpty(), "Only explicit ack retires retained work");
                        helper.assertValueEqual(media.stat(shelf.getItem(2), "reading").field("version"), version, "No second book revision");
                        helper.assertValueEqual(media.read(shelf.getItem(2)).get("reading").value(), new DataValue.IntegerValue(44), "Written value unchanged");
                        var stored = (DataValue.RecordValue)com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.decode(
                            shelf.getItem(2).get(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get()).copyTag(), CBConfigs.SERVER.biocomputing.current(true).archive());
                        helper.assertTrue(((DataValue.RecordValue)stored.field("receipts")).fields().isEmpty(), "Acknowledgement releases the book receipt");
                        helper.assertTrue(computer.advertisement().isEmpty() && restored.getBehaviour(LibraryServiceBehaviour.TYPE).advertisements().isEmpty(), "No legacy RPC fallback");
                        helper.succeed();
                    });
                });
            });
        else awaitDriver(helper, computer, verify);
    }
    private static void pauseAndReloadAdmittedWrite(GameTestHelper helper, ComputerBehaviour computer, LibraryBlockEntity core, boolean disconnect, Runnable finished) {
        helper.runAfterDelay(1, () -> {
            var drive = core.getBehaviour(LibraryDriveBehaviour.TYPE); var state = drive.snapshot().orElseThrow();
            helper.assertTrue(computer.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE,
                "Observe admission before the driver finishes");
            if (state.writing().isEmpty()) { pauseAndReloadAdmittedWrite(helper, computer, core, disconnect, finished); return; }
            helper.assertTrue(state.result().isEmpty(), "Acceptance precedes physical completion");
            computer.setPaused(true); var paused = computer.flowSnapshot().orElseThrow().state();
            var level = helper.getLevel(); var pos = core.getBlockPos(); var saved = core.saveWithFullMetadata(level.registryAccess());
            if (disconnect) level.setBlock(pos.west(2), Blocks.AIR.defaultBlockState(), 3);
            level.removeBlockEntity(pos);
            helper.runAfterDelay(5, () -> {
                var restored = (LibraryBlockEntity)BlockEntity.loadStatic(pos, core.getBlockState(), saved, level.registryAccess());
                level.setBlockEntity(restored);
                helper.assertValueEqual(restored.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow(), state,
                    "Unloaded time does not reduce remaining librarian work");
                helper.runAfterDelay((int)state.writing().orElseThrow().remaining() + 8, () -> {
                    var completed = restored.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow();
                    helper.assertValueEqual(completed.result().orElseThrow().code(), "written", "Device finishes independently of the paused Computer");
                    helper.assertValueEqual(computer.flowSnapshot().orElseThrow().state(), paused, "Library does not advance paused program state");
                    helper.assertTrue(completed.writing().isPresent(), "Unclaimed result keeps its cleanup context");
                    finished.run();
                });
            });
        });
    }
    private static void pauseAndReloadAdmittedDirectory(GameTestHelper helper, ComputerBehaviour computer, LibraryBlockEntity core, Runnable finished) {
        helper.runAfterDelay(1, () -> {
            var state = core.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow();
            helper.assertTrue(computer.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE,
                "Observe slow directory admission before the public driver finishes");
            if (state.pending().filter(work -> work.action() == LibraryReadHead.Action.LIST).isEmpty()) {
                pauseAndReloadAdmittedDirectory(helper, computer, core, finished); return;
            }
            var work = state.pending().orElseThrow();
            helper.assertTrue(work.remainingTicks() > 0 && state.result().isEmpty(), "Listing is real outstanding device work");
            computer.setPaused(true); var paused = computer.flowSnapshot().orElseThrow().state();
            var level = helper.getLevel(); var pos = core.getBlockPos(); var saved = core.saveWithFullMetadata(level.registryAccess());
            level.removeBlockEntity(pos);
            helper.runAfterDelay(5, () -> {
                var restored = (LibraryBlockEntity)BlockEntity.loadStatic(pos, core.getBlockState(), saved, level.registryAccess());
                level.setBlockEntity(restored);
                helper.assertValueEqual(restored.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow(), state,
                    "Saved directory page and remaining work survive without catch-up or a fresh query");
                helper.runAfterDelay((int)work.remainingTicks() + 8, () -> {
                    var complete = restored.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow();
                    helper.assertValueEqual(complete.sequence(), state.sequence(), "No second admission on restoration");
                    helper.assertValueEqual(complete.result().orElseThrow().code(), "list", "Library completes independently of the paused Computer");
                    helper.assertValueEqual(complete.result().orElseThrow().value(), work.sample().orElseThrow().value(), "Retains the exact admitted page");
                    helper.assertValueEqual(computer.flowSnapshot().orElseThrow().state(), paused, "No hidden advancement of the controller");
                    finished.run();
                });
            });
        });
    }
    private static void awaitDriver(GameTestHelper helper, ComputerBehaviour computer, Consumer<FlowRuntime.Checkpoint> done) {
        helper.runAfterDelay(4, () -> {
            var state = computer.flowSnapshot().orElseThrow().state();
            helper.assertTrue(state.status() != FlowRuntime.Status.FAILED, "Driver computation failed: " + state.diagnostic() + "; " + computer.diagnostic());
            if (state.status() != FlowRuntime.Status.COMPLETE) { awaitDriver(helper, computer, done); return; }
            computer.setPaused(true); done.accept(state);
        });
    }
    @GameTest(template = "empty", timeoutTicks = 3600)
    public static void publicSerialInstructionsPositionSelectAndReadTheRealBookWithoutLegacyRpc(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(8, 2, 3));
        var block = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.EAST);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos, block.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), 3);
        level.setBlock(pos.above(), block.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var core = (LibraryBlockEntity)level.getBlockEntity(pos);
        core.applyPlacementData(LibraryCoreItemData.configured(CommunicationFrequency.of("serial-librarian"), 0, UUID.randomUUID(), 0)); core.initialize();
        var computer = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, pos.west(4)));
        computer.installFlow(new FlowProgram(Map.of(), List.of()), new DataValue.RecordValue(Map.of()));
        for (int x = 1; x <= 3; x++) level.setBlock(pos.west(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "librarian-serial")); actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        var control = core.getBehaviour(LibraryControlBehaviour.TYPE);
        var choices = control.discover(actor);
        helper.assertValueEqual(choices.size(), 1, "An installed Computer has a stable selectable identity");
        control.configure(actor, 0, Optional.of(choices.getFirst()));
        var serial = core.getBehaviour(LibrarySerialBehaviour.TYPE);
        helper.assertTrue(serial != null, "Real Library lifecycle registers the finite serial adapter");
        helper.assertValueEqual(serial.device().status("s0").connection(), "closed", "Binding alone never opens hardware");
        helper.assertValueEqual(control.configureSerial(actor, 1, true), "configured", "Explicit lectern-authorized serial opening");
        computer.serialDevice().configure("s0", Direction.EAST, true);
        level.setBlock(pos.north(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), 3);
        var shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(pos.north());
        var media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), CBConfigs.SERVER.biocomputing.current(true).archive());
        var book = new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get()).rename(media.put(new ItemStack(Items.WRITABLE_BOOK),
            "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(43))), "日志"); shelf.setItem(2, book.copy());
        core.getBehaviour(LibraryServiceBehaviour.TYPE).configure(0, new LibraryServiceConfiguration(Optional.empty(), Map.of("日志", LibraryServiceConfiguration.Access.READ_ONLY)));
        var drive = core.getBehaviour(LibraryDriveBehaviour.TYPE);
        var target = new LibraryTrackAddress(pos.getX(), pos.getY(), pos.getZ()).value();
        var track = LibraryTrackAddress.from(pos, Direction.EAST, pos.north()).value();
        var seek = command(target, 1, "seek", 0, track);
        exchange(helper, computer, seek, accepted -> {
            code(helper, accepted, "accepted");
            helper.assertValueEqual(drive.snapshot().orElseThrow().sequence(), 1L, "Complete physical command admitted once");
            exchange(helper, computer, command(target, 2, "result", 1, DataValue.NullValue.INSTANCE), positioned -> {
                code(helper, positioned, "positioned");
                helper.assertTrue(drive.snapshot().orElseThrow().result().isPresent(), "Sending a result is not its consumption");
                exchange(helper, computer, command(target, 3, "ack", 1, DataValue.NullValue.INSTANCE), ack -> {
                    code(helper, ack, "acknowledged");
                    exchange(helper, computer, seek, replay -> {
                        code(helper, replay, "stale_operation");
                        helper.assertTrue(drive.snapshot().orElseThrow().pending().isEmpty(), "Replayed command cannot start new work");
                        exchange(helper, computer, command(target, 4, "select", 1, new DataValue.IntegerValue(2)), selected -> {
                            code(helper, selected, "accepted");
                            exchange(helper, computer, command(target, 5, "result", 2, DataValue.NullValue.INSTANCE), taken -> {
                                code(helper, taken, "selected");
                                exchange(helper, computer, command(target, 6, "ack", 2, DataValue.NullValue.INSTANCE), ack2 -> {
                                    code(helper, ack2, "acknowledged");
                                    exchange(helper, computer, command(target, 7, "read", 2, new DataValue.TextValue("reading")), read -> {
                                        code(helper, read, "accepted");
                                        exchange(helper, computer, command(target, 8, "result", 3, DataValue.NullValue.INSTANCE), result -> {
                                            code(helper, result, "read");
                                            helper.assertValueEqual(((DataValue.RecordValue)result.field("value")).field("value"), new DataValue.IntegerValue(43), "Actual book copy returned");
                                            helper.assertTrue(drive.snapshot().orElseThrow().result().isPresent(), "Explicit acknowledgement still required");
                                            helper.assertTrue(ItemStack.matches(book, shelf.getItem(2)), "Reading never rewrites book/components/pages");
                                            helper.assertTrue(computer.advertisement().isEmpty(), "No legacy message endpoint");
                                            var other = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, pos.west(2).south(2)));
                                            level.setBlock(pos.west(2).south(), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
                                            other.serialDevice().configure("s0", Direction.NORTH, true);
                                            var forged = command(target, 9, "ack", 3, DataValue.NullValue.INSTANCE);
                                            other.installFlow(new FlowProgram(Map.of("packet", DataType.typeOf(forged)), List.of(new FlowProgram.SerialSend("send", "s0", "packet", "sent"))),
                                                new DataValue.RecordValue(Map.of("packet", forged))); other.step();
                                            helper.runAfterDelay(200, () -> {
                                                helper.assertValueEqual(serial.status().field("last"), new DataValue.TextValue("denied"), "A copied command record cannot impersonate the delegate");
                                                helper.assertTrue(drive.snapshot().orElseThrow().result().isPresent(), "Unauthorized ack cannot consume data");
                                                // The observer Computer receives raw cable traffic. Drain its unrelated forged request before the next query.
                                                computer.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.SerialReceive("discard_unrelated", "s0", "received"))), new DataValue.RecordValue(Map.of())); computer.step();
                                                var checkpoint = drive.snapshot().orElseThrow(); var saved = core.saveWithFullMetadata(level.registryAccess());
                                                level.removeBlockEntity(pos);
                                                var restored = (LibraryBlockEntity)BlockEntity.loadStatic(pos, core.getBlockState(), saved, level.registryAccess());
                                                level.setBlockEntity(restored);
                                                helper.runAfterDelay(4, () -> {
                                                    var loadedDrive = restored.getBehaviour(LibraryDriveBehaviour.TYPE);
                                                    helper.assertValueEqual(loadedDrive.snapshot().orElseThrow(), checkpoint, "Result and hardware reload without replaying a command");
                                                    exchange(helper, computer, command(target, 10, "result", 3, DataValue.NullValue.INSTANCE), again -> {
                                                        code(helper, again, "read"); helper.assertValueEqual(again.field("value"), result.field("value"), "Explicit re-query after reload recovers the same result");
                                                        helper.succeed();
                                                    });
                                                });
                                            });
                                        });
                                    });
                                });
                            });
                        });
                    });
                });
            });
        });
    }
    static DataValue.RecordValue command(DataValue target, long request, String operation, long sequence, DataValue data) {
        return new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("library_command"), "target", target,
            "request", new DataValue.IntegerValue(request), "operation", new DataValue.TextValue(operation),
            "sequence", new DataValue.IntegerValue(sequence), "data", data));
    }
    static void code(GameTestHelper h, DataValue.RecordValue reply, String expected) {
        h.assertValueEqual(reply.field("code"), new DataValue.TextValue(expected), "Finite serial reply");
    }
    static void exchange(GameTestHelper h, ComputerBehaviour computer, DataValue.RecordValue packet, Consumer<DataValue.RecordValue> done) {
        java.util.function.Function<net.minecraft.resources.ResourceLocation, java.io.InputStream> open = id ->
            BiocomputingLibrarySerialGameTests.class.getClassLoader().getResourceAsStream("assets/" + id.getNamespace() + "/" + id.getPath());
        var example = ProgramExampleCatalog.load(open).stream().filter(e -> e.id().equals("library_serial_command")).findFirst().orElseThrow();
        var document = ProgramExampleCatalog.preview(example, open, CBConfigs.SERVER.biocomputing.current(true).archive());
        var body = document.flow().orElseThrow(); var values = new LinkedHashMap<>(body.initial().fields()); values.put("packet", packet);
        document = new ProgramDocument(document.archive(), document.positions(), document.terminal(),
            Optional.of(new FlowDocumentBody(body.inputs(), body.steps(), new DataValue.RecordValue(values))));
        computer.installDocument(document, ComputerRuntime.MemoryPolicy.RESET);
        computer.step(); // Actual packaged public instruction; no private drive admission.
        h.assertValueEqual(((DataValue.RecordValue)computer.flowSnapshot().orElseThrow().state().locals().field("sent")).field("status"), new DataValue.TextValue("queued"), "One explicit send, no retries");
        computer.setPaused(false);
        await(h, computer, packet, done);
    }
    static void await(GameTestHelper h, ComputerBehaviour computer, DataValue.RecordValue packet, Consumer<DataValue.RecordValue> done) {
        h.runAfterDelay(4, () -> {
            var state = computer.flowSnapshot().orElseThrow().state();
            h.assertTrue(state.status() != FlowRuntime.Status.FAILED, "Packaged program must not fail: " + computer.diagnostic());
            if (state.status() != FlowRuntime.Status.COMPLETE) { await(h, computer, packet, done); return; }
            computer.setPaused(true);
            h.assertValueEqual(state.locals().field("matched"), new DataValue.BooleanValue(true), "Ordinary graph matches request/kind/target");
            var result = (DataValue.RecordValue)((DataValue.RecordValue)state.locals().field("received")).field("value");
            h.assertValueEqual(result.field("request"), packet.field("request"), "Correlation survives actual frame round trip"); done.accept(result);
        });
    }
}
