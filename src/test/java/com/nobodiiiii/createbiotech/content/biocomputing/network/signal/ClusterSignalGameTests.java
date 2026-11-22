package com.nobodiiiii.createbiotech.content.biocomputing.network.signal;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class ClusterSignalGameTests {
    @GameTest(template = "empty", timeoutTicks = 150)
    public static void explicitMemberSignalUsesControllerAndShellFailureDoesNotPauseTheComputer(GameTestHelper h) {
        var level = h.getLevel(); var min = h.absolutePos(new BlockPos(1, 1, 1)); var max = min.offset(4, 3, 4);
        var a = CableSignalGameTests.computer(h, min.offset(1, 1, 1));
        var b = CableSignalGameTests.computer(h, min.offset(3, 1, 3));
        var untouched = b.flowSnapshot(); var hardware = a.hardware();
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("level", DataType.Scalar.INTEGER, "ticks", DataType.Scalar.INTEGER, "after", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.SignalWrite("drive", "p0", "level"), new FlowProgram.Sleep("delay", "ticks"), new FlowProgram.SignalWrite("after", "p0", "after")),
            new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(12), "ticks", new DataValue.IntegerValue(10), "after", new DataValue.IntegerValue(7))),
            FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        a.installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET); a.setPaused(true);
        for (int i = 0; i < 8 && !a.signalOutputs().orElseThrow().outputs().containsKey("p0"); i++) a.step();
        var paused = a.flowSnapshot();
        for (var p : BlockPos.betweenClosed(min, max))
            if (p.getX() == min.getX() || p.getX() == max.getX() || p.getY() == min.getY() || p.getY() == max.getY() || p.getZ() == min.getZ() || p.getZ() == max.getZ())
                level.setBlock(p, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), 3);
        var controlPos = min.offset(4, 1, 2); var cable = controlPos.east(); var dust = controlPos.east(2);
        var controlState = CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING, Direction.EAST);
        level.setBlock(controlPos, controlState, 3); var controller = (ClusterControllerBlockEntity)level.getBlockEntity(controlPos);
        level.setBlock(cable, CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(dust.below(), Blocks.STONE.defaultBlockState(), 3); level.setBlock(dust, Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "cluster-sio")); actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(controlPos.east()));
        var access = new ClusterMemberEditorAccess(controller, a.blockEntity);
        h.assertTrue(access.stillValid(actor), "A shell-contacting Computer is configurable with air inside");
        var grant = SignalGatewayAccess.authorize(access, actor, "p0", "status"); a.configureSignalGateway("p0", Optional.of(grant));
        try {
            SignalGatewayAccess.authorize(new ClusterMemberEditorAccess(controller, b.blockEntity), actor, "p0", "status");
            throw new AssertionError("Duplicate public SIO alias accepted");
        } catch (IllegalArgumentException conflict) { h.assertValueEqual(conflict.getMessage(), "gateway_conflict", "Disabled grants still reserve their name"); }
        h.runAfterDelay(3, () -> {
            h.assertValueEqual(level.getSignal(cable, Direction.EAST), 0, "Binding is not enabling");
            a.configureSignal(new SignalConnections.Configuration("p0", Direction.EAST, true));
            h.runAfterDelay(3, () -> {
                h.assertValueEqual(level.getBlockState(dust).getValue(RedStoneWireBlock.POWER), 12, "Concentrated output reaches actual dust");
                h.assertValueEqual(a.flowSnapshot(), paused, "Publishing does not run the paused member");
                level.setBlock(min, Blocks.AIR.defaultBlockState(), 3);
                h.assertValueEqual(CableSignalWorldTicks.power(level, cable), 0, "Shell removal invalidates output immediately");
                a.setPaused(false);
                h.runAfterDelay(25, () -> {
                    h.assertValueEqual(a.signalOutputs().orElseThrow().outputs().get("p0"), 7, "Local ISA and Sleep continue while shell is broken");
                    h.assertValueEqual(level.getSignal(cable, Direction.EAST), 0, "No local-port fallback while gateway is unformed");
                    h.assertValueEqual(b.flowSnapshot(), untouched, "Other member retains independent paused execution");
                    h.assertValueEqual(a.hardware(), hardware, "No pooled Cluster hardware");
                    level.setBlock(min, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), 3);
                    h.runAfterDelay(3, () -> {
                        h.assertValueEqual(level.getSignal(cable, Direction.EAST), 7, "Repair samples the current output, not an old value or replay");
                        var saved = controller.saveWithFullMetadata(level.registryAccess()); level.removeBlockEntity(controlPos);
                        h.assertValueEqual(CableSignalWorldTicks.power(level, cable), 0, "Controller unload invalidates output");
                        var restored = (ClusterControllerBlockEntity)BlockEntity.loadStatic(controlPos, controlState, saved, level.registryAccess()); level.setBlockEntity(restored);
                        h.runAfterDelay(4, () -> {
                            h.assertValueEqual(level.getSignal(cable, Direction.EAST), 7, "Same controller identity resumes after its actual tick");
                            level.setBlock(controlPos, Blocks.AIR.defaultBlockState(), 3); level.setBlock(controlPos, controlState, 3);
                            h.runAfterDelay(3, () -> {
                                h.assertValueEqual(level.getSignal(cable, Direction.EAST), 0, "A new controller cannot inherit the old grant");
                                var status = (DataValue.RecordValue)((DataValue.RecordValue)a.signalStatus().field("ports")).field("p0");
                                h.assertValueEqual(status.field("status"), new DataValue.TextValue("gateway_changed"), "Replacement has a distinct diagnostic");
                                h.succeed();
                            });
                        });
                    });
                });
            });
        });
    }
}
