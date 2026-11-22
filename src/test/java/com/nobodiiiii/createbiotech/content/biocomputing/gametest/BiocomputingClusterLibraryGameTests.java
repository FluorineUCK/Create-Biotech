package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.ClusterLibraryPrograms.obj;
import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingWorldGameTests.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingClusterPairGameTests.reloadBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.*;

/** External caller -> enclosed Computer -> physical book CAS -> original centralized reply. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingClusterLibraryGameTests {
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void clusterStoresComputedRecordAndReturnsErrorsWithoutBypassingLibrary(GameTestHelper helper) {
        var level = helper.getLevel();
        var callerBlock = computer(helper, helper.absolutePos(new BlockPos(1, 2, 5)));
        var workerBlock = computer(helper, helper.absolutePos(new BlockPos(4, 2, 5)));
        var min = helper.absolutePos(new BlockPos(3, 1, 4)); var max = min.offset(3, 3, 3);
        for (var pos : BlockPos.betweenClosed(min, max))
            if (pos.getX()==min.getX() || pos.getX()==max.getX() || pos.getY()==min.getY() || pos.getY()==max.getY()
                || pos.getZ()==min.getZ() || pos.getZ()==max.getZ())
                level.setBlock(pos, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), Block.UPDATE_ALL);
        var gatewayPos = workerBlock.getBlockPos().west();
        level.setBlock(gatewayPos, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING, Direction.WEST), Block.UPDATE_ALL);
        var controller = (ClusterControllerBlockEntity)level.getBlockEntity(gatewayPos);
        helper.assertValueEqual(controller.enclosure().status(), EnclosureScanner.Status.READY, "Closed cuboid including embedded gateway");
        helper.assertTrue(level.isEmptyBlock(min.offset(2, 1, 2)), "Interior air is allowed");
        helper.assertValueEqual(controller.enclosure().enclosure().orElseThrow().contacts().keySet(), Set.of(workerBlock.getBlockPos()), "Only the interior wall-contact Computer is a member");
        var caller = host(callerBlock); var worker = host(workerBlock);
        caller.configureEndpoint(new EndpointConfiguration("client", true));
        worker.configureEndpoint(new EndpointConfiguration("worker", true));
        caller.install(ClusterLibraryPrograms.caller(), ComputerRuntime.MemoryPolicy.RESET);
        worker.install(ClusterLibraryPrograms.worker(), ComputerRuntime.MemoryPolicy.RESET);
        var member = new ClusterConfiguration.Member(worker.communicationIdentity().orElseThrow(), workerBlock.getBlockPos());
        BiocomputingClusterPairGameTests.configureThroughMenu(helper, controller,
            new ClusterConfiguration(Map.of("worker", member), Map.of(
                "calculate", new ClusterConfiguration.Port("worker", "worker", "return", "client", true),
                "storage_reply", new ClusterConfiguration.Port("worker", "worker", "storage", "books", true))), "storage-example");
        helper.assertValueEqual(worker.clusterAttachment().orElseThrow().outputs(), Set.of("return", "storage"), "Only explicit centralized egress names");
        for (int z = 3; z <= 5; z++) level.setBlock(helper.absolutePos(new BlockPos(2, 2, z)), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
        for (int x = 3; x <= 9; x++) level.setBlock(helper.absolutePos(new BlockPos(x, 2, 3)), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(helper.absolutePos(new BlockPos(9, 2, 4)), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
        var cut = helper.absolutePos(new BlockPos(9, 2, 5));
        var corePos = helper.absolutePos(new BlockPos(10, 2, 5));
        var coreState = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.EAST);
        level.setBlock(corePos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(corePos, coreState.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
        level.setBlock(corePos.above(), coreState.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        var core = (LibraryBlockEntity)level.getBlockEntity(corePos);
        core.initialize(); // Raw world fixture must enter the physical identity lifecycle before serving.
        var service = core.getBehaviour(LibraryServiceBehaviour.TYPE);
        configure(helper, service, LibraryServiceConfiguration.Access.READ_WRITE);
        level.setBlock(corePos.north(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
        var shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(corePos.north());
        var media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), CBConfigs.SERVER.biocomputing.current(true).archive());
        var volume = new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get());
        var original = new ItemStack(Items.WRITABLE_BOOK);
        original.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(Filterable.passThrough("Keep this physical source page"))));
        shelf.setItem(0, volume.rename(media.put(original, "joint_reading", new LibraryEntry(LibraryEntry.Kind.DATA, value(43))), "日志"));
        caller.begin(value(7));
        helper.runAfterDelay(25, () -> {
            helper.assertValueEqual(memory(caller, "calls"), new DataValue.IntegerValue(0), "External caller waits across missing Library cable");
            var waiting = worker.snapshot().orElseThrow().rpc().pending().get("stat");
            helper.assertTrue(waiting != null, "Internal worker retains its child request");
            helper.assertValueEqual(waiting.continuation().orElseThrow().captured().orElseThrow(), value(8), "System retains the internal computed value");
            helper.assertValueEqual(controller.routingState().orElseThrow().pending().size(), 2, "Original and child routes coexist in gateway");
            helper.assertValueEqual(media.read(shelf.getItem(0)).get("joint_reading").value(), value(43), "No disconnected writes");
            var resumedController = (ClusterControllerBlockEntity)reloadBlock(level, controller);
            var resumedWorker = host((ComputerBlockEntity)reloadBlock(level, workerBlock)); resumedWorker.document();
            var resumedCaller = host((ComputerBlockEntity)reloadBlock(level, callerBlock)); resumedCaller.document();
            var resumedCore = (LibraryBlockEntity)reloadBlock(level, core);
            resumedCore.initialize();
            var resumedShelf = (ChiseledBookShelfBlockEntity)reloadBlock(level, shelf);
            LibraryShelfUpdates.changed(level, resumedShelf.getBlockPos());
            helper.assertValueEqual(resumedWorker.snapshot().orElseThrow().rpc().pending().get("stat"), waiting, "Actual BE reload preserves capture and original route");
            level.setBlock(cut, CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
            helper.runAfterDelay(90, () -> {
                assertReply(helper, resumedCaller, "ok", 1, 8);
                helper.assertValueEqual(media.read(resumedShelf.getItem(0)).get("joint_reading").value(), value(8), "Only internal computation is stored by public put");
                helper.assertValueEqual(resumedShelf.getItem(0).get(DataComponents.WRITABLE_BOOK_CONTENT), original.get(DataComponents.WRITABLE_BOOK_CONTENT), "Original source page unchanged");
                helper.assertTrue(resumedWorker.snapshot().orElseThrow().machine().memory().fields().isEmpty(), "Worker does not share caller RAM");
                helper.assertTrue(resumedController.routingState().orElseThrow().pending().isEmpty(), "All centralized return paths complete");
                var writtenBook = resumedShelf.getItem(0).copy();
                configure(helper, resumedCore.getBehaviour(LibraryServiceBehaviour.TYPE), LibraryServiceConfiguration.Access.READ_ONLY);
                resumedCaller.begin(value(20));
                helper.runAfterDelay(70, () -> {
                    assertReply(helper, resumedCaller, "readonly", 2, 21);
                    helper.assertTrue(ItemStack.matches(writtenBook, resumedShelf.getItem(0)), "Readonly business error does not alter the book");
                    var taken = resumedShelf.removeItem(0, 1);
                    resumedCaller.begin(value(30));
                    helper.runAfterDelay(50, () -> {
                        assertReply(helper, resumedCaller, "missing_volume", 3, 31);
                        helper.assertTrue(resumedWorker.snapshot().orElseThrow().rpc().pending().isEmpty(), "Failed stat returns directly without put wait");
                        helper.assertTrue(resumedController.routingState().orElseThrow().pending().isEmpty(), "Error also uses and clears original central route");
                        helper.assertValueEqual(media.read(taken).get("joint_reading").value(), value(8), "Removed medium remains the data owner");
                        resumedShelf.setItem(0, taken);
                        helper.succeed();
                    });
                });
            });
        });
    }
    private static DataValue.RecordValue value(long value) { return obj("value", new DataValue.IntegerValue(value)); }
    private static void configure(GameTestHelper helper, LibraryServiceBehaviour service, LibraryServiceConfiguration.Access access) {
        long revision = ((DataValue.IntegerValue)service.status().field("revision")).value();
        helper.assertValueEqual(service.configure(revision, new LibraryServiceConfiguration(
            Optional.of(new EndpointConfiguration("books", true)), Map.of("日志", access))), "configured", "Explicit Library volume grant");
    }
    private static void assertReply(GameTestHelper helper, ComputerBehaviour caller, String code, int calls, long result) {
        helper.assertValueEqual(memory(caller, "code"), new DataValue.TextValue(code), "Centralized application reply code");
        helper.assertValueEqual(memory(caller, "calls"), new DataValue.IntegerValue(calls), "Exactly one response per external request");
        helper.assertValueEqual(memory(caller, "value"), value(result), "Reply contains the frozen computed record");
        helper.assertTrue(caller.snapshot().orElseThrow().rpc().pending().isEmpty(), "Original external request completes");
    }
}
