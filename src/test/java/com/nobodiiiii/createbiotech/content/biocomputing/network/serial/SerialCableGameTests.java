package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.network.signal.SignalConnections;
import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramWorkspace;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.nobodiiiii.createbiotech.registry.CBItems;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.LoggerFactory;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class SerialCableGameTests {
    private static final DataValue.RecordValue FRAME = sample("signal");
    private static final DataValue.RecordValue REPLY = sample("return");

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void registeredFastCableCarriesFramesAndRejectsAMixedRoute(GameTestHelper h) {
        var fast = cable(h, "biocomputing_fast_data_cable");
        var bench = new Bench(h, fast);
        h.startSequence().thenIdle(3).thenExecute(() -> {
            bench.expectConnection("ready");
            bench.expectQueued(bench.a, FRAME);
            h.assertTrue(bench.b.poll("s0").isEmpty(), "No immediate receipt");
        }).thenWaitUntil(() -> h.assertValueEqual(bench.a.status("s0").transmit(), "transmitted", "Wait for real serial hardware"))
            .thenExecute(() -> {
                h.assertValueEqual(bench.b.poll("s0").orElse(null), FRAME, "The actual cable delivers one complete frame");
                bench.set(bench.cablePos.east(), CBBlocks.DATA_CABLE.get());
            }).thenIdle(2).thenExecute(() -> {
                bench.expectConnection("incompatible_media");
                h.assertTrue(bench.b.poll("s0").isEmpty(), "The mixed route does not bridge or repeat the frame");
            }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void duplexCableRejectsBranchesAndRequiresIndependentHardwareAtBothEnds(GameTestHelper h) {
        var duplex = cable(h, "biocomputing_duplex_data_cable");
        var bench = new Bench(h, duplex);
        var branch = bench.cablePos.south();
        bench.set(branch, duplex);
        h.startSequence().thenIdle(3).thenExecute(() -> {
            bench.expectConnection("duplex_topology");
            bench.set(branch, Blocks.AIR);
        }).thenIdle(2).thenExecute(() -> {
            var aPort = bench.ownerA.hardware().limits().serial().ports().get("s0");
            var bPort = bench.ownerB.hardware().limits().serial().ports().get("s0");
            bench.expectConnection(aPort.duplex().isPresent() && bPort.duplex().isPresent() ? "ready" : "duplex_hardware");
        }).thenSucceed();
    }

    @GameTestGenerator
    public static List<TestFunction> configuredHardware() {
        if (!Boolean.getBoolean("biocomputing.verifyEquippedSerialCables")) return List.of();
        return List.of(new TestFunction("serial_cable_equipped", "physical_serial_rate_duplex_and_reconnect", "create_biotech:empty",
                1400, 0, true, SerialCableGameTests::physicalWireRatesAndRecovery),
            new TestFunction("serial_cable_equipped", "physical_fast_serial_and_explicit_sio", "create_biotech:empty",
                400, 0, true, h -> serialAndSio(h, CBBlocks.FAST_DATA_CABLE.get())),
            new TestFunction("serial_cable_equipped", "physical_duplex_serial_and_explicit_sio", "create_biotech:empty",
                400, 0, true, h -> serialAndSio(h, CBBlocks.DUPLEX_DATA_CABLE.get())));
    }

    private static void serialAndSio(GameTestHelper h, Block cable) {
        var bench = new Bench(h, cable, VillagerProfession.CLERIC);
        var dust = bench.cablePos.east().south();
        bench.set(dust.below(), Blocks.STONE);
        bench.set(dust, Blocks.REDSTONE_WIRE);
        var workspace = new ProgramWorkspace();
        var initial = new DataValue.RecordValue(Map.of("frame", FRAME, "power", new DataValue.IntegerValue(12)));
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(
            Map.of("frame", DataType.typeOf(FRAME), "power", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.SignalWrite("signal", "p0", "power"),
                new FlowProgram.SerialSend("emit", "s0", "frame", "sent"))).withAssembly(), initial)));
        h.assertValueEqual(bench.ownerA.installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET),
            ComputerRuntime.InstallResult.APPLIED, "Install the same ISA program for both media");
        bench.ownerA.setPaused(true);
        h.startSequence().thenIdle(3).thenExecute(() -> {
            bench.expectConnection("ready");
            h.assertValueEqual(dustPower(h, dust), 0, "Serial availability is not an implicit SIO binding");
            bench.ownerA.configureSignal(new SignalConnections.Configuration("p0", Direction.EAST, true));
            bench.ownerA.setPaused(false);
        }).thenWaitUntil(() -> h.assertValueEqual(bench.a.status("s0").transmit(), "transmitting", "An actual frame is in flight"))
            .thenExecute(() -> {
                h.assertValueEqual(dustPower(h, dust), 12, "The explicitly bound SIO output drives real redstone during transmission");
                h.assertTrue(bench.b.poll("s0").isEmpty(), "Redstone power does not make a partial frame readable");
                bench.ownerA.configureSignal(new SignalConnections.Configuration("p0", Direction.EAST, false));
                h.assertValueEqual(dustPower(h, dust), 0, "Removing only the signal mapping removes redstone drive");
            }).thenWaitUntil(() -> h.assertValueEqual(bench.b.status("s0").receive().frames(), 1, "The in-flight frame is still delivered"))
            .thenExecute(() -> {
                bench.expectConnection("ready");
                h.assertValueEqual(bench.b.poll("s0").orElse(null), FRAME, "Received the original record, not a redstone signal");
                h.assertTrue(bench.b.poll("s0").isEmpty(), "No extra frame was generated by changing the signal mapping");
                h.assertValueEqual(dustPower(h, dust), 0, "Finishing a serial send does not re-enable redstone");
            }).thenSucceed();
    }

    private static int dustPower(GameTestHelper h, BlockPos dust) {
        return h.getLevel().getBlockState(dust).getValue(RedStoneWireBlock.POWER);
    }

    private static final Path COLD_WITNESS = Path.of("serial-cable-cut.nbt");

    @GameTestGenerator
    public static List<TestFunction> coldHardware() {
        String phase = System.getProperty("biocomputing.verifySerialCableDisk", "");
        if (phase.isEmpty()) return List.of();
        if (!phase.equals("prepare") && !phase.equals("verify")) throw new IllegalArgumentException("Unknown cable disk phase");
        return List.of(new TestFunction("serial_cable_disk", "physical_wire_disk_" + phase, "create_biotech:empty", 400, 0, true,
            phase.equals("prepare") ? SerialCableGameTests::prepareCold : SerialCableGameTests::verifyCold));
    }

    private static DataValue coldReply() {
        var tag = new CompoundTag();
        tag.putByte("valid", (byte) 1);
        tag.putIntArray("samples", new int[] {2, 3, 5});
        var codec = CBConfigs.SERVER.biocomputing.serialCodec(CBConfigs.SERVER_SPEC.isLoaded());
        return NativeNbtCodec.snapshot(tag, new NativeNbtCodec.Limits(codec.maxEncodedBytes(), codec.maxDecodeHeapBytes(), codec.values()));
    }

    private static void prepareCold(GameTestHelper h) {
        var bench = new Bench(h, CBBlocks.DUPLEX_DATA_CABLE.get(), VillagerProfession.CLERIC);
        h.assertTrue(!Files.exists(COLD_WITNESS), "Use a fresh isolated world for the preparation phase");
        h.startSequence().thenIdle(3).thenExecute(() -> {
            bench.expectConnection("ready");
            bench.expectQueued(bench.a, FRAME);
            var reply = bench.b.prepareSend("s0", coldReply());
            h.assertValueEqual(reply.result(), SerialResult.send("queued"), "Queue a short native NBT frame in the opposite direction");
            h.assertTrue(reply.publish(), "Apply the second independent hardware admission");
        }).thenWaitUntil(() -> {
            h.assertValueEqual(bench.a.status("s0").receive().frames(), 1, "A complete native frame is in the real receiver");
            h.assertValueEqual(bench.a.status("s0").transmit(), "transmitting", "The opposite direction remains in flight at the save cut");
            h.assertValueEqual(bench.b.status("s0").receive().frames(), 0, "The long frame has not reached the peer");
        }).thenExecute(() -> {
            var witness = new CompoundTag();
            witness.putLong("Process", ProcessHandle.current().pid());
            witness.putLong("Left", bench.cablePos.west().asLong());
            witness.putUUID("LeftId", bench.ownerA.communicationIdentity().orElseThrow());
            witness.putUUID("RightId", bench.ownerB.communicationIdentity().orElseThrow());
            try (var stream = Files.newOutputStream(COLD_WITNESS, StandardOpenOption.CREATE_NEW)) {
                NbtIo.writeCompressed(witness, stream);
            } catch (IOException failure) { throw new IllegalStateException(failure); }
        }).thenSucceed();
    }

    private static void verifyCold(GameTestHelper h) {
        CompoundTag witness;
        try { witness = NbtIo.readCompressed(COLD_WITNESS, NbtAccounter.create(64 * 1024L)); }
        catch (IOException failure) { throw new IllegalStateException(failure); }
        h.assertTrue(witness.getLong("Process") != ProcessHandle.current().pid(), "Cold restore must use a new JVM");
        var level = h.getLevel();
        var start = BlockPos.of(witness.getLong("Left"));
        var end = start.east(3);
        for (int cx = start.getX() >> 4; cx <= end.getX() >> 4; cx++) level.setChunkForced(cx, start.getZ() >> 4, true);
        var aBlock = (ComputerBlockEntity)level.getBlockEntity(start);
        var bBlock = (ComputerBlockEntity)level.getBlockEntity(end);
        h.assertTrue(aBlock != null && bBlock != null, "Restore the actual Computers from the old region files");
        h.assertTrue(level.getBlockState(start.east()).is(CBBlocks.DUPLEX_DATA_CABLE.get()) &&
            level.getBlockState(start.east(2)).is(CBBlocks.DUPLEX_DATA_CABLE.get()), "Both registered cable blocks survive normal save");
        var ownerA = aBlock.getBehaviour(ComputerBehaviour.TYPE);
        var ownerB = bBlock.getBehaviour(ComputerBehaviour.TYPE);
        h.assertValueEqual(ownerA.communicationIdentity().orElseThrow(), witness.getUUID("LeftId"), "Same physical source");
        h.assertValueEqual(ownerB.communicationIdentity().orElseThrow(), witness.getUUID("RightId"), "Same physical peer");
        var a = ownerA.serialDevice();
        var b = ownerB.serialDevice();
        h.assertValueEqual(a.status("s0").transmit(), "interrupted", "Half-frame must not resume or silently become transmitted");
        h.assertTrue(List.of("unloaded", "reloaded").contains(a.status("s0").failure()), "The transport says why it ended");
        var nativeFrame = a.prepareReceive("s0", true);
        h.assertValueEqual(nativeFrame.result(), SerialResult.receive("received", coldReply()), "Complete native tag survives without losing type information");
        h.assertTrue(nativeFrame.publish(), "Claim the complete restored frame");
        h.assertValueEqual(a.status("s0").receive().frames(), 0, "No duplicate restored frame");
        h.startSequence().thenIdle(40).thenExecute(() -> {
            h.assertValueEqual(a.status("s0").connection(), "ready", "Persisted endpoints reconnect without changing installation");
            h.assertValueEqual(b.status("s0").receive().frames(), 0, "The incomplete direction was not retried");
            h.assertValueEqual(a.status("s0").transmit(), "interrupted", "No stale success replaces the failure");
            h.assertValueEqual(a.send("s0", FRAME), "queued", "Explicit new transfer is accepted");
        }).thenWaitUntil(() -> h.assertValueEqual(b.status("s0").receive().frames(), 1, "Fresh transfer occurs through the old persisted cable"))
            .thenExecute(() -> h.assertValueEqual(b.poll("s0").orElse(null), FRAME, "Only the fresh frame is delivered"))
            .thenSucceed();
    }

    private static void physicalWireRatesAndRecovery(GameTestHelper h) {
        var basic = CBBlocks.DATA_CABLE.get();
        var fast = cable(h, "biocomputing_fast_data_cable");
        var duplex = cable(h, "biocomputing_duplex_data_cable");
        var bench = new Bench(h, basic, VillagerProfession.CLERIC);
        var aPort = bench.ownerA.hardware().limits().serial().ports().get("s0");
        var bPort = bench.ownerB.hardware().limits().serial().ports().get("s0");
        h.assertTrue(aPort.duplex().isPresent() && bPort.duplex().isPresent(), "Fixture TOML must explicitly equip both physical s0 ports");

        var config = CBConfigs.SERVER.biocomputing;
        boolean loaded = CBConfigs.SERVER_SPEC.isLoaded();
        var basicMedium = config.serialCable(SerialWireMedium.Material.BASIC, loaded).limits();
        var fastMedium = config.serialCable(SerialWireMedium.Material.FAST, loaded).limits();
        long bits = SerialRecordCodec.encode(FRAME, config.serialCodec(loaded)).length * 8L + basicMedium.framingBits();
        long[] basicCycles = {0};
        var basicTx = new MeasuredSend(bench.a);
        var fastTx = new MeasuredSend(bench.a);
        var aDuplex = new MeasuredSend(bench.a);
        var bDuplex = new MeasuredSend(bench.b);
        bench.install(bench.ownerA, FRAME);
        bench.install(bench.ownerB, REPLY);
        h.startSequence().thenIdle(3).thenExecute(() -> {
            bench.expectConnection("ready");
            bench.ownerA.setPaused(false);
        }).thenWaitUntil(() -> basicTx.measure(h)).thenExecute(() -> {
            h.assertValueEqual(bench.b.poll("s0").orElse(null), FRAME, "Basic physical cable delivers the ISA frame");
            h.assertValueEqual(basicTx.ticks, ceilTicks(bits, Math.min(aPort.bitsPerTick(), basicMedium.bitsPerStep())), "Basic medium time");
            basicCycles[0] = bench.ownerA.flowSnapshot().orElseThrow().state().work();
            bench.rewire(fast);
        }).thenIdle(3).thenExecute(() -> {
            bench.expectConnection("ready");
            restart(bench.ownerA);
        }).thenWaitUntil(() -> fastTx.measure(h)).thenExecute(() -> {
            h.assertValueEqual(bench.b.poll("s0").orElse(null), FRAME, "Same installed program and frame on fast cable");
            h.assertValueEqual(fastTx.ticks, ceilTicks(bits, Math.min(aPort.bitsPerTick(), fastMedium.bitsPerStep())), "Fast medium time");
            h.assertTrue(fastTx.ticks < basicTx.ticks, "Fixture must let physical line rate, not CPU or port, distinguish the two lines");
            h.assertValueEqual(bench.ownerA.flowSnapshot().orElseThrow().state().work(), basicCycles[0],
                "Changing the line changes hardware time, not instruction cost");
            LoggerFactory.getLogger(SerialCableGameTests.class).info("Physical wire timing: {} bits / basic {} ticks / fast {} ticks / {} CPU cycles",
                bits, basicTx.ticks, fastTx.ticks, basicCycles[0]);
            bench.rewire(duplex);
        }).thenIdle(3).thenExecute(() -> {
            bench.expectConnection("ready");
            restart(bench.ownerA);
            bench.ownerB.setPaused(false);
        }).thenWaitUntil(() -> {
            boolean aDone = aDuplex.observe(h), bDone = bDuplex.observe(h);
            h.assertTrue(aDone && bDone, "Wait for both independent hardware directions");
        }).thenExecute(() -> {
            h.assertValueEqual(aDuplex.begin, bDuplex.begin, "Two programs truly begin emissions on the same tick");
            h.assertValueEqual(bench.b.poll("s0").orElse(null), FRAME, "No collision on the eastbound circuit");
            h.assertValueEqual(bench.a.poll("s0").orElse(null), REPLY, "No collision on the westbound circuit");
            h.assertValueEqual(bench.ownerA.flowSnapshot().orElseThrow().state().locals().field("sent"), SerialResult.send("queued"),
                "An ISA send is still only admission, distinct from reception");
            LoggerFactory.getLogger(SerialCableGameTests.class).info("Physical duplex: same-tick reverse send, {} / {} ticks", aDuplex.ticks, bDuplex.ticks);
            restart(bench.ownerA);
        }).thenWaitUntil(() -> h.assertValueEqual(bench.a.status("s0").transmit(), "transmitting", "Cut a real in-flight frame"))
            .thenExecute(() -> bench.set(bench.cablePos, Blocks.AIR))
            .thenIdle(2).thenExecute(() -> {
                h.assertValueEqual(bench.a.status("s0").transmit(), "interrupted", "Breaking a real duplex cable interrupts the partial frame");
                bench.set(bench.cablePos, duplex);
            }).thenIdle(20).thenExecute(() -> {
                bench.expectConnection("ready");
                h.assertTrue(bench.b.poll("s0").isEmpty(), "Repair does not retry");
                restart(bench.ownerA);
            }).thenWaitUntil(() -> h.assertValueEqual(bench.b.status("s0").receive().frames(), 1, "A new explicit send follows the repaired route"))
            .thenExecute(() -> {
                h.assertValueEqual(bench.b.poll("s0").orElse(null), FRAME, "New complete frame");
                h.assertTrue(bench.b.poll("s0").isEmpty(), "No partial or duplicate frame");
            }).thenSucceed();
    }

    private static long ceilTicks(long bits, long rate) { return 1 + (bits - 1) / rate; }
    private static DataValue.RecordValue sample(String text) {
        return new DataValue.RecordValue(Map.of("sample", new DataValue.TextValue(text.repeat(96))));
    }
    private static void restart(ComputerBehaviour computer) { computer.reset(); computer.setPaused(false); }
    private static Block cable(GameTestHelper h, String id) {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create_biotech", id));
        h.assertTrue(block != Blocks.AIR, "A player-placeable cable must be registered: " + id);
        h.assertValueEqual(BuiltInRegistries.ITEM.getKey(block.asItem()), ResourceLocation.fromNamespaceAndPath("create_biotech", id),
            "The cable has a registered player-facing block item");
        h.assertTrue(h.getLevel().getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath("create_biotech", "crafting/" + id)).isPresent(),
            "The cable can be obtained by crafting");
        return block;
    }

    private static final class MeasuredSend {
        final SerialDevice port;
        long begin = -1, ticks = -1;
        MeasuredSend(SerialDevice port) { this.port = port; }
        void measure(GameTestHelper h) { h.assertTrue(observe(h), "Wait for the real hardware interval to finish"); }
        boolean observe(GameTestHelper h) {
            if (ticks != -1) return true;
            var phase = port.status("s0").transmit();
            long now = h.getLevel().getGameTime();
            if (phase.equals("transmitting") && begin == -1) begin = now;
            if (phase.equals("transmitted") && begin != -1) ticks = now - begin + 1;
            return ticks != -1;
        }
    }

    private static ComputerBehaviour place(GameTestHelper h, BlockPos pos, VillagerProfession profession) {
        var level = h.getLevel();
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        var lower = CBBlocks.COMPUTER.get().defaultBlockState().setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlockAndUpdate(pos, lower);
        level.setBlockAndUpdate(pos.above(), lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER));
        var block = (ComputerBlockEntity)level.getBlockEntity(pos);
        var resident = EntityType.VILLAGER.create(level);
        resident.setVillagerData(resident.getVillagerData().setProfession(profession));
        resident.setBaby(false);
        var box = new ItemStack(CBItems.CARDBOARD_BOX.get());
        h.assertTrue(CapturedEntityBoxHelper.captureEntity(box, resident), "Use an actual resident");
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "wire-native"));
        player.moveTo(Vec3.atCenterOf(pos));
        player.setItemInHand(InteractionHand.MAIN_HAND, box);
        h.assertValueEqual(block.installResident(player, InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS, "Install native Computer hardware");
        var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        var idle = new ProgramWorkspace();
        idle.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(Map.of(), List.of()).withAssembly(),
            new DataValue.RecordValue(Map.of()))));
        h.assertValueEqual(computer.installDocument(idle.snapshot(), ComputerRuntime.MemoryPolicy.RESET),
            ComputerRuntime.InstallResult.APPLIED, "Install an idle program to configure the physical test host");
        return computer;
    }

    private static final class Bench {
        final GameTestHelper h;
        final BlockPos cablePos;
        final ComputerBehaviour ownerA, ownerB;
        final SerialDevice a, b;

        Bench(GameTestHelper h, Block cable) { this(h, cable, VillagerProfession.NONE); }
        Bench(GameTestHelper h, Block cable, VillagerProfession resident) {
            this.h = h;
            var start = h.absolutePos(new BlockPos(2, 3, 3));
            cablePos = start.east();
            ownerA = place(h, start, resident);
            ownerB = place(h, start.east(3), resident);
            a = ownerA.serialDevice();
            b = ownerB.serialDevice();
            a.configure("s0", Direction.EAST, true);
            b.configure("s0", Direction.WEST, true);
            rewire(cable);
        }
        void rewire(Block cable) { set(cablePos, cable); set(cablePos.east(), cable); }
        void set(BlockPos pos, Block block) { h.getLevel().setBlockAndUpdate(pos, block.defaultBlockState()); }
        void install(ComputerBehaviour computer, DataValue.RecordValue frame) {
            var workspace = new ProgramWorkspace();
            workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(
                Map.of("frame", DataType.typeOf(frame)), List.of(new FlowProgram.SerialSend("emit", "s0", "frame", "sent"))).withAssembly(),
                new DataValue.RecordValue(Map.of("frame", frame)))));
            h.assertValueEqual(computer.installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET),
                ComputerRuntime.InstallResult.APPLIED, "Install an ordinary program through the compiled ISA path");
            computer.setPaused(true);
        }
        void expectQueued(SerialDevice from, DataValue.RecordValue frame) {
            h.assertValueEqual(from.send("s0", frame), "queued", "Queue through the existing bounded serial interface");
        }
        void expectConnection(String expected) {
            h.assertValueEqual(a.status("s0").connection(), expected, "First physical port reports the route");
            h.assertValueEqual(b.status("s0").connection(), expected, "Second physical port reports the same route");
        }
    }
}
