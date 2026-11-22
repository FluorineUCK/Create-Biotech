package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Registered Computer + public server menu; rendered UI is verified separately. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingNumericGameTests {
    private static final List<ScalarOperation> OPERATIONS = List.of(ScalarOperation.SQRT_DECIMAL, ScalarOperation.POWER_DECIMAL,
        ScalarOperation.REMAINDER_DECIMAL, ScalarOperation.ROUND_DECIMAL_TO_INTEGER,
        ScalarOperation.CEIL_DECIMAL_TO_INTEGER, ScalarOperation.FLOOR_DECIMAL_TO_INTEGER);
    private record Session(ComputerBehaviour computer, FakePlayer player, UUID nonce) {
        void control(long intent, ComputerControlSession.Action action, DataValue.RecordValue inputs) {
            new ProgramControlRequest(2, nonce, intent, computer.programRevision(), action,
                inputs == null ? new byte[0] : ProgramEditorWire.encodeValue(inputs, computer.hardware().limits().archive())).handle(player);
        }
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void numericMenuInstallPauseReloadAndFailurePreserveOwnRam(GameTestHelper helper) {
        var block = BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(2, 2, 2)));
        var player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "numeric-menu"));
        player.moveTo(Vec3.atCenterOf(block.getBlockPos())); verify(helper, block, player, 0);
    }
    private static void verify(GameTestHelper helper, ComputerBlockEntity block, FakePlayer player, int index) {
        var operation = OPERATIONS.get(index); var session = open(helper, block, player); var computer = session.computer();
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>(); var bindings = new LinkedHashMap<String, String>();
        var values = new LinkedHashMap<String, DataValue>();
        operation.inputs().forEach((name, type) -> {
            nodes.put(name, new ComputationGraph.Input(name)); bindings.put(name, name);
            values.put(name, new DataValue.DecimalValue(name.equals("right") ? 0.5 : 9));
        });
        nodes.put("numeric", new ComputationGraph.Scalar(operation, bindings));
        var graph = new ComputationGraph(operation.inputs(), nodes, Map.of("result", "numeric"));
        var archive = new ProgramArchive(new ProgramDefinition(graph,
            Map.of("saved", new ProgramDefinition.Cell(DataType.Scalar.ANY, DataValue.NullValue.INSTANCE)),
            List.of(new ProgramDefinition.Write("saved", "result"))), Map.of());
        var document = new ProgramDocument(archive, Map.of("root", Map.of("numeric", new ProgramDocument.Position(43, -17))));
        new ProgramEditorRequest(2, session.nonce(), 1, computer.programRevision(), true, true,
            ProgramDocumentWire.encode(document, computer.hardware().limits().archive())).handle(player);
        helper.assertValueEqual(computer.document().orElseThrow(), document, "Server installs the public numeric document and layout");
        session.control(1, ComputerControlSession.Action.START_PAUSED, new DataValue.RecordValue(values));
        session.control(2, ComputerControlSession.Action.STEP, null);
        var before = computer.snapshot().orElseThrow();
        helper.assertValueEqual(before.machine().workUnits(), 1L, "Single step consumes only one ordinary work unit");
        var restoredBlock = reload(helper, block); var restored = BiocomputingWorldGameTests.host(restoredBlock); restored.document();
        helper.assertValueEqual(restored.snapshot().orElseThrow(), before, "Exact paused checkpoint survives registered BE serialization");
        helper.runAfterDelay(3, () -> {
            helper.assertValueEqual(restored.snapshot().orElseThrow(), before, "Paused world ticks do not advance computation");
            var fresh = open(helper, restoredBlock, player); fresh.control(1, ComputerControlSession.Action.RESUME, null);
            helper.runAfterDelay(8, () -> {
                DataValue expected = switch (operation) {
                    case SQRT_DECIMAL, POWER_DECIMAL -> new DataValue.DecimalValue(3);
                    case REMAINDER_DECIMAL -> new DataValue.DecimalValue(0);
                    default -> new DataValue.IntegerValue(9);
                };
                helper.assertValueEqual(restored.snapshot().orElseThrow().machine().status(), ComputationMachine.Status.SUCCEEDED, "World ticks finish the operation");
                helper.assertValueEqual(BiocomputingWorldGameTests.memory(restored, "saved"), expected, operation.name());
                var code = switch (operation) {
                    case SQRT_DECIMAL -> { values.put("value", new DataValue.DecimalValue(-1)); yield ComputationException.Code.INVALID_ARGUMENT; }
                    case POWER_DECIMAL -> { values.put("left", new DataValue.DecimalValue(-2)); yield ComputationException.Code.INVALID_ARGUMENT; }
                    case REMAINDER_DECIMAL -> { values.put("right", new DataValue.DecimalValue(-0.0)); yield ComputationException.Code.DIVISION_BY_ZERO; }
                    default -> { values.put("value", new DataValue.DecimalValue(0x1.0p63)); yield ComputationException.Code.ARITHMETIC_OVERFLOW; }
                };
                fresh.control(2, ComputerControlSession.Action.START_PAUSED, new DataValue.RecordValue(values));
                for (int step = 0; restored.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; step++) {
                    helper.assertTrue(step < 20, "Failure uses the ordinary budget"); fresh.control(3 + step, ComputerControlSession.Action.STEP, null);
                }
                var failed = restored.snapshot().orElseThrow();
                helper.assertValueEqual(failed.machine().status(), ComputationMachine.Status.FAILED, "Arithmetic failure is explicit");
                helper.assertValueEqual(failed.machine().diagnostic().orElseThrow().code(), code, "Specific arithmetic diagnostic");
                helper.assertValueEqual(BiocomputingWorldGameTests.memory(restored, "saved"), expected, "Failed event keeps committed RAM");
                var failedBlock = reload(helper, restoredBlock); var failedHost = BiocomputingWorldGameTests.host(failedBlock); failedHost.document();
                helper.assertValueEqual(failedHost.snapshot().orElseThrow(), failed, "Failure and previous RAM persist together");
                if (index + 1 == OPERATIONS.size()) helper.succeed();
                else verify(helper, failedBlock, player, index + 1);
            });
        });
    }
    private static Session open(GameTestHelper helper, ComputerBlockEntity block, FakePlayer player) {
        var provider = ComputerProgramMenu.provider(block); player.containerMenu = provider.createMenu(2, player.getInventory(), player);
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
        UUID nonce; try { provider.writeOpeningData(buffer); nonce = buffer.readUUID(); } finally { buffer.release(); }
        return new Session(BiocomputingWorldGameTests.host(block), player, nonce);
    }
    private static ComputerBlockEntity reload(GameTestHelper helper, ComputerBlockEntity block) {
        var level = helper.getLevel(); var pos = block.getBlockPos(); var state = block.getBlockState();
        var saved = block.saveWithFullMetadata(level.registryAccess()); level.removeBlockEntity(pos);
        var replacement = (ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(replacement); return replacement;
    }
}
