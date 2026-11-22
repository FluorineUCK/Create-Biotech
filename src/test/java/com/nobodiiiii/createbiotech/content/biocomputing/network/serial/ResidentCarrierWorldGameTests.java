package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.device.carrier.CarrierCableBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.device.carrier.CarrierPathBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentRadioHardware;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierClock;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierPortStatus;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.content.biocomputing.device.carrier.WitchCarrierStage;
import net.minecraft.world.entity.EntityType;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class ResidentCarrierWorldGameTests {
    private static final String PORT = ResidentRadioHardware.PORT;
    private static final CarrierFrequency SYNTHESIZED = new CarrierFrequency(200, 3);

    @GameTest(templateNamespace = "create_biotech_instruments", template = "empty", timeoutTicks = 900)
    public static void twoPhysicalTradersLoopTheirOwnCrystalThroughBendsAndNeverFallbackOnLoss(GameTestHelper h) {
        var txOwner = ResidentRadioWorldGameTests.place(h, new BlockPos(2, 4, 2), true);
        var rxOwner = ResidentRadioWorldGameTests.place(h, new BlockPos(8, 4, 2), false);
        var receiverAtPreset = ResidentRadioWorldGameTests.place(h, new BlockPos(3, 4, 7), true).getBehaviour(ComputerBehaviour.TYPE);
        var tx = txOwner.getBehaviour(ComputerBehaviour.TYPE);
        var rx = rxOwner.getBehaviour(ComputerBehaviour.TYPE);
        var sourceSlot = loop(h, txOwner.getBlockPos());
        h.runAfterDelay(5, () -> {
            tx.configureCarrierInput(tx.programRevision(), Optional.of(Direction.SOUTH));
            h.assertValueEqual(tx.radioStatus().field("armed"), new DataValue.BooleanValue(false), "Selecting a face does not turn rf0 on");
            receiverAtPreset.configureRadio(receiverAtPreset.programRevision(), 0, true);
            h.startSequence().thenWaitUntil(() -> expectLocked(h, tx)).thenExecute(() -> {
                // This second Computer gains its own source after the first circuit is already locked.
                rxOwner.clearResidentSource();
                ResidentRadioWorldGameTests.seat(h, rxOwner, true);
                loop(h, rxOwner.getBlockPos());
                rx.configureCarrierInput(rx.programRevision(), Optional.of(Direction.SOUTH));
                tx.configureRadio(tx.programRevision(), 0, true);
                rx.configureRadio(rx.programRevision(), 0, true);
                long revision = tx.programRevision();
                tx.configureCarrierInput(revision, Optional.of(Direction.SOUTH));
                h.assertValueEqual(tx.programRevision(), revision, "Re-selecting the same face does not retire the RF session");
                h.assertTrue(tx.serialDevice().attachments().isEmpty(), "The dedicated carrier wiring does not attach framed serial");
            }).thenWaitUntil(() -> expectLocked(h, rx)).thenExecute(() ->
                send(h, tx, "retained")
            ).thenWaitUntil(() -> h.assertValueEqual(frames(rx), 1, "One complete frame on the synthesized carrier")).thenExecute(() -> {
                h.assertValueEqual(frames(receiverAtPreset), 0, "No delivery on the built-in carrier");
                send(h, tx, "must interrupt");
            }).thenWaitUntil(() -> h.assertValueEqual(tx.serialDevice().status(PORT).transmit(), "transmitting", "A physical frame starts"))
            .thenExecute(() -> h.getLevel().setBlockAndUpdate(sourceSlot, Blocks.AIR.defaultBlockState()))
            .thenWaitUntil(() -> {
                var state = carrier(tx);
                h.assertValueEqual(state.input(), Optional.of(Direction.SOUTH), "Breaking the reference leaves rf0 physically selected");
                h.assertTrue(state.output().frequency().isEmpty(), "Broken reference must not silently revert");
                h.assertValueEqual(tx.serialDevice().status(PORT).transmit(), "interrupted", "An old half-frame cannot survive loss");
            }).thenExecuteAfter(60, () -> {
                h.assertValueEqual(frames(rx), 1, "No partial frame and the full frame remains claimable");
                h.assertValueEqual(frames(receiverAtPreset), 0, "No fallback leakage");
                tx.configureCarrierInput(tx.programRevision(), Optional.empty());
            }).thenWaitUntil(() -> h.assertValueEqual(carrier(tx).output().frequency(),
                Optional.of(CarrierFrequency.whole(100)), "Explicit input disconnection may restore the preset"))
            .thenExecute(() -> send(h, tx, "new builtin frame")).thenWaitUntil(() -> {
                h.assertValueEqual(frames(receiverAtPreset), 1, "A fresh send on the original preset works");
                h.assertValueEqual(frames(rx), 1, "The peer on 200/3 no longer tunes to the sender");
                long revision = rx.programRevision();
                rx.configureCarrierInput(revision, Optional.empty());
                h.assertValueEqual(frames(rx), 1, "Changing the actual receiving face keeps its complete frame");
                var retained = rx.serialDevice().poll(PORT).orElseThrow();
                h.assertValueEqual(retained.field("sample"), new DataValue.TextValue("retained"), "Only the first frame was retained");
                try {
                    rx.configureCarrierInput(revision, Optional.of(Direction.SOUTH));
                    h.fail("A stale face selection must not replace the current one");
                } catch (IllegalArgumentException stale) {
                    h.assertValueEqual(stale.getMessage(), "stale", "The normal editor revision authorizes the face change");
                }
            }).thenSucceed();
        });
    }

    @GameTest(templateNamespace = "create_biotech_instruments", template = "empty")
    public static void cablePlayerInteractionSeparatelySelectsInputAndOutputWithoutReplacingTheCable(GameTestHelper h) {
        var position = h.absolutePos(new BlockPos(2, 4, 2));
        wire(h, position, Direction.WEST, Direction.EAST);
        var level = h.getLevel();
        var player = new net.neoforged.neoforge.common.util.FakePlayer(level,
            new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "clock-wire-fixture"));
        player.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(position));
        var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(position),
            Direction.UP, position, false);
        level.getBlockState(position).useWithoutItem(level, player, hit);
        var afterOutput = level.getBlockState(position);
        h.assertTrue(afterOutput.is(CBBlocks.CARRIER_CABLE.get()), "The placed physical wire remains");
        h.assertValueEqual(afterOutput.getValue(CarrierCableBlock.INPUT), Direction.WEST, "Output adjustment preserves input");
        h.assertTrue(CarrierPathBlock.output(afterOutput) != Direction.EAST, "An empty-handed click adjusts the output");
        player.setShiftKeyDown(true);
        for (int click = 0; click < Direction.values().length; click++) {
            var before = level.getBlockState(position);
            before.useWithoutItem(level, player, hit);
            var after = level.getBlockState(position);
            h.assertValueEqual(CarrierPathBlock.output(after), CarrierPathBlock.output(afterOutput), "Sneak-click leaves output fixed");
            h.assertTrue(!after.getValue(CarrierCableBlock.INPUT).equals(before.getValue(CarrierCableBlock.INPUT))
                && after.getValue(CarrierCableBlock.INPUT) != CarrierPathBlock.output(after), "Only one distinct input is selected");
        }
        h.succeed();
    }

    @GameTest(templateNamespace = "create_biotech_instruments", template = "empty", timeoutTicks = 100)
    public static void topCarrierPinsUseTheOutsideOfTheTwoBlockCase(GameTestHelper h) {
        var trader = ResidentRadioWorldGameTests.place(h, new BlockPos(3, 3, 3), true);
        var origin = trader.getBlockPos();
        wire(h, origin.above(2), Direction.DOWN, Direction.EAST);
        wire(h, origin.above(2).east(), Direction.WEST, Direction.DOWN);
        witch(h, origin.east(), Direction.UP, Direction.SOUTH, WitchCarrierStage.Mode.DIVIDE, 4);
        wire(h, origin.east().south(), Direction.NORTH, Direction.WEST);
        wire(h, origin.south(), Direction.EAST, Direction.NORTH);
        var machine = trader.getBehaviour(ComputerBehaviour.TYPE);
        machine.configureCarrierInput(machine.programRevision(), Optional.of(Direction.SOUTH));
        h.succeedWhen(() -> h.assertValueEqual(carrier(machine).output().frequency(),
            Optional.of(CarrierFrequency.whole(25)), "Top pins use the roof, not the inside of the upper half"));
    }

    static BlockPos loop(GameTestHelper h, BlockPos computer) {
        var source = computer.east();
        wire(h, source, Direction.WEST, Direction.EAST);
        witch(h, computer.east(2), Direction.WEST, Direction.EAST, WitchCarrierStage.Mode.DIVIDE, 3);
        wire(h, computer.east(3), Direction.WEST, Direction.SOUTH);
        witch(h, computer.east(3).south(), Direction.NORTH, Direction.WEST, WitchCarrierStage.Mode.MULTIPLY, 2);
        wire(h, computer.east(2).south(), Direction.EAST, Direction.WEST);
        wire(h, computer.east().south(), Direction.EAST, Direction.WEST);
        wire(h, computer.south(), Direction.EAST, Direction.NORTH);
        return source;
    }

    private static void witch(GameTestHelper h, BlockPos pos, Direction in, Direction out,
                              WitchCarrierStage.Mode mode, int factor) {
        var owner = ResidentRadioWorldGameTests.placeAbsolute(h, pos, false);
        owner.clearResidentSource();
        ResidentRadioWorldGameTests.seat(h, owner, EntityType.WITCH.create(h.getLevel()));
        var computer = owner.getBehaviour(ComputerBehaviour.TYPE);
        computer.configureWitchCarrier(computer.programRevision(),
            new WitchCarrierStage.Setting(mode, Optional.of(in), Optional.of(out), factor));
    }

    private static void wire(GameTestHelper h, BlockPos pos, Direction input, Direction output) {
        h.getLevel().setBlockAndUpdate(pos, CBBlocks.CARRIER_CABLE.get().defaultBlockState()
            .setValue(CarrierPathBlock.FACING, output).setValue(CarrierCableBlock.INPUT, input));
    }

    private static CarrierPortStatus carrier(ComputerBehaviour station) {
        return CarrierPortStatus.decode((DataValue.RecordValue) station.radioStatus().field("carrier"));
    }

    private static void expectLocked(GameTestHelper h, ComputerBehaviour station) {
        var observed = carrier(station);
        h.assertValueEqual(observed.output().state(), CarrierClock.State.LOCKED, "Physical devices lock");
        h.assertValueEqual(observed.output().frequency(), Optional.of(SYNTHESIZED), "Radio observes the exact 2*f/3 carrier");
    }

    private static int frames(ComputerBehaviour computer) {
        return computer.serialDevice().status(PORT).receive().frames();
    }

    private static void send(GameTestHelper h, ComputerBehaviour sender, String label) {
        var record = new DataValue.RecordValue(Map.of("sample", new DataValue.TextValue(label)));
        h.assertValueEqual(sender.serialDevice().send(PORT, record), "queued", "One explicit finite transmission");
    }
}
