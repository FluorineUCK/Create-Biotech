package com.nobodiiiii.createbiotech.content.biocomputing.device.display;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlock;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Transport/physical authorization integration; not a GUI or published driver acceptance. */
@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class TextOutputSerialGameTests {
    @GameTest(template = "empty", timeoutTicks = 1000)
    public static void explicitMenuGrantAndFiniteSerialReplaceThenClearNativeBoard(GameTestHelper h) { path(h); }
    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void reopeningThePhysicalSenderDoesNotApplyAnInterruptedTextCommand(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 2, 3));
        var pos = origin.east(4);
        var computer = computer(h, origin);
        for (int x = 1; x < 4; x++) level.setBlock(origin.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(pos, CBBlocks.TEXT_OUTPUT.get().defaultBlockState().setValue(TextOutputBlock.FACING, Direction.EAST), 3);
        var output = (TextOutputBlockEntity)level.getBlockEntity(pos);
        var service = output.getBehaviour(TextOutputSerialBehaviour.TYPE);
        var actor = new MenuPlayer(level);
        actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        var serial = computer.serialDevice();
        serial.configure("s0", Direction.EAST, true);
        h.runAfterDelay(4, () -> {
            var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), Direction.NORTH, pos, false);
            output.getBlockState().useWithoutItem(level, actor, hit);
            actor.send(actor.client.request(TextOutputRequest.Action.GRANT, 0));
            h.assertValueEqual(actor.client.code(), "configured", "Grant the actual source through the device menu");
            var target = service.address();
            h.runAfterDelay(3, () -> exchange(h, computer, command(target, "before", "status", Map.of()), before -> {
                h.assertValueEqual(before.field("code"), text("ok"), "Initial query and its token crossed the physical cable");
                var args = new LinkedHashMap<>(((DataValue.RecordValue)before.field("state")).fields());
                args.keySet().retainAll(Set.of("device", "session", "revision"));
                args.put("lines", new DataValue.ListValue(List.of(text("RETIRED"))));
                h.assertValueEqual(serial.send("s0", command(target, "retired", "replace", args)), "queued", "First text change was admitted");
                h.startSequence().thenWaitUntil(() ->
                        h.assertValueEqual(serial.status("s0").transmit(), "transmitting", "Wait for the actual wire to start the frame"))
                    .thenExecute(() -> {
                        serial.configure("s0", Direction.EAST, false);
                        serial.configure("s0", Direction.EAST, true);
                        h.assertValueEqual(serial.status("s0").failure(), "configuration_changed", "The source interrupted the old frame");
                    }).thenExecuteAfter(160, () -> {
                        h.assertTrue(output.state().lines().isEmpty(), "Interrupted text applied after the physical interface reopened");
                        h.assertTrue(serial.poll("s0").isEmpty(), "Interrupted command cannot generate a device reply");
                        h.assertValueEqual(serial.status("s0").transmit(), "interrupted", "The cable must not overwrite the local interrupted diagnosis");
                        exchange(h, computer, command(target, "after", "status", Map.of()), after -> {
                            h.assertValueEqual(after.field("code"), text("ok"), "Explicit new query works through the same physical cable");
                            var nextArgs = new LinkedHashMap<>(((DataValue.RecordValue)after.field("state")).fields());
                            nextArgs.keySet().retainAll(Set.of("device", "session", "revision"));
                            nextArgs.put("lines", new DataValue.ListValue(List.of(text("FRESH"))));
                            exchange(h, computer, command(target, "fresh", "replace", nextArgs), result -> {
                                h.assertValueEqual(result.field("code"), text("accepted"), "Fresh explicit output applied");
                                h.assertValueEqual(output.state().lines(), List.of("FRESH"), "Only the fresh action reached the actual display adapter");
                                h.succeed();
                            });
                        });
                    });
            }));
        });
    }

    private static void path(GameTestHelper h) {
        var level = h.getLevel(); var origin = h.absolutePos(new BlockPos(2, 2, 3)); var pos = origin.east(4);
        var computer = computer(h, origin);
        for (int x = 1; x < 4; x++) level.setBlock(origin.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(pos, CBBlocks.TEXT_OUTPUT.get().defaultBlockState().setValue(TextOutputBlock.FACING, Direction.EAST), 3);
        var output = (TextOutputBlockEntity)level.getBlockEntity(pos);
        var service = output.getBehaviour(TextOutputSerialBehaviour.TYPE);
        var actor = new MenuPlayer(level); actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        computer.serialDevice().configure("s0", Direction.EAST, true);
        var boardPos = pos.east(3);
        level.setBlockAndUpdate(boardPos, AllBlocks.DISPLAY_BOARD.getDefaultState());
        var board = (FlapDisplayBlockEntity)level.getBlockEntity(boardPos);
        board.updateControllerStatus();
        var linkPos = pos.above();
        level.setBlockAndUpdate(linkPos, AllBlocks.DISPLAY_LINK.getDefaultState().setValue(DisplayLinkBlock.FACING, Direction.UP));
        var link = (DisplayLinkBlockEntity)level.getBlockEntity(linkPos);
        link.initialize();
        link.target(boardPos); link.activeSource = CBDisplaySources.TEXT_OUTPUT.get();
        TextOutputGameTests.powerBoard(level, board);
        h.assertTrue(board.isSpeedRequirementFulfilled(), "The registered native board is powered");
        board.applyTextManually(1, Component.literal("KEEP"));
        h.runAfterDelay(950, () -> h.fail("Serial path stalled: source=" + computer.serialDevice().view()
            + "; output=" + service.status() + "; level=" + output.state().lines()
            + "; ticked=" + com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialPeripheralBehaviour.ticking(level).size()));
        h.runAfterDelay(4, () -> {
            h.assertTrue(service.binding().isEmpty(), "Default closed, not open because a cable exists");
            var choices = service.discover(actor);
            h.assertValueEqual(choices.size(), 1, "Only physically exposed Computer interface is offered");
            var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), Direction.NORTH, pos, false);
            h.assertValueEqual(output.getBlockState().useWithoutItem(level, actor, hit), net.minecraft.world.InteractionResult.CONSUME, "Real block interaction opens registered manager");
            h.assertTrue(actor.containerMenu instanceof TextOutputMenu, "Actual server menu");
            h.assertValueEqual(((DataValue.ListValue)actor.client.view().field("choices")).values().size(), 1, "Bounded opening uses registered client menu factory");
            var request = actor.client.request(TextOutputRequest.Action.GRANT, 0);
            new TextOutputRequest(request.containerId(), UUID.randomUUID(), request.revision(), request.action(), request.choice()).handle(actor);
            h.assertTrue(service.binding().isEmpty(), "Wrong nonce cannot authorize");
            actor.send(request);
            h.assertValueEqual(actor.client.code(), "configured", "Actual menu packet grants selected captured source");
            h.assertValueEqual(service.revision(), 1L, "Grant is applied once");
            actor.send(request); h.assertValueEqual(service.revision(), 1L, "Reused container id with old nonce cannot replay");
            h.assertValueEqual(service.configure(actor, 0, Optional.empty()), "changed", "Stale configuration cannot revoke a newer grant");
            var sync = output.getUpdateTag(level.registryAccess());
            h.assertTrue(!sync.contains("BiocomputingTextOutput") && !sync.contains("BiocomputingTextSerial"), "Client update contains no private grant or frame archive");
            var context = new com.nobodiiiii.createbiotech.content.biocomputing.peripheral.BlockReadContext(level, pos, Direction.NORTH, actor,
                output.getBlockState(), output, CBConfigs.SERVER.biocomputing.peripherals(true).snapshot(), true);
            h.assertValueEqual(context.serializedData(), new DataValue.ErrorValue("access_denied", "access_denied"), "Raw reader cannot expose private serial queue or session");
            h.assertTrue(!(context.publicData() instanceof DataValue.ErrorValue), "Public device snapshot remains readable");
            var address = service.address();
            // Opening a previously closed physical port changes the segment. Let that real topology
            // transition finish BEFORE submitting the first command; interrupted frames are never retried.
            h.runAfterDelay(3, () -> exchange(h, computer, command(address, "query", "status", Map.of()), reply -> {
                h.assertValueEqual(reply.field("code"), text("ok"), "Status reply physically returns");
                var token = (DataValue.RecordValue)reply.field("state");
                var arguments = new LinkedHashMap<>(token.fields()); arguments.keySet().retainAll(Set.of("device", "session", "revision"));
                arguments.put("lines", new DataValue.ListValue(List.of(text("READY"), text("OTHER"))));
                exchange(h, computer, command(address, "replace", "replace", arguments), applied -> {
                    h.assertValueEqual(applied.field("code"), text("accepted"), "Only addressed finite command updates the page");
                    h.assertValueEqual(output.state().lines(), List.of("READY", "OTHER"), "Serial page is accepted");
                    link.tickSource();
                    h.assertValueEqual(line(board, 0), "READY", "Native feed writes the first row");
                    h.assertValueEqual(line(board, 1), "KEEP", "Native reservation blocks the second submitted row");
                    var nextToken = new LinkedHashMap<>(((DataValue.RecordValue)applied.field("state")).fields());
                    nextToken.keySet().retainAll(Set.of("device", "session", "revision"));
                    exchange(h, computer, command(address, "clear", "clear", nextToken), cleared -> {
                        h.assertValueEqual(cleared.field("code"), text("accepted"), "Explicit clear accepted");
                        h.assertValueEqual(output.state().lines(), List.of(), "Clear never means revoke or hidden reset");
                        h.assertTrue(service.binding().isPresent(), "Explicit clear leaves the controller grant in place");
                        link.tickSource();
                        h.assertValueEqual(line(board, 0), "", "Accepted clear empties the actual linked display row");
                        h.assertValueEqual(line(board, 1), "KEEP", "Clear does not erase a row reserved by another source");
                        level.removeBlock(origin.east(2), false);
                        actor.send(actor.client.request(TextOutputRequest.Action.REVOKE, -1));
                        h.assertValueEqual(actor.client.code(), "configured", "Revocation does not need a live wire");
                        h.succeed();
                    });
                });
            }));
        });
    }
    private static String line(FlapDisplayBlockEntity board, int row) {
        var section = board.getLines().get(row).getSections().getFirst();
        return section.getText() == null ? "" : section.getText().getString();
    }

    static ComputerBehaviour computer(GameTestHelper h, BlockPos pos) {
        var level = h.getLevel(); level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        var lower = CBBlocks.COMPUTER.get().defaultBlockState().setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlock(pos, lower, 3); level.setBlock(pos.above(), lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var block = (ComputerBlockEntity)level.getBlockEntity(pos); var villager = EntityType.VILLAGER.create(level); villager.setBaby(false);
        var box = new ItemStack(CBItems.CARDBOARD_BOX.get()); h.assertTrue(CapturedEntityBoxHelper.captureEntity(box, villager), "Supported resident");
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "redstone-computer")); actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        actor.setItemInHand(InteractionHand.MAIN_HAND, box);
        h.assertValueEqual(block.installResident(actor, InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS, "Normal checked resident installation");
        var computer = block.getBehaviour(ComputerBehaviour.TYPE); var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(), new DataValue.RecordValue(Map.of()),
            FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        computer.installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET); return computer;
    }
    static DataValue.RecordValue command(DataValue.RecordValue address, String request, String operation, Map<String, DataValue> arguments) {
        return new DataValue.RecordValue(Map.of("kind", text("text_output_command"), "version", integer(1), "target", address,
            "request", text(request), "operation", text(operation), "arguments", new DataValue.RecordValue(arguments)));
    }
    static void exchange(GameTestHelper h, ComputerBehaviour computer, DataValue.RecordValue request, Consumer<DataValue.RecordValue> finish) {
        h.assertValueEqual(computer.serialDevice().send("s0", request), "queued", "No instantaneous device shortcut");
        h.assertTrue(computer.serialDevice().poll("s0").isEmpty(), "Reply consumes wire time");
        awaitReply(h, computer, request.field("request"), finish);
    }
    private static void awaitReply(GameTestHelper h, ComputerBehaviour computer, DataValue correlation, Consumer<DataValue.RecordValue> finish) {
        h.runAfterDelay(1, () -> {
            var reply = computer.serialDevice().poll("s0");
            if (reply.isEmpty()) { awaitReply(h, computer, correlation, finish); return; }
            h.assertValueEqual(reply.orElseThrow().field("request"), correlation, "Explicit matching reply"); finish.accept(reply.orElseThrow());
        });
    }
    static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }

    private static final class MenuPlayer extends FakePlayer {
        TextOutputMenu client;
        MenuPlayer(net.minecraft.server.level.ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "redstone-control")); }
        @Override public OptionalInt openMenu(net.minecraft.world.MenuProvider provider,
                java.util.function.Consumer<net.minecraft.network.RegistryFriendlyByteBuf> opening) {
            containerMenu = provider.createMenu(1, getInventory(), this);
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try { opening.accept(buffer); client = CBMenuTypes.TEXT_OUTPUT.get().create(1, getInventory(), buffer); }
            finally { buffer.release(); }
            return OptionalInt.of(1);
        }
        void send(TextOutputRequest packet) {
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try { packet.write(buffer); new TextOutputRequest(buffer).handle(this); } finally { buffer.release(); }
        }
    }
}
