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
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;

/** Two separate GameTest JVMs, same isolated world. No block reconstruction or archive injection. */
@GameTestHolder("create_biotech") @EventBusSubscriber(modid = "create_biotech")
public final class RedstoneOutputColdRecoveryGameTests {
    private static final Path CUT = Path.of("redstone-cold-cut.nbt");
    private static Runnable afterTick;

    /** Opt-in only: an ordinary native suite must not accidentally prepare a cold-recovery fixture. */
    @GameTestGenerator
    public static Collection<TestFunction> tests() {
        String phase = System.getProperty("biocomputing.redstoneColdPhase", "");
        if (phase.isEmpty()) return List.of();
        if (!Set.of("prepare", "verify").contains(phase)) throw new IllegalArgumentException("Unknown cold recovery phase");
        return List.of(new TestFunction("redstone_cold", scenario() + "_set_" + phase, "create_biotech:empty", 1600, 0, true,
            phase.equals("prepare") ? RedstoneOutputColdRecoveryGameTests::prepare : RedstoneOutputColdRecoveryGameTests::verify));
    }
    private static String scenario() {
        String value = System.getProperty("biocomputing.redstoneColdCase", "received");
        if (!Set.of("received", "reply").contains(value)) throw new IllegalArgumentException("Unknown cold recovery case");
        return value;
    }

    @SubscribeEvent
    public static void afterWorldTicks(ServerTickEvent.Post event) {
        // Runs after all level serial ticks. Completing the only test here lets GameTestServer
        // stop and save normally before another device tick, without pausing or modifying hardware.
        if (afterTick != null) afterTick.run();
    }

    private static void prepare(GameTestHelper h) {
        h.assertTrue(!Files.exists(CUT), "Never overwrite or reseed a prior cut");
        var level = h.getLevel(); var origin = h.absolutePos(new BlockPos(2, 2, 3)); var pos = origin.east(4);
        var computer = computer(h, origin);
        for (int x = 1; x < 4; x++) level.setBlock(origin.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(pos, CBBlocks.REDSTONE_OUTPUT.get().defaultBlockState().setValue(RedstoneOutputBlock.FACING, Direction.EAST), 3);
        level.setBlock(pos.east().below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos.east(), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        var output = (RedstoneOutputBlockEntity)level.getBlockEntity(pos);
        var service = output.getBehaviour(RedstoneOutputSerialBehaviour.TYPE);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "cold-output"));
        actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        computer.serialDevice().configure("s0", Direction.EAST, true);
        h.runAfterDelay(4, () -> {
            h.assertValueEqual(service.configure(actor, 0, Optional.of(service.discover(actor).getFirst())), "configured", "Explicit source grant");
            h.runAfterDelay(3, () -> exchange(h, computer, command(service, "query", "status", Map.of()), token -> {
                var state = (DataValue.RecordValue)token.field("state");
                h.assertValueEqual(computer.serialDevice().send("s0", command(service, "pending-set", "set", arguments(state))), "queued", "Real finite transport");
                afterTick = () -> {
                    if (scenario().equals("received")) {
                        if (service.device().status("s0").receive().frames() != 1) return;
                        h.assertValueEqual(output.appliedLevel(), 0, "Cut after reception, before application");
                        h.assertValueEqual(output.state().revision(), 1L, "Only authorization changed revision");
                        h.assertValueEqual(service.device().prepareReceive("s0").result().field("value"), command(service, "pending-set", "set", arguments(state)), "Complete original command");
                    } else {
                        if (output.appliedLevel() != 13 || !service.device().status("s0").transmit().equals("transmitting")) return;
                        h.assertValueEqual(output.state().revision(), 2L, "One set applied before in-flight reply cut");
                        h.assertTrue(computer.serialDevice().poll("s0").isEmpty(), "Sender has not received the reply");
                    }
                    var cut = new CompoundTag();
                    cut.putString("Case", scenario());
                    cut.putLong("Process", ProcessHandle.current().pid()); cut.putLong("Computer", origin.asLong()); cut.putLong("Output", pos.asLong());
                    cut.putUUID("ComputerIdentity", computer.communicationIdentity().orElseThrow());
                    cut.put("OutputArchive", output.saveWithFullMetadata(level.registryAccess()));
                    write(CUT, cut); afterTick = null; h.succeed();
                };
            }));
        });
    }

    private static void verify(GameTestHelper h) {
        var cut = read(CUT);
        h.assertValueEqual(cut.getString("Case"), scenario(), "Restore exactly the prepared scenario");
        h.assertTrue(cut.getLong("Process") != ProcessHandle.current().pid(), "A new JVM must read the saved world");
        h.assertTrue(!Files.exists(Path.of("redstone-cold-verified.nbt")), "Never replay a completed verification");
        var level = h.getLevel(); var origin = BlockPos.of(cut.getLong("Computer")); var pos = BlockPos.of(cut.getLong("Output"));
        // Only the fixture loads its previous chunks. No program gets arbitrary coordinate access.
        level.getChunk(origin); level.getChunk(pos.east());
        var owner = (ComputerBlockEntity)level.getBlockEntity(origin);
        var output = (RedstoneOutputBlockEntity)level.getBlockEntity(pos);
        h.assertTrue(owner != null && output != null, "Actual owners restored from world region files");
        var computer = owner.getBehaviour(ComputerBehaviour.TYPE); var service = output.getBehaviour(RedstoneOutputSerialBehaviour.TYPE);
        var prior = cut.getCompound("OutputArchive").getCompound("BiocomputingRedstoneOutput");
        h.assertValueEqual(computer.communicationIdentity().orElseThrow(), cut.getUUID("ComputerIdentity"), "Source identity survives, no reinstall");
        h.assertValueEqual(output.state().device(), prior.getUUID("Device"), "Same output device");
        h.assertTrue(!output.state().session().equals(prior.getUUID("Session")), "New output session");
        h.assertValueEqual(output.appliedLevel(), 0, "No power on load");
        long expectedRevision = scenario().equals("received") ? 1 : 2;
        h.assertValueEqual(output.state().revision(), expectedRevision, "No execution during save or automatic replay during load");
        h.assertValueEqual(output.state().desired(), scenario().equals("received") ? 0 : 13, "Durable intent is distinct from actual power");
        // Spawn chunks may already have processed the saved command before the GameTest starts.
        // Disk equality is checked between JVMs; here the observable contract is the original
        // correlated refusal, unchanged latch/version and no duplicate reply, not queue residence.
        for (int x = origin.getX() >> 4; x <= pos.east().getX() >> 4; x++) level.setChunkForced(x, origin.getZ() >> 4, true);
        if (scenario().equals("reply")) {
            h.assertValueEqual(service.device().status("s0").transmit(), "interrupted", "Incomplete reply is not restarted");
            h.assertValueEqual(service.device().status("s0").failure(), "reloaded", "Interruption remains explicit after real cold load");
            h.runAfterDelay(20, () -> {
                h.assertTrue(computer.serialDevice().poll("s0").isEmpty(), "No old success reaches the sender");
                h.assertValueEqual(service.device().status("s0").transmit(), "interrupted", "No hidden retransmission during device ticks");
                freshRequest(h, computer, output, service, expectedRevision);
            });
            return;
        }
        reply(h, computer, refusal -> {
            h.assertValueEqual(refusal.field("request"), text("pending-set"), "Original request, not a new request");
            h.assertValueEqual(refusal.field("code"), text("stale_session"), "Old session explicitly refused");
            h.assertValueEqual(output.state().revision(), 1L, "Refusal never applies the set");
            h.assertValueEqual(output.appliedLevel(), 0, "No late power from old input");
            h.runAfterDelay(20, () -> {
                h.assertTrue(computer.serialDevice().poll("s0").isEmpty(), "No duplicate refusal or hidden retry");
                freshRequest(h, computer, output, service, expectedRevision);
            });
        });
    }

    private static void freshRequest(GameTestHelper h, ComputerBehaviour computer, RedstoneOutputBlockEntity output,
                                     RedstoneOutputSerialBehaviour service, long revision) {
        h.assertValueEqual(output.appliedLevel(), 0, "No old command or saved intent reenergizes output");
        exchange(h, computer, command(service, "new-query", "status", Map.of()), token -> {
            var state = (DataValue.RecordValue)token.field("state");
            h.assertValueEqual(state.field("applied"), integer(0), "Public query reports actual zero");
            h.assertValueEqual(state.field("revision"), integer(revision), "Public query preserves accepted history");
            exchange(h, computer, command(service, "new-set", "set", arguments(state)), applied -> {
                h.assertValueEqual(applied.field("code"), text("applied"), "Explicit fresh session works without reinstalling or reauthorizing");
                h.assertValueEqual(output.state().revision(), revision + 1, "Fresh command applies exactly once");
                h.assertValueEqual(h.getLevel().getBlockState(output.getBlockPos().east()).getValue(RedStoneWireBlock.POWER), 13, "Real downstream power");
                var evidence = new CompoundTag(); evidence.putLong("Process", ProcessHandle.current().pid()); evidence.putString("Case", scenario());
                evidence.put("OutputArchive", output.saveWithFullMetadata(h.getLevel().registryAccess()));
                write(Path.of("redstone-cold-verified.nbt"), evidence); h.succeed();
            });
        });
    }

    private static ComputerBehaviour computer(GameTestHelper h, BlockPos pos) {
        var level = h.getLevel(); level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        var state = CBBlocks.COMPUTER.get().defaultBlockState().setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlock(pos, state, 3); level.setBlock(pos.above(), state.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var block = (ComputerBlockEntity)level.getBlockEntity(pos); var villager = EntityType.VILLAGER.create(level); villager.setBaby(false);
        var box = new ItemStack(CBItems.CARDBOARD_BOX.get()); h.assertTrue(CapturedEntityBoxHelper.captureEntity(box, villager), "Resident capture");
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "cold-computer")); actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        actor.setItemInHand(InteractionHand.MAIN_HAND, box);
        h.assertValueEqual(block.installResident(actor, InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS, "Checked resident installation");
        var computer = block.getBehaviour(ComputerBehaviour.TYPE); var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(), new DataValue.RecordValue(Map.of()),
            FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        computer.installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET); return computer;
    }
    private static DataValue.RecordValue command(RedstoneOutputSerialBehaviour service, String request, String operation, Map<String, DataValue> arguments) {
        return new DataValue.RecordValue(Map.of("kind", text("redstone_command"), "version", integer(1), "target", service.address(),
            "request", text(request), "operation", text(operation), "arguments", new DataValue.RecordValue(arguments)));
    }
    private static Map<String, DataValue> arguments(DataValue.RecordValue state) {
        return Map.of("device", state.field("device"), "session", state.field("session"), "revision", state.field("revision"), "level", integer(13));
    }
    private static void exchange(GameTestHelper h, ComputerBehaviour computer, DataValue.RecordValue command, Consumer<DataValue.RecordValue> next) {
        h.assertValueEqual(computer.serialDevice().send("s0", command), "queued", "Actual wire, no test-side business execution");
        reply(h, computer, result -> { h.assertValueEqual(result.field("request"), command.field("request"), "Correlated response"); next.accept(result); });
    }
    private static void reply(GameTestHelper h, ComputerBehaviour computer, Consumer<DataValue.RecordValue> next) {
        h.runAfterDelay(1, () -> { var result = computer.serialDevice().poll("s0"); if (result.isPresent()) next.accept(result.orElseThrow()); else reply(h, computer, next); });
    }
    private static void write(Path path, CompoundTag tag) {
        try { requireAbsent(path); NbtIo.write(tag, path); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private static void requireAbsent(Path path) { if (Files.exists(path)) throw new IllegalStateException("Evidence already exists: " + path); }
    private static CompoundTag read(Path path) {
        try { return Objects.requireNonNull(NbtIo.read(path), "Missing cut evidence"); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
}
