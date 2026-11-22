package com.nobodiiiii.createbiotech.content.biocomputing.device.particle;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class ParticleOutputSerialGameTests {
    @GameTest(template = "empty", timeoutTicks = 800)
    public static void realCableAdmitsOneBoundedBurstAndFencesOldSessionOnReload(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 2, 3));
        var pos = origin.east(4);
        var computer = computer(h, origin);
        for (int x = 1; x < 4; x++) level.setBlock(origin.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(pos, CBBlocks.PARTICLE_OUTPUT.get().defaultBlockState().setValue(ParticleOutputBlock.FACING, Direction.EAST), 3);
        var output = (ParticleOutputBlockEntity)level.getBlockEntity(pos);
        var service = output.getBehaviour(ParticleOutputSerialBehaviour.TYPE);
        var actor = new MenuPlayer(level);
        actor.moveTo(Vec3.atCenterOf(pos));
        computer.serialDevice().configure("s0", Direction.EAST, true);

        h.runAfterDelay(4, () -> {
            h.assertTrue(service.binding().isEmpty(), "Cabling does not grant authority");
            var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false);
            h.assertValueEqual(output.getBlockState().useWithoutItem(level, actor, hit), InteractionResult.CONSUME, "Registered manager opens");
            h.assertValueEqual(((DataValue.ListValue)actor.client.view().field("choices")).values().size(), 1, "A real cabled pin is offered");
            actor.send(actor.client.request(ParticleOutputRequest.Action.GRANT, 0));
            h.assertValueEqual(actor.client.code(), "configured", "Menu grants the captured source");
            var address = service.address();
            h.runAfterDelay(3, () -> exchange(h, computer, command(address, "query", "status", Map.of()), reply -> {
                var args = burstArguments(reply);
                exchange(h, computer, command(address, "emit", "emit", args), accepted -> {
                    h.assertValueEqual(accepted.field("code"), text("accepted"), "Physical request admitted");
                    h.assertValueEqual(output.state().last(), "no_viewers", "Attempted native dispatch with no actual players");
                    h.assertTrue(!output.state().pending(), "Burst is settled, not retained");
                    var sync = output.getUpdateTag(level.registryAccess());
                    h.assertTrue(!sync.contains("BiocomputingParticleOutput") && !sync.contains("BiocomputingParticleSerial"),
                        "Private archives are not sent to clients");

                    var archive = output.saveWithFullMetadata(level.registryAccess());
                    h.assertTrue(archive.contains("BiocomputingParticleSerial") && !archive.contains("BiocomputingTextSerial"),
                        "New saves use only the emitter's key");
                    var legacySerial = archive.get("BiocomputingParticleSerial");
                    archive.remove("BiocomputingParticleSerial");
                    archive.put("BiocomputingTextSerial", legacySerial);
                    var oldSession = output.state().session();
                    var blockState = output.getBlockState();
                    level.removeBlockEntity(pos);
                    var restored = new ParticleOutputBlockEntity(output.getType(), pos, blockState);
                    restored.loadWithComponents(archive, level.registryAccess());
                    level.setBlockEntity(restored);
                    restored.initialize();
                    h.assertTrue(!restored.state().session().equals(oldSession), "Recovery renews the session");
                    h.assertValueEqual(restored.state().last(), "no_viewers", "Recovery retains only the outcome");
                    h.runAfterDelay(3, () -> exchange(h, computer, command(address, "old", "emit", args), stale -> {
                        h.assertValueEqual(stale.field("code"), text("stale_session"), "Old frame cannot emit after restore");
                        h.assertTrue(!restored.state().pending(), "Nothing was re-enqueued");
                        h.succeed();
                    }));
                });
            }));
        });
    }

    private static Map<String, DataValue> burstArguments(DataValue.RecordValue reply) {
        var token = (DataValue.RecordValue)reply.field("state");
        var zero = new DataValue.IntegerValue(0);
        var burst = new DataValue.RecordValue(Map.of("kind", text("minecraft:smoke"), "count", new DataValue.IntegerValue(3),
            "offset", new DataValue.RecordValue(Map.of("x", zero, "y", zero, "z", zero)),
            "spread", new DataValue.DecimalValue(0.25), "speed", zero, "options", new DataValue.RecordValue(Map.of())));
        return Map.of("device", token.field("device"), "session", token.field("session"), "revision", token.field("revision"), "burst", burst);
    }

    private static DataValue.RecordValue command(DataValue.RecordValue address, String id, String operation, Map<String, DataValue> arguments) {
        return new DataValue.RecordValue(Map.of("kind", text("particle_output_command"), "version", new DataValue.IntegerValue(1),
            "target", address, "request", text(id), "operation", text(operation), "arguments", new DataValue.RecordValue(arguments)));
    }

    private static void exchange(GameTestHelper h, ComputerBehaviour computer, DataValue.RecordValue frame,
            Consumer<DataValue.RecordValue> finish) {
        h.assertValueEqual(computer.serialDevice().send("s0", frame), "queued", "Transfer consumes physical wire time");
        await(h, computer, frame.field("request"), finish);
    }

    private static void await(GameTestHelper h, ComputerBehaviour computer, DataValue request, Consumer<DataValue.RecordValue> finish) {
        h.runAfterDelay(1, () -> {
            var reply = computer.serialDevice().poll("s0");
            if (reply.isEmpty()) { await(h, computer, request, finish); return; }
            h.assertValueEqual(reply.orElseThrow().field("request"), request, "Replies match correlation");
            finish.accept(reply.orElseThrow());
        });
    }

    private static ComputerBehaviour computer(GameTestHelper h, BlockPos pos) {
        var level = h.getLevel();
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        var lower = CBBlocks.COMPUTER.get().defaultBlockState().setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlock(pos, lower, 3);
        level.setBlock(pos.above(), lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var block = (ComputerBlockEntity)level.getBlockEntity(pos);
        var villager = EntityType.VILLAGER.create(level); villager.setBaby(false);
        var box = new ItemStack(CBItems.CARDBOARD_BOX.get());
        h.assertTrue(CapturedEntityBoxHelper.captureEntity(box, villager), "Supported resident");
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "particle-computer"));
        actor.moveTo(Vec3.atCenterOf(pos));
        actor.setItemInHand(InteractionHand.MAIN_HAND, box);
        h.assertValueEqual(block.installResident(actor, InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS, "Checked resident installation");
        var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(), new DataValue.RecordValue(Map.of()),
            FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        computer.installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET);
        return computer;
    }

    private static DataValue.TextValue text(String s) { return new DataValue.TextValue(s); }

    private static final class MenuPlayer extends FakePlayer {
        ParticleOutputMenu client;
        MenuPlayer(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "particle-control")); }
        @Override public OptionalInt openMenu(MenuProvider provider, Consumer<RegistryFriendlyByteBuf> opening) {
            containerMenu = provider.createMenu(1, getInventory(), this);
            var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try { opening.accept(buffer); client = CBMenuTypes.PARTICLE_OUTPUT.get().create(1, getInventory(), buffer); }
            finally { buffer.release(); }
            return OptionalInt.of(1);
        }
        void send(ParticleOutputRequest packet) {
            var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try { packet.write(buffer); new ParticleOutputRequest(buffer).handle(this); } finally { buffer.release(); }
        }
    }
}
