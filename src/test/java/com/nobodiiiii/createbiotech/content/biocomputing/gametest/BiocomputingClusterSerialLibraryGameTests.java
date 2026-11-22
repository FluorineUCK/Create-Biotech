package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingClusterSerialLibraryGameTests {
    @GameTest(template = "empty", timeoutTicks = 12000)
    public static void publicProgramsComputeInsideSealedHeterogeneousClusterSavePhysicalBookAndReplyOverSerial(GameTestHelper helper) {
        run(helper, LibraryServiceConfiguration.Access.READ_WRITE, true, false);
    }
    @GameTest(template = "empty", timeoutTicks = 12000)
    public static void publicProgramsReturnReadOnlyFailureWithoutMutatingThePhysicalBook(GameTestHelper helper) {
        run(helper, LibraryServiceConfiguration.Access.READ_ONLY, false, false);
    }
    @GameTest(template = "empty", timeoutTicks = 12000)
    public static void pausedComputeMemberReturnsExplicitFailureWithoutBorrowingOrStartingItsProgram(GameTestHelper helper) {
        run(helper, LibraryServiceConfiguration.Access.READ_WRITE, false, true);
    }
    @GameTest(template = "empty", timeoutTicks = 12000)
    public static void libraryPlacedBeforeControllerAcceptsTheSamePublicSerialDriver(GameTestHelper helper) {
        run(helper, LibraryServiceConfiguration.Access.READ_WRITE, true, false, true);
    }
    @GameTest(template = "empty", timeoutTicks = 12000)
    public static void removedPhysicalBookReturnsMissingMediumToThePublicTerminalWithoutInventingAResult(GameTestHelper helper) {
        run(helper, LibraryServiceConfiguration.Access.READ_WRITE, false, false, false, true);
    }
    @GameTest(template = "empty", timeoutTicks = 12000)
    public static void revokingVolumeAfterWriteAdmissionReturnsDeniedWithoutChangingBookOrPoolingMembers(GameTestHelper helper) {
        run(helper, LibraryServiceConfiguration.Access.READ_WRITE, false, false, false, false, WriteChange.REVOKE);
    }
    @GameTest(template = "empty", timeoutTicks = 12000)
    public static void locallyEditingAnAdmittedWriteReturnsStaleVersionAndPreservesTheNewerPhysicalEntry(GameTestHelper helper) {
        run(helper, LibraryServiceConfiguration.Access.READ_WRITE, false, false, false, false, WriteChange.LOCAL_EDIT);
    }
    private enum WriteChange { NONE, REVOKE, LOCAL_EDIT }
    private static void run(GameTestHelper helper, LibraryServiceConfiguration.Access access, boolean success, boolean pausedWorker) {
        run(helper, access, success, pausedWorker, false);
    }
    private static void run(GameTestHelper helper, LibraryServiceConfiguration.Access access, boolean success, boolean pausedWorker, boolean libraryFirst) {
        run(helper, access, success, pausedWorker, libraryFirst, false);
    }
    private static void run(GameTestHelper helper, LibraryServiceConfiguration.Access access, boolean success, boolean pausedWorker, boolean libraryFirst, boolean missingBook) {
        run(helper, access, success, pausedWorker, libraryFirst, missingBook, WriteChange.NONE);
    }
    private static void run(GameTestHelper helper, LibraryServiceConfiguration.Access access, boolean success, boolean pausedWorker, boolean libraryFirst, boolean missingBook, WriteChange writeChange) {
        boolean revokeDuringWrite = writeChange == WriteChange.REVOKE;
        var level = helper.getLevel(); var min = helper.absolutePos(new BlockPos(1, 1, 1)); var max = min.offset(4, 3, 4);
        var controllerPos = min.offset(4, 1, 1); var coordinatorPos = controllerPos.west(); var workerPos = coordinatorPos.south(2);
        var libraryPos = controllerPos.east(4);
        // Real player order: an existing Library can be commissioned to a newly placed Cluster.
        var earlyLibrary = libraryFirst ? library(helper, libraryPos) : null;
        var coordinator = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, coordinatorPos, VillagerProfession.LIBRARIAN, 5));
        var worker = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, workerPos, VillagerProfession.NONE, 1));
        var callerPos = controllerPos.offset(2, 0, 2);
        var caller = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, callerPos, VillagerProfession.LIBRARIAN, 3));
        for (var pos : BlockPos.betweenClosed(min, max))
            if (pos.getX() == min.getX() || pos.getX() == max.getX() || pos.getY() == min.getY() || pos.getY() == max.getY()
                || pos.getZ() == min.getZ() || pos.getZ() == max.getZ())
                level.setBlock(pos, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), 3);
        level.setBlock(controllerPos, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING, Direction.EAST), 3);
        var controller = (ClusterControllerBlockEntity)level.getBlockEntity(controllerPos);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "joint-serial"));
        actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(controllerPos.east()));
        for (var computer : List.of(coordinator, worker)) computer.installFlow(new FlowProgram(Map.of(), List.of()), new DataValue.RecordValue(Map.of()));
        coordinator.configureEndpoint(new EndpointConfiguration("coordinator", true)); worker.configureEndpoint(new EndpointConfiguration("worker", true));
        var members = Map.of("coordinator", new ClusterConfiguration.Member(coordinator.communicationIdentity().orElseThrow(), coordinatorPos),
            "worker", new ClusterConfiguration.Member(worker.communicationIdentity().orElseThrow(), workerPos));
        var wire = new XBusWire(new XBusWire.Pin("coordinator", "coordinator", "x0"), new XBusWire.Pin("worker", "worker", "x0"), true);
        helper.assertValueEqual(controller.configure(new ClusterConfiguration(members, Map.of(), Map.of(), Map.of("compute", wire)), actor), "configured", "Explicit internal XBus wire");
        var binding = SerialGatewayAccess.authorize(new ClusterMemberEditorAccess(controller, coordinator.blockEntity), actor, "s0", "journal");
        coordinator.configureSerialGateway("s0", Optional.of(binding)); coordinator.configureSerial(new SerialControlData.Configuration("s0", Direction.EAST, true));
        caller.configureSerial(new SerialControlData.Configuration("s0", Direction.NORTH, true));
        for (int x = 1; x <= 3; x++) level.setBlock(controllerPos.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(callerPos.north(), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);

        var library = earlyLibrary != null ? earlyLibrary : library(helper, libraryPos);
        level.setBlock(libraryPos.north(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), 3);
        var shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(libraryPos.north());
        var media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), CBConfigs.SERVER.biocomputing.current(true).archive());
        var book = media.put(new ItemStack(Items.WRITABLE_BOOK), "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(43)));
        book = new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get()).rename(book, "日志"); shelf.setItem(2, book.copy());
        var originalBook = book.copy();
        library.getBehaviour(LibraryServiceBehaviour.TYPE).configure(0, new LibraryServiceConfiguration(Optional.empty(), Map.of("日志", access)));
        actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(libraryPos));
        var control = library.getBehaviour(LibraryControlBehaviour.TYPE);
        var selected = control.discover(actor).stream().filter(c -> c.member().equals(coordinatorPos) && c.controller().isPresent()).findFirst().orElseThrow();
        control.configure(actor, 0, Optional.of(selected)); control.configureSerial(actor, 1, true);

        var document = load("coordinator"); var body = document.flow().orElseThrow(); var values = new LinkedHashMap<>(body.initial().fields());
        values.put("target", new LibraryTrackAddress(libraryPos.getX(), libraryPos.getY(), libraryPos.getZ()).value());
        var arguments = new LinkedHashMap<>(((DataValue.RecordValue)values.get("arguments")).fields());
        arguments.put("track", LibraryTrackAddress.from(libraryPos, Direction.EAST, libraryPos.north()).value()); values.put("arguments", new DataValue.RecordValue(arguments));
        coordinator.installDocument(new ProgramDocument(document.archive(), document.positions(), document.terminal(), Optional.of(
            new FlowDocumentBody(body.inputs(), body.steps(), new DataValue.RecordValue(values)))), ComputerRuntime.MemoryPolicy.RESET);
        worker.installDocument(load("worker"), ComputerRuntime.MemoryPolicy.RESET); caller.installDocument(load("caller"), ComputerRuntime.MemoryPolicy.RESET);
        var coordinatorHardware = coordinator.hardware(); var workerHardware = worker.hardware();
        helper.assertTrue(!coordinatorHardware.equals(workerHardware), "Resident/profession/level/resource differences are real");
        var programA = coordinator.flowSnapshot().orElseThrow().program(); var programB = worker.flowSnapshot().orElseThrow().program();
        var presentation = com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalStatusView.decode(caller.terminalStatus());
        helper.assertTrue(presentation.terminal().isPresent() && presentation.terminal().orElseThrow().readOnly(), "Public caller carries an editable presentation");
        helper.assertValueEqual(presentation.roots().field("output"), DataValue.NullValue.INSTANCE, "No completed output before execution");
        var carriedBook = missingBook ? shelf.removeItem(2, 1) : ItemStack.EMPTY;
        // All later computation, commands and replies must be chosen by the installed public documents.
        coordinator.setPaused(false); if (!pausedWorker) worker.setPaused(false); caller.setPaused(false);
        var revoked = new boolean[1];
        var editedBook = new ItemStack[1];
        if (revokeDuringWrite) revokeAdmittedWrite(helper, library, shelf, originalBook, revoked);
        if (writeChange == WriteChange.LOCAL_EDIT) editAdmittedWrite(helper, library, shelf, originalBook, actor, media, editedBook);
        await(helper, caller, library, () -> {
            if (revokeDuringWrite) helper.assertTrue(revoked[0], "The request was actually admitted before authorization changed");
            if (writeChange == WriteChange.LOCAL_EDIT) helper.assertTrue(editedBook[0] != null, "Local edit occurred after actual admission, not before the request");
            var response = (DataValue.RecordValue)caller.flowSnapshot().orElseThrow().state().locals().field("reply");
            var beforeObservation = caller.flowSnapshot();
            var terminal = com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalStatusView.decode(caller.terminalStatus());
            var replyNode = (com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition.Text)terminal.terminal().orElseThrow().nodes().get("reply");
            helper.assertValueEqual(replyNode.value().resolve(terminal.roots()), response, "Terminal uses the actual completed reply, including failures");
            helper.assertValueEqual(caller.flowSnapshot(), beforeObservation, "Reading the terminal never replays serial or Library work");
            helper.assertValueEqual(response.field("ok"), new DataValue.BooleanValue(success), "Business outcome, not local transmit acceptance");
            helper.assertValueEqual(response.field("request"), caller.flowSnapshot().orElseThrow().state().locals().field("client_request"), "System-associated reply");
            if (success) {
                var stored = (DataValue.RecordValue)response.field("value");
                helper.assertValueEqual(stored.field("value"), new DataValue.IntegerValue(42), "Actual internal calculation read back from the book");
                var valueNode = (com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition.Text)terminal.terminal().orElseThrow().nodes().get("value");
                helper.assertValueEqual(valueNode.value().resolve(terminal.roots()), new DataValue.IntegerValue(42), "Ordinary terminal field binding displays the stored value");
                helper.assertValueEqual(stored.field("kind"), new DataValue.TextValue("DATA"), "Typed entry retained");
                helper.assertValueEqual(stored.field("version"), media.stat(shelf.getItem(2), "reading").field("version"), "Readback carries the committed version");
                helper.assertValueEqual(media.read(shelf.getItem(2)).get("reading").value(), new DataValue.IntegerValue(42), "Physical book owns the result");
            } else {
                helper.assertTrue(ItemStack.matches(editedBook[0] == null ? originalBook : editedBook[0], missingBook ? carriedBook : shelf.getItem(2)), "Failed operation leaves the complete current physical or carried book unchanged");
                if (missingBook) helper.assertTrue(shelf.getItem(2).isEmpty(), "The driver cannot recreate a removed medium");
                var valueNode = (com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition.Text)terminal.terminal().orElseThrow().nodes().get("value");
                helper.assertValueEqual(valueNode.value().resolve(terminal.roots()), DataValue.NullValue.INSTANCE, "Failure must not present an earlier stat as the saved result");
                var errorNode = (com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition.Text)terminal.terminal().orElseThrow().nodes().get("error");
                String refusal = missingBook ? "missing_medium" : revokeDuringWrite ? "denied" : writeChange == WriteChange.LOCAL_EDIT ? "stale_version" : "readonly";
                helper.assertValueEqual(errorNode.value().resolve(terminal.roots()), pausedWorker ? response.field("error")
                    : new DataValue.TextValue(refusal), "Terminal shows the real failure, not null or a record summary");
                if (pausedWorker) {
                    helper.assertTrue(response.field("error") instanceof DataValue.ErrorValue, "Paused peer is a typed error, not zero");
                    helper.assertValueEqual(worker.flowSnapshot().orElseThrow().state().work(), 0L, "Paused member does no hidden work");
                    helper.assertTrue(library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().isEmpty()
                        || library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow().sequence() == 0, "No Library operation before actual internal calculation");
                } else helper.assertValueEqual(((DataValue.RecordValue)response.field("error")).field("code"), new DataValue.TextValue(refusal), "Actual device refusal returns externally");
            }
            if (!pausedWorker) helper.assertValueEqual(worker.flowSnapshot().orElseThrow().state().locals().field("output"), new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(42))), "Other member actually performed arithmetic");
            helper.assertValueEqual(coordinator.hardware(), coordinatorHardware, "Coordinator hardware is not pooled");
            helper.assertValueEqual(worker.hardware(), workerHardware, "Worker hardware is not pooled");
            helper.assertValueEqual(coordinator.flowSnapshot().orElseThrow().program(), programA, "No hidden installed program replacement");
            helper.assertValueEqual(worker.flowSnapshot().orElseThrow().program(), programB, "Each member keeps its own program");
            helper.assertTrue(controller.enclosure().enclosure().isPresent(), "Outer cuboid stays sealed, internal air allowed");
            helper.assertTrue(worker.serialDevice().view().ports().values().stream().noneMatch(p -> p.enabled()), "The worker is not publicly exposed");
            helper.assertTrue(controller.routingState().orElseThrow().mailbox().queued().isEmpty(), "No old immediate-message mailbox substitute");
            helper.assertTrue(library.getBehaviour(LibraryServiceBehaviour.TYPE).advertisements().isEmpty(), "Old immediate Library service stays closed");
            if (missingBook) reinsertAndExplicitlyRetry(helper, caller, coordinator, worker, library, shelf, carriedBook, media);
            else if (revokeDuringWrite) {
                var completed = List.of(caller, coordinator, worker).stream().map(c -> c.flowSnapshot().orElseThrow()).toList();
                var drive = library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow();
                helper.assertTrue(drive.pending().isEmpty() && drive.writing().isEmpty(), "Denied work cannot remain scheduled for a later grant");
                helper.assertValueEqual(drive.result().orElseThrow().code(), "denied", "The driver stops on an unexpected device result without implicitly acknowledging it");
                var observed = library.getBehaviour(LibraryControlBehaviour.TYPE).status().field("drive");
                helper.assertValueEqual(((DataValue.RecordValue)observed).field("result"), new DataValue.TextValue("denied"), "Lectern metadata exposes the retained failure, not book contents");
                helper.assertValueEqual(library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow(), drive, "Observation never consumes a result or retries work");
                var service = library.getBehaviour(LibraryServiceBehaviour.TYPE);
                helper.assertValueEqual(service.configure(((DataValue.IntegerValue)service.status().field("revision")).value(),
                    new LibraryServiceConfiguration(Optional.empty(), Map.of("日志", LibraryServiceConfiguration.Access.READ_WRITE))), "configured", "Restore grants through the normal configuration gate");
                helper.runAfterDelay(40, () -> {
                    helper.assertValueEqual(List.of(caller, coordinator, worker).stream().map(c -> c.flowSnapshot().orElseThrow()).toList(), completed, "Restoring authorization cannot replay any member's completed request");
                    helper.assertValueEqual(library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow(), drive, "Restoring authorization cannot restart old mechanical work");
                    helper.assertTrue(ItemStack.matches(originalBook, shelf.getItem(2)), "No deferred write after reauthorization");
                    claimFailureAndExplicitlyRetry(helper, caller, coordinator, worker, library, shelf, originalBook, media);
                });
            }
            else if (writeChange == WriteChange.LOCAL_EDIT) {
                var before = library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow();
                var computers = List.of(caller, coordinator, worker).stream().map(c -> c.flowSnapshot().orElseThrow()).toList();
                helper.assertValueEqual(before.result().orElseThrow().code(), "stale_version", "Retain the real stale-write receipt for explicit handling");
                helper.assertTrue(before.writing().isEmpty() && before.pending().isEmpty(), "No deferred overwrite remains");
                helper.runAfterDelay(40, () -> {
                    helper.assertTrue(ItemStack.matches(editedBook[0], shelf.getItem(2)), "The new entry and its version cannot be rolled back by a delayed old write");
                    helper.assertValueEqual(library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow(), before, "No automatic retry or ACK");
                    helper.assertValueEqual(List.of(caller, coordinator, worker).stream().map(c -> c.flowSnapshot().orElseThrow()).toList(), computers, "No member implicitly replays the failed request");
                    helper.succeed();
                });
            } else helper.succeed();
        });
    }
    private static void editAdmittedWrite(GameTestHelper helper, LibraryBlockEntity library,
        ChiseledBookShelfBlockEntity shelf, ItemStack original, FakePlayer actor, LibraryBookMedia media, ItemStack[] edited) {
        helper.runAfterDelay(1, () -> {
            var state = library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow();
            if (state.writing().isEmpty()) { editAdmittedWrite(helper, library, shelf, original, actor, media, edited); return; }
            helper.assertTrue(state.writing().orElseThrow().remaining() > 0 && state.result().isEmpty(), "Write is admitted, not completed");
            helper.assertTrue(ItemStack.matches(original, shelf.getItem(2)), "Admission never edits the source book");
            try (var world = new LibraryBookWorldAccess(helper.getLevel(), library.getBlockPos(), actor, () -> true,
                () -> true, () -> List.of(shelf.getBlockPos()))) {
                var books = new LibraryBookAccess(world, media, new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get()));
                books.put(books.at(shelf.getBlockPos(), 2), "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(99)));
            }
            edited[0] = shelf.getItem(2).copy();
            helper.assertValueEqual(media.read(edited[0]).get("reading").value(), new DataValue.IntegerValue(99), "Authorized local management edit reaches the physical medium");
        });
    }
    private static void claimFailureAndExplicitlyRetry(GameTestHelper helper, ComputerBehaviour caller, ComputerBehaviour coordinator,
        ComputerBehaviour worker, LibraryBlockEntity library, ChiseledBookShelfBlockEntity shelf, ItemStack book, LibraryBookMedia media) {
        var oldApplication = coordinator.document().orElseThrow();
        var callerBefore = caller.flowSnapshot().orElseThrow(); var workerBefore = worker.flowSnapshot().orElseThrow();
        var hardware = List.of(caller.hardware(), coordinator.hardware(), worker.hardware());
        var retained = library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow();
        var source = loadExample("library_failure_recovery_driver"); var body = source.flow().orElseThrow();
        var initial = new LinkedHashMap<>(body.initial().fields());
        initial.put("target", oldApplication.flow().orElseThrow().initial().field("target"));
        coordinator.setPaused(true); coordinator.reset();
        coordinator.installDocument(new ProgramDocument(source.archive(), source.positions(), source.terminal(), Optional.of(
            new FlowDocumentBody(body.inputs(), body.steps(), new DataValue.RecordValue(initial)))), ComputerRuntime.MemoryPolicy.RESET);
        helper.assertValueEqual(library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow(), retained, "Installing a recovery draft cannot acknowledge anything");
        coordinator.setPaused(false); // Explicit player start, not a failure handler hidden in the original application.
        await(helper, coordinator, library, () -> {
            var result = coordinator.flowSnapshot().orElseThrow().state().locals();
            helper.assertValueEqual(result.field("success"), new DataValue.BooleanValue(true), "Receipt acknowledgement completed: " + result);
            var receipt = (DataValue.RecordValue)result.field("read_result");
            helper.assertValueEqual(receipt.field("code"), new DataValue.TextValue("denied"), "The original failure survives acknowledgement");
            helper.assertValueEqual(receipt.field("sequence"), new DataValue.IntegerValue(retained.sequence()), "Claim exactly the retained operation");
            var terminal = com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalStatusView.decode(coordinator.terminalStatus());
            var node = (com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition.Text)terminal.terminal().orElseThrow().nodes().get("receipt_code");
            helper.assertValueEqual(node.value().resolve(terminal.roots()), new DataValue.TextValue("denied"), "The public terminal must not call the original write successful");
            var idle = library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow();
            helper.assertValueEqual(idle.sequence(), retained.sequence(), "Only status/result/ack, no new mechanical operation");
            helper.assertTrue(idle.pending().isEmpty() && idle.writing().isEmpty() && idle.result().isEmpty(), "Only explicit ACK releases the failure");
            helper.assertTrue(ItemStack.matches(book, shelf.getItem(2)), "Recovery does not mutate the physical book");
            helper.assertValueEqual(caller.flowSnapshot().orElseThrow(), callerBefore, "Claiming the device failure cannot change the original caller result");
            helper.assertValueEqual(worker.flowSnapshot().orElseThrow(), workerBefore, "The other member does not run or share memory");
            helper.assertValueEqual(List.of(caller.hardware(), coordinator.hardware(), worker.hardware()), hardware, "Resident and hardware differences survive driver replacement");
            for (var computer : List.of(caller, coordinator, worker)) { computer.setPaused(true); computer.reset(); }
            coordinator.installDocument(oldApplication, ComputerRuntime.MemoryPolicy.RESET); // Explicit restoration of the editable original application.
            coordinator.setPaused(false); worker.setPaused(false); caller.setPaused(false);
            await(helper, caller, library, () -> {
                var current = caller.flowSnapshot().orElseThrow().state().locals();
                helper.assertValueEqual(current.field("success"), new DataValue.BooleanValue(true), "A fresh explicit request is usable after failure recovery");
                helper.assertValueEqual(current.field("value"), new DataValue.IntegerValue(42), "Fresh internal calculation and committed readback");
                helper.assertTrue(!current.field("client_request").equals(callerBefore.state().locals().field("client_request")), "The previous denied reply cannot satisfy the new request");
                helper.assertValueEqual(media.read(shelf.getItem(2)).get("reading").value(), new DataValue.IntegerValue(42), "The same physical book owns the newly saved value");
                var completed = library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow();
                helper.assertValueEqual(completed.sequence(), retained.sequence() + 5, "Exactly seek/select/stat/write/read, no duplicate or hidden retry");
                helper.assertTrue(completed.result().isEmpty() && completed.writing().isEmpty(), "The new application explicitly retires its result");
                helper.assertValueEqual(List.of(caller.hardware(), coordinator.hardware(), worker.hardware()), hardware, "No capacity pooling in either application");
                helper.succeed();
            });
        });
    }
    private static void revokeAdmittedWrite(GameTestHelper helper, LibraryBlockEntity library,
        ChiseledBookShelfBlockEntity shelf, ItemStack originalBook, boolean[] revoked) {
        helper.runAfterDelay(1, () -> {
            var drive = library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow();
            if (drive.writing().isEmpty()) { revokeAdmittedWrite(helper, library, shelf, originalBook, revoked); return; }
            helper.assertTrue(drive.writing().orElseThrow().remaining() > 0 && drive.result().isEmpty(), "Actual slow write is admitted but unfinished");
            helper.assertTrue(ItemStack.matches(originalBook, shelf.getItem(2)), "Admission is not a physical commit");
            var service = library.getBehaviour(LibraryServiceBehaviour.TYPE);
            helper.assertValueEqual(service.configure(((DataValue.IntegerValue)service.status().field("revision")).value(),
                new LibraryServiceConfiguration(Optional.empty(), Map.of("日志", LibraryServiceConfiguration.Access.CLOSED))), "configured", "Live volume authorization can be revoked during device work");
            revoked[0] = true;
        });
    }
    private static void reinsertAndExplicitlyRetry(GameTestHelper helper, ComputerBehaviour caller, ComputerBehaviour coordinator,
        ComputerBehaviour worker, LibraryBlockEntity library, ChiseledBookShelfBlockEntity shelf, ItemStack book, LibraryBookMedia media) {
        var computers = List.of(caller, coordinator, worker);
        var failed = computers.stream().map(c -> c.flowSnapshot().orElseThrow()).toList();
        var hardware = computers.stream().map(ComputerBehaviour::hardware).toList();
        var drive = library.getBehaviour(LibraryDriveBehaviour.TYPE);
        var failedDrive = drive.snapshot();
        var oldRequest = failed.getFirst().state().locals().field("client_request");
        shelf.setItem(2, book.copy());
        helper.runAfterDelay(40, () -> {
            for (int i = 0; i < computers.size(); i++)
                helper.assertValueEqual(computers.get(i).flowSnapshot().orElseThrow(), failed.get(i), "Inserting media cannot restart any member or replace its old failure");
            helper.assertValueEqual(drive.snapshot(), failedDrive, "No hidden Library retry after the shelf update");
            helper.assertTrue(ItemStack.matches(book, shelf.getItem(2)), "Reinserted medium remains unchanged until a new explicit request");
            for (var computer : computers) { computer.setPaused(true); computer.reset(); }
            coordinator.setPaused(false); worker.setPaused(false); caller.setPaused(false);
            await(helper, caller, library, () -> {
                var current = caller.flowSnapshot().orElseThrow().state();
                var reply = (DataValue.RecordValue)current.locals().field("reply");
                helper.assertTrue(!oldRequest.equals(current.locals().field("client_request")), "Explicit rerun owns a fresh correlation");
                helper.assertValueEqual(reply.field("request"), current.locals().field("client_request"), "Old failure cannot satisfy the new run");
                helper.assertValueEqual(reply.field("ok"), new DataValue.BooleanValue(true), "Fresh explicit operation succeeds after reinsertion");
                helper.assertValueEqual(current.locals().field("value"), new DataValue.IntegerValue(42), "The public result is the new committed readback");
                helper.assertValueEqual(current.locals().field("error"), DataValue.NullValue.INSTANCE, "Old error does not leak into the fresh result");
                helper.assertValueEqual(media.read(shelf.getItem(2)).get("reading").value(), new DataValue.IntegerValue(42), "The same physical medium owns the new result");
                helper.assertValueEqual(((DataValue.RecordValue)reply.field("value")).field("version"), media.stat(shelf.getItem(2), "reading").field("version"), "Reply version belongs to this actual book");
                var completed = drive.snapshot().orElseThrow();
                helper.assertTrue(completed.pending().isEmpty() && completed.result().isEmpty() && completed.writing().isEmpty(), "The new result was explicitly acknowledged");
                for (int i = 0; i < computers.size(); i++) {
                    helper.assertValueEqual(computers.get(i).flowSnapshot().orElseThrow().program(), failed.get(i).program(), "Recovery does not reinstall programs");
                    helper.assertValueEqual(computers.get(i).hardware(), hardware.get(i), "Recovery does not pool or replace member hardware");
                }
                helper.succeed();
            });
        });
    }
    private static LibraryBlockEntity library(GameTestHelper helper, BlockPos pos) {
        var level = helper.getLevel();
        var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.EAST);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos, state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), 3);
        level.setBlock(pos.above(), state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var library = (LibraryBlockEntity)level.getBlockEntity(pos);
        library.applyPlacementData(LibraryCoreItemData.configured(CommunicationFrequency.of("joint-serial"), 0, UUID.randomUUID(), 0));
        library.initialize(); return library;
    }
    private static ProgramDocument load(String role) {
        return loadExample("cluster_serial_library_" + role);
    }
    private static ProgramDocument loadExample(String exampleId) {
        java.util.function.Function<net.minecraft.resources.ResourceLocation, java.io.InputStream> open = id ->
            BiocomputingClusterSerialLibraryGameTests.class.getClassLoader().getResourceAsStream("assets/" + id.getNamespace() + "/" + id.getPath());
        var entry = ProgramExampleCatalog.load(open).stream().filter(e -> e.id().equals(exampleId)).findFirst().orElseThrow();
        return ProgramExampleCatalog.preview(entry, open, CBConfigs.SERVER.biocomputing.current(true).archive());
    }
    private static void await(GameTestHelper helper, ComputerBehaviour computer, LibraryBlockEntity library, Runnable finished) {
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(!library.getBehaviour(LibrarySerialBehaviour.TYPE).status().field("last")
                .equals(new DataValue.TextValue("gateway_unavailable")), "A ticking commissioned gateway must not depend on block entity tick order");
            var state = computer.flowSnapshot().orElseThrow().state();
            helper.assertTrue(state.status() != FlowRuntime.Status.FAILED, "Public program failed: " + state.diagnostic());
            if (state.status() != FlowRuntime.Status.COMPLETE) { await(helper, computer, library, finished); return; }
            finished.run();
        });
    }
}
