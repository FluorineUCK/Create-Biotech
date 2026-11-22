package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Two actual passive gateways. Explicit TEST coordination is not a player wiring scheduler. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingPassivePairGameTests {
    private record Cluster(ClusterControllerBlockEntity controller, ComputerBehaviour member,
        FakePlayer player, Direction outward, String publicName, String pin, ClusterConfiguration config) {}
    private record Pair(Cluster left, Cluster right) {}

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void eitherEnclosureLostAfterBothRouteAuthorizationsRejectsWithoutAdvancingMembers(GameTestHelper helper) {
        var pair = fixture(helper); var left = pair.left(); var right = pair.right();
        var toRight = bind(helper, left, right); var toLeft = bind(helper, right, left);
        for (var damaged : List.of(left, right)) {
            var a = resolve(right, toLeft); var b = resolve(left, toRight);
            var beforeA = left.member().flowSnapshot(); var beforeB = right.member().flowSnapshot();
            var roof = damaged.controller().getBlockPos().above(2); var block = helper.getLevel().getBlockState(roof);
            helper.assertTrue(block.is(CBBlocks.TRANSPARENT_COMPUTER_CASING.get()), "Damage an actual enclosure cell, not unrelated air");
            var late = b.withPhysicalRoute(() -> {
                // Runs AFTER both route authorizations. Suppress notifications so final proof must observe geometry itself.
                helper.getLevel().setBlock(roof, Blocks.AIR.defaultBlockState(), 2);
                return Optional.empty();
            }, Optional::empty);
            helper.assertValueEqual(a.exchange(late), XBusExchange.Status.UNAVAILABLE, "Final proof rejects either damaged enclosure");
            helper.assertValueEqual(left.member().flowSnapshot(), beforeA, "No partial caller continuation or receipt");
            helper.assertValueEqual(right.member().flowSnapshot(), beforeB, "No partial worker continuation or receipt");
            helper.getLevel().setBlock(roof, block, Block.UPDATE_ALL);
        }
        helper.assertValueEqual(resolve(right, toLeft).exchange(resolve(left, toRight)), XBusExchange.Status.EXCHANGED,
            "Restored geometry and fresh explicit access still perform the originally waiting handoff");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void lateRevocationAndSameIdentityGatewayReloadRequireFreshAuthorityOnBothSides(GameTestHelper helper) {
        var pair = fixture(helper); var left = pair.left(); var right = pair.right();
        var toRight = bind(helper, left, right); var toLeft = bind(helper, right, left);
        for (var revoked : List.of(left, right)) {
            var a = resolve(right, toLeft); var b = resolve(left, toRight);
            var beforeA = left.member().flowSnapshot(); var beforeB = right.member().flowSnapshot();
            var mapping = revoked.config().publicPins().get(revoked.publicName());
            var disabled = new ClusterConfiguration(revoked.config().members(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(revoked.publicName(), new XBusPublicPin(mapping.target(), false)));
            var late = b.withPhysicalRoute(() -> {
                BiocomputingClusterXBusGameTests.configureThroughMenu(helper, revoked.controller(), revoked.player(), disabled);
                return Optional.empty();
            }, Optional::empty);
            helper.assertValueEqual(a.exchange(late), XBusExchange.Status.STALE, "Post-authorization configuration change revokes either gateway proof");
            helper.assertValueEqual(left.member().flowSnapshot(), beforeA, "No caller progress during late revocation");
            helper.assertValueEqual(right.member().flowSnapshot(), beforeB, "No worker progress during late revocation");
            var source = revoked == left ? right : left;
            var target = revoked == left ? toLeft : toRight;
            helper.assertValueEqual(XBusCableRoute.resolve(source.controller(), Set.of(source.outward()), target.target()).code(), "closed",
                "Fresh lookup distinguishes closed authorization from a disconnected cable");
            BiocomputingClusterXBusGameTests.configureThroughMenu(helper, revoked.controller(), revoked.player(), revoked.config());
            helper.assertValueEqual(a.exchange(b), XBusExchange.Status.STALE, "Reopening cannot revive captured authority");
        }
        var oldA = resolve(right, toLeft); var oldB = resolve(left, toRight);
        var beforeA = left.member().flowSnapshot(); var beforeB = right.member().flowSnapshot();
        var newLeft = reload(helper, left); var newRight = reload(helper, right);
        helper.assertValueEqual(oldA.exchange(oldB), XBusExchange.Status.UNAVAILABLE, "Detached gateway instances cannot reuse fixed-identity access");
        helper.assertValueEqual(left.member().flowSnapshot(), beforeA, "Gateway reload does not reset or run its member");
        helper.assertValueEqual(right.member().flowSnapshot(), beforeB, "Other gateway reload does not reset or run its member");
        helper.assertValueEqual(newLeft.controller().controllerId(), toLeft.target().device(), "Left physical identity survives normal saved reload");
        helper.assertValueEqual(newRight.controller().controllerId(), toRight.target().device(), "Right physical identity survives normal saved reload");
        helper.assertValueEqual(resolve(newRight, toLeft).exchange(resolve(newLeft, toRight)), XBusExchange.Status.EXCHANGED,
            "Fixed identities can be resolved afresh through both reloaded gateways");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void twoPassiveGatewaysReturnCalculatedCopyWithoutAcceptingOrRunningForMembers(GameTestHelper helper) {
        var pair = fixture(helper); var left = pair.left(); var right = pair.right();
        var toRight = bind(helper, left, right); var toLeft = bind(helper, right, left);
        helper.assertValueEqual(toRight.target().device(), right.controller().controllerId(), "Right gateway has its own fixed physical identity");
        helper.assertValueEqual(toLeft.target().computer(), left.member().communicationIdentity().orElseThrow(), "Left gateway resolves only the configured member");
        helper.runAfterDelay(4, () -> {
            var beforeLeft = left.member().flowSnapshot().orElseThrow(); var beforeRight = right.member().flowSnapshot().orElseThrow();
            helper.assertValueEqual(beforeLeft.state().status(), FlowRuntime.Status.WAITING_WRITE, "No scheduler was invented by connecting cables");
            helper.assertValueEqual(beforeRight.state().status(), FlowRuntime.Status.WAITING_READ, "No passive gateway consumes data");
            for (var paused : List.of(left, right)) {
                paused.member().setPaused(true);
                var pausedLeft = left.member().flowSnapshot(); var pausedRight = right.member().flowSnapshot();
                helper.assertValueEqual(resolve(right, toLeft).exchange(resolve(left, toRight)), XBusExchange.Status.PAUSED, "Either waiting member must be running to exchange");
                helper.assertValueEqual(left.member().flowSnapshot(), pausedLeft, "Gateway does not advance a caller with either member paused");
                helper.assertValueEqual(right.member().flowSnapshot(), pausedRight, "Gateway does not advance a worker with either member paused");
                paused.member().setPaused(false);
            }
            helper.assertValueEqual(resolve(right, toLeft).exchange(resolve(left, toRight)), XBusExchange.Status.EXCHANGED, "Explicit test coordination performs one direct handoff");
            helper.runAfterDelay(20, () -> {
                helper.assertValueEqual(left.member().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_READ, "Caller waits for a real return read");
                helper.assertValueEqual(right.member().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_WRITE, "Worker calculated and waits for return consumption");
                helper.assertValueEqual(resolve(left, toRight).exchange(resolve(right, toLeft)), XBusExchange.Status.EXCHANGED, "Both passive pins support reverse direction");
                var a = left.member().flowSnapshot().orElseThrow(); var b = right.member().flowSnapshot().orElseThrow();
                helper.assertValueEqual(a.state().locals().field("packet"), BiocomputingFlowFixture.inputs(12).field("packet"), "Original sender data remains private and unchanged");
                helper.assertValueEqual(a.state().locals().field("answer"), BiocomputingFlowFixture.inputs(19).field("packet"), "Named calculated record returns through the two gateways");
                helper.assertValueEqual(b.state().locals(), BiocomputingFlowFixture.inputs(19), "Worker owns its calculated copy");
                helper.assertValueEqual(a.receipts().size(), 2, "Caller has exactly two real exchanges");
                helper.assertValueEqual(b.receipts().size(), 2, "Worker has exactly two real exchanges");
                for (var cluster : List.of(left, right)) {
                    helper.assertTrue(cluster.controller().routingState().orElseThrow().mailbox().queued().isEmpty(), "Controller never stores incoming data");
                    helper.assertTrue(cluster.controller().routingState().orElseThrow().configuration().wires().isEmpty(), "Public mapping never adds a hidden internal wire");
                }
                helper.succeed();
            });
        });
    }

    private static XBusCableRoute.Pin bind(GameTestHelper helper, Cluster source, Cluster target) {
        var result = XBusCableRoute.bind(source.controller(), Set.of(source.outward()), target.publicName(), target.pin());
        helper.assertValueEqual(result.code(), "ready", "Actual outward cable reaches the explicitly published pin");
        return result.pin().orElseThrow();
    }
    private static XBusAccess resolve(Cluster source, XBusCableRoute.Pin destination) {
        return XBusCableRoute.resolve(source.controller(), Set.of(source.outward()), destination.target()).pin().orElseThrow().access();
    }
    private static Cluster reload(GameTestHelper helper, Cluster cluster) {
        var level = helper.getLevel(); var pos = cluster.controller().getBlockPos();
        var saved = cluster.controller().saveWithFullMetadata(level.registryAccess()); var state = level.getBlockState(pos);
        level.removeBlockEntity(pos);
        var restored = (ClusterControllerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(restored);
        return new Cluster(restored, cluster.member(), cluster.player(), cluster.outward(), cluster.publicName(), cluster.pin(), cluster.config());
    }
    private static Pair fixture(GameTestHelper helper) {
        var packet = BiocomputingFlowFixture.PACKET;
        var caller = new FlowProgram(Map.of("packet", packet), List.of(
            new FlowProgram.Write("send", "x0", "packet"), new FlowProgram.Read("return", "x0", "answer", packet)));
        var worker = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("receive", "x1", "packet", packet),
            new FlowProgram.Evaluate("calculate", "sum"), new FlowProgram.Write("return", "x1", "packet")), BiocomputingFlowFixture.calculator().expressions());
        var min = helper.absolutePos(new BlockPos(1, 1, 1));
        var left = cluster(helper, min, Direction.EAST, "front", "x0", caller, BiocomputingFlowFixture.inputs(12));
        var right = cluster(helper, min.east(7), Direction.WEST, "back", "x1", worker, new DataValue.RecordValue(Map.of()));
        for (int i = 1; i <= 4; i++)
            helper.getLevel().setBlockAndUpdate(left.controller().getBlockPos().east(i), CBBlocks.DATA_CABLE.get().defaultBlockState());
        left.member().step(); right.member().step();
        left.member().setPaused(false); right.member().setPaused(false);
        return new Pair(left, right);
    }
    private static Cluster cluster(GameTestHelper helper, BlockPos min, Direction facing, String publicName, String pin,
            FlowProgram program, DataValue.RecordValue inputs) {
        var level = helper.getLevel(); var max = min.offset(2, 3, 2); var memberPos = min.offset(1, 1, 1);
        var member = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, memberPos));
        for (var pos : BlockPos.betweenClosed(min, max))
            if (pos.getX() == min.getX() || pos.getX() == max.getX() || pos.getY() == min.getY() || pos.getY() == max.getY()
                    || pos.getZ() == min.getZ() || pos.getZ() == max.getZ())
                level.setBlock(pos, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), Block.UPDATE_ALL);
        var controllerPos = memberPos.relative(facing);
        level.setBlock(controllerPos, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING, facing), Block.UPDATE_ALL);
        var controller = (ClusterControllerBlockEntity)level.getBlockEntity(controllerPos);
        member.installFlow(program, inputs); member.configureEndpoint(new EndpointConfiguration("inside", true));
        var config = new ClusterConfiguration(Map.of("cpu", new ClusterConfiguration.Member(member.communicationIdentity().orElseThrow(), memberPos)),
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(publicName, new XBusPublicPin(new XBusWire.Pin("cpu", "inside", pin), true)));
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "passive-pair"));
        player.moveTo(Vec3.atCenterOf(controllerPos.relative(facing)));
        BiocomputingClusterXBusGameTests.configureThroughMenu(helper, controller, player, config);
        return new Cluster(controller, member, player, facing, publicName, pin, config);
    }
}
