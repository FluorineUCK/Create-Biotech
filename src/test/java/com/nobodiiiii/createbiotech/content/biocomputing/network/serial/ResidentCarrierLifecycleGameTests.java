package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentRadioHardware;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierClock;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierPortStatus;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class ResidentCarrierLifecycleGameTests {
    @GameTest(templateNamespace = "create_biotech_instruments", template = "empty", timeoutTicks = 1600)
    public static void staggeredChunkReloadPreservesAnActualInProgressCarrierCapture(GameTestHelper h) {
        var level = h.getLevel();
        var chunks = level.getChunkSource();
        // Structure tickets must not hold the clock circuit in memory. All its blocks are
        // initially ticking together, farther away than the GameTest and spawn tickets.
        var far = h.absolutePos(new BlockPos(49152, 4, 1));
        var origin = new BlockPos((far.getX() & ~15) + 5, far.getY(), (far.getZ() & ~15) + 5);
        var location = new ChunkPos(origin);
        var viewOnlyAnchor = new ChunkPos(location.x, location.z - 2);
        var multiplierPos = origin.east(3).south();
        level.setChunkForced(location.x, location.z, true);
        var staged = new RecoveryCheckpoint();

        h.startSequence().thenWaitUntil(() ->
            h.assertTrue(chunks.isPositionTicking(location.toLong()) && level.areEntitiesLoaded(location.toLong()),
                "Native ticket grants block-entity ticks to the remote fixture"))
        .thenExecute(() -> {
            staged.originalComputer = seat(h, origin);
            ResidentCarrierWorldGameTests.loop(h, origin);
            staged.originalMultiplier = (ComputerBlockEntity) level.getBlockEntity(multiplierPos);
            var machine = staged.originalComputer.getBehaviour(ComputerBehaviour.TYPE);
            machine.configureCarrierInput(machine.programRevision(), Optional.of(Direction.SOUTH));
            machine.configureRadio(machine.programRevision(), 0, true);
        }).thenWaitUntil(() -> {
            var station = staged.originalComputer.getBehaviour(ComputerBehaviour.TYPE);
            h.assertValueEqual(carrier(station).output().state(), CarrierClock.State.ACQUIRING,
                "The real multiplier has begun acquiring the crystal");
            h.assertTrue(active(level, staged.originalMultiplier) > 0, "An actual active tick is already recorded");
        }).thenExecute(() -> level.setChunkForced(location.x, location.z, false))
        .thenWaitUntil(() -> {
            h.assertTrue(chunks.getChunkNow(location.x, location.z) == null
                && staged.originalComputer.isRemoved() && staged.originalMultiplier.isRemoved(),
                "This is real eviction of the actual entities");
        }).thenExecute(() -> {
            staged.capture = capture(level, staged.originalMultiplier);
            int elapsed = staged.capture.getInt("ActiveTicks");
            int duration = staged.capture.getInt("RequiredTicks");
            h.assertTrue(elapsed > 0 && elapsed + 1 < duration, "The saved device still needs several active ticks");
            staged.unloadedAt = level.getGameTime();
        }).thenWaitUntil(() ->
            h.assertTrue(level.getGameTime() - staged.unloadedAt > staged.capture.getInt("RequiredTicks"),
                "Leave the chunk absent longer than an entire lock acquisition"))
        .thenExecute(() -> level.setChunkForced(viewOnlyAnchor.x, viewOnlyAnchor.z, true))
        .thenWaitUntil(() -> {
            var chunk = chunks.getChunkNow(location.x, location.z);
            h.assertTrue(chunk != null && !level.shouldTickBlocksAt(location.toLong()),
                "The neighboring edge ticket restores the chunk without starting its device ticks");
            if (chunk == null) throw new AssertionError("unreachable");
            var owner = chunk.getBlockEntity(origin);
            var multiplier = chunk.getBlockEntity(multiplierPos);
            h.assertTrue(owner instanceof ComputerBlockEntity && multiplier instanceof ComputerBlockEntity
                && owner != staged.originalComputer && multiplier != staged.originalMultiplier,
                "Minecraft materializes fresh block entities from disk");
            staged.reloadedComputer = (ComputerBlockEntity) owner;
            staged.reloadedMultiplier = (ComputerBlockEntity) multiplier;
            h.assertValueEqual(capture(level, staged.reloadedMultiplier), staged.capture,
                "Before receiving a device tick the reference and exact progress are unchanged");
            var station = staged.reloadedComputer.getBehaviour(ComputerBehaviour.TYPE);
            h.assertValueEqual(station.carrierInput(), Optional.of(Direction.SOUTH), "The actual rf0 external selection persisted");
            h.assertTrue(carrier(station).output().frequency().isEmpty(), "A loaded but inactive clock does not emit");
        }).thenExecute(() -> level.setChunkForced(location.x, location.z, true))
        .thenWaitUntil(() -> {
            h.assertTrue(chunks.isPositionTicking(location.toLong()) && level.areEntitiesLoaded(location.toLong()),
                "The reloaded fixture reaches the world's device tick boundary");
            int saved = staged.capture.getInt("ActiveTicks");
            int current = active(level, staged.reloadedMultiplier);
            h.assertTrue(current != saved, "Wait for the first actual multiplier update");
            h.assertValueEqual(current, saved + 1, "The first new active tick continues, neither skipping nor restarting capture");
            h.assertValueEqual(carrier(staged.reloadedComputer.getBehaviour(ComputerBehaviour.TYPE)).output().state(),
                CarrierClock.State.ACQUIRING, "No locked RF carrier can be exposed immediately");
        }).thenWaitUntil(() -> {
            var machine = staged.reloadedComputer.getBehaviour(ComputerBehaviour.TYPE);
            h.assertValueEqual(carrier(machine).output().frequency(), Optional.of(new CarrierFrequency(200, 3)),
                "Only after the remaining active ticks does synthesized RF return");
            h.assertValueEqual(((DataValue.BooleanValue) machine.radioStatus().field("armed")).value(), true,
                "The original armed state survived without a new tune");
        }).thenExecute(() -> {
            level.setChunkForced(location.x, location.z, false);
            level.setChunkForced(viewOnlyAnchor.x, viewOnlyAnchor.z, false);
        }).thenSucceed();
    }

    @GameTest(templateNamespace = "create_biotech_instruments", template = "empty", timeoutTicks = 1600)
    public static void carrierLineInANonTickingChunkCannotLeakIntoTheInternalPreset(GameTestHelper h) {
        var level = h.getLevel();
        var chunks = level.getChunkSource();
        var far = h.absolutePos(new BlockPos(98304, 4, 1));
        var origin = new BlockPos((far.getX() & ~15) + 4, far.getY(), (far.getZ() & ~15) + 15);
        var computerChunk = new ChunkPos(origin);
        var lineChunk = new ChunkPos(origin.south());
        var asymmetricAnchor = new ChunkPos(computerChunk.x, computerChunk.z - 1);
        String port = ResidentRadioHardware.PORT;
        level.setChunkForced(computerChunk.x, computerChunk.z, true);
        level.setChunkForced(lineChunk.x, lineChunk.z, true);
        var stations = new ComputerBehaviour[2];

        h.startSequence().thenWaitUntil(() -> h.assertTrue(chunks.isPositionTicking(computerChunk.toLong())
            && chunks.isPositionTicking(lineChunk.toLong()), "The full circuit starts with both chunks active"))
        .thenExecute(() -> {
            stations[0] = seat(h, origin).getBehaviour(ComputerBehaviour.TYPE);
            stations[1] = seat(h, origin.east(7).north(2)).getBehaviour(ComputerBehaviour.TYPE);
            ResidentCarrierWorldGameTests.loop(h, origin);
            stations[0].configureCarrierInput(stations[0].programRevision(), Optional.of(Direction.SOUTH));
            for (var machine : stations) machine.configureRadio(machine.programRevision(), 0, true);
        }).thenWaitUntil(() -> {
            h.assertValueEqual(carrier(stations[0]).output().frequency(), Optional.of(new CarrierFrequency(200, 3)),
                "The loop crosses the active boundary");
            h.assertValueEqual(stations[1].serialDevice().status(port).connection(), "ready", "A local 100-unit listener is armed");
        }).thenExecute(() -> {
            var frame = new DataValue.RecordValue(Map.of("sample", new DataValue.TextValue("carrier_outage".repeat(40))));
            h.assertValueEqual(stations[0].serialDevice().send(port, frame), "queued", "A real multicycle frame starts");
        }).thenWaitUntil(() -> h.assertValueEqual(stations[0].serialDevice().status(port).transmit(), "transmitting",
            "Wait until the old outside-carrier frame is on the air"))
        .thenExecute(() -> {
            level.setChunkForced(asymmetricAnchor.x, asymmetricAnchor.z, true);
            level.setChunkForced(lineChunk.x, lineChunk.z, false);
            level.setChunkForced(computerChunk.x, computerChunk.z, false);
        }).thenWaitUntil(() -> {
            h.assertTrue(level.shouldTickBlocksAt(computerChunk.toLong())
                && chunks.getChunkNow(lineChunk.x, lineChunk.z) != null
                && !level.shouldTickBlocksAt(lineChunk.toLong()), "The wire chunk remains FULL but not active");
            var input = carrier(stations[0]);
            h.assertValueEqual(input.input(), Optional.of(Direction.SOUTH), "The external face was not unplugged");
            h.assertValueEqual(input.output().state(), CarrierClock.State.UNAVAILABLE, "The live Computer must not trace a non-ticking wire");
            h.assertValueEqual(stations[0].serialDevice().status(port).transmit(), "interrupted", "The in-flight frame is retired");
        }).thenIdle(10).thenExecute(() -> {
            h.assertTrue(!level.shouldTickBlocksAt(lineChunk.toLong()), "Observing the inactive circuit must not upgrade its ticket");
            h.assertValueEqual(stations[1].serialDevice().status(port).receive().frames(), 0, "No default-frequency fallback or leakage");
            level.setChunkForced(lineChunk.x, lineChunk.z, true);
        }).thenWaitUntil(() -> {
            h.assertValueEqual(carrier(stations[0]).output().frequency(), Optional.of(new CarrierFrequency(200, 3)),
                "The same external route is usable when both chunks are active");
            h.assertValueEqual(stations[0].serialDevice().status(port).connection(), "ready", "Same RF port is connected again");
            h.assertValueEqual(stations[0].serialDevice().status(port).transmit(), "interrupted", "Recovery does not restart an old send");
        }).thenExecute(() -> {
            h.assertValueEqual(stations[1].serialDevice().status(port).receive().frames(), 0, "No replay on the built-in listener");
            level.setChunkForced(lineChunk.x, lineChunk.z, false);
            level.setChunkForced(asymmetricAnchor.x, asymmetricAnchor.z, false);
        }).thenSucceed();
    }

    private static final class RecoveryCheckpoint {
        ComputerBlockEntity originalComputer, reloadedComputer;
        ComputerBlockEntity originalMultiplier, reloadedMultiplier;
        long unloadedAt;
        CompoundTag capture;
    }

    private static CarrierPortStatus carrier(ComputerBehaviour machine) {
        return CarrierPortStatus.decode((DataValue.RecordValue) machine.radioStatus().field("carrier"));
    }

    private static CompoundTag capture(ServerLevel level, ComputerBlockEntity multiplier) {
        return multiplier.saveWithoutMetadata(level.registryAccess()).getCompound("BiocomputingWitchCarrier").getCompound("Converter").getCompound("Capture");
    }

    private static int active(ServerLevel level, ComputerBlockEntity multiplier) {
        return capture(level, multiplier).getInt("ActiveTicks");
    }

    private static ComputerBlockEntity seat(GameTestHelper h, BlockPos p) {
        return ResidentRadioWorldGameTests.placeAbsolute(h, p, true);
    }
}
