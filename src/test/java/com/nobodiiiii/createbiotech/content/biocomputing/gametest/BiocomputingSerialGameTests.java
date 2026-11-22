package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.*;

/** Real registered blocks, resident installation, vanilla ticks and BE reload; not a rendered player-path claim. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingSerialGameTests {
    private static final DataValue.RecordValue RECORD = new DataValue.RecordValue(Map.of(
        "reading", new DataValue.IntegerValue(37), "sample", new DataValue.TextValue("a".repeat(256))));

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void publicFaultClaimKeepsActualGatewayFramesAcrossBlockReloadAndDoesNotBorrowNeighbourWork(GameTestHelper helper) {
        var cluster = serialCluster(helper, new BlockPos(1, 1, 1), Direction.EAST, VillagerProfession.NONE, 1);
        var member = cluster.member(); var world = helper.getLevel();
        var externalPos = cluster.controller().getBlockPos().east(2);
        var sender = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, externalPos, VillagerProfession.NONE, 1));
        sender.configureSerial(new SerialControlData.Configuration("s0", Direction.WEST, true));
        sender.installFlow(new FlowProgram(Map.of(), List.of()), new DataValue.RecordValue(Map.of()));
        world.setBlock(externalPos.west(), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        var hardware = member.hardware(); var neighbour = cluster.hidden().flowSnapshot(); var senderState = sender.flowSnapshot();
        int capacity = hardware.limits().serial().ports().get("s0").receiveFrames();
        var sequence = helper.startSequence().thenIdle(4);
        for (int i = 0; i <= capacity; i++) sequence.thenExecute(() -> {
            helper.assertValueEqual(sender.serialDevice().send("s0", RECORD), "queued", "Explicit finite physical transmission");
        }).thenIdle(Math.toIntExact(duration(sender) + 4));
        sequence.thenExecute(() -> {
            var full = member.serialDevice().status("s0").receive();
            helper.assertValueEqual(full.frames(), capacity, "Resident-owned bounded receive capacity");
            helper.assertTrue(full.faults().contains(com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialReceiveBuffer.Fault.BUFFER_FULL), "Real cable overflow is explicit");
            member.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.SerialStatus("view", "s0", "before"),
                new FlowProgram.SerialStatus("claim", "s0", "receipt", true), new FlowProgram.SerialReceive("read", "s0", "packet"))), new DataValue.RecordValue(Map.of()));
            member.step(); helper.assertValueEqual(member.serialDevice().status("s0").receive(), full, "Read-only status keeps frames and faults");
            member.step();
            var receipt = (DataValue.RecordValue)member.flowSnapshot().orElseThrow().state().locals().field("receipt");
            helper.assertValueEqual(receipt.field("receive_faults"), new DataValue.ListValue(List.of(new DataValue.TextValue("buffer_full"))), "Error copied into this member's own RAM before clearing");
            helper.assertValueEqual(member.serialDevice().status("s0").receive().frames(), capacity, "Acknowledgement never discards a frame");
            helper.assertTrue(member.serialDevice().status("s0").receive().faults().isEmpty(), "Only local receive fault flags cleared");
            var savedSnapshot = member.flowSnapshot();
            var entity = member.blockEntity; var pos = entity.getBlockPos(); var state = entity.getBlockState();
            var data = entity.saveWithFullMetadata(world.registryAccess());
            world.removeBlockEntity(pos);
            var replacement = (ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos, state, data, world.registryAccess()));
            world.setBlockEntity(replacement);
            helper.runAfterDelay(4, () -> {
                var restored = replacement.getBehaviour(ComputerBehaviour.TYPE);
                helper.assertValueEqual(restored.flowSnapshot(), savedSnapshot, "Paused continuation and error receipt survive actual BE reconstruction");
                helper.assertValueEqual(restored.serialDevice().status("s0").receive().frames(), capacity, "No automatic drain or replay on load");
                restored.step();
                helper.assertValueEqual(((DataValue.RecordValue)restored.flowSnapshot().orElseThrow().state().locals().field("packet")).field("value"), RECORD, "Separate public receive retrieves intact frame");
                helper.assertValueEqual(restored.serialDevice().status("s0").receive().frames(), capacity - 1, "One explicit receive consumes exactly one frame");
                helper.assertValueEqual(restored.flowSnapshot().orElseThrow().state().work(), 3L, "Only three own instructions");
                helper.assertValueEqual(restored.hardware(), hardware, "No resident or hardware changes");
                helper.assertValueEqual(cluster.hidden().flowSnapshot(), neighbour, "No pooling with the differently specified neighbour");
                helper.assertValueEqual(sender.flowSnapshot(), senderState, "Receiver operations do not advance the sender");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void nonTickingControllerCannotStopMemberSleepOrServeItsSerialGateway(GameTestHelper helper) {
        var level = helper.getLevel();
        var far = helper.absolutePos(new BlockPos(4096, 1, 1));
        var min = new BlockPos((far.getX() & ~15) + 12, far.getY(), (far.getZ() & ~15) + 1);
        var memberChunk = new net.minecraft.world.level.ChunkPos(min);
        var controllerChunk = new net.minecraft.world.level.ChunkPos(memberChunk.x + 1, memberChunk.z);
        var anchor = new net.minecraft.world.level.ChunkPos(memberChunk.x - 1, memberChunk.z);
        // With only the anchor ticket, the adjacent member chunk ticks but the next controller chunk is only loaded.
        level.setChunkForced(anchor.x, anchor.z, true); level.setChunkForced(controllerChunk.x, controllerChunk.z, true);
        var cluster = serialClusterAt(helper, min, Direction.EAST, VillagerProfession.NONE, 1);
        var member = cluster.member(); var controller = cluster.controller();
        helper.assertTrue(new net.minecraft.world.level.ChunkPos(member.blockEntity.getBlockPos()).equals(memberChunk)
            && new net.minecraft.world.level.ChunkPos(controller.getBlockPos()).equals(controllerChunk), "Fixture crosses the real ticking boundary");
        var externalPos = controller.getBlockPos().east(2);
        var external = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, externalPos, VillagerProfession.FLETCHER, 4));
        external.configureSerial(new SerialControlData.Configuration("s0", Direction.WEST, true));
        external.installFlow(new FlowProgram(Map.of(), List.of()), new DataValue.RecordValue(Map.of()));
        level.setBlock(externalPos.west(), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        var hardware = member.hardware(); var hidden = cluster.hidden().flowSnapshot();
        var sleep = new FlowProgram(Map.of("ticks", com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.INTEGER),
            List.of(new FlowProgram.Sleep("sleep", "ticks"), new FlowProgram.SerialStatus("observe", "s0", "link")));
        int interval = Math.toIntExact(duration(member) + 4);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(member.serialDevice().status("s0").connection(), "ready", "Both chunks initially tick");
        }).thenExecute(() -> {
            member.installFlow(sleep, new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(32))));
            member.setPaused(false);
        }).thenIdle(2).thenExecute(() -> {
            helper.assertValueEqual(member.flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.SLEEPING, "Independent member entered local sleep");
            level.setChunkForced(controllerChunk.x, controllerChunk.z, false);
        }).thenWaitUntil(() -> {
            helper.assertTrue(!controller.physicalIoTickAvailable()
                && level.shouldTickBlocksAt(memberChunk.toLong()), "Controller is non-ticking while the member remains ticking");
        }).thenExecute(() -> {
            helper.assertTrue(!controller.isRemoved(), "This is asymmetric non-ticking, not an actual unload claim");
            helper.assertValueEqual(member.serialDevice().send("s0", RECORD), "queued", "A local send attempt is not a gateway delivery promise");
        }).thenIdle(2).thenExecute(() -> {
            helper.assertValueEqual(member.serialDevice().status("s0").connection(), "gateway_unavailable", "A loaded but non-ticking controller cannot serve the interface");
            helper.assertValueEqual(member.serialDevice().status("s0").transmit(), "interrupted", "The failed local submission must not remain queued");
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(member.flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.COMPLETE, "Local sleep and following instruction continue without the controller");
        }).thenExecute(() -> {
            var state = member.flowSnapshot().orElseThrow().state();
            helper.assertValueEqual(state.work(), 2L, "Only the member's own two instructions execute");
            helper.assertValueEqual(((DataValue.RecordValue)state.locals().field("link")).field("connection"),
                new DataValue.TextValue("gateway_unavailable"), "Public status instruction exposes failure rather than inventing a healthy link");
            helper.assertValueEqual(cluster.hidden().flowSnapshot(), hidden, "No work is borrowed from the paused neighbour");
            level.setChunkForced(controllerChunk.x, controllerChunk.z, true);
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(member.serialDevice().status("s0").connection(), "ready", "Actual controller tick restores the explicitly bound interface");
        }).thenIdle(interval).thenExecute(() -> {
            helper.assertTrue(external.serialDevice().poll("s0").isEmpty(), "Restoring controller activity never retries the interrupted submission");
            helper.assertValueEqual(member.serialDevice().send("s0", RECORD), "queued", "A new explicit send is allowed");
        }).thenIdle(interval).thenExecute(() -> {
            helper.assertValueEqual(external.serialDevice().poll("s0").orElseThrow(), RECORD, "Reactivated gateway transmits a newly requested frame");
            helper.assertValueEqual(member.hardware(), hardware, "Controller lifecycle does not alter resident hardware");
            helper.assertValueEqual(member.flowSnapshot().orElseThrow().state().work(), 2L, "Physical recovery does not replay the completed program");
            level.setChunkForced(anchor.x, anchor.z, false); level.setChunkForced(controllerChunk.x, controllerChunk.z, false);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void actualChunkUnloadRestoresGatewayFrameAndIndependentPausedProgram(GameTestHelper helper) {
        var level = helper.getLevel();
        // Outside the GameTest structure's forced chunks; only this fixture owns this ticket.
        var far = helper.absolutePos(new BlockPos(2048, 1, 1));
        var min = new BlockPos((far.getX() & ~15) + 1, far.getY(), (far.getZ() & ~15) + 1);
        var chunk = new net.minecraft.world.level.ChunkPos(min);
        level.setChunkForced(chunk.x, chunk.z, true);
        var cluster = serialClusterAt(helper, min, Direction.EAST, VillagerProfession.NONE, 1);
        var externalPos = cluster.controller().getBlockPos().east(2);
        helper.assertTrue(new net.minecraft.world.level.ChunkPos(externalPos).equals(chunk)
            && new net.minecraft.world.level.ChunkPos(cluster.member().blockEntity.getBlockPos()).equals(chunk),
            "All fixture devices must be in the one explicitly ticketed chunk");
        var external = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, externalPos, VillagerProfession.FLETCHER, 4));
        external.configureSerial(new SerialControlData.Configuration("s0", Direction.WEST, true));
        external.installFlow(new FlowProgram(Map.of(), List.of()), new DataValue.RecordValue(Map.of()));
        level.setBlock(externalPos.west(), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        var original = cluster.member(); var originalEntity = original.blockEntity;
        var memberPos = originalEntity.getBlockPos(); var controllerPos = cluster.controller().getBlockPos();
        var savedProgram = original.flowSnapshot(); var savedHardware = original.hardware();
        var binding = original.serialDevice().view().ports().get("s0").gateway();
        int interval = Math.toIntExact(duration(external) + 4);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(original.serialDevice().status("s0").connection(), "ready", "Fixture member is physically ticking");
            helper.assertValueEqual(external.serialDevice().status("s0").connection(), "ready", "Fixture sender is physically ticking");
        }).thenExecute(() -> {
            helper.assertValueEqual(external.serialDevice().send("s0", RECORD), "queued", "Emit through actual world ticks before unloading");
        }).thenIdle(interval).thenExecute(() -> {
            helper.assertValueEqual(original.serialDevice().status("s0").receive().frames(), 1, "The complete frame belongs to member hardware");
            level.setChunkForced(chunk.x, chunk.z, false);
        }).thenWaitUntil(() -> {
            helper.assertTrue(level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null && originalEntity.isRemoved(),
                "Wait for actual chunk/BE unloading, not distance, non-ticking, or a manual BE replacement");
        }).thenExecute(() -> level.setChunkForced(chunk.x, chunk.z, true)).thenWaitUntil(() -> {
            var restoredChunk = level.getChunkSource().getChunkNow(chunk.x, chunk.z);
            helper.assertTrue(restoredChunk != null && restoredChunk.getBlockEntity(controllerPos) instanceof ClusterControllerBlockEntity c
                && c.physicalIoTickAvailable(), "Wait for the newly loaded controller's actual physical tick");
        }).thenIdle(2).thenExecute(() -> {
            var reloaded = level.getBlockEntity(memberPos);
            helper.assertTrue(reloaded != originalEntity && !reloaded.isRemoved(), "Disk load must create a different live Computer instance");
            var member = BiocomputingWorldGameTests.host((com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity)reloaded);
            member.document(); // Normal authorized observation decodes retained program state, without running it.
            helper.assertTrue(level.getBlockEntity(controllerPos) != cluster.controller(), "Controller must also be a fresh physical instance");
            helper.assertValueEqual(member.flowSnapshot(), savedProgram, "Paused program/RAM/clock do not catch up while absent");
            helper.assertValueEqual(member.hardware(), savedHardware, "Resident identity and independent hardware survive actual unload");
            helper.assertValueEqual(member.serialDevice().view().ports().get("s0").gateway(), binding, "Explicit gateway binding is retained");
            helper.assertValueEqual(member.serialDevice().status("s0").connection(), "ready", "Fresh controller must tick before restored routing is available");
            helper.assertValueEqual(member.serialDevice().status("s0").receive().frames(), 1, "One original complete frame, not an empty or replayed buffer");
            helper.assertValueEqual(member.serialDevice().poll("s0").orElseThrow(), RECORD, "The restored original typed frame is consumable once");
        }).thenIdle(interval).thenExecute(() -> {
            var member = BiocomputingWorldGameTests.host((com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity)level.getBlockEntity(memberPos));
            helper.assertTrue(member.serialDevice().poll("s0").isEmpty(), "Reload does not silently retransmit a completed emission");
            level.setChunkForced(chunk.x, chunk.z, false);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void twoClosedClustersKeepIndependentResidentsThroughCollisionAndCableRepair(GameTestHelper helper) {
        var level = helper.getLevel();
        var left = serialCluster(helper, new BlockPos(1, 1, 1), Direction.EAST, VillagerProfession.NONE, 1);
        var right = serialCluster(helper, new BlockPos(9, 1, 1), Direction.WEST, VillagerProfession.FLETCHER, 4);
        var cable = CBBlocks.DATA_CABLE.get().defaultBlockState();
        var gap = left.controller().getBlockPos().east(2);
        for (int i = 1; i <= 3; i++) level.setBlock(left.controller().getBlockPos().east(i), cable, 3);
        var a = left.member(); var b = right.member();
        var computers = List.of(a, left.hidden(), b, right.hidden());
        var programs = computers.stream().map(ComputerBehaviour::flowSnapshot).toList();
        var hardware = computers.stream().map(ComputerBehaviour::hardware).toList();
        helper.assertTrue(!a.hardware().limits().hardware().equals(b.hardware().limits().hardware()),
            "Clusters cannot replace heterogeneous resident CPU/RAM budgets with pooled hardware");
        int interval = Math.toIntExact(Math.max(duration(a), duration(b)) + 4);
        helper.startSequence().thenIdle(4).thenExecute(() -> {
            helper.assertValueEqual(a.serialDevice().send("s0", RECORD), "queued", "Explicit left submission");
        }).thenIdle(2).thenExecute(() -> {
            helper.assertTrue(b.serialDevice().poll("s0").isEmpty(), "Two gateways do not make a partial frame instantaneous");
        }).thenIdle(interval).thenExecute(() -> {
            helper.assertValueEqual(b.serialDevice().status("s0").receive().frames(), 1, "Right member owns one complete frame");
            helper.assertValueEqual(b.serialDevice().send("s0", RECORD), "queued", "Right independently emits its reply");
        }).thenIdle(interval).thenExecute(() -> {
            helper.assertValueEqual(a.serialDevice().poll("s0").orElseThrow(), RECORD, "Reply follows the physical shared cable");
            helper.assertValueEqual(b.serialDevice().poll("s0").orElseThrow(), RECORD, "Sending did not consume the input");
            a.serialDevice().send("s0", RECORD); b.serialDevice().send("s0", RECORD);
        }).thenIdle(interval).thenExecute(() -> {
            helper.assertValueEqual(a.serialDevice().status("s0").transmit(), "collision", "No first-controller winner");
            helper.assertValueEqual(b.serialDevice().status("s0").transmit(), "collision", "Overlapping emissions both fail");
            helper.assertTrue(a.serialDevice().poll("s0").isEmpty() && b.serialDevice().poll("s0").isEmpty(), "Collisions produce no record");
            a.serialDevice().send("s0", RECORD);
        }).thenIdle(2).thenExecute(() -> {
            level.setBlock(gap, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        }).thenIdle(2).thenExecute(() -> {
            helper.assertValueEqual(a.serialDevice().status("s0").transmit(), "interrupted", "Breaking the actual cable cancels the old partial frame");
            level.setBlock(gap, cable, 3);
        }).thenIdle(interval).thenExecute(() -> {
            helper.assertTrue(b.serialDevice().poll("s0").isEmpty(), "Repair never silently retransmits the old frame");
            helper.assertValueEqual(a.serialDevice().send("s0", RECORD), "queued", "A new explicit send is allowed after repair");
        }).thenIdle(interval).thenExecute(() -> {
            helper.assertValueEqual(b.serialDevice().poll("s0").orElseThrow(), RECORD, "The repaired cable carries a newly requested frame");
            for (var cluster : List.of(left, right)) {
                helper.assertTrue(cluster.controller().enclosure().enclosure().isPresent(), "Neither Cluster was opened to connect it");
                helper.assertTrue(cluster.hidden().serialDevice().poll("s0").isEmpty(), "A touching member is not an automatically exposed receiver");
                helper.assertTrue(cluster.controller().routingState().orElseThrow().mailbox().queued().isEmpty(), "No controller mailbox substitute");
            }
            helper.assertValueEqual(computers.stream().map(ComputerBehaviour::flowSnapshot).toList(), programs, "Hardware traffic leaves each paused program and RAM independent");
            helper.assertValueEqual(computers.stream().map(ComputerBehaviour::hardware).toList(), hardware, "Resident hardware never aggregates across controllers");
            helper.assertTrue(computers.stream().allMatch(c -> c.advertisement().isEmpty()), "No legacy message endpoint required");
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void scratchIsaTxResumesItsPaidEncodingThroughActualGatewayAndLegacyReceiveOnce(GameTestHelper helper) {
        var level = helper.getLevel();
        var cluster = serialCluster(helper, new BlockPos(1, 1, 1), Direction.EAST, VillagerProfession.LIBRARIAN, 5);
        var externalPos = cluster.controller().getBlockPos().east(2);
        var receiver = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, externalPos, VillagerProfession.CLERIC, 5));
        receiver.configureSerial(new SerialControlData.Configuration("s0", Direction.WEST, true));
        level.setBlock(externalPos.west(), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        var selectedHardware = cluster.member().hardware();
        var hiddenHardware = cluster.hidden().hardware();
        var hiddenProgram = cluster.hidden().flowSnapshot();
        helper.assertTrue(!selectedHardware.limits().hardware().equals(hiddenHardware.limits().hardware()),
            "The explicit sender and hidden neighbour have different resident CPU/RAM budgets");
        var sender = new ComputerBehaviour[]{cluster.member()};
        var beforeTransmit = new String[]{"idle"};
        DataValue record = new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(37),
            "sample", new DataValue.TextValue("保存-你好")));
        DataValue nbt = EditorValueFormat.parse(EditorValueFormat.Kind.NBT, "{reading:37s,bytes:[B;1b,-2b]}");
        var sequence = helper.startSequence().thenIdle(4);
        for (var payload : List.of(record, nbt)) {
            boolean nativeNbt = payload instanceof DataValue.NbtValue;
            sequence.thenExecute(() -> {
                // The sender is source-bound Scratch/REGISTER, not a legacy graph installation.
                var inputs = new DataValue.RecordValue(Map.of("packet", payload));
                var program = new FlowProgram(Map.of("packet", com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.typeOf(payload)),
                    List.of(new FlowProgram.SerialSend("send", "s0", "packet", "sent"), new FlowProgram.SerialStatus("status", "s0", "view"))).withAssembly();
                var workspace = new ProgramWorkspace();
                workspace.setFlow(Optional.of(new FlowDocumentBody(program.inputs(), program.steps(), inputs,
                    FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
                beforeTransmit[0] = sender[0].serialDevice().status("s0").transmit();
                sender[0].installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET);
                // RX is an explicitly selected, compatible public legacy program. No automatic fallback.
                receiver.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.SerialReceive("receive", "s0", "received", nativeNbt))),
                    new DataValue.RecordValue(Map.of()));
                helper.assertTrue(program.usage(selectedHardware.limits().hardware()).programUnits() <= selectedHardware.limits().hardware().programUnits(),
                    "The unchanged actual resident admits the static ISA words");
                sender[0].step();
                var paused = sender[0].flowSnapshot().orElseThrow();
                var registers = paused.state().assembly().orElseThrow();
                helper.assertValueEqual(paused.program().execution(), FlowProgram.Execution.REGISTER_V1, "Actual installed backend is ISA");
                helper.assertValueEqual(paused.state().work(), 1L, "Single-step paid the real first encoding cycle");
                helper.assertValueEqual(registers.pc(), 0, "Encoding retains the actual send PC");
                helper.assertTrue(registers.serial().isPresent() && !registers.serial().orElseThrow().done(), "An actual unfinished encoding cursor is retained");
                helper.assertTrue(!paused.state().locals().fields().containsKey("sent"), "No partial send result is published");
                helper.assertValueEqual(sender[0].serialDevice().status("s0").transmit(), beforeTransmit[0], "Encoding does not occupy TX or replace previous physical metadata");
                helper.assertValueEqual(receiver.serialDevice().status("s0").receive().frames(), 0, "No half frame can reach the receiver");
                var entity = sender[0].blockEntity; var pos = entity.getBlockPos(); var state = entity.getBlockState();
                var save = entity.saveWithFullMetadata(level.registryAccess());
                level.removeBlockEntity(pos);
                var replacement = (ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos, state, save, level.registryAccess()));
                level.setBlockEntity(replacement);
                sender[0] = replacement.getBehaviour(ComputerBehaviour.TYPE);
            }).thenIdle(4).thenExecute(() -> {
                var paused = sender[0].flowSnapshot().orElseThrow();
                helper.assertValueEqual(paused.state().work(), 1L, "BE reconstruction does not claim free CPU progress");
                helper.assertValueEqual(paused.state().assembly().orElseThrow().pc(), 0, "The saved send PC is unchanged");
                helper.assertTrue(paused.state().paused() && paused.state().assembly().orElseThrow().serial().isPresent(), "Pause and paid encoding continuation survive reload");
                helper.assertValueEqual(paused.state().locals().field("packet"), payload, "Frozen source survives actual BE reload");
                helper.assertValueEqual(sender[0].hardware(), selectedHardware, "Reload never increases this resident's RAM/CPU/port capacity");
                helper.assertValueEqual(sender[0].serialDevice().status("s0").connection(), "ready", "Existing explicit controller gateway is physically available");
                helper.assertValueEqual(sender[0].serialDevice().status("s0").transmit(), beforeTransmit[0], "Load does not transmit the unfinished encoding");
                helper.assertValueEqual(receiver.serialDevice().status("s0").receive().frames(), 0, "No hidden frame during saved pause");
                sender[0].setPaused(false);
            }).thenWaitUntil(() -> {
                var saved = sender[0].flowSnapshot().orElseThrow();
                if (saved.state().assembly().orElseThrow().serial().isPresent()) {
                    helper.assertValueEqual(receiver.serialDevice().status("s0").receive().frames(), 0, "No receive frame before encoding/owner admission completes");
                }
                helper.assertValueEqual(saved.state().status(), FlowRuntime.Status.COMPLETE, "Normal world ticks finish the actual ISA program");
            }).thenWaitUntil(() -> {
                helper.assertValueEqual(receiver.serialDevice().status("s0").receive().frames(), 1, "Physical outer cable delivers exactly one complete frame");
            }).thenExecute(() -> {
                var completed = sender[0].flowSnapshot().orElseThrow();
                helper.assertValueEqual(completed.state().locals().field("sent"),
                    com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.send("queued"), "Local admission is distinct from physical transmission and business success");
                helper.assertValueEqual(completed.state().locals().field("packet"), payload, "Completed TX does not modify its frozen source");
                helper.assertTrue(completed.state().work() > 2, "Variable encoding used real local CPU cycles");
                helper.assertTrue(receiver.flowSnapshot().orElseThrow().state().paused(), "Physical RX cannot run the receiving program");
                receiver.step();
                helper.assertValueEqual(receiver.flowSnapshot().orElseThrow().state().locals().field("received"),
                    com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.receive("received", payload), "The explicit legacy public receive preserves this selected format");
                helper.assertValueEqual(receiver.serialDevice().status("s0").receive().frames(), 0, "Public receive consumes this one frame atomically");
            }).thenIdle(Math.toIntExact(duration(cluster.member()) + 4)).thenExecute(() -> {
                helper.assertValueEqual(receiver.serialDevice().status("s0").receive().frames(), 0, "Completed ISA TX never retries or emits a second frame");
                helper.assertValueEqual(cluster.hidden().flowSnapshot(), hiddenProgram, "Neither TX continuation nor physical gateway advances the hidden Computer");
                helper.assertValueEqual(cluster.hidden().hardware(), hiddenHardware, "Hidden resident, program RAM and local CPU budget remain independent and unchanged");
                helper.assertValueEqual(sender[0].hardware(), selectedHardware, "The transmitting resident retains the original finite budget");
                helper.assertTrue(cluster.controller().routingState().orElseThrow().mailbox().queued().isEmpty(), "Controller is not free queue/RAM for ISA encoding");
            });
        }
        sequence.thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void scratchIsaReceiveResumesRealPaidDecodeAcrossBlockRebuildAndClaimsOnce(GameTestHelper helper) {
        var level=helper.getLevel();
        var cluster=serialCluster(helper,new BlockPos(1,1,1),Direction.EAST,VillagerProfession.LIBRARIAN,5);
        var externalPos=cluster.controller().getBlockPos().east(2);
        var rx=new ComputerBehaviour[]{BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper,externalPos,VillagerProfession.LIBRARIAN,3))};
        rx[0].configureSerial(new SerialControlData.Configuration("s0",Direction.WEST,true));
        level.setBlock(externalPos.west(),CBBlocks.DATA_CABLE.get().defaultBlockState(),3);
        var sender=cluster.member();var senderHardware=sender.hardware();var receiverHardware=rx[0].hardware();
        var hiddenHardware=cluster.hidden().hardware();var hiddenProgram=cluster.hidden().flowSnapshot();
        helper.assertTrue(!senderHardware.limits().hardware().equals(receiverHardware.limits().hardware()),"Real two-sided ISA path keeps the different resident levels");
        var record=new DataValue.RecordValue(Map.of("reading",new DataValue.IntegerValue(37),"sample",new DataValue.TextValue("保存-你好")));
        var nbt=new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of("negativeZero",new DataValue.NbtValue(5,new DataValue.IntegerValue(Integer.MIN_VALUE)),"reading",new DataValue.NbtValue(2,new DataValue.IntegerValue(37)))));
        var sequence=helper.startSequence().thenIdle(4);
        for(DataValue payload:List.of(record,nbt)) {
            boolean nativeNbt=payload instanceof DataValue.NbtValue;
            var checkpoint=new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockHost.Saved[1];
            sequence.thenExecute(()->{
                var txProgram=new FlowProgram(Map.of("packet",com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.typeOf(payload)),List.of(new FlowProgram.SerialSend("send","s0","packet","sent"))).withAssembly();
                var rxProgram=new FlowProgram(Map.of(),List.of(new FlowProgram.SerialReceive("receive","s0","received",nativeNbt))).withAssembly();
                installScratchIsa(sender,txProgram,new DataValue.RecordValue(Map.of("packet",payload)));
                installScratchIsa(rx[0],rxProgram,new DataValue.RecordValue(Map.of()));
                helper.assertValueEqual(rx[0].flowSnapshot().orElseThrow().program().execution(),FlowProgram.Execution.REGISTER_V1,"Receiver uses the default Scratch native ISA, not a graph fallback");
                sender.setPaused(false);
            }).thenWaitUntil(()->helper.assertValueEqual(sender.flowSnapshot().orElseThrow().state().status(),FlowRuntime.Status.COMPLETE,"Real native TX finishes under its own CPU budget"))
            .thenWaitUntil(()->helper.assertValueEqual(rx[0].serialDevice().status("s0").receive().frames(),1,"Real gateway/cable/hardware delivered one finite frame"))
            .thenExecute(()->{
                helper.assertValueEqual(rx[0].flowSnapshot().orElseThrow().state().work(),0L,"Paused receiver has no hidden hardware CPU progress");
                for(int i=0;i<12;i++)rx[0].step();
                checkpoint[0]=rx[0].flowSnapshot().orElseThrow();
                var progress=checkpoint[0].state().assembly().orElseThrow().serial().orElseThrow();
                helper.assertTrue(progress.receiving()&&!progress.done()&&progress.paidWork()==12,"Mid-wire parsing is a real paid incomplete RX continuation");
                helper.assertTrue(!checkpoint[0].state().locals().fields().containsKey("received"),"No partial result register");
                helper.assertValueEqual(rx[0].serialDevice().status("s0").receive().frames(),1,"Before owner admission, the frame remains buffered");
                rx[0]=rebuildComputer(level,rx[0]);
            }).thenIdle(4).thenExecute(()->{
                rx[0].document();
                helper.assertValueEqual(rx[0].flowSnapshot().orElseThrow(),checkpoint[0],"Real lazy-load BE reconstruction preserves PC/task/paid decoder and no free work");
                helper.assertValueEqual(rx[0].serialDevice().status("s0").receive().frames(),1,"Reconstruction cannot claim the paused head");
                helper.assertValueEqual(rx[0].hardware(),receiverHardware,"Original receiver RAM, CPU and port specification remains finite");
                rx[0].setPaused(false);
            }).thenWaitUntil(()->helper.assertValueEqual(rx[0].flowSnapshot().orElseThrow().state().status(),FlowRuntime.Status.COMPLETE,"World ticks finish bounded native RX"))
            .thenExecute(()->{
                helper.assertValueEqual(rx[0].flowSnapshot().orElseThrow().state().locals().field("received"),com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.receive("received",payload),"Record/native tag widths and raw negative-zero bits survive the actual ISA round trip");
                helper.assertValueEqual(rx[0].serialDevice().status("s0").receive().frames(),0,"Queue and result/PC were claimed exactly once");
                rx[0].setPaused(true);checkpoint[0]=rx[0].flowSnapshot().orElseThrow();rx[0]=rebuildComputer(level,rx[0]);
            }).thenIdle(4).thenExecute(()->{
                rx[0].document();helper.assertValueEqual(rx[0].flowSnapshot().orElseThrow(),checkpoint[0],"After-claim restore never repeats RX");
                helper.assertValueEqual(rx[0].serialDevice().status("s0").receive().frames(),0,"No replayed frame after completed continuation reload");
                helper.assertValueEqual(sender.hardware(),senderHardware,"No sender resource increase");helper.assertValueEqual(rx[0].hardware(),receiverHardware,"No receiver resource increase");
                helper.assertValueEqual(cluster.hidden().hardware(),hiddenHardware,"Hidden member resources are not pooled");helper.assertValueEqual(cluster.hidden().flowSnapshot(),hiddenProgram,"Hidden member never runs another member's codec");
                helper.assertTrue(cluster.controller().routingState().orElseThrow().mailbox().queued().isEmpty(),"Gateway is not free program RAM/result buffering");
            });
        }
        sequence.thenSucceed();
    }
    private static void installScratchIsa(ComputerBehaviour computer,FlowProgram program,DataValue.RecordValue inputs) {
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(new FlowDocumentBody(program.inputs(),program.steps(),inputs,FlowBlockLayout.DEFAULT,Map.of(),FlowProgram.Execution.REGISTER_V1)));
        computer.installDocument(workspace.snapshot(),ComputerRuntime.MemoryPolicy.RESET);
    }
    private static ComputerBehaviour rebuildComputer(net.minecraft.server.level.ServerLevel level,ComputerBehaviour computer) {
        var entity=computer.blockEntity;var pos=entity.getBlockPos();var state=entity.getBlockState();var save=entity.saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(pos);var replacement=(ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos,state,save,level.registryAccess()));level.setBlockEntity(replacement);return replacement.getBehaviour(ComputerBehaviour.TYPE);
    }

    private record SerialCluster(ClusterControllerBlockEntity controller, ComputerBehaviour member, ComputerBehaviour hidden) {}
    private static SerialCluster serialCluster(GameTestHelper helper, BlockPos relativeMin, Direction outward,
                                                VillagerProfession profession, int level) {
        return serialClusterAt(helper, helper.absolutePos(relativeMin), outward, profession, level);
    }
    private static SerialCluster serialClusterAt(GameTestHelper helper, BlockPos min, Direction outward,
                                                VillagerProfession profession, int level) {
        var world = helper.getLevel(); var max = min.offset(4, 3, 4);
        var controlPos = min.offset(outward == Direction.EAST ? 4 : 0, 1, 1);
        var memberPos = controlPos.relative(outward.getOpposite());
        var member = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, memberPos, profession, level));
        var hidden = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, memberPos.south(2), VillagerProfession.CLERIC, 3));
        for (var pos : BlockPos.betweenClosed(min, max))
            if (pos.getX() == min.getX() || pos.getX() == max.getX() || pos.getY() == min.getY() || pos.getY() == max.getY()
                || pos.getZ() == min.getZ() || pos.getZ() == max.getZ()) world.setBlock(pos, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), 3);
        world.setBlock(controlPos, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING, outward), 3);
        var controller = (ClusterControllerBlockEntity)world.getBlockEntity(controlPos);
        var player = new net.neoforged.neoforge.common.util.FakePlayer(world, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "serial-pair"));
        player.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(controlPos.relative(outward)));
        var access = new com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess(controller, member.blockEntity);
        var binding = com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialGatewayAccess.authorize(access, player, "s0", "data");
        member.configureSerialGateway("s0", Optional.of(binding)); member.configureSerial(new SerialControlData.Configuration("s0", outward, true));
        hidden.configureSerial(new SerialControlData.Configuration("s0", outward, true));
        for (var computer : List.of(member, hidden)) computer.installFlow(new FlowProgram(Map.of(), List.of()), new DataValue.RecordValue(Map.of()));
        return new SerialCluster(controller, member, hidden);
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void sealedClusterExportsOnlySelectedResidentSerialThroughActualOuterCable(GameTestHelper helper) {
        var level = helper.getLevel(); var min = helper.absolutePos(new BlockPos(4, 1, 1)); var max = min.offset(4, 3, 4);
        var controlPos = min.offset(0, 1, 1); var memberPos = controlPos.east(); var otherPos = memberPos.offset(2, 0, 2);
        var member = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, memberPos, VillagerProfession.NONE, 1));
        var other = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, otherPos, VillagerProfession.LIBRARIAN, 2));
        var external = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, controlPos.west(2), VillagerProfession.FLETCHER, 3));
        for (var pos : BlockPos.betweenClosed(min, max))
            if (pos.getX() == min.getX() || pos.getX() == max.getX() || pos.getY() == min.getY() || pos.getY() == max.getY()
                || pos.getZ() == min.getZ() || pos.getZ() == max.getZ()) level.setBlock(pos, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), 3);
        level.setBlock(controlPos, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING, Direction.WEST), 3);
        level.setBlock(controlPos.west(), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        var controller = (ClusterControllerBlockEntity)level.getBlockEntity(controlPos);
        var player = new net.neoforged.neoforge.common.util.FakePlayer(level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "serial-gateway"));
        player.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(controlPos.west()));
        var access = new com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess(controller, member.blockEntity);
        var binding = com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialGatewayAccess.authorize(access, player, "s0", "sensor");
        member.configureSerialGateway("s0", Optional.of(binding)); member.configureSerial(new SerialControlData.Configuration("s0", Direction.WEST, true));
        external.configureSerial(new SerialControlData.Configuration("s0", Direction.EAST, true));
        other.configureSerial(new SerialControlData.Configuration("s0", Direction.WEST, true));
        for (var computer : List.of(member, other, external)) computer.installFlow(new FlowProgram(Map.of(), List.of()), new DataValue.RecordValue(Map.of()));
        var programs = List.of(member.flowSnapshot(), other.flowSnapshot(), external.flowSnapshot());
        var hardware = List.of(member.hardware(), other.hardware(), external.hardware());
        helper.assertTrue(controller.enclosure().enclosure().orElseThrow().members().containsAll(Set.of(memberPos, otherPos)), "Air and shell-contacting members retain geometric membership");
        helper.runAfterDelay(4, () -> {
            external.serialDevice().send("s0", RECORD);
            helper.runAfterDelay(duration(external) + 4, () -> {
                helper.assertValueEqual(member.serialDevice().poll("s0").orElseThrow(), RECORD, "Outer cable reaches only explicitly routed member hardware");
                helper.assertTrue(other.serialDevice().poll("s0").isEmpty(), "Geometric membership is not an automatic interface grant");
                helper.assertValueEqual(member.serialDevice().send("s0", RECORD), "queued", "Selected member can reply over the same finite interface");
                helper.runAfterDelay(duration(member) + 4, () -> {
                    helper.assertValueEqual(external.serialDevice().poll("s0").orElseThrow(), RECORD, "Concentrated output follows physical cable");
                    helper.assertValueEqual(List.of(member.flowSnapshot(), other.flowSnapshot(), external.flowSnapshot()), programs, "Gateway does not execute or pool member programs");
                    helper.assertValueEqual(List.of(member.hardware(), other.hardware(), external.hardware()), hardware, "Resident budgets remain independent");
                    helper.assertTrue(member.advertisement().isEmpty() && external.advertisement().isEmpty(), "No old message endpoint required");
                    helper.assertTrue(controller.routingState().orElseThrow().mailbox().queued().isEmpty(), "Controller never buffers a serial frame");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void packagedLocalConverterUsesSeparateResidentBudgetsAndActualClusterXBusThenCable(GameTestHelper helper) {
        var level = helper.getLevel(); var min = helper.absolutePos(new BlockPos(2, 1, 1)); var max = min.offset(6, 3, 4);
        var controlPos = min.offset(0, 1, 1); var sourcePos = controlPos.east();
        var converterPos = sourcePos.east(2); var receiverPos = converterPos.east(2);
        var source = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, sourcePos, VillagerProfession.FLETCHER, 2));
        var converter = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, converterPos, VillagerProfession.NONE, 1));
        var receiver = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, receiverPos, VillagerProfession.CLERIC, 5));
        for (var pos : BlockPos.betweenClosed(min, max))
            if (pos.getX() == min.getX() || pos.getX() == max.getX() || pos.getY() == min.getY() || pos.getY() == max.getY()
                || pos.getZ() == min.getZ() || pos.getZ() == max.getZ())
                level.setBlock(pos, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), 3);
        level.setBlock(controlPos, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING, Direction.WEST), 3);
        level.setBlock(converterPos.east(), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        var controller = (ClusterControllerBlockEntity)level.getBlockEntity(controlPos);
        var computers = Map.of("source", source, "converter", converter, "receiver", receiver);
        for (var role : computers.entrySet()) {
            var document = serialExample(role.getKey());
            role.getValue().installDocument(document, ComputerRuntime.MemoryPolicy.RESET);
            helper.assertValueEqual(role.getValue().document().orElseThrow(), document, "Install packaged ordinary document with layouts");
        }
        source.configureEndpoint(new EndpointConfiguration("left", true)); converter.configureEndpoint(new EndpointConfiguration("right", true));
        converter.serialDevice().configure("s0", Direction.EAST, true); receiver.serialDevice().configure("s0", Direction.WEST, true);
        var members = new LinkedHashMap<String, ClusterConfiguration.Member>();
        computers.forEach((name, computer) -> members.put(name,
            new ClusterConfiguration.Member(computer.communicationIdentity().orElseThrow(), computer.blockEntity.getBlockPos())));
        var player = new net.neoforged.neoforge.common.util.FakePlayer(level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "serial-converter"));
        player.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(controlPos.west()));
        var wire = new XBusWire(new XBusWire.Pin("source", "left", "x0"), new XBusWire.Pin("converter", "right", "x1"), true);
        helper.assertValueEqual(controller.configure(new ClusterConfiguration(members, Map.of()), player), "configured", "Bind geometric members before wiring");
        helper.assertValueEqual(controller.configure(new ClusterConfiguration(members, Map.of(), Map.of(), Map.of("data", wire)), player),
            "configured", "Only the explicitly configured local XBus wire is enabled");
        helper.assertTrue(controller.enclosure().enclosure().isPresent(), "Closed rectangular casing contains air and the three touching members");
        var hardware = computers.values().stream().map(c -> c.hardware().limits().hardware()).toList();
        helper.assertValueEqual(new HashSet<>(hardware).size(), 3, "Resident profession and level give independent hardware");
        var pausedConverter = converter.flowSnapshot(); source.setPaused(false); receiver.setPaused(false);
        helper.runAfterDelay(25, () -> {
            helper.assertValueEqual(converter.flowSnapshot(), pausedConverter, "Source retry does not execute the paused converter");
            helper.assertValueEqual(converter.serialDevice().status("s0").transmit(), "idle", "Paused-peer failures do not emit a frame");
            converter.setPaused(false);
            helper.runAfterDelay(150, () -> {
                for (var computer : computers.values()) helper.assertValueEqual(computer.flowSnapshot().orElseThrow().state().status(),
                    FlowRuntime.Status.COMPLETE, "Actual normal world ticks finish each independently owned program");
                var packet = serialExample("source").flow().orElseThrow().initial().field("packet");
                helper.assertValueEqual(converter.flowSnapshot().orElseThrow().state().locals().field("sent"),
                    com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.send("queued"), "Local instruction result is not an ACK");
                helper.assertValueEqual(receiver.flowSnapshot().orElseThrow().state().locals().field("received"),
                    com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.receive("received", (DataValue.RecordValue)packet),
                    "Actual local XBus then real cable preserve the typed record");
                helper.assertValueEqual(receiver.serialDevice().status("s0").receive().frames(), 0, "One complete frame consumed once");
                helper.assertValueEqual(computers.values().stream().map(c -> c.hardware().limits().hardware()).toList(), hardware, "Cluster never pools hardware");
                helper.assertTrue(controller.routingState().orElseThrow().mailbox().queued().isEmpty(), "No legacy buffered message protocol");
                helper.succeed();
            });
        });
    }

    private static ProgramDocument serialExample(String role) {
        java.util.function.Function<net.minecraft.resources.ResourceLocation, java.io.InputStream> open = location ->
            BiocomputingSerialGameTests.class.getClassLoader().getResourceAsStream("assets/" + location.getNamespace() + "/" + location.getPath());
        var example = ProgramExampleCatalog.load(open).stream().filter(e -> e.id().equals("serial_bridge_" + role)).findFirst().orElseThrow();
        return ProgramExampleCatalog.preview(example, open, new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(10000, 100000, 1000000));
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void publicFlowOperationsUseRealResidentInterfacesAndReloadBothContinuationAndQueue(GameTestHelper helper) {
        publicFrameRoundTrip(helper, false);
    }
    @GameTest(template = "empty", timeoutTicks = 240)
    public static void nativeNbtFlowsPreserveTagKindsOnRealCableAndReloadWithoutReconsumption(GameTestHelper helper) {
        publicFrameRoundTrip(helper, true);
    }
    private static void publicFrameRoundTrip(GameTestHelper helper, boolean nativeNbt) {
        DataValue payload = nativeNbt ? EditorValueFormat.parse(EditorValueFormat.Kind.NBT,
            "{reading:37s,bytes:[B;1b,-2b],ints:[I;3,4],longs:[L;5L],sample:\"" + "a".repeat(256) + "\"}") : RECORD;
        var aPos = helper.absolutePos(new BlockPos(2, 1, 2)); var bPos = aPos.east(2); var level = helper.getLevel();
        var aBlock = BiocomputingWorldGameTests.computer(helper, aPos, VillagerProfession.NONE, 1);
        var bBlock = BiocomputingWorldGameTests.computer(helper, bPos, VillagerProfession.CLERIC, 5);
        var a = aBlock.getBehaviour(ComputerBehaviour.TYPE); var b = bBlock.getBehaviour(ComputerBehaviour.TYPE);
        level.setBlock(aPos.east(), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        a.serialDevice().configure("s0", Direction.EAST, true); b.serialDevice().configure("s0", Direction.WEST, true);
        a.installFlow(new FlowProgram(Map.of("packet", com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.typeOf(payload)),
            List.of(new FlowProgram.SerialSend("send", "s0", "packet", "sent"))), new DataValue.RecordValue(Map.of("packet", payload)));
        b.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.SerialReceive("receive", "s0", "received", nativeNbt))), new DataValue.RecordValue(Map.of()));
        var pausedReceiver = b.flowSnapshot(); a.setPaused(false);
        helper.runAfterDelay(4, () -> {
            helper.assertValueEqual(a.flowSnapshot().orElseThrow().state().locals().field("sent"),
                com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.send("queued"), "Public instruction submits only a local frame");
            helper.assertValueEqual(b.serialDevice().status("s0").receive().frames(), 0, "Program completion is not physical delivery");
            helper.runAfterDelay(duration(a) + 4, () -> {
                helper.assertValueEqual(b.flowSnapshot(), pausedReceiver, "Physical reception does not run the paused program");
                helper.assertValueEqual(b.serialDevice().status("s0").receive().frames(), 1, "Ordinary villager emitted a full frame over real cable");
                b.step();
                helper.assertValueEqual(b.flowSnapshot().orElseThrow().state().locals().field("received"),
                    com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.receive("received", payload), "Public receive instruction preserves the explicitly selected payload type");
                helper.assertValueEqual(b.serialDevice().status("s0").receive().frames(), 0, "Exactly one local receive is published");
                var state = bBlock.getBlockState(); var save = bBlock.saveWithFullMetadata(level.registryAccess());
                var continuation = b.flowSnapshot(); level.removeBlockEntity(bPos);
                var restored = (ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(bPos, state, save, level.registryAccess()));
                level.setBlockEntity(restored);
                helper.runAfterDelay(4, () -> {
                    var loaded = restored.getBehaviour(ComputerBehaviour.TYPE);
                    helper.assertValueEqual(loaded.flowSnapshot(), continuation, "Result and cursor share the same block save as the consumed queue");
                    helper.assertValueEqual(loaded.serialDevice().status("s0").receive().frames(), 0, "Reload cannot reconsume the same frame");
                    helper.assertTrue(a.advertisement().isEmpty() && loaded.advertisement().isEmpty(), "No old message endpoint bypass");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void ordinaryResidentsTransmitOnRealCableWithoutLegacyMessagePortsOrCpuProgress(GameTestHelper helper) {
        var aPos = helper.absolutePos(new BlockPos(2, 1, 2)); var bPos = aPos.east(2);
        var a = BiocomputingWorldGameTests.computer(helper, aPos, VillagerProfession.NONE, 1).getBehaviour(ComputerBehaviour.TYPE);
        var b = BiocomputingWorldGameTests.computer(helper, bPos, VillagerProfession.CLERIC, 5).getBehaviour(ComputerBehaviour.TYPE);
        helper.getLevel().setBlock(aPos.east(), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        for (var computer : List.of(a, b)) computer.installFlow(new FlowProgram(Map.of(), List.of()), new DataValue.RecordValue(Map.of()));
        var beforeA = a.flowSnapshot(); var beforeB = b.flowSnapshot();
        helper.assertTrue(!a.hardware().limits().hardware().equals(b.hardware().limits().hardware()), "Real residents retain heterogeneous CPU/RAM specifications");
        a.serialDevice().configure("s0", Direction.EAST, true); b.serialDevice().configure("s0", Direction.WEST, true);
        helper.assertTrue(a.advertisement().isEmpty() && b.advertisement().isEmpty(), "No old message endpoint was opened");
        helper.runAfterDelay(4, () -> {
            helper.assertValueEqual(a.serialDevice().send("s0", RECORD), "queued", "Explicit local hardware submission");
            helper.runAfterDelay(2, () -> helper.assertTrue(b.serialDevice().poll("s0").isEmpty(), "A partial physical frame is not a record"));
            helper.runAfterDelay(duration(a) + 4, () -> {
                helper.assertValueEqual(a.serialDevice().status("s0").transmit(), "transmitted", "World event advances the medium, not a test driver");
                helper.assertValueEqual(b.serialDevice().poll("s0").orElseThrow(), RECORD, "Complete record arrives through actual cable");
                helper.assertValueEqual(a.flowSnapshot(), beforeA, "Paused sender program/RAM/clock/budget unchanged");
                helper.assertValueEqual(b.flowSnapshot(), beforeB, "Paused receiver program/RAM/clock/budget unchanged");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void realBlockReloadRetainsCompleteReceiveButNeverResendsTheInterruptedFrame(GameTestHelper helper) {
        var aPos = helper.absolutePos(new BlockPos(2, 1, 2)); var bPos = aPos.east(2); var level = helper.getLevel();
        var aBlock = BiocomputingWorldGameTests.computer(helper, aPos); var bBlock = BiocomputingWorldGameTests.computer(helper, bPos);
        var a = aBlock.getBehaviour(ComputerBehaviour.TYPE); var b = bBlock.getBehaviour(ComputerBehaviour.TYPE);
        level.setBlock(aPos.east(), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        a.serialDevice().configure("s0", Direction.EAST, true); b.serialDevice().configure("s0", Direction.WEST, true);
        helper.runAfterDelay(4, () -> {
            a.serialDevice().send("s0", RECORD);
            helper.runAfterDelay(duration(a) + 4, () -> {
                helper.assertValueEqual(b.serialDevice().status("s0").receive().frames(), 1, "First complete frame retained");
                a.serialDevice().send("s0", RECORD);
                helper.runAfterDelay(2, () -> {
                    var aSaved = aBlock.saveWithFullMetadata(level.registryAccess()); var bSaved = bBlock.saveWithFullMetadata(level.registryAccess());
                    var aState = aBlock.getBlockState(); var bState = bBlock.getBlockState();
                    level.removeBlockEntity(aPos); level.removeBlockEntity(bPos);
                    var aNew = (ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(aPos, aState, aSaved, level.registryAccess()));
                    var bNew = (ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(bPos, bState, bSaved, level.registryAccess()));
                    level.setBlockEntity(aNew); level.setBlockEntity(bNew);
                    helper.runAfterDelay(duration(a) + 5, () -> {
                        var restoredA = aNew.getBehaviour(ComputerBehaviour.TYPE).serialDevice();
                        var restoredB = bNew.getBehaviour(ComputerBehaviour.TYPE).serialDevice();
                        helper.assertValueEqual(restoredA.status("s0").transmit(), "interrupted", "No implicit retry of a partial frame");
                        helper.assertValueEqual(restoredB.status("s0").receive().frames(), 1, "Completed frame belongs to the receiver, not the cable");
                        helper.assertValueEqual(restoredB.poll("s0").orElseThrow(), RECORD, "Saved typed record remains intact");
                        helper.assertTrue(restoredB.poll("s0").isEmpty(), "No duplicate created during reload");
                        helper.succeed();
                    });
                });
            });
        });
    }
    private static long duration(ComputerBehaviour computer) {
        var config = CBConfigs.SERVER.biocomputing; boolean loaded = CBConfigs.SERVER_SPEC.isLoaded();
        var limits = config.serialMedium(loaded); var hardware = computer.hardware().limits().serial().ports().get("s0");
        long bits = SerialRecordCodec.encode(RECORD, config.serialCodec(loaded)).length * 8L + limits.framingBits();
        long rate = Math.min(hardware.bitsPerTick(), limits.bitsPerStep());
        return bits / rate + (bits % rate == 0 ? 0 : 1);
    }
}
