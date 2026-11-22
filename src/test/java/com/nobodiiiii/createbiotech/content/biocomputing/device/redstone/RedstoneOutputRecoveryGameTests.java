package com.nobodiiiii.createbiotech.content.biocomputing.device.redstone;

import static com.nobodiiiii.createbiotech.content.biocomputing.device.redstone.RedstoneOutputSerialGameTests.*;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialReceiveBuffer;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual wire/adapter failure boundaries; not a player UI or cold-JVM acceptance. */
@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class RedstoneOutputRecoveryGameTests {
    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void replyCollisionDoesNotUndoAppliedPowerOrRetryTheCommand(GameTestHelper h) {
        fixture(h, path -> {
            var branch = path.computer().blockEntity.getBlockPos().east(2).south();
            h.getLevel().setBlock(branch, CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
            var jammer = computer(h, branch.south());
            jammer.serialDevice().configure("s0", Direction.NORTH, true);
            h.runAfterDelay(4, () -> exchange(h, path.computer(), command(path.service().address(), "query", "status", Map.of()), token -> {
                var before = path.output().state();
                var set = command(path.service().address(), "set", "set", arguments(token, 9));
                h.assertValueEqual(path.computer().serialDevice().send("s0", set), "queued", "Actual wire command");
                await(h, () -> path.output().appliedLevel() == 9 && path.service().device().status("s0").transmit().equals("transmitting"), () -> {
                    h.assertValueEqual(jammer.serialDevice().send("s0", new DataValue.RecordValue(Map.of(
                        "kind", text("overlapping_emission"), "padding", text("x".repeat(1000))))), "queued", "Third physical device overlaps the reply, not the command");
                    await(h, () -> path.service().device().status("s0").transmit().equals("collision")
                        && jammer.serialDevice().status("s0").transmit().equals("collision"), () -> {
                        h.assertValueEqual(path.output().appliedLevel(), 9, "Reply collision does not undo an accepted command");
                        h.assertValueEqual(path.output().state().revision(), before.revision() + 1, "Only one application");
                        h.assertValueEqual(h.getLevel().getBlockState(path.position().east()).getValue(RedStoneWireBlock.POWER), 9, "Real circuit remains energized");
                        h.assertTrue(path.computer().serialDevice().status("s0").receive().faults().contains(SerialReceiveBuffer.Fault.COLLISION), "Receiver sees physical collision");
                        h.assertTrue(path.computer().serialDevice().poll("s0").isEmpty(), "Neither collided frame becomes a valid reply");
                        h.runAfterDelay(20, () -> {
                            h.assertTrue(path.computer().serialDevice().poll("s0").isEmpty(), "No hidden reliable retransmission");
                            exchange(h, path.computer(), command(path.service().address(), "inspect", "status", Map.of()), status -> {
                                h.assertValueEqual(((DataValue.RecordValue)status.field("state")).field("applied"), integer(9), "Public query resolves unknown outcome");
                                exchange(h, path.computer(), set, refusal -> {
                                    h.assertValueEqual(refusal.field("code"), text("changed"), "Explicit old-version retry is refused");
                                    h.assertValueEqual(path.output().state().revision(), before.revision() + 1, "Old command cannot apply twice");
                                    h.succeed();
                                });
                            });
                        });
                    });
                });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void rebuildingDuringReplyKeepsDesiredButNeverReplaysPowerOrAnOldSuccess(GameTestHelper h) {
        fixture(h, path -> exchange(h, path.computer(), command(path.service().address(), "query", "status", Map.of()), token -> {
            var before = path.output().state();
            h.assertValueEqual(path.computer().serialDevice().send("s0",
                command(path.service().address(), "set", "set", arguments(token, 9))), "queued", "Actual finite command");
            await(h, () -> path.output().appliedLevel() == 9 && path.service().device().status("s0").transmit().equals("transmitting"), () -> {
                h.assertValueEqual(path.output().state().revision(), before.revision() + 1, "Applied once before interruption");
                var restored = rebuild(h, path);
                h.assertValueEqual(restored.output().state().desired(), 9, "Desired value is durable");
                h.assertValueEqual(restored.output().appliedLevel(), 0, "Loaded device does not reapply old desired value");
                h.assertValueEqual(restored.output().state().revision(), before.revision() + 1, "Accepted operation is not forgotten");
                h.assertValueEqual(restored.service().device().status("s0").transmit(), "interrupted", "Partial reply is not resumed or restarted");
                h.assertValueEqual(restored.service().device().status("s0").failure(), "reloaded", "Interruption reason remains explicit");
                h.runAfterDelay(20, () -> {
                    h.assertTrue(restored.computer().serialDevice().poll("s0").isEmpty(), "No stale success or automatic reply replay");
                    h.assertValueEqual(restored.output().appliedLevel(), 0, "Automatic device ticks do not energize output");
                    exchange(h, restored.computer(), command(restored.service().address(), "inspect", "status", Map.of()), status -> {
                        var state = (DataValue.RecordValue)status.field("state");
                        h.assertValueEqual(state.field("desired"), integer(9), "Explicit query exposes saved intent");
                        h.assertValueEqual(state.field("applied"), integer(0), "Explicit query distinguishes actual electrical state");
                        h.assertValueEqual(state.field("revision"), integer(before.revision() + 1), "Read cannot repeat accepted write");
                        h.succeed();
                    });
                });
            });
        }));
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void receivedSetSurvivesOwnerRebuildButOldSessionCannotEnergizeTheLoadedOutput(GameTestHelper h) {
        fixture(h, path -> exchange(h, path.computer(), command(path.service().address(), "query", "status", Map.of()), token -> {
            var before = path.output().state();
            var set = command(path.service().address(), "old-set", "set", arguments(token, 13));
            h.assertValueEqual(path.computer().serialDevice().send("s0", set), "queued", "Send through actual wire");
            await(h, () -> path.service().device().status("s0").receive().frames() == 1, () -> {
                h.assertTrue(path.output().state() == before, "Command is fully received but not applied");
                var frame = path.service().device().prepareReceive("s0");
                var restored = rebuild(h, path);
                var received = restored.service().device().prepareReceive("s0");
                h.assertValueEqual(received.result().field("value"), set, "Full received frame retained across owner serialization");
                h.assertValueEqual(received.source(), frame.source(), "Physical source proof retained, not reauthored from payload");
                h.assertValueEqual(restored.output().state().revision(), before.revision(), "No early application");
                h.assertTrue(!restored.output().state().session().equals(before.session()), "New output session fences old command");
                reply(h, restored.computer(), refusal -> {
                    h.assertValueEqual(refusal.field("request"), text("old-set"), "Failure belongs to original command");
                    h.assertValueEqual(refusal.field("code"), text("stale_session"), "Old command is explicit failure, not 0 or success");
                    h.assertValueEqual(restored.output().appliedLevel(), 0, "Loaded output never energizes from old queued work");
                    h.assertValueEqual(restored.output().state().revision(), before.revision(), "Refusal does not spend a write revision");
                    h.assertValueEqual(restored.service().device().status("s0").receive().frames(), 0, "Old frame consumed with refusal once");
                    h.runAfterDelay(12, () -> {
                        h.assertTrue(restored.computer().serialDevice().poll("s0").isEmpty(), "No automatic retry of refused operation");
                        exchange(h, restored.computer(), command(restored.service().address(), "fresh-query", "status", Map.of()), fresh ->
                            exchange(h, restored.computer(), command(restored.service().address(), "fresh-set", "set", arguments(fresh, 13)), applied -> {
                                h.assertValueEqual(applied.field("code"), text("applied"), "Explicit new-session request works");
                                h.assertValueEqual(restored.output().state().revision(), before.revision() + 1, "Only fresh request applies");
                                h.assertValueEqual(h.getLevel().getBlockState(restored.position().east()).getValue(RedStoneWireBlock.POWER), 13, "Fresh command controls real circuit");
                                h.succeed();
                            }));
                    });
                });
            });
        }));
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void fullCallerBufferDoesNotOverwriteOlderRepliesOrPretendTheSetWasReceived(GameTestHelper h) {
        fixture(h, path -> exchange(h, path.computer(), command(path.service().address(), "token", "status", Map.of()), token -> {
            int capacity = path.computer().serialDevice().attachments().getFirst().hardware().receiveFrames();
            fillCaller(h, path, 0, capacity, () -> {
                var before = path.output().state();
                var set = command(path.service().address(), "full-set", "set", arguments(token, 11));
                h.assertValueEqual(path.computer().serialDevice().send("s0", set), "queued", "Full RX does not invent a remote acknowledgement");
                await(h, () -> path.computer().serialDevice().status("s0").receive().faults().contains(SerialReceiveBuffer.Fault.BUFFER_FULL), () -> {
                    h.assertValueEqual(path.output().appliedLevel(), 11, "Device may apply even when caller misses its reply");
                    h.assertValueEqual(path.output().state().revision(), before.revision() + 1, "One accepted write only");
                    h.assertValueEqual(path.computer().serialDevice().status("s0").receive().frames(), capacity, "Finite receiver is not enlarged");
                    for (int i = 0; i < capacity; i++)
                        h.assertValueEqual(path.computer().serialDevice().poll("s0").orElseThrow().field("request"), text("fill-" + i), "Old frames remain in order");
                    h.assertTrue(path.computer().serialDevice().poll("s0").isEmpty(), "Lost set reply is not fabricated");
                    h.runAfterDelay(20, () -> {
                        h.assertTrue(path.computer().serialDevice().poll("s0").isEmpty(), "Draining RX does not automatically resend a lost reply");
                        exchange(h, path.computer(), command(path.service().address(), "inspect", "status", Map.of()), status -> {
                            var state = (DataValue.RecordValue)status.field("state");
                            h.assertValueEqual(state.field("applied"), integer(11), "Explicit query can inspect unknown business outcome");
                            h.assertValueEqual(state.field("revision"), integer(before.revision() + 1), "Inspection is read-only");
                            h.succeed();
                        });
                    });
                });
            });
        }));
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void busyReplyBufferKeepsReceivedSetAndLatchTogetherUntilItCanPublish(GameTestHelper h) {
        fixture(h, path -> exchange(h, path.computer(), command(path.service().address(), "query", "status", Map.of()), reply -> {
            var before = path.output().state();
            var set = command(path.service().address(), "set", "set", arguments(reply, 7));
            h.assertValueEqual(path.computer().serialDevice().send("s0", set), "queued", "Command uses actual cable time");
            await(h, () -> path.service().device().status("s0").receive().frames() == 1, () -> {
                var received = path.service().device().prepareReceive("s0");
                h.assertValueEqual(received.result().field("value"), set, "Complete command arrived, not yet applied");
                h.assertTrue(path.output().state() == before, "Wire reception alone does not apply a command");
                var busy = new DataValue.RecordValue(Map.of("kind", text("test_busy"), "padding", text("x".repeat(1800))));
                h.assertValueEqual(path.service().device().send("s0", busy), "queued", "One finite hardware TX slot is occupied");
                path.service().processReceived();
                h.assertTrue(path.output().state() == before, "Busy reply slot cannot half-apply output");
                h.assertValueEqual(path.service().device().prepareReceive("s0").result().field("value"), set, "Command remains owned by RX buffer");
                h.runAfterDelay(3, () -> {
                    h.assertValueEqual(path.service().device().status("s0").transmit(), "transmitting", "Busy frame is really on the wire");
                    h.assertTrue(path.output().state() == before, "Automatic adapter ticks also respect TX backpressure");
                    h.assertValueEqual(path.service().device().status("s0").receive().frames(), 1, "No lost or duplicate input");
                    reply(h, path.computer(), value -> {
                        h.assertValueEqual(value, busy, "First occupied frame returns without overwritten payload");
                        reply(h, path.computer(), applied -> {
                            h.assertValueEqual(applied.field("request"), text("set"), "Original command, no resend");
                            h.assertValueEqual(applied.field("code"), text("applied"), "Application follows available reply capacity");
                            h.assertValueEqual(path.output().state().revision(), before.revision() + 1, "Exactly one application");
                            h.assertValueEqual(h.getLevel().getBlockState(path.position().east()).getValue(RedStoneWireBlock.POWER), 7, "Real output updated once");
                            h.runAfterDelay(8, () -> {
                                h.assertValueEqual(path.output().state().revision(), before.revision() + 1, "No automatic second execution");
                                h.assertValueEqual(path.service().device().status("s0").receive().frames(), 0, "Published command consumed");
                                h.succeed();
                            });
                        });
                    });
                });
            });
        }));
    }

    private record Path(ComputerBehaviour computer, RedstoneOutputBlockEntity output,
                        RedstoneOutputSerialBehaviour service, BlockPos position) {}
    private static void fixture(GameTestHelper h, Consumer<Path> ready) {
        var level = h.getLevel(); var origin = h.absolutePos(new BlockPos(2, 2, 3)); var position = origin.east(4);
        var computer = computer(h, origin);
        for (int x = 1; x < 4; x++) level.setBlock(origin.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(position, CBBlocks.REDSTONE_OUTPUT.get().defaultBlockState().setValue(RedstoneOutputBlock.FACING, Direction.EAST), 3);
        level.setBlock(position.east().below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(position.east(), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        var output = (RedstoneOutputBlockEntity)level.getBlockEntity(position);
        var service = output.getBehaviour(RedstoneOutputSerialBehaviour.TYPE);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "redstone-recovery"));
        actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(position));
        computer.serialDevice().configure("s0", Direction.EAST, true);
        h.runAfterDelay(4, () -> {
            h.assertValueEqual(service.configure(actor, 0, Optional.of(service.discover(actor).getFirst())), "configured", "Explicit discovered source grant");
            h.runAfterDelay(3, () -> ready.accept(new Path(computer, output, service, position)));
        });
        h.runAfterDelay(1550, () -> h.fail("Recovery path stalled: source=" + computer.serialDevice().view() + "; output=" + service.status()));
    }
    private static Map<String, DataValue> arguments(DataValue.RecordValue reply, int level) {
        var fields = new LinkedHashMap<>(((DataValue.RecordValue)reply.field("state")).fields());
        fields.keySet().retainAll(Set.of("device", "session", "revision")); fields.put("level", integer(level)); return fields;
    }
    private static Path rebuild(GameTestHelper h, Path path) {
        var level = h.getLevel(); var saved = path.output().saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(path.position());
        var restored = new RedstoneOutputBlockEntity(path.output().getType(), path.position(), level.getBlockState(path.position()));
        restored.loadWithComponents(saved, level.registryAccess()); level.setBlockEntity(restored); restored.initialize();
        return new Path(path.computer(), restored, restored.getBehaviour(RedstoneOutputSerialBehaviour.TYPE), path.position());
    }
    private static void fillCaller(GameTestHelper h, Path path, int count, int capacity, Runnable filled) {
        if (count == capacity) { filled.run(); return; }
        h.assertValueEqual(path.computer().serialDevice().send("s0", command(path.service().address(), "fill-" + count, "status", Map.of())),
            "queued", "Fill RX using actual status replies, not injected queue data");
        await(h, () -> path.computer().serialDevice().status("s0").receive().frames() == count + 1,
            () -> fillCaller(h, path, count + 1, capacity, filled));
    }
    private static void await(GameTestHelper h, BooleanSupplier condition, Runnable ready) {
        h.runAfterDelay(1, () -> { if (condition.getAsBoolean()) ready.run(); else await(h, condition, ready); });
    }
    private static void reply(GameTestHelper h, ComputerBehaviour computer, Consumer<DataValue.RecordValue> result) {
        await(h, () -> computer.serialDevice().status("s0").receive().frames() > 0,
            () -> result.accept(computer.serialDevice().poll("s0").orElseThrow()));
    }
}
