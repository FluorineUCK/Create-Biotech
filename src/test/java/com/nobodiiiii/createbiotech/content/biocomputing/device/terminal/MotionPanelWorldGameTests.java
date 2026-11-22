package com.nobodiiiii.createbiotech.content.biocomputing.device.terminal;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.biocomputing.device.settings.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocument;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentWire;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.SerialControlData;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialGatewayAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.network.signal.SignalConnections;
import com.nobodiiiii.createbiotech.content.biocomputing.network.signal.SignalGatewayAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalProjection;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.Instruction;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlock;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import com.simibubi.create.content.redstone.diodes.BrassDiodeBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Loads the exact editable example, not a test-only reconstruction of its program. */
@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class MotionPanelWorldGameTests {
    @GameTest(template = "empty", timeoutTicks = 6500)
    public static void terminalMovesOneRealLoadThroughAuthorizedDevices(GameTestHelper h) {
        var f = new Worksite(h);
        h.runAfterDelay(12, () -> {
            f.authorize();
            f.b.setPaused(false);
            h.runAfterDelay(12, () -> f.run("1", 0, result -> {
                h.assertValueEqual(result.field("status"), text("no_drive"), "Unpowered gearshift refused");
                h.assertValueEqual(result.field("interval_applied"), new DataValue.BooleanValue(false), "Only a plan, no clock write");
                h.assertTrue(f.clockValue() == f.originalInterval && !f.sawSignal && !f.sawPulse,
                    "Unpowered request doesn't change real clock or emit a redstone edge");
                h.assertTrue(f.motion.biocomputingMotionId() == null && f.level.getBlockState(f.load).is(Blocks.GOLD_BLOCK),
                    "No native action or movement");
                f.power();
                h.runAfterDelay(12, () -> {
                    f.a.reset();
                    f.run("3", 1, moved -> {
                        h.assertValueEqual(moved.field("status"), text("sequence_completed"), "The program read completion for its own action");
                        h.assertValueEqual(moved.field("arrival"), text("not_measured"), "A gearshift is not an arrival sensor");
                        var id = f.motion.biocomputingMotionId();
                        h.assertTrue(id != null && text(id.toString()).equals(moved.field("action")), "The published result refers to the real action");
                        h.assertValueEqual(f.clockValue(), 16, "Configured the native interval through the other adapter");
                        h.assertTrue(f.sawSignal && f.sawPulse && f.sawMoving, "Real dust, pulse extender and native pulley responded");
                        h.runAfterDelay(32, () -> {
                            h.assertValueEqual(f.dustPower(), 0, "Program lowered the physical indicator");
                            h.assertTrue(!f.pulsePower(), "Create finished the physical pulse");
                            // A real rope replaces the original cell; requiring air there would reject a successful lowering.
                            h.assertTrue(Math.abs(f.pulley.offset - 3) < 0.001 && f.level.getBlockState(f.load.below(3)).is(Blocks.GOLD_BLOCK),
                                "Measured actual displacement: original load landed exactly three blocks lower");
                            for (int d = 0; d < 3; d++)
                                h.assertTrue(!f.level.getBlockState(f.load.below(d)).is(Blocks.GOLD_BLOCK), "No duplicate at an earlier height");
                            h.assertValueEqual(f.pulley.getSpeed(), 0f, "Native pulley is no longer being driven");
                            h.assertValueEqual(f.motion.biocomputingMotionId(), id, "No second start");
                            h.assertValueEqual(Instruction.serializeAll(f.gear.getInstructions()), f.preset, "The native program was not replaced");
                            h.succeed();
                        });
                    });
                });
            }));
        });
    }

    private static final class Worksite {
        final GameTestHelper h;
        final ServerLevel level;
        final BlockPos terminal, timerAdapter, gearAdapter, clock, dust, load, motor;
        final ComputerBehaviour a, b;
        final SequencedGearshiftBlockEntity gear;
        final SequencedMotion.Access motion;
        final PulleyBlockEntity pulley;
        final DataValue.RecordValue guards;
        final net.minecraft.nbt.Tag preset;
        final Actor player;
        final int originalInterval;
        boolean sawSignal, sawPulse, sawMoving;

        Worksite(GameTestHelper h) {
            this.h = h;
            level = h.getLevel();
            var min = h.absolutePos(new BlockPos(1, 5, 1));
            var max = min.offset(6, 4, 4);
            var gateway = min.offset(6, 1, 2);
            var first = min.offset(1, 1, 2);
            var second = gateway.west();
            for (var pos : BlockPos.betweenClosed(min, max))
                if (pos.getX() == min.getX() || pos.getX() == max.getX() || pos.getY() == min.getY()
                        || pos.getY() == max.getY() || pos.getZ() == min.getZ() || pos.getZ() == max.getZ())
                    level.setBlockAndUpdate(pos, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState());
            level.setBlockAndUpdate(gateway, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState()
                .setValue(ClusterControllerBlock.FACING, Direction.EAST));
            var cluster = (ClusterControllerBlockEntity)level.getBlockEntity(gateway);
            player = new Actor(level);
            a = computer(first);
            b = computer(second);
            player.moveTo(Vec3.atCenterOf(gateway.east()));
            a.configureEndpoint(new EndpointConfiguration("a", true));
            b.configureEndpoint(new EndpointConfiguration("b", true));
            var members = Map.of("a", member(a), "b", member(b));
            var wire = new XBusWire(new XBusWire.Pin("a", "a", "x0"), new XBusWire.Pin("b", "b", "x0"), true);
            h.assertValueEqual(cluster.configure(new ClusterConfiguration(members, Map.of(), Map.of(), Map.of("action", wire)), player),
                "configured", "Closed shell with individually named members");
            b.configureSerialGateway("s0", Optional.of(SerialGatewayAccess.authorize(
                new ClusterMemberEditorAccess(cluster, b.blockEntity), player, "s0", "motion_io")));
            b.configureSerial(new SerialControlData.Configuration("s0", Direction.EAST, true));
            a.configureSignalGateway("p0", Optional.of(SignalGatewayAccess.authorize(
                new ClusterMemberEditorAccess(cluster, a.blockEntity), player, "p0", "indicator")));
            a.configureSignal(new SignalConnections.Configuration("p0", Direction.EAST, true));

            for (int x = 1; x <= 5; x++) cable(gateway.east(x));
            cable(gateway.east(5).north());
            cable(gateway.east(5).north(2));
            cable(gateway.east(3).south());
            terminal = gateway.east(6);
            level.setBlockAndUpdate(terminal, CBBlocks.TERMINAL_UI.get().defaultBlockState()
                .setValue(TerminalUiBlock.FACING, Direction.EAST));
            timerAdapter = gateway.east(4).south();
            adapter(timerAdapter, Direction.SOUTH);
            clock = timerAdapter.south();
            dust = clock.west();
            for (var support : List.of(dust.below(), clock.below())) level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(dust, Blocks.REDSTONE_WIRE.defaultBlockState());
            level.setBlockAndUpdate(clock, AllBlocks.PULSE_EXTENDER.getDefaultState().setValue(BrassDiodeBlock.FACING, Direction.WEST));
            level.setBlockAndUpdate(clock.east(), Blocks.REDSTONE_LAMP.defaultBlockState());
            originalInterval = clockValue();
            gearAdapter = gateway.east(4).north(2);
            adapter(gearAdapter, Direction.WEST);
            var gearPos = gearAdapter.west();
            motor = gearPos.north();
            var pulleyPos = gearPos.south();
            load = pulleyPos.below();
            level.setBlockAndUpdate(gearPos, AllBlocks.SEQUENCED_GEARSHIFT.getDefaultState()
                .setValue(SequencedGearshiftBlock.HORIZONTAL_AXIS, Direction.Axis.Z).setValue(SequencedGearshiftBlock.VERTICAL, false));
            level.setBlockAndUpdate(pulleyPos, AllBlocks.ROPE_PULLEY.getDefaultState()
                .setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.Z));
            level.setBlockAndUpdate(load, Blocks.GOLD_BLOCK.defaultBlockState());
            gear = (SequencedGearshiftBlockEntity)level.getBlockEntity(gearPos);
            motion = (SequencedMotion.Access)gear;
            pulley = (PulleyBlockEntity)level.getBlockEntity(pulleyPos);
            preset = Instruction.serializeAll(gear.getInstructions());
            guards = new DataValue.RecordValue(Map.of("terminal_pos", address(terminal), "timer_pos", address(timerAdapter),
                "gear_pos", address(gearAdapter)));
            a.installDocument(withInputs(example("motion_panel_a"), guards.fields()), ComputerRuntime.MemoryPolicy.RESET);
            b.installDocument(example("serial_exchange"), ComputerRuntime.MemoryPolicy.RESET);
            h.onEachTick(() -> {
                sawSignal |= dustPower() == 15;
                sawPulse |= pulsePower() && level.getBlockState(clock.east()).getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT);
                sawMoving |= pulley.running && pulley.offset > 0;
            });
        }

        private ComputerBehaviour computer(BlockPos pos) {
            var lower = CBBlocks.COMPUTER.get().defaultBlockState().setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
            level.setBlockAndUpdate(pos, lower);
            level.setBlockAndUpdate(pos.above(), lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER));
            var block = (ComputerBlockEntity)level.getBlockEntity(pos);
            var villager = Objects.requireNonNull(EntityType.VILLAGER.create(level));
            villager.setVillagerData(new VillagerData(VillagerType.PLAINS, VillagerProfession.CLERIC, 5));
            var box = new ItemStack(CBItems.CARDBOARD_BOX.get());
            h.assertTrue(CapturedEntityBoxHelper.captureEntity(box, villager), "Real master cleric");
            player.moveTo(Vec3.atCenterOf(pos));
            player.setItemInHand(InteractionHand.MAIN_HAND, box);
            h.assertValueEqual(block.installResident(player, InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS, "Install real resident");
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            var host = block.getBehaviour(ComputerBehaviour.TYPE);
            host.installFlow(new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(Map.of(), List.of()),
                new DataValue.RecordValue(Map.of()));
            return host;
        }

        void authorize() {
            var configurer = new MotionPanelSettingsFixture(level);
            for (var adapter : List.of(timerAdapter, gearAdapter)) configurer.grant(h, adapter);
            open(terminal);
            player.terminal(TerminalUiRequest.Action.MANAGE, -1, "", "");
            player.terminal(TerminalUiRequest.Action.GRANT, 0, "", "");
            h.assertValueEqual(player.terminal.code(), "configured", "Authorized the terminal separately");
            player.terminal(TerminalUiRequest.Action.VIEW, -1, "", "");
        }

        private void open(BlockPos pos) {
            var center = Vec3.atCenterOf(pos);
            player.moveTo(center);
            h.assertValueEqual(level.getBlockState(pos).useWithoutItem(level, player, new BlockHitResult(center, Direction.UP, pos, false)),
                InteractionResult.CONSUME, "Open the real block menu");
        }

        void run(String distance, long oldRevision, Consumer<DataValue.RecordValue> done) {
            a.setPaused(false);
            await(() -> {
                player.terminal(TerminalUiRequest.Action.REFRESH, -1, "", "");
                return !number(oldRevision).equals(player.terminal.view().field("revision"));
            }, 1600, "New terminal layout", () -> {
                var page = (DataValue.RecordValue)player.terminal.view().field("page");
                var input = TerminalProjection.project(TerminalCodec.decode(page.field("layout")),
                    (DataValue.RecordValue)page.field("roots"), Map.of(), 4, 16);
                h.assertValueEqual(input.input().orElseThrow().text(), "1", "Rendered view contains the supplied typed input");
                player.terminal(TerminalUiRequest.Action.SUBMIT, -1, input.instance(), distance);
                h.assertValueEqual(player.terminal.code(), "queued", "Submit at the native terminal menu");
                await(() -> a.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.COMPLETE, 2400, "Worksite result",
                    () -> done.accept(a.flowSnapshot().orElseThrow().state().locals()));
            });
        }

        private void await(java.util.function.BooleanSupplier ready, int remaining, String description, Runnable next) {
            h.runAfterDelay(4, () -> {
                var state = a.flowSnapshot().orElseThrow().state();
                var relay = b.flowSnapshot().orElseThrow().state();
                h.assertTrue(state.status() != FlowRuntime.Status.FAILED && relay.status() != FlowRuntime.Status.FAILED,
                    "Installed program fault: " + state.diagnostic() + ", relay: " + relay.diagnostic());
                if (ready.getAsBoolean()) next.run();
                else if (remaining <= 0) h.fail(description + ": A=" + state.status() + " " + state.locals().fields()
                    + ", B=" + relay.status() + ", cable=" + b.serialDevice().status("s0"));
                else await(ready, remaining - 4, description, next);
            });
        }

        void power() {
            level.setBlockAndUpdate(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.SOUTH));
            var engine = (CreativeMotorBlockEntity)level.getBlockEntity(motor);
            engine.initialize(); engine.generatedSpeed.setValue(16); engine.updateGeneratedRotation();
        }

        int clockValue() { return BlockEntityBehaviour.get(level.getBlockEntity(clock), ScrollValueBehaviour.TYPE).getValue(); }
        int dustPower() { return level.getBlockState(dust).getValue(RedStoneWireBlock.POWER); }
        boolean pulsePower() { return level.getBlockState(clock).getValue(BrassDiodeBlock.POWERING); }
        private void cable(BlockPos pos) { level.setBlockAndUpdate(pos, CBBlocks.DATA_CABLE.get().defaultBlockState()); }
        private void adapter(BlockPos pos, Direction side) { level.setBlockAndUpdate(pos,
            CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState().setValue(ComponentSettingsBlock.FACING, side)); }
        private static ClusterConfiguration.Member member(ComputerBehaviour host) { return new ClusterConfiguration.Member(
            host.communicationIdentity().orElseThrow(), host.blockEntity.getBlockPos()); }
    }

    private static ProgramDocument example(String name) {
        try (var in = MotionPanelWorldGameTests.class.getResourceAsStream("/assets/create_biotech/biocomputing/examples/" + name + ".bcp")) {
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(Objects.requireNonNull(in).readAllBytes(),
                StandardCharsets.UTF_8).strip()), CBConfigs.SERVER.biocomputing.current(true).archive());
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }

    private static ProgramDocument withInputs(ProgramDocument doc, Map<String, DataValue> values) {
        var body = doc.flow().orElseThrow();
        var fields = new LinkedHashMap<>(body.initial().fields());
        fields.putAll(values);
        return new ProgramDocument(doc.archive(), doc.positions(), doc.terminal(), Optional.of(new FlowDocumentBody(
            body.inputs(), body.steps(), new DataValue.RecordValue(fields), body.canvas(), body.inline(), body.execution(), body.compilation())));
    }

    private static DataValue.RecordValue address(BlockPos pos) {
        return new DataValue.RecordValue(Map.of("x", number(pos.getX()), "y", number(pos.getY()), "z", number(pos.getZ())));
    }
    private static DataValue.IntegerValue number(long value) { return new DataValue.IntegerValue(value); }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }

    private static final class Actor extends FakePlayer {
        private TerminalUiMenu terminal;
        private int id;
        Actor(ServerLevel world) { super(world, new GameProfile(UUID.randomUUID(), "winch-player")); }
        @Override public OptionalInt openMenu(MenuProvider provider, Consumer<RegistryFriendlyByteBuf> opening) {
            int next = ++id;
            containerMenu = provider.createMenu(next, getInventory(), this);
            var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess());
            try {
                opening.accept(wire);
                terminal = CBMenuTypes.TERMINAL_UI.get().create(next, getInventory(), wire);
            } finally { wire.release(); }
            return OptionalInt.of(next);
        }
        void terminal(TerminalUiRequest.Action action, int choice, String instance, String value) {
            var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess());
            try { terminal.request(action, choice, instance, value).write(wire); new TerminalUiRequest(wire).handle(this); }
            finally { wire.release(); }
        }
    }
}
