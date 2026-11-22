package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.EnclosureScanner;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real registered Cluster, real Computer residents and real ticks; no test hosts in the production JAR. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingClusterGameTests {
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void explicitInternalConnectionUsesRealMemberMailboxesAndReloadsWithoutAnyInternalCable(GameTestHelper helper) {
        var level = helper.getLevel(); var min = helper.absolutePos(new BlockPos(3, 1, 1)); var max = min.offset(2, 3, 4);
        var controllerPos = min.offset(0, 1, 1); var bPos = controllerPos.east(); var aPos = bPos.south(2);
        var aBlock = BiocomputingWorldGameTests.computer(helper, aPos); var bBlock = BiocomputingWorldGameTests.computer(helper, bPos);
        var a = BiocomputingWorldGameTests.host(aBlock); var b = BiocomputingWorldGameTests.host(bBlock);
        for (var pos : BlockPos.betweenClosed(min, max))
            if (pos.getX() == min.getX() || pos.getX() == max.getX() || pos.getY() == min.getY() || pos.getY() == max.getY()
                || pos.getZ() == min.getZ() || pos.getZ() == max.getZ())
                level.setBlock(pos, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(controllerPos, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING, Direction.WEST), Block.UPDATE_ALL);
        var controller = (ClusterControllerBlockEntity)level.getBlockEntity(controllerPos);
        a.install(caller(), ComputerRuntime.MemoryPolicy.RESET); b.install(worker(), ComputerRuntime.MemoryPolicy.RESET);
        a.configureEndpoint(new EndpointConfiguration("caller", true)); b.configureEndpoint(new EndpointConfiguration("worker", true));
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "internal-test")); player.moveTo(Vec3.atCenterOf(controllerPos.west()));
        var config = new ClusterConfiguration(Map.of("A", new ClusterConfiguration.Member(a.communicationIdentity().orElseThrow(), aPos),
            "B", new ClusterConfiguration.Member(b.communicationIdentity().orElseThrow(), bPos)), Map.of(), Map.of(
            "query", new ClusterConfiguration.Connection("A", "query", "B", "worker", true),
            "reply", new ClusterConfiguration.Connection("B", "caller", "A", "caller", true)));
        var provider = ClusterControllerMenu.provider(controller); var menu = (ClusterControllerMenu)provider.createMenu(1, player.getInventory(), player);
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), level.registryAccess());
        UUID nonce; try { provider.writeOpeningData(buffer); nonce = buffer.readUUID(); } finally { buffer.release(); }
        player.containerMenu = menu; request(nonce, 1, controller.configurationRevision(), config).handle(player);
        helper.assertValueEqual(controller.routingState().orElseThrow().configuration(), config, "Player packet preserves explicit internal connections");
        helper.assertTrue(controller.advertisements().isEmpty(), "Internal wiring must not advertise private endpoints");
        b.setPaused(true); a.begin(new DataValue.RecordValue(Map.of()));
        helper.runAfterDelay(16, () -> {
            helper.assertValueEqual(b.snapshot().orElseThrow().mailbox().queued().size(), 1, "A delivers without a cable, only to configured B");
            helper.assertValueEqual(value(b), new DataValue.IntegerValue(0), "Admission does not execute B");
            helper.assertValueEqual(controller.configure(ClusterConfiguration.empty(), player), "busy", "Cannot redirect an admitted internal request");
            level.setBlock(min, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL); b.setPaused(false);
            helper.runAfterDelay(16, () -> {
                helper.assertValueEqual(b.transportResult().orElseThrow().code(), "open_shell", "Reply stays in B outbox while shell broken");
                var restored = (ClusterControllerBlockEntity)reload(level, controller);
                var restoredB = ((com.simibubi.create.foundation.blockEntity.SmartBlockEntity)reload(level, bBlock)).getBehaviour(ComputerBehaviour.TYPE);
                restoredB.document();
                helper.assertValueEqual(restored.routingState().orElseThrow().configuration(), config, "Internal configuration survives physical reload");
                level.setBlock(min, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), Block.UPDATE_ALL);
                helper.runAfterDelay(24, () -> {
                    helper.assertValueEqual(value(a), new DataValue.IntegerValue(21), "B reply resumes through explicit reverse connection");
                    helper.assertTrue(a.snapshot().orElseThrow().rpc().pending().isEmpty(), "Original correlation consumed by original Computer");
                    helper.assertTrue(restored.routingState().orElseThrow().mailbox().queued().isEmpty(), "No duplicate internal gateway queue");
                    var requestProgram = caller().program();
                    a.install(new ProgramArchive(new ProgramDefinition(requestProgram.graph(), cells(), List.of(),
                        List.of(new ProgramDefinition.Send("target", "payload"))), Map.of()), ComputerRuntime.MemoryPolicy.RESET);
                    restoredB.install(new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), cells(), List.of(), List.of(),
                        Map.of("message", new ProgramDefinition.Entry(values(), List.of(new ProgramDefinition.Write("value", "value")), List.of()))), Map.of()),
                        ComputerRuntime.MemoryPolicy.RESET);
                    a.begin(new DataValue.RecordValue(Map.of()));
                    helper.runAfterDelay(16, () -> {
                        helper.assertValueEqual(value(restoredB), new DataValue.IntegerValue(21), "Plain message also uses the configured internal path");
                        helper.assertValueEqual(value(a), new DataValue.IntegerValue(0), "B plain message does not edit A RAM");
                        helper.assertTrue(a.snapshot().orElseThrow().rpc().pending().isEmpty(), "Plain message does not invent a request");
                        helper.succeed();
                    });
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void touchingMemberRepliesThroughEmbeddedControllerAndRetainsOutputAcrossBrokenShellReload(GameTestHelper helper) {
        var level = helper.getLevel();
        var min = helper.absolutePos(new BlockPos(3, 1, 1));
        var max = min.offset(2, 3, 2);
        var controllerPos = min.offset(0, 1, 1);
        var memberPos = controllerPos.east();
        var externalPos = controllerPos.west(2);
        var memberBlock = BiocomputingWorldGameTests.computer(helper, memberPos);
        var externalBlock = BiocomputingWorldGameTests.computer(helper, externalPos);
        var member = BiocomputingWorldGameTests.host(memberBlock);
        var external = BiocomputingWorldGameTests.host(externalBlock);
        // Restore the bottom shell after the ordinary Computer fixture placed its support block.
        for (var pos : BlockPos.betweenClosed(min, max))
            if (pos.getX() == min.getX() || pos.getX() == max.getX() || pos.getY() == min.getY()
                    || pos.getY() == max.getY() || pos.getZ() == min.getZ() || pos.getZ() == max.getZ())
                level.setBlock(pos, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(controllerPos, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING, Direction.WEST), Block.UPDATE_ALL);
        level.setBlock(controllerPos.west(), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
        var controller = (ClusterControllerBlockEntity)Objects.requireNonNull(level.getBlockEntity(controllerPos));
        helper.assertTrue(controller.getType() == CBBlockEntityTypes.CLUSTER_CONTROLLER.get(), "Actually registered controller BlockEntity type");
        helper.assertValueEqual(controller.enclosure().status(), EnclosureScanner.Status.READY, "Controller must close the full shell");
        for (Direction face : Direction.values())
            helper.assertTrue((level.getCapability(CommunicationEndpoint.CAPABILITY, controllerPos, face) == controller) == (face == Direction.WEST),
                "Only outward face provides communication");
        helper.assertTrue(level.getCapability(CommunicationEndpoint.CAPABILITY, controllerPos, null) == null, "No unsided bypass of outward port");
        member.install(worker(), ComputerRuntime.MemoryPolicy.RESET);
        external.install(caller(), ComputerRuntime.MemoryPolicy.RESET);
        member.configureEndpoint(new EndpointConfiguration("worker", true));
        external.configureEndpoint(new EndpointConfiguration("caller", true));
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "cluster-test"));
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.moveTo(Vec3.atCenterOf(controllerPos.west()));
        var config = new ClusterConfiguration(Map.of("A", new ClusterConfiguration.Member(member.communicationIdentity().orElseThrow(), memberPos)),
            Map.of("query", new ClusterConfiguration.Port("A", "worker", "return", "caller", true)));
        var session = configureThroughPlayerPackets(helper, controller, member, player, config);
        helper.assertTrue(member.clusterAttachment().isPresent(), "Membership must be explicit saved metadata");
        member.setPaused(true);
        external.begin(new DataValue.RecordValue(Map.of()));
        helper.runAfterDelay(16, () -> {
            helper.assertValueEqual(controller.routingState().orElseThrow().pending().size(), 1, "Real external request registered its frozen return path");
            helper.assertValueEqual(value(member), new DataValue.IntegerValue(0), "Controller admission must not execute or write member RAM");
            helper.assertValueEqual(member.snapshot().orElseThrow().mailbox().queued().size(), 1, "Only configured member admitted the request");
            level.setBlock(min, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            member.setPaused(false);
            helper.runAfterDelay(16, () -> {
                helper.assertValueEqual(value(member), new DataValue.IntegerValue(21), "Member executes independently even with a broken shell");
                helper.assertValueEqual(member.transportResult().orElseThrow().code(), "open_shell", "Broken shell must stop explicit internal output");
                helper.assertValueEqual(value(external), new DataValue.IntegerValue(0), "Broken shell cannot silently forward reply");
                var identity = controller.controllerId();
                var restoredController = (ClusterControllerBlockEntity)reload(level, controller);
                helper.assertTrue(!session.menu().stillValid(player), "Old menu loses authority when its physical BlockEntity is replaced");
                request(session.nonce(), 99, restoredController.configurationRevision(), ClusterConfiguration.empty()).handle(player);
                helper.assertValueEqual(restoredController.routingState().orElseThrow().configuration(), config, "A stale physical menu cannot edit the reloaded controller");
                var restoredMember = ((com.simibubi.create.foundation.blockEntity.SmartBlockEntity)reload(level, memberBlock)).getBehaviour(ComputerBehaviour.TYPE);
                restoredMember.document();
                helper.assertValueEqual(restoredController.controllerId(), identity, "Saved controller identity survives real BE loading");
                helper.assertValueEqual(restoredController.routingState().orElseThrow().pending().size(), 1, "Frozen RPC correlation survives reload");
                helper.assertTrue(restoredMember.clusterAttachment().orElseThrow().controller().equals(identity), "Computer reload keeps own explicit attachment");
                level.setBlock(min, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), Block.UPDATE_ALL);
                helper.runAfterDelay(32, () -> {
                    helper.assertValueEqual(value(external), new DataValue.IntegerValue(21), "Repaired shell returns the original named value through the outward cable");
                    helper.assertTrue(external.snapshot().orElseThrow().rpc().pending().isEmpty(), "Caller matches and consumes its original correlation");
                    helper.assertTrue(!restoredController.routingState().orElseThrow().busy(), "Controller acknowledges the reply only after admission");
                    helper.assertValueEqual(value(restoredMember), new DataValue.IntegerValue(21), "Reload did not replace member RAM");
                    helper.succeed();
                });
            });
        });
    }

    private record MenuSession(ClusterControllerMenu menu, UUID nonce) {}
    private static MenuSession configureThroughPlayerPackets(GameTestHelper helper, ClusterControllerBlockEntity controller,
        ComputerBehaviour member, FakePlayer player, ClusterConfiguration config) {
        var provider = ClusterControllerMenu.provider(controller);
        var menu = (ClusterControllerMenu)provider.createMenu(1, player.getInventory(), player);
        helper.assertTrue(menu.getType() == CBMenuTypes.BIOCOMPUTING_CLUSTER.get() && menu.stillValid(player), "Registered authorized configuration menu");
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
        UUID nonce;
        try { provider.writeOpeningData(buffer); nonce = buffer.readUUID(); helper.assertTrue(!buffer.isReadable(), "Opening wire contains only the menu nonce"); }
        finally { buffer.release(); }
        var packet = request(nonce, 4, controller.configurationRevision(), config);
        var stranger = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "cluster-stranger"));
        stranger.moveTo(player.position()); stranger.containerMenu = menu;
        packet.handle(stranger);
        helper.assertTrue(controller.routingState().orElseThrow().configuration().members().isEmpty(), "Wrong player cannot use another player's menu");
        packet.handle(player);
        helper.assertTrue(controller.routingState().orElseThrow().configuration().members().isEmpty(), "Request requires the currently open menu");
        player.containerMenu = menu;
        request(UUID.randomUUID(), 1, 0, config).handle(player);
        helper.assertTrue(controller.routingState().orElseThrow().configuration().members().isEmpty(), "Wrong menu nonce cannot apply configuration");
        var near = player.position(); player.moveTo(near.add(100, 0, 0)); packet.handle(player);
        helper.assertTrue(controller.routingState().orElseThrow().configuration().members().isEmpty(), "Player must remain close to the physical controller");
        player.moveTo(near); packet.handle(player);
        helper.assertValueEqual(controller.routingState().orElseThrow().configuration(), config, "Actual authorized packet configures a touching member without an internal cable");
        var revision = controller.configurationRevision(); packet.handle(player);
        helper.assertValueEqual(controller.configurationRevision(), revision, "Replayed intent cannot repeat the configuration transaction");
        request(nonce, 5, revision - 1, ClusterConfiguration.empty()).handle(player);
        helper.assertValueEqual(controller.routingState().orElseThrow().configuration(), config, "Stale revision cannot clear a newer configuration");
        member.disableEndpoint();
        var disabled = new ClusterConfiguration(config.members(), Map.of("query", new ClusterConfiguration.Port("A", "worker", "return", "caller", false)));
        request(nonce, 6, controller.configurationRevision(), disabled).handle(player);
        helper.assertValueEqual(controller.routingState().orElseThrow().configuration(), disabled, "Explicitly disabling a route does not require the member input to stay open");
        member.configureEndpoint(new EndpointConfiguration("worker", true));
        request(nonce, 7, controller.configurationRevision(), config).handle(player);
        helper.assertValueEqual(controller.routingState().orElseThrow().configuration(), config, "Reopen the route through the same public menu protocol");
        return new MenuSession(menu, nonce);
    }
    private static ClusterControlRequest request(UUID nonce, long intent, long revision, ClusterConfiguration config) {
        var limits = CBConfigs.SERVER.biocomputing.cluster(CBConfigs.SERVER_SPEC.isLoaded());
        return new ClusterControlRequest(1, nonce, intent, revision, ClusterControlSession.Action.CONFIGURE,
            ClusterControlWire.encodeConfiguration(config, limits.archive()));
    }

    private static DataValue value(ComputerBehaviour computer) { return computer.snapshot().orElseThrow().machine().memory().field("value"); }
    private static BlockEntity reload(ServerLevel level, BlockEntity before) {
        var pos = before.getBlockPos(); var state = before.getBlockState();
        var saved = before.saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(pos);
        var after = Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(after); return after;
    }
    private static Map<String, ProgramDefinition.Cell> cells() {
        return Map.of("value", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0)));
    }
    private static ComputationGraph values() {
        return new ComputationGraph(Map.of("value", DataType.Scalar.INTEGER), Map.of("v", new ComputationGraph.Input("value"),
            "payload", new ComputationGraph.MakeRecord(Map.of("value", "v"))), Map.of("value", "v", "payload", "payload"));
    }
    private static ProgramArchive worker() {
        var entry = new ProgramDefinition.Entry(values(), List.of(new ProgramDefinition.Write("value", "value")), List.of(ProgramDefinition.Send.reply("payload")));
        return new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), cells(), List.of(), List.of(), Map.of("request", entry)), Map.of());
    }
    private static ProgramArchive caller() {
        var graph = new ComputationGraph(Map.of(), Map.of("target", new ComputationGraph.Constant(new DataValue.TextValue("query")),
            "payload", new ComputationGraph.Constant(new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(21))))), Map.of("target", "target", "payload", "payload"));
        var reply = new ProgramDefinition.Entry(values(), List.of(new ProgramDefinition.Write("value", "value")), List.of());
        return new ProgramArchive(new ProgramDefinition(graph, cells(), List.of(), List.of(ProgramDefinition.Send.request("target", "payload", "lookup", "reply")), Map.of("reply", reply)), Map.of());
    }
}
