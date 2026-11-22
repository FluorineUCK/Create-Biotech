package com.nobodiiiii.createbiotech.content.biocomputing.device.redstone;

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
public final class RedstoneOutputSerialGameTests {
    @GameTest(template = "empty", timeoutTicks = 1000)
    public static void onlyExplicitPhysicalGrantCanSetTheLatchThroughFiniteSerial(GameTestHelper h) {
        path(h, false);
    }
    @GameTest(template = "empty", timeoutTicks = 1000)
    public static void revocationClearsPowerEvenWhenTheSerialConfigurationCounterIsExhausted(GameTestHelper h) {
        path(h, true);
    }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void rotatingAnExhaustedInterfaceReportsFailureWithoutCrashingItsTick(GameTestHelper h) {
        var level = h.getLevel(); var origin = h.absolutePos(new BlockPos(2, 2, 3)); var pos = origin.east(4);
        var computer = computer(h, origin);
        for (int x = 1; x < 4; x++) level.setBlock(origin.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(pos, CBBlocks.REDSTONE_OUTPUT.get().defaultBlockState().setValue(RedstoneOutputBlock.FACING, Direction.EAST), 3);
        var output = (RedstoneOutputBlockEntity)level.getBlockEntity(pos);
        var service = output.getBehaviour(RedstoneOutputSerialBehaviour.TYPE);
        var actor = new MenuPlayer(level); actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        computer.serialDevice().configure("s0", Direction.EAST, true);
        h.runAfterDelay(4, () -> {
            h.assertValueEqual(service.configure(actor, 0, Optional.of(service.discover(actor).getFirst())), "configured", "Explicit physical grant");
            var before = output.state();
            var powered = RedstoneOutputState.set(before, before.controller().orElseThrow(), before.device(), before.session(), before.revision(), 9);
            h.assertTrue(output.publish(before, powered.state()), "Energized before rotation");
            var saved = (net.minecraft.nbt.CompoundTag)service.device().write();
            saved.getCompound("Ports").getCompound("s0").putLong("ConfigurationEpoch", Long.MAX_VALUE);
            service.device().read(saved);
            level.setBlock(pos, output.getBlockState().setValue(RedstoneOutputBlock.FACING, Direction.NORTH), 3);
            service.tick();
            h.assertValueEqual(output.appliedLevel(), 0, "Changing output face clears the electrical latch");
            h.assertValueEqual(service.status().field("last"), text("invalid_save"), "Exhausted interface reports its existing failure code");
            h.assertTrue(!com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialPeripheralBehaviour.ticking(level).contains(service),
                "Wrong-face interface cannot participate in transport");
            h.assertValueEqual(((net.minecraft.nbt.CompoundTag)service.device().write()).getCompound("Ports").getCompound("s0").getLong("ConfigurationEpoch"),
                Long.MAX_VALUE, "No counter wrap or replacement archive");
            h.assertValueEqual(service.configure(actor, service.revision(), Optional.empty()), "configured", "Fault does not prevent revocation");
            h.succeed();
        });
    }
    private static void path(GameTestHelper h, boolean exhaustedSerial) {
        var level = h.getLevel(); var origin = h.absolutePos(new BlockPos(2, 2, 3)); var pos = origin.east(4);
        var computer = computer(h, origin);
        for (int x = 1; x < 4; x++) level.setBlock(origin.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(pos, CBBlocks.REDSTONE_OUTPUT.get().defaultBlockState().setValue(RedstoneOutputBlock.FACING, Direction.EAST), 3);
        level.setBlock(pos.east().below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos.east(), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        var output = (RedstoneOutputBlockEntity)level.getBlockEntity(pos);
        var service = output.getBehaviour(RedstoneOutputSerialBehaviour.TYPE);
        var actor = new MenuPlayer(level); actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        computer.serialDevice().configure("s0", Direction.EAST, true);
        h.runAfterDelay(950, () -> h.fail("Serial path stalled: source=" + computer.serialDevice().view()
            + "; output=" + service.status() + "; level=" + output.appliedLevel()
            + "; ticked=" + com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialPeripheralBehaviour.ticking(level).size()));
        h.runAfterDelay(4, () -> {
            h.assertTrue(service.binding().isEmpty(), "Default closed, not open because a cable exists");
            var choices = service.discover(actor);
            h.assertValueEqual(choices.size(), 1, "Only physically exposed Computer interface is offered");
            var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), Direction.NORTH, pos, false);
            h.assertValueEqual(output.getBlockState().useWithoutItem(level, actor, hit), net.minecraft.world.InteractionResult.CONSUME, "Real block interaction opens registered manager");
            h.assertTrue(actor.containerMenu instanceof RedstoneOutputMenu, "Actual server menu");
            h.assertValueEqual(((DataValue.ListValue)actor.client.view().field("choices")).values().size(), 1, "Bounded opening uses registered client menu factory");
            var request = actor.client.request(RedstoneOutputRequest.Action.GRANT, 0);
            new RedstoneOutputRequest(request.containerId(), UUID.randomUUID(), request.revision(), request.action(), request.choice()).handle(actor);
            h.assertTrue(service.binding().isEmpty(), "Wrong nonce cannot authorize");
            actor.send(request);
            h.assertValueEqual(actor.client.code(), "configured", "Actual menu packet grants selected captured source");
            h.assertValueEqual(service.revision(), 1L, "Grant is applied once");
            actor.send(request); h.assertValueEqual(service.revision(), 1L, "Reused container id with old nonce cannot replay");
            h.assertValueEqual(service.configure(actor, 0, Optional.empty()), "changed", "Stale configuration cannot revoke a newer grant");
            var sync = output.getUpdateTag(level.registryAccess());
            h.assertTrue(!sync.contains("BiocomputingRedstoneOutput") && !sync.contains("BiocomputingRedstoneSerial"), "Client update contains no private grant or frame archive");
            var context = new com.nobodiiiii.createbiotech.content.biocomputing.peripheral.BlockReadContext(level, pos, Direction.NORTH, actor,
                output.getBlockState(), output, CBConfigs.SERVER.biocomputing.peripherals(true).snapshot(), true);
            h.assertValueEqual(context.serializedData(), new DataValue.ErrorValue("access_denied", "access_denied"), "Raw reader cannot expose private serial queue or session");
            h.assertTrue(!(context.publicData() instanceof DataValue.ErrorValue), "Public electrical snapshot remains readable");
            var address = service.address();
            // Opening a previously closed physical port changes the segment. Let that real topology
            // transition finish BEFORE submitting the first command; interrupted frames are never retried.
            h.runAfterDelay(3, () -> exchange(h, computer, command(address, "query", "status", Map.of()), reply -> {
                h.assertValueEqual(reply.field("code"), text("ok"), "Status reply physically returns");
                var token = (DataValue.RecordValue)reply.field("state");
                var arguments = new LinkedHashMap<>(token.fields()); arguments.keySet().retainAll(Set.of("device", "session", "revision"));
                arguments.put("level", integer(9));
                exchange(h, computer, command(address, "set", "set", arguments), applied -> {
                    h.assertValueEqual(applied.field("code"), text("applied"), "Only addressed finite command changes output");
                    h.assertValueEqual(level.getBlockState(pos.east()).getValue(RedStoneWireBlock.POWER), 9, "Real downstream circuit");
                    level.removeBlock(origin.east(2), false);
                    h.runAfterDelay(8, () -> {
                        h.assertValueEqual(output.appliedLevel(), 9, "Disconnected controller latches, not zero");
                        if (exhaustedSerial) {
                            var saved = (net.minecraft.nbt.CompoundTag)service.device().write();
                            saved.getCompound("Ports").getCompound("s0").putLong("ConfigurationEpoch", Long.MAX_VALUE);
                            service.device().read(saved);
                        }
                        actor.send(actor.client.request(RedstoneOutputRequest.Action.REVOKE, -1));
                        h.assertValueEqual(actor.client.code(), "configured", "Menu revocation does not need a working wire");
                        h.assertValueEqual(level.getBlockState(pos.east()).getValue(RedStoneWireBlock.POWER), 0, "Revocation clears real wire");
                        h.assertValueEqual(output.state().desired(), 9, "Desired is not replayed"); h.succeed();
                    });
                });
            }));
        });
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
        return new DataValue.RecordValue(Map.of("kind", text("redstone_command"), "version", integer(1), "target", address,
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
        RedstoneOutputMenu client;
        MenuPlayer(net.minecraft.server.level.ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "redstone-control")); }
        @Override public OptionalInt openMenu(net.minecraft.world.MenuProvider provider,
                java.util.function.Consumer<net.minecraft.network.RegistryFriendlyByteBuf> opening) {
            containerMenu = provider.createMenu(1, getInventory(), this);
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try { opening.accept(buffer); client = CBMenuTypes.REDSTONE_OUTPUT.get().create(1, getInventory(), buffer); }
            finally { buffer.release(); }
            return OptionalInt.of(1);
        }
        void send(RedstoneOutputRequest packet) {
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try { packet.write(buffer); new RedstoneOutputRequest(buffer).handle(this); } finally { buffer.release(); }
        }
    }
}
