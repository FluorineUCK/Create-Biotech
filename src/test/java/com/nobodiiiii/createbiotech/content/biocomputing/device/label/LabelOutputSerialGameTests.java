package com.nobodiiiii.createbiotech.content.biocomputing.device.label;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.DataCableBlock;
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
public final class LabelOutputSerialGameTests {
    @GameTest(template = "empty", timeoutTicks = 800)
    public static void realCableAndMenuPublishOnlyLiteralLabelAndRevokeWithoutLiveWire(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 2, 3));
        var pos = origin.east(4);
        var computer = computer(h, origin);
        var cable = CBBlocks.DATA_CABLE.get().defaultBlockState()
            .setValue(DataCableBlock.connection(Direction.WEST), true)
            .setValue(DataCableBlock.connection(Direction.EAST), true);
        for (int x = 1; x < 4; x++) level.setBlock(origin.east(x), cable, 3);
        level.setBlock(pos, CBBlocks.LABEL_OUTPUT.get().defaultBlockState().setValue(LabelOutputBlock.FACING, Direction.EAST), 3);
        var output = (LabelOutputBlockEntity)level.getBlockEntity(pos);
        var service = output.getBehaviour(LabelOutputSerialBehaviour.TYPE);
        var actor = new MenuPlayer(level);
        actor.moveTo(Vec3.atCenterOf(pos));
        computer.serialDevice().configure("s0", Direction.EAST, true);

        h.runAfterDelay(4, () -> {
            h.assertTrue(service.binding().isEmpty(), "Cabling does not authorize output");
            var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false);
            h.assertValueEqual(output.getBlockState().useWithoutItem(level, actor, hit), InteractionResult.CONSUME, "Registered manager opens");
            h.assertValueEqual(((DataValue.ListValue)actor.client.view().field("choices")).values().size(), 1, "A real cabled pin is offered");
            actor.send(actor.client.request(LabelOutputRequest.Action.GRANT, 0));
            h.assertValueEqual(actor.client.code(), "configured", "Menu grants the captured source");
            var address = service.address();
            h.runAfterDelay(3, () -> exchange(h, computer, command(address, "query", "status", Map.of()), reply -> {
                h.assertValueEqual(reply.field("code"), text("ok"), "Status came back over the physical line");
                exchange(h, computer, command(address, "apply", "replace", withLabel(reply, "READY")), applied -> {
                    h.assertValueEqual(applied.field("code"), text("accepted"), "Addressed request commits");
                    h.assertValueEqual(output.getUpdateTag(level.registryAccess()).getString("RenderLabel"), "READY", "Client receives the label");
                    var moved = new LinkedHashMap<>(withLabel(applied, "READY"));
                    moved.put("offset", new LabelOffset(1, 0.5, -0.25).value());
                    exchange(h, computer, command(address, "move", "replace", moved), result -> {
                        h.assertValueEqual(result.field("code"), text("accepted"), "Move without replacing the text");
                        var sync = output.getUpdateTag(level.registryAccess());
                        h.assertValueEqual(LabelOffset.load(sync.get("RenderOffset")), new LabelOffset(1, 0.5, -0.25),
                            "Watcher receives the offset, not a world target");
                        h.assertTrue(output.saveWithFullMetadata(level.registryAccess()).contains("BiocomputingLabelSerial"),
                            "The label adapter uses its own persisted grant key");
                        h.assertTrue(!sync.contains("BiocomputingLabelOutput") && !sync.contains("BiocomputingLabelSerial"),
                            "Neither archive is synchronized to watchers");
                        var context = new com.nobodiiiii.createbiotech.content.biocomputing.peripheral.BlockReadContext(level, pos, Direction.NORTH,
                            actor, output.getBlockState(), output, CBConfigs.SERVER.biocomputing.peripherals(true).snapshot(), true);
                        h.assertValueEqual(context.serializedData(), new DataValue.ErrorValue("access_denied", "access_denied"), "No raw reader bypass");
                        level.removeBlock(origin.east(2), false);
                        actor.send(actor.client.request(LabelOutputRequest.Action.REVOKE, -1));
                        h.assertValueEqual(actor.client.code(), "configured", "Revocation works disconnected");
                        h.assertTrue(service.binding().isEmpty(), "No controller");
                        h.assertValueEqual(output.getUpdateTag(level.registryAccess()).getString("RenderLabel"), "", "Revocation synchronizes blank");
                        h.assertTrue(!output.getUpdateTag(level.registryAccess()).contains("RenderOffset"), "Revoke resets offset");
                        h.succeed();
                    });
                });
            }));
        });
    }

    private static Map<String, DataValue> withLabel(DataValue.RecordValue reply, String label) {
        var token = (DataValue.RecordValue)reply.field("state");
        return Map.of("device", token.field("device"), "session", token.field("session"), "revision", token.field("revision"), "text", text(label));
    }

    private static DataValue.RecordValue command(DataValue.RecordValue address, String id, String operation, Map<String, DataValue> arguments) {
        return new DataValue.RecordValue(Map.of("kind", text("label_output_command"), "version", new DataValue.IntegerValue(1),
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
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "label-computer"));
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
        LabelOutputMenu client;
        MenuPlayer(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "label-control")); }
        @Override public OptionalInt openMenu(MenuProvider provider, Consumer<RegistryFriendlyByteBuf> opening) {
            containerMenu = provider.createMenu(1, getInventory(), this);
            var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try { opening.accept(buffer); client = CBMenuTypes.LABEL_OUTPUT.get().create(1, getInventory(), buffer); }
            finally { buffer.release(); }
            return OptionalInt.of(1);
        }
        void send(LabelOutputRequest packet) {
            var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try { packet.write(buffer); new LabelOutputRequest(buffer).handle(this); } finally { buffer.release(); }
        }
    }
}
