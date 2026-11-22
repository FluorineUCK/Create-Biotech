package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Actual world/finite transport evidence, not a rendered UI or public driver test. */
@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class ReaderSerialGameTests {
    private static final String REDSTONE = "create_biotech:redstone";
    private static final Set<UUID> DENIED = new HashSet<>();
    private static final Set<UUID> OBSERVED = new HashSet<>();
    private static final String LARGE = "create_biotech:test_large_reader_value";
    private static int largeReads;
    static { ReadComponents.registerPolicy(context -> {
        var observed = context.actor().getUUID(); OBSERVED.add(observed);
        return DENIED.contains(observed) ? Optional.of(new DataValue.ErrorValue("access_denied", "Test policy revoked")) : Optional.empty();
    });
        ReadComponents.register(new ReadComponentProvider<BlockReadContext>() {
            public ReadComponentDescriptor descriptor() { return new ReadComponentDescriptor(LARGE, "test.large", Set.of("block"),
                com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.TEXT, ReadComponentDescriptor.UpdateHint.EXPLICIT, true); }
            public boolean supports(BlockReadContext context) { return true; }
            public DataValue read(BlockReadContext context) { largeReads++; return text("x".repeat(6000)); }
        });
    }
    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void reloadFencesOldSessionsAndOversizeSamplesDoNotBlockLaterCommands(GameTestHelper h) {
        var level = h.getLevel(); var origin = h.absolutePos(new BlockPos(2, 2, 3)); var pos = origin.east(4);
        var computer = computer(h, origin);
        for (int x = 1; x < 4; x++) level.setBlock(origin.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(pos, CBBlocks.BLOCK_READER.get().defaultBlockState().setValue(BlockReaderBlock.FACING, Direction.EAST), 3);
        level.setBlock(pos.east(), Blocks.STONE.defaultBlockState(), 3);
        var reader = (BlockReaderBlockEntity)level.getBlockEntity(pos); var service = reader.getBehaviour(ReaderSerialBehaviour.TYPE);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "reader-recovery")); actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        computer.serialDevice().configure("s0", Direction.EAST, true);
        h.runAfterDelay(4, () -> {
            h.assertValueEqual(service.configure(actor, 0, Optional.of(service.discover(actor).getFirst()), Set.of(LARGE)), "configured", "Explicit grant");
            var privacy = BlockSnapshotReader.readComponents(level, pos, Direction.WEST, actor, new ReadLimits(4096, 32768, 65536), Set.of(BuiltinReadComponents.SERIALIZED_DATA));
            h.assertValueEqual(((DataValue.ErrorValue)privacy.field(BuiltinReadComponents.SERIALIZED_DATA)).code(), "access_denied", "Generic serialization cannot read reader authority/queues");
            h.assertTrue(!reader.getUpdateTag(level.registryAccess()).contains("BiocomputingReaderSerial"), "No private archive in client sync");
            h.runAfterDelay(4, () -> exchange(h, computer, command(service.address(), "status", Map.of()), before -> {
                var old = (DataValue.RecordValue)before.field("state");
                var archive = reader.saveWithFullMetadata(level.registryAccess());
                reader.loadWithComponents(archive, level.registryAccess());
                h.assertValueEqual(service.components(), Set.of(LARGE), "Saved component grant restored");
                h.runAfterDelay(4, () -> exchange(h, computer, command(service.address(), "sample", sampleArgs(old, LARGE)), stale -> {
                    h.assertValueEqual(stale.field("code"), text("stale_session"), "Old complete command cannot sample a new session");
                    exchange(h, computer, command(service.address(), "status", Map.of()), fresh -> {
                        var state = (DataValue.RecordValue)fresh.field("state");
                        h.assertValueEqual(state.field("device"), old.field("device"), "Same persisted hardware identity");
                        int reads = largeReads;
                        exchange(h, computer, command(service.address(), "sample", sampleArgs(state, LARGE)), oversized -> {
                            h.assertValueEqual(oversized.field("code"), text("payload_too_large"), "Explicit bounded-frame refusal");
                            h.assertTrue(!oversized.fields().containsKey("value"), "No successful partial sample");
                            h.assertValueEqual(largeReads, reads + 1, "One explicit request captures once");
                            exchange(h, computer, command(service.address(), "status", Map.of()), next -> {
                                h.assertValueEqual(next.field("code"), text("ok"), "Large sample does not strand queue");
                                h.assertValueEqual(largeReads, reads + 1, "Status query does not retry sample");
                                h.assertValueEqual(service.device().send("s0", next), "queued", "Pending device reply before grant replacement");
                                h.runAfterDelay(1, () -> {
                                    h.assertValueEqual(service.device().status("s0").transmit(), "transmitting", "Reply is actually on the shared medium");
                                    h.assertValueEqual(service.configure(actor, service.revision(), service.binding(), Set.of(BuiltinReadComponents.BLOCK)), "configured", "Replace component grant");
                                    h.assertValueEqual(service.device().status("s0").transmit(), "interrupted", "Local send is interrupted");
                                    h.runAfterDelay(100, () -> {
                                        h.assertTrue(computer.serialDevice().poll("s0").isEmpty(), "Old active medium must not deliver after replaced authority");
                                        exchange(h, computer, command(service.address(), "status", Map.of()), current -> {
                                            var grant = (DataValue.RecordValue)current.field("state");
                                            h.assertTrue(!grant.field("session").equals(state.field("session")), "Grant replacement changes sample session");
                                            exchange(h, computer, command(service.address(), "sample", sampleArgs(grant, BuiltinReadComponents.BLOCK)), result -> {
                                                var snapshot = (DataValue.RecordValue)result.field("value");
                                                var block = (DataValue.RecordValue)((DataValue.RecordValue)snapshot.field("components")).field(BuiltinReadComponents.BLOCK);
                                                h.assertValueEqual(block.field("id"), text("minecraft:stone"), "Explicit new request succeeds after reconnection"); h.succeed();
                                            });
                                        });
                                    });
                                });
                            });
                        });
                    });
                }));
            }));
        });
    }
    private static Map<String, DataValue> sampleArgs(DataValue.RecordValue state, String component) {
        return Map.of("device", state.field("device"), "session", state.field("session"), "components", new DataValue.ListValue(List.of(text(component))));
    }
    @GameTest(template = "empty", timeoutTicks = 950)
    public static void authorizedKineticSamplesDistinguishTheLiveNetworkFromTheLastSnapshot(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 3, 3));
        var computer = computer(h, origin);
        var readerPos = origin.east(4);
        for (int x = 1; x < 4; x++) level.setBlockAndUpdate(origin.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(readerPos, CBBlocks.BLOCK_READER.get().defaultBlockState()
            .setValue(BlockReaderBlock.FACING, Direction.EAST));
        var pumpPos = readerPos.east();
        level.setBlockAndUpdate(pumpPos, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(pumpPos.above(), AllBlocks.COGWHEEL.getDefaultState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        var drive = pumpPos.above().west();
        level.setBlockAndUpdate(drive, AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(CreativeMotorBlock.FACING, Direction.EAST));
        var motor = (CreativeMotorBlockEntity)level.getBlockEntity(drive);
        motor.initialize(); motor.generatedSpeed.setValue(64); motor.updateGeneratedRotation();
        var pump = (PumpBlockEntity)level.getBlockEntity(pumpPos);
        var reader = (BlockReaderBlockEntity)level.getBlockEntity(readerPos);
        var service = reader.getBehaviour(ReaderSerialBehaviour.TYPE);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "kinetics-meter"));
        actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(readerPos));
        computer.serialDevice().configure("s0", Direction.EAST, true);
        h.runAfterDelay(20, () -> {
            var network = pump.getUpdateTag(level.registryAccess()).getCompound("Network");
            float stress = network.getFloat("Stress"), capacity = network.getFloat("Capacity");
            float speed = pump.getSpeed();
            h.assertTrue(speed != 0 && capacity > stress && stress > 0, "Fixture includes a working, loaded stress consumer");
            h.assertValueEqual(service.configure(actor, 0, Optional.of(service.discover(actor).getFirst()),
                Set.of(CreateReadComponents.KINETICS)), "configured", "Grant the kinetic projection only");
            h.runAfterDelay(4, () -> exchange(h, computer, command(service.address(), "status", Map.of()), status -> {
                h.assertValueEqual(status.field("code"), text("ok"), "The public query returns the reader session");
                var state = (DataValue.RecordValue)status.field("state");
                var args = sampleArgs(state, CreateReadComponents.KINETICS);
                exchange(h, computer, command(service.address(), "sample", args), reply -> {
                    var powered = kineticSample(h, reply);
                    h.assertValueEqual(powered.field("speed"), new DataValue.DecimalValue(speed), "Native signed RPM");
                    h.assertValueEqual(powered.field("theoretical_speed"), new DataValue.DecimalValue(pump.getTheoreticalSpeed()),
                        "Native theoretical speed");
                    h.assertValueEqual(powered.field("network_stress"), new DataValue.DecimalValue(stress), "Synchronized network stress");
                    h.assertValueEqual(powered.field("network_capacity"), new DataValue.DecimalValue(capacity), "Synchronized network capacity");
                    h.assertValueEqual(powered.field("overstressed"), new DataValue.BooleanValue(false), "Actual network is not overloaded");
                    h.assertValueEqual(pump.getUpdateTag(level.registryAccess()).getCompound("Network"), network,
                        "Reading does not refresh or change native stress data");
                    level.setBlockAndUpdate(drive, Blocks.AIR.defaultBlockState());
                    exchange(h, computer, command(service.address(), "sample", args), stopped -> {
                        var idle = kineticSample(h, stopped);
                        h.assertValueEqual(pump.getSpeed(), 0f, "The same native pump loses power when the fixture drive is removed");
                        h.assertValueEqual(idle.field("speed"), new DataValue.DecimalValue(0), "New sample observes stopped pump");
                        h.assertTrue(!pump.hasNetwork(), "Fixture removed the kinetic network from the target");
                        h.assertTrue(idle.field("network_capacity") instanceof DataValue.ErrorValue error && error.code().equals("unsupported"),
                            "Missing network is an explicit unsupported field, not the old capacity or zero");
                        h.assertValueEqual(powered.field("network_capacity"), new DataValue.DecimalValue(capacity), "First result remains a snapshot");
                        h.succeed();
                    });
                });
            }));
        });
    }
    private static DataValue.RecordValue kineticSample(GameTestHelper h, DataValue.RecordValue reply) {
        h.assertValueEqual(reply.field("code"), text("ok"), "Fresh sample accepted");
        var snapshot = (DataValue.RecordValue)reply.field("value");
        var components = (DataValue.RecordValue)snapshot.field("components");
        h.assertValueEqual(components.fields().keySet(), Set.of(CreateReadComponents.KINETICS), "No raw NBT or additional components leak");
        return (DataValue.RecordValue)components.field(CreateReadComponents.KINETICS);
    }

    @GameTest(template = "empty", timeoutTicks = 700)
    public static void authorizedCreatePipeSampleReportsLocalFlowWithoutTakingFluid(GameTestHelper h) {
        final String component = "create_biotech:create_fluid_transport";
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(4, 3, 2));
        var computer = computer(h, origin);
        var readerPos = origin.south(4);
        for (int z = 1; z < 4; z++) level.setBlockAndUpdate(origin.south(z), CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(readerPos, CBBlocks.BLOCK_READER.get().defaultBlockState()
            .setValue(BlockReaderBlock.FACING, Direction.SOUTH));
        var pipe = readerPos.south();
        var pump = pipe.east();
        var sourcePos = pipe.west();
        var sinkPos = pump.east();
        level.setBlockAndUpdate(sourcePos, AllBlocks.FLUID_TANK.getDefaultState());
        level.setBlockAndUpdate(sinkPos, AllBlocks.FLUID_TANK.getDefaultState());
        var source = ((FluidTankBlockEntity)level.getBlockEntity(sourcePos)).getTankInventory();
        var sink = ((FluidTankBlockEntity)level.getBlockEntity(sinkPos)).getTankInventory();
        h.assertValueEqual(source.fill(new FluidStack(Fluids.WATER, 8_000), FluidAction.EXECUTE), 8_000,
            "Supply the fixture with water through the native tank handler");
        level.setBlockAndUpdate(pipe, AllBlocks.SMART_FLUID_PIPE.getDefaultState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        level.setBlockAndUpdate(pump, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(pump.above(), AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        var drive = pump.above().west();
        level.setBlockAndUpdate(drive, AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(CreativeMotorBlock.FACING, Direction.EAST));
        var motor = (CreativeMotorBlockEntity)level.getBlockEntity(drive);
        motor.initialize(); motor.generatedSpeed.setValue(16); motor.updateGeneratedRotation();
        var reader = (BlockReaderBlockEntity)level.getBlockEntity(readerPos);
        var service = reader.getBehaviour(ReaderSerialBehaviour.TYPE);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "pipe-meter"));
        actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(readerPos));
        computer.serialDevice().configure("s0", Direction.SOUTH, true);
        h.runAfterDelay(8, () -> {
            h.assertValueEqual(service.configure(actor, 0, Optional.of(service.discover(actor).getFirst()), Set.of(component)),
                "configured", "Explicit pipe status authorization through the existing component catalog");
            h.runAfterDelay(4, () -> exchange(h, computer, command(service.address(), "status", Map.of()), status -> {
                var current = (DataValue.RecordValue)status.field("state");
                exchange(h, computer, command(service.address(), "sample", sampleArgs(current, component)), reply -> {
                    h.assertValueEqual(reply.field("code"), text("ok"), "Real response has the requested component");
                    var snapshot = (DataValue.RecordValue)reply.field("value");
                    var components = (DataValue.RecordValue)snapshot.field("components");
                    var result = (DataValue.RecordValue)components.field(component);
                    var ports = ((DataValue.ListValue)result.field("ports")).values();
                    var west = pipePort(ports, "west");
                    var east = pipePort(ports, "east");
                    h.assertValueEqual(ports.size(), 2, "Only the target smart pipe's two initialized sides are returned");
                    h.assertValueEqual(west.field("fluid"), text("minecraft:water"), "Inbound fluid identity");
                    h.assertValueEqual(east.field("fluid"), text("minecraft:water"), "Outbound fluid identity");
                    h.assertValueEqual(west.field("direction"), text("inbound"), "Water enters from the source side");
                    h.assertValueEqual(east.field("direction"), text("outbound"), "Water leaves toward the pump");
                    h.assertValueEqual(east.field("stage"), text("flowing"), "The native flow finished propagating to this face");
                    h.assertTrue(sink.getFluidAmount() > 0, "Native pump really moved fixture water");
                    h.assertValueEqual(source.getFluidAmount() + sink.getFluidAmount(), 8_000, "Sampling has no fluid side effect");
                    h.assertValueEqual(service.configure(actor, service.revision(), service.binding(), Set.of(BuiltinReadComponents.BLOCK)),
                        "configured", "Leave the same cable and a different component grant active");
                    h.runAfterDelay(4, () -> exchange(h, computer, command(service.address(), "status", Map.of()), after -> {
                        h.assertValueEqual(after.field("code"), text("ok"), "The Computer is still authorized on this cable");
                        var withoutPipeGrant = (DataValue.RecordValue)after.field("state");
                        exchange(h, computer, command(service.address(), "sample", sampleArgs(withoutPipeGrant, component)), denied -> {
                            h.assertValueEqual(denied.field("code"), text("component_denied"), "Pipe reads require their own grant");
                            h.assertTrue(!denied.fields().containsKey("value"), "No port data is returned without that grant");
                            h.succeed();
                        });
                    }));
                });
            }));
        });
    }

    private static DataValue.RecordValue pipePort(List<DataValue> ports, String face) {
        return ports.stream().map(v -> (DataValue.RecordValue)v)
            .filter(p -> p.field("face").equals(text(face))).findFirst().orElseThrow();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void pipeObservationDoesNotInitializeConnectionsOrTreatATankAsAPipe(GameTestHelper h) {
        var level = h.getLevel();
        var pipe = h.absolutePos(new BlockPos(3, 3, 3));
        level.setBlockAndUpdate(pipe, AllBlocks.SMART_FLUID_PIPE.getDefaultState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        var nativePipe = (SmartBlockEntity)level.getBlockEntity(pipe);
        var behaviour = nativePipe.getBehaviour(FluidTransportBehaviour.TYPE);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "pipe-observer"));
        actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pipe.north()));
        var limits = new ReadLimits(4096, 32768, 65536);
        h.assertTrue(behaviour != null && behaviour.interfaces == null, "New native pipe awaits the first tick");
        var cold = BlockSnapshotReader.readComponents(level, pipe, Direction.NORTH, actor, limits, Set.of(CreateFluidReadComponent.ID));
        h.assertTrue(cold.field(CreateFluidReadComponent.ID) instanceof DataValue.ErrorValue e && e.code().equals("not_initialized"),
            "Uninitialized does not appear to be empty or disconnected");
        h.assertTrue(behaviour.interfaces == null, "Observation did not initialize the native pipe");
        var tank = pipe.east(2);
        level.setBlockAndUpdate(tank, AllBlocks.FLUID_TANK.getDefaultState());
        var storage = BlockSnapshotReader.readComponents(level, tank, Direction.NORTH, actor, limits, Set.of(CreateFluidReadComponent.ID));
        h.assertTrue(storage.field(CreateFluidReadComponent.ID) instanceof DataValue.ErrorValue e && e.code().equals("unsupported"),
            "Tanks do not become transport endpoints");
        h.runAfterDelay(3, () -> {
            var warm = BlockSnapshotReader.readComponents(level, pipe, Direction.NORTH, actor, limits, Set.of(CreateFluidReadComponent.ID));
            var ports = ((DataValue.ListValue)((DataValue.RecordValue)warm.field(CreateFluidReadComponent.ID)).field("ports")).values();
            h.assertValueEqual(ports.size(), 2, "Native pipe initializes both faces even without a running source");
            for (var value : ports) {
                var port = (DataValue.RecordValue)value;
                h.assertValueEqual(port.field("stage"), text("idle"), "An initialized but empty face is idle");
                h.assertValueEqual(port.field("direction"), DataValue.NullValue.INSTANCE, "Idle face has no direction");
                h.assertValueEqual(port.field("fluid"), DataValue.NullValue.INSTANCE, "Idle face has no fluid");
            }
            var defaults = BlockSnapshotReader.read(level, pipe, Direction.NORTH, actor, limits);
            h.assertTrue(!((DataValue.RecordValue)defaults.field("components")).fields().containsKey(CreateFluidReadComponent.ID),
                "Existing broad snapshots leave opt-in telemetry out");
            h.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void offlineGrantSamplesOnlyOnRequestAndRechecksIdentityOnEveryCapture(GameTestHelper h) {
        var level = h.getLevel(); var origin = h.absolutePos(new BlockPos(2, 2, 3)); var pos = origin.east(4);
        var computer = computer(h, origin);
        for (int x = 1; x < 4; x++) level.setBlock(origin.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(pos, CBBlocks.BLOCK_READER.get().defaultBlockState().setValue(BlockReaderBlock.FACING, Direction.EAST), 3);
        level.setBlock(pos.east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        h.assertTrue(level.getBlockEntity(pos) instanceof BlockReaderBlockEntity, "Existing reader is actual serial hardware");
        var reader = (BlockReaderBlockEntity)level.getBlockEntity(pos); var service = reader.getBehaviour(ReaderSerialBehaviour.TYPE);
        h.assertTrue(service.binding().isEmpty() && service.device().attachments().isEmpty(), "Default closed");
        var actor = new MenuPlayer(level); actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        h.assertTrue(level.getServer().getPlayerList().getPlayer(actor.getUUID()) == null, "Grantor is not online");
        computer.serialDevice().configure("s0", Direction.EAST, true);
        h.runAfterDelay(4, () -> {
            h.assertTrue(ReaderMenu.open(actor, reader, "ok"), "Player management entry");
            var menu = actor.client;
            var catalog = ((DataValue.ListValue)menu.view().field("catalog")).values();
            int index = -1;
            for (int i = 0; i < catalog.size(); i++) if (((DataValue.RecordValue)catalog.get(i)).field("id").equals(text(REDSTONE))) index = i;
            h.assertTrue(index >= 0, "Existing component catalog offered");
            actor.send(menu.request(ReaderRequest.Action.GRANT, 0, List.of(index)));
            h.assertValueEqual(actor.client.code(), "configured", "Explicit source and component grant through encoded menu packet");
            actor.send(menu.request(ReaderRequest.Action.REVOKE, -1, List.of()));
            h.assertValueEqual(service.revision(), 1L, "Old menu nonce cannot revoke current grant");
            actor.containerMenu = actor.inventoryMenu;
            h.runAfterDelay(4, () -> exchange(h, computer, command(service.address(), "status", Map.of()), status -> {
                h.assertValueEqual(status.field("code"), text("ok"), "Status query");
                var state = (DataValue.RecordValue)status.field("state");
                var args = Map.<String, DataValue>of("device", state.field("device"), "session", state.field("session"),
                    "components", new DataValue.ListValue(List.of(text(REDSTONE))));
                exchange(h, computer, command(service.address(), "sample", args), first -> {
                    h.assertValueEqual(signal(first), integer(15), "Fresh front-face redstone snapshot");
                    h.assertTrue(OBSERVED.contains(actor.getUUID()), "Offline capture keeps granting identity");
                    level.setBlock(pos.east(), Blocks.STONE.defaultBlockState(), 3);
                    exchange(h, computer, command(service.address(), "sample", args), second -> {
                        h.assertValueEqual(signal(second), integer(0), "Another explicit request samples changed world");
                        h.assertValueEqual(signal(first), integer(15), "Prior reply stays a value copy");
                        DENIED.add(actor.getUUID());
                        exchange(h, computer, command(service.address(), "sample", args), denied -> {
                            h.assertValueEqual(denied.field("code"), text("access_denied"), "Revoked target policy is not bypassed offline");
                            h.assertTrue(!denied.fields().containsKey("value"), "Denied result does not leak sampled data");
                            DENIED.remove(actor.getUUID());
                            h.assertValueEqual(service.configure(actor, service.revision(), Optional.empty(), Set.of()), "configured", "Explicit revoke");
                            h.assertTrue(service.device().attachments().isEmpty(), "Revoke withdraws serial endpoint"); h.succeed();
                        });
                    });
                });
            }));
        });
    }
    private static DataValue signal(DataValue.RecordValue reply) {
        var sample = (DataValue.RecordValue)reply.field("value");
        return ((DataValue.RecordValue)((DataValue.RecordValue)sample.field("components")).field(REDSTONE)).field("signal");
    }
    static ComputerBehaviour computer(GameTestHelper h, BlockPos pos) {
        var level = h.getLevel(); level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        var lower = CBBlocks.COMPUTER.get().defaultBlockState().setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlock(pos, lower, 3); level.setBlock(pos.above(), lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var block = (ComputerBlockEntity)level.getBlockEntity(pos); var villager = EntityType.VILLAGER.create(level); villager.setBaby(false);
        var box = new ItemStack(CBItems.CARDBOARD_BOX.get()); h.assertTrue(CapturedEntityBoxHelper.captureEntity(box, villager), "Resident");
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "reader-computer")); actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        actor.setItemInHand(InteractionHand.MAIN_HAND, box);
        h.assertValueEqual(block.installResident(actor, InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS, "Resident installation");
        var computer = block.getBehaviour(ComputerBehaviour.TYPE); var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(), new DataValue.RecordValue(Map.of()), FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        computer.installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET); return computer;
    }
    static DataValue.RecordValue command(DataValue.RecordValue address, String operation, Map<String, DataValue> args) {
        return new DataValue.RecordValue(Map.of("kind", text("reader_command"), "version", integer(1), "target", address,
            "request", text(UUID.randomUUID().toString()), "operation", text(operation), "arguments", new DataValue.RecordValue(args)));
    }
    static void exchange(GameTestHelper h, ComputerBehaviour computer, DataValue.RecordValue command, Consumer<DataValue.RecordValue> done) {
        h.assertValueEqual(computer.serialDevice().send("s0", command), "queued", "Real finite send");
        h.assertTrue(computer.serialDevice().poll("s0").isEmpty(), "No instantaneous reply");
        await(h, computer, command.field("request"), done);
    }
    private static void await(GameTestHelper h, ComputerBehaviour computer, DataValue correlation, Consumer<DataValue.RecordValue> done) {
        h.runAfterDelay(1, () -> { var reply = computer.serialDevice().poll("s0");
            if (reply.isEmpty()) { await(h, computer, correlation, done); return; }
            h.assertValueEqual(reply.orElseThrow().field("request"), correlation, "Matching response"); done.accept(reply.orElseThrow()); });
    }
    static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
    private static final class MenuPlayer extends FakePlayer {
        ReaderMenu client;
        MenuPlayer(net.minecraft.server.level.ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "reader-owner")); }
        @Override public OptionalInt openMenu(net.minecraft.world.MenuProvider provider, Consumer<net.minecraft.network.RegistryFriendlyByteBuf> opening) {
            containerMenu = provider.createMenu(1, getInventory(), this);
            var b = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try { opening.accept(b); client = CBMenuTypes.BLOCK_READER.get().create(1, getInventory(), b); } finally { b.release(); }
            return OptionalInt.of(1);
        }
        void send(ReaderRequest request) {
            var b = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try { request.write(b); new ReaderRequest(b).handle(this); } finally { b.release(); }
        }
    }
}
