package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterState;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.EnclosureScanner;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControlRequest;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControlSession;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControlWire;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControllerMenu;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageProtocol;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.RequestCorrelation;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationEndpoint;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.network.PeerDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Two real enclosures and residents; only normal server ticks carry the request and reply. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingClusterPairGameTests {
    private static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());
    private static final DataValue.RecordValue PAYLOAD = new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(21)));

    // Catches lost return-path correlation, private endpoint leakage, premature execution, and replay after ACK/reload.
    @GameTest(template = "empty", timeoutTicks = 180)
    public static void twoClustersRetainAQueuedReplyAcrossExternalCableCutAndPhysicalReload(GameTestHelper helper) {
        var level = helper.getLevel();
        // empty.nbt is 20 x 12 x 12. Every placed block (including Computer support/upper halves)
        // is inside x=2..11, y=1..4, z=3..5; no neighboring GameTest structure is touched.
        var a = enclosure(helper, new BlockPos(2, 1, 3), Direction.EAST);
        var b = enclosure(helper, new BlockPos(9, 1, 3), Direction.WEST);
        for (int x = 5; x <= 8; x++)
            level.setBlock(helper.absolutePos(new BlockPos(x, 2, 4)), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
        var cut = helper.absolutePos(new BlockPos(7, 2, 4));

        helper.assertValueEqual(a.member().install(caller(), ComputerRuntime.MemoryPolicy.RESET), ComputerRuntime.InstallResult.APPLIED,
            "Install the caller program in real resident A");
        helper.assertValueEqual(b.member().install(worker(), ComputerRuntime.MemoryPolicy.RESET), ComputerRuntime.InstallResult.APPLIED,
            "Install the worker program in real resident B");
        a.member().configureEndpoint(new EndpointConfiguration("caller", true));
        b.member().configureEndpoint(new EndpointConfiguration("worker", true));
        var aId = a.member().communicationIdentity().orElseThrow();
        var bId = b.member().communicationIdentity().orElseThrow();
        helper.assertValueEqual(Set.of(aId, bId, a.controller().controllerId(), b.controller().controllerId()).size(), 4,
            "Each Computer and controller has its own identity");

        var aConfig = new ClusterConfiguration(Map.of("private_a", new ClusterConfiguration.Member(aId, a.memberBlock().getBlockPos())),
            Map.of("caller_public", new ClusterConfiguration.Port("private_a", "caller", "query", "query", true)));
        var bConfig = new ClusterConfiguration(Map.of("private_b", new ClusterConfiguration.Member(bId, b.memberBlock().getBlockPos())),
            Map.of("query", new ClusterConfiguration.Port("private_b", "worker", "return", "caller_public", true)));
        var aPlayer = configureThroughMenu(helper, a.controller(), aConfig, "pair-a");
        var bPlayer = configureThroughMenu(helper, b.controller(), bConfig, "pair-b");
        helper.assertValueEqual(a.member().clusterAttachment().orElseThrow().outputs(), Set.of("query"), "A reserves only its explicit output");
        helper.assertValueEqual(b.member().clusterAttachment().orElseThrow().outputs(), Set.of("return"), "B reserves only its explicit output");
        assertPublicPeer(helper, a.controller(), aPlayer, Direction.EAST, "query", b.controller().getBlockPos());
        assertPublicPeer(helper, b.controller(), bPlayer, Direction.WEST, "caller_public", a.controller().getBlockPos());

        b.member().setPaused(true);
        a.member().begin(EMPTY);
        helper.runAfterDelay(20, () -> {
            helper.assertValueEqual(b.member().snapshot().orElseThrow().mailbox().queued().size(), 1,
                "Request deadline: real A -> controller A -> cable -> controller B must reach paused B");
            assertMemory(helper, a.member(), 0, 0, 101, "A waits for its original reply");
            assertMemory(helper, b.member(), 0, 0, 202, "Admission into paused B must not execute or commit RAM");
            var aState = a.member().snapshot().orElseThrow();
            helper.assertValueEqual(aState.rpc().pending().keySet(), Set.of("lookup"), "Only the original caller owns a pending RPC slot");
            var correlation = aState.rpc().pending().get("lookup").correlation();
            helper.assertValueEqual(correlation.requester(), aId, "The requester is A, not either gateway");
            helper.assertValueEqual(correlation.eventId(), aState.delivery().orElseThrow().eventId(), "Keep the original request event identity");
            helper.assertValueEqual(aState.delivery().orElseThrow().acknowledged(), 1, "A handed off its request before the cable is cut");
            var request = b.member().snapshot().orElseThrow().mailbox().queued().getFirst();
            helper.assertValueEqual(request.source(), aId, "Both gateways preserve the original source UUID");
            helper.assertValueEqual(request.sequence(), aState.delivery().orElseThrow().sequenceBase(), "Both gateways preserve A's original sequence");
            helper.assertValueEqual(request.eventId(), correlation.eventId(), "Both gateways preserve the original envelope event UUID");
            helper.assertValueEqual(request.ordinal(), correlation.ordinal(), "Both gateways preserve the original request ordinal");
            helper.assertValueEqual(request.payload(), PAYLOAD, "The program receives only its typed application value");
            helper.assertTrue(request.protocol() instanceof MessageProtocol.Request, "The real mailbox contains a request, not a plain message");
            var context = ((MessageProtocol.Request) request.protocol()).context();
            helper.assertValueEqual(context.correlation(), correlation, "B receives exactly A's original correlation");
            helper.assertValueEqual(context.returnEndpoint(), "return", "B uses the system return endpoint, not a private caller name");
            helper.assertValueEqual(a.controller().routingState().orElseThrow().pending(), Map.of(correlation,
                new ClusterState.ReturnPath(new ClusterState.Ingress(ClusterState.Side.EXTERNAL, "caller_public"),
                    new ClusterState.Target(ClusterState.Side.INTERNAL, "caller", Optional.of(aConfig.members().get("private_a"))), Optional.empty())),
                "A controller freezes the public reply back to its original member");
            helper.assertValueEqual(b.controller().routingState().orElseThrow().pending(), Map.of(correlation,
                new ClusterState.ReturnPath(new ClusterState.Ingress(ClusterState.Side.INTERNAL, "return"),
                    new ClusterState.Target(ClusterState.Side.EXTERNAL, "caller_public", Optional.empty()), Optional.of(bId))),
                "B controller freezes the reply to A's public name without exposing caller");

            level.setBlock(cut, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            assertNoPeer(helper, a.controller(), aPlayer, Direction.EAST);
            assertNoPeer(helper, b.controller(), bPlayer, Direction.WEST);
            b.member().setPaused(false);
            helper.runAfterDelay(20, () -> {
                assertMemory(helper, b.member(), 21, 1, 202, "B independently computes one reply after unpausing");
                assertMemory(helper, a.member(), 0, 0, 101, "A must not receive across a cut external cable");
                helper.assertValueEqual(a.controller().enclosure().status(), EnclosureScanner.Status.READY, "Cable cut must not break A's shell");
                helper.assertValueEqual(b.controller().enclosure().status(), EnclosureScanner.Status.READY, "Cable cut must not break B's shell");
                var queued = b.controller().routingState().orElseThrow();
                helper.assertValueEqual(queued.mailbox().queued().size(), 1,
                    "Reply deadline: B controller, not merely B Computer, must durably own the blocked reply");
                helper.assertValueEqual(queued.forwards().size(), 1, "The blocked reply has one persisted routing entry");
                helper.assertTrue(queued.pending().isEmpty(), "B consumed its return correlation when it accepted the reply");
                var outgoing = queued.outbound().orElseThrow();
                helper.assertValueEqual(outgoing.target(), new ClusterState.Target(ClusterState.Side.EXTERNAL, "caller_public", Optional.empty()),
                    "The queued egress is A's public endpoint");
                helper.assertValueEqual(outgoing.envelope().source(), bId, "The reply source remains the actual worker");
                helper.assertValueEqual(outgoing.envelope().eventId(), b.member().snapshot().orElseThrow().delivery().orElseThrow().eventId(),
                    "The controller retains B's reply event identity");
                helper.assertValueEqual(outgoing.envelope().sequence(), b.member().snapshot().orElseThrow().delivery().orElseThrow().sequenceBase(),
                    "The controller retains B's original reply sequence");
                helper.assertValueEqual(outgoing.envelope().protocol(), new MessageProtocol.Reply(correlation), "The queued reply retains the original correlation");
                helper.assertValueEqual(outgoing.envelope().payload(), PAYLOAD, "The queued reply preserves the application value");
                helper.assertValueEqual(b.member().snapshot().orElseThrow().delivery().orElseThrow().acknowledged(), 1,
                    "B Computer acknowledges only after the controller owns the reply");
                helper.assertValueEqual(b.controller().diagnosticCode(), "not_found", "Real external transport attempted the disconnected route");
                helper.assertTrue(a.controller().routingState().orElseThrow().pending().containsKey(correlation), "A's frozen return path still waits");

                var restoredA = reload(helper, a);
                var restoredB = reload(helper, b);
                helper.runAfterDelay(12, () -> {
                    helper.assertValueEqual(restoredB.controller().routingState().orElseThrow().outbound(), Optional.of(outgoing),
                        "Reload deadline: disconnected ticks preserve the same reply envelope and target, not a replacement event");
                    assertMemory(helper, restoredA.member(), 0, 0, 101, "Reload does not bypass the still missing cable");
                    assertMemory(helper, restoredB.member(), 21, 1, 202, "Reload does not rerun B while its controller retries");
                    level.setBlock(cut, CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
                    assertPublicPeer(helper, restoredA.controller(), aPlayer, Direction.EAST, "query", restoredB.controller().getBlockPos());
                    assertPublicPeer(helper, restoredB.controller(), bPlayer, Direction.WEST, "caller_public", restoredA.controller().getBlockPos());
                    helper.runAfterDelay(32, () -> {
                        assertCompleted(helper, restoredA, restoredB, correlation, "Reconnect deadline");
                        var completedA = reload(helper, restoredA);
                        var completedB = reload(helper, restoredB);
                        helper.runAfterDelay(20, () -> {
                            assertCompleted(helper, completedA, completedB, correlation, "Post-ACK reload deadline: completed events must not replay");
                            helper.succeed();
                        });
                    });
                });
            });
        });
    }

    private record Fixture(ClusterControllerBlockEntity controller, ComputerBlockEntity memberBlock) {
        ComputerBehaviour member() { return BiocomputingWorldGameTests.host(memberBlock); }
    }

    private static Fixture enclosure(GameTestHelper helper, BlockPos relativeMin, Direction outward) {
        var level = helper.getLevel();
        var min = helper.absolutePos(relativeMin);
        var max = min.offset(2, 3, 2);
        var memberBlock = BiocomputingWorldGameTests.computer(helper, min.offset(1, 1, 1));
        // Restore the complete bottom shell after the real resident fixture places its support.
        for (var pos : BlockPos.betweenClosed(min, max))
            if (pos.getX() == min.getX() || pos.getX() == max.getX() || pos.getY() == min.getY() || pos.getY() == max.getY()
                || pos.getZ() == min.getZ() || pos.getZ() == max.getZ())
                level.setBlock(pos, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), Block.UPDATE_ALL);
        var controllerPos = memberBlock.getBlockPos().relative(outward);
        level.setBlock(controllerPos, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING, outward), Block.UPDATE_ALL);
        var controller = (ClusterControllerBlockEntity) Objects.requireNonNull(level.getBlockEntity(controllerPos));
        helper.assertTrue(controller.getType() == CBBlockEntityTypes.CLUSTER_CONTROLLER.get(), "Use the registered physical controller");
        helper.assertTrue(memberBlock.getType() == CBBlockEntityTypes.COMPUTER.get(), "Use the registered two-block resident Computer");
        var geometry = controller.enclosure();
        helper.assertValueEqual(geometry.status(), EnclosureScanner.Status.READY, "Each embedded controller completes its own closed shell");
        helper.assertValueEqual(geometry.enclosure().orElseThrow().contacts().keySet(), Set.of(memberBlock.getBlockPos()),
            "The independent shell encloses exactly its own Computer");
        helper.assertTrue(!geometry.enclosure().orElseThrow().contacts().get(memberBlock.getBlockPos()).isEmpty(), "The Computer contacts its own shell");
        for (var pos : BlockPos.betweenClosed(min, max))
            helper.assertTrue(!level.getBlockState(pos).is(CBBlocks.DATA_CABLE.get()), "No internal cable substitutes for explicit membership");
        for (var face : Direction.values())
            helper.assertTrue((level.getCapability(CommunicationEndpoint.CAPABILITY, controllerPos, face) == controller) == (face == outward),
                "The embedded controller exposes only its outward physical face");
        helper.assertTrue(level.getCapability(CommunicationEndpoint.CAPABILITY, controllerPos, null) == null, "No unsided capability bridges the shell");
        return new Fixture(controller, memberBlock);
    }

    static FakePlayer configureThroughMenu(GameTestHelper helper, ClusterControllerBlockEntity controller,
        ClusterConfiguration configuration, String playerName) {
        var level = helper.getLevel();
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), playerName));
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.moveTo(Vec3.atCenterOf(controller.getBlockPos().relative(controller.getBlockState().getValue(ClusterControllerBlock.FACING))));
        var provider = ClusterControllerMenu.provider(controller);
        var menu = (ClusterControllerMenu) provider.createMenu(1, player.getInventory(), player);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        UUID nonce;
        try { provider.writeOpeningData(buffer); nonce = buffer.readUUID(); }
        finally { buffer.release(); }
        player.containerMenu = menu;
        var limits = CBConfigs.SERVER.biocomputing.cluster(CBConfigs.SERVER_SPEC.isLoaded());
        new ClusterControlRequest(1, nonce, 1, controller.configurationRevision(), ClusterControlSession.Action.CONFIGURE,
            ClusterControlWire.encodeConfiguration(configuration, limits.archive())).handle(player);
        helper.assertValueEqual(controller.routingState().orElseThrow().configuration(), configuration,
            "The current physical menu's authorized public configuration packet binds the member and named port");
        helper.assertValueEqual(controller.advertisements(), configuration.ports().keySet().stream().map(name -> new EndpointConfiguration(name, true)).toList(),
            "Only explicitly configured public ports are advertised");
        return player;
    }

    private static void assertPublicPeer(GameTestHelper helper, ClusterControllerBlockEntity controller, FakePlayer player,
        Direction outward, String name, BlockPos peerController) {
        var directory = PeerDiscovery.discover(controller, player, Set.of(outward));
        helper.assertValueEqual(directory.fields().keySet(), Set.of("dimension", "game_time", "peers"), "The wire directory adds no private metadata");
        var position = new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(peerController.getX()),
            "y", new DataValue.IntegerValue(peerController.getY()), "z", new DataValue.IntegerValue(peerController.getZ())));
        var expected = new DataValue.RecordValue(Map.of(name, new DataValue.RecordValue(Map.of(
            "ambiguous", new DataValue.BooleanValue(false), "endpoints", new DataValue.ListValue(List.of(new DataValue.RecordValue(Map.of("position", position))))))));
        helper.assertValueEqual(directory.field("peers"), expected,
            "Real cable discovery exposes only the peer public name/controller position, no member name, endpoint, position, or UUID");
    }

    private static void assertNoPeer(GameTestHelper helper, ClusterControllerBlockEntity controller, FakePlayer player, Direction outward) {
        helper.assertValueEqual(PeerDiscovery.discover(controller, player, Set.of(outward)).field("peers"), EMPTY,
            "Physically cutting the external cable disconnects discovery in both directions");
    }

    private static Fixture reload(GameTestHelper helper, Fixture before) {
        var routing = before.controller().routingState().orElseThrow();
        var identity = before.controller().controllerId();
        var computer = before.member().snapshot().orElseThrow();
        var attachment = before.member().clusterAttachment();
        var endpoint = before.member().advertisement();
        var controller = (ClusterControllerBlockEntity) reloadBlock(helper.getLevel(), before.controller());
        var memberBlock = (ComputerBlockEntity) reloadBlock(helper.getLevel(), before.memberBlock());
        var after = new Fixture(controller, memberBlock);
        after.member().document(); // Ordinary observation restores the durable host; it does not tick or execute it.
        helper.assertTrue(controller != before.controller() && memberBlock != before.memberBlock(), "Actually replace both physical BlockEntities");
        helper.assertValueEqual(controller.controllerId(), identity, "Controller identity survives physical save/load");
        helper.assertValueEqual(controller.routingState().orElseThrow(), routing, "Full configuration, receipts, queued envelope, and frozen return paths survive");
        helper.assertValueEqual(after.member().snapshot().orElseThrow(), computer, "The Computer's identity, RAM, RPC, sequence, inbox, and ACK cursor survive");
        helper.assertValueEqual(after.member().clusterAttachment(), attachment, "Explicit attachment remains bound to the restored controller identity");
        helper.assertValueEqual(after.member().advertisement(), endpoint, "The private Computer endpoint remains unchanged");
        return after;
    }

    static BlockEntity reloadBlock(ServerLevel level, BlockEntity before) {
        var pos = before.getBlockPos();
        var state = before.getBlockState();
        var saved = before.saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(pos);
        var after = Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(after);
        return after;
    }

    private static void assertMemory(GameTestHelper helper, ComputerBehaviour computer, long value, long calls, long sentinel, String phase) {
        helper.assertValueEqual(BiocomputingWorldGameTests.memory(computer, "value"), new DataValue.IntegerValue(value), phase + ": value");
        helper.assertValueEqual(BiocomputingWorldGameTests.memory(computer, "calls"), new DataValue.IntegerValue(calls), phase + ": exact execution count");
        helper.assertValueEqual(BiocomputingWorldGameTests.memory(computer, "sentinel"), new DataValue.IntegerValue(sentinel), phase + ": independent RAM sentinel");
    }

    private static void assertCompleted(GameTestHelper helper, Fixture a, Fixture b, RequestCorrelation correlation, String phase) {
        assertMemory(helper, a.member(), 21, 1, 101, phase + ": A processes exactly one reply");
        assertMemory(helper, b.member(), 21, 1, 202, phase + ": B processes exactly one request");
        helper.assertTrue(a.member().snapshot().orElseThrow().rpc().pending().isEmpty(), phase + ": A consumes its original pending RPC");
        helper.assertValueEqual(a.member().snapshot().orElseThrow().rpc().reply().orElseThrow().pending().correlation(), correlation,
            phase + ": the reply event actually completed the original correlation");
        for (var fixture : List.of(a, b)) {
            var routing = fixture.controller().routingState().orElseThrow();
            helper.assertTrue(routing.mailbox().queued().isEmpty() && routing.forwards().isEmpty() && routing.pending().isEmpty(),
                phase + ": both controllers drain their accepted queues and return paths");
            helper.assertTrue(fixture.member().communicationQuiescent(), phase + ": no member inbox, outbox, or pending request remains");
        }
    }

    private static Map<String, ProgramDefinition.Cell> cells(long sentinel) {
        return Map.of("value", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0)),
            "calls", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0)),
            "sentinel", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(sentinel)));
    }

    private static ProgramDefinition.Entry countedEntry(boolean reply) {
        var graph = new ComputationGraph(Map.of("value", DataType.Scalar.INTEGER), Map.of(
            "value", new ComputationGraph.Input("value"),
            "payload", new ComputationGraph.MakeRecord(Map.of("value", "value")),
            "old", new ComputationGraph.ReadMemory("calls"),
            "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "next", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "old", "one")),
            Map.of("value", "value", "payload", "payload", "calls", "next"));
        return new ProgramDefinition.Entry(graph, List.of(new ProgramDefinition.Write("value", "value"), new ProgramDefinition.Write("calls", "calls")),
            reply ? List.of(ProgramDefinition.Send.reply("payload")) : List.of());
    }

    private static ProgramArchive caller() {
        var graph = new ComputationGraph(Map.of(), Map.of("target", new ComputationGraph.Constant(new DataValue.TextValue("query")),
            "payload", new ComputationGraph.Constant(PAYLOAD)), Map.of("target", "target", "payload", "payload"));
        return new ProgramArchive(new ProgramDefinition(graph, cells(101), List.of(),
            List.of(ProgramDefinition.Send.request("target", "payload", "lookup", "reply")), Map.of("reply", countedEntry(false))), Map.of());
    }

    private static ProgramArchive worker() {
        return new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), cells(202), List.of(), List.of(),
            Map.of("request", countedEntry(true))), Map.of());
    }
}
