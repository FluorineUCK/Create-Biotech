package com.nobodiiiii.createbiotech.content.biocomputing.device.terminal;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests;
import com.nobodiiiii.createbiotech.content.biocomputing.device.settings.ArmControl;
import com.nobodiiiii.createbiotech.content.biocomputing.device.settings.ComponentSettingsBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.device.settings.DeployerControl;
import com.nobodiiiii.createbiotech.content.biocomputing.device.settings.MotionPanelSettingsFixture;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialGatewayAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.network.signal.SignalConnections;
import com.nobodiiiii.createbiotech.content.biocomputing.network.signal.SignalGatewayAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.BuiltinReadComponents;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReaderSerialBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.RedstoneReadComponent;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalProjection;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmPlacementPacket;
import com.simibubi.create.content.kinetics.transmission.GearshiftBlock;
import com.simibubi.create.content.logistics.funnel.AbstractDirectionalFunnelBlock;
import com.simibubi.create.content.logistics.funnel.AbstractFunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.content.redstone.diodes.BrassDiodeBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class LineDrainWorldGameTests {
    // The workcell spans x=1..31. Using the 20-wide "empty" template lets
    // another test overwrite the far end of the real data cable in a batch.
    @GameTest(template = "biocomputing_line_workcell", timeoutTicks = 19000)
    public static void closesNativeGatesAndSeparatelyObservesActualItemsAndFluid(GameTestHelper h) {
        if (Boolean.getBoolean("biocomputing.qa.lineDrainFastTick")) h.getLevel().getServer().tickRateManager().setTickRate(80);
        var bench = new Bench(h);
        h.runAfterDelay(30, () -> {
            bench.authorize();
            h.assertTrue(bench.tank().fill(new FluidStack(Fluids.WATER, 250), IFluidHandler.FluidAction.EXECUTE) == 250,
                "Seed an actual Create tank with a bounded amount of water");
            bench.barrel().setItem(26, new ItemStack(Items.GOLD_INGOT, 2));
            bench.b.setPaused(false);
            bench.run(true, result -> {
                bench.assertStopped(result, "material_remaining", "items");
                h.assertTrue(bench.sawOpen && bench.sawPermit && bench.sawCloseAfterOpen,
                    "A positive Simple I/O permit opened both native gates, then the example closed them: " + bench.evidence());
                h.assertTrue(bench.barrel().getItem(26).getCount() == 2 && bench.water() == 250,
                    "The guard did not move or consume material while stopping");
                var removed = bench.barrel().removeItem(26, 2);
                h.assertTrue(removed.is(Items.GOLD_INGOT) && removed.getCount() == 2, "Manual downstream clearing preserves both items");
                bench.run(false, wet -> {
                    bench.assertStopped(wet, "material_remaining", "fluid");
                    h.assertTrue(bench.water() == 250, "The guard did not drain the fluid buffer");
                    var drained = bench.tank().drain(250, IFluidHandler.FluidAction.EXECUTE);
                    h.assertTrue(drained.is(Fluids.WATER) && drained.getAmount() == 250, "Manual drain preserves the exact fluid");
                    bench.run(false, clear -> {
                        bench.assertStopped(clear, "observed_drained", "finished");
                        h.assertTrue(bench.barrel().isEmpty() && bench.water() == 0, "Only the observed buffers are empty");
                        h.succeed();
                    });
                });
            });
        });
    }

    @GameTest(template = "biocomputing_line_workcell", timeoutTicks = 13000)
    public static void verifiesFiltersModeAndChosenNativeRouteWithoutReopening(GameTestHelper h) {
        if (Boolean.getBoolean("biocomputing.qa.lineDrainFastTick")) h.getLevel().getServer().tickRateManager().setTickRate(80);
        var bench = new Bench(h);
        var itemPort = bench.funnel.east();
        bench.set(itemPort, CBBlocks.VIRTUAL_FILTER.get().defaultBlockState().setValue(VirtualFilterBlock.FACING, Direction.WEST));
        bench.cable(itemPort.east());
        bench.cable(itemPort.east().south());
        var fluidPort = bench.at(19, -1);
        var pipe = fluidPort.north();
        bench.set(fluidPort, CBBlocks.VIRTUAL_FILTER.get().defaultBlockState().setValue(VirtualFilterBlock.FACING, Direction.NORTH));
        bench.set(pipe, AllBlocks.SMART_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        var armPos = bench.armPort.north();
        var expectedRoute = prepareNativeRoutes(bench, armPos);
        var arm = (ArmBlockEntity)bench.level.getBlockEntity(armPos);
        var hand = (DeployerControl.Access)bench.level.getBlockEntity(bench.handPort.north());
        h.runAfterDelay(30, () -> {
            var original = new CompoundTag();
            arm.writeInteractionPoints(original);
            h.assertTrue(hand.biocomputingUseMode(), "Fixture starts in use mode");
            bench.authorize();
            VirtualFilterSerialGameTests.grantDirect(h, itemPort);
            VirtualFilterSerialGameTests.grantDirect(h, fluidPort);
            var doc = example("line_machine_switch_a");
            var body = doc.flow().orElseThrow();
            var initial = new LinkedHashMap<>(body.initial().fields());
            initial.putAll(bench.inputs);
            initial.put("item_filter", redirect(initial.get("item_filter"), itemPort));
            initial.put("fluid_filter", redirect(initial.get("fluid_filter"), fluidPort));
            initial.put("arm_route", expectedRoute);
            bench.b.setPaused(false);
            bench.a.reset();
            h.assertValueEqual(bench.a.installDocument(new ProgramDocument(doc.archive(), doc.positions(), doc.terminal(), Optional.of(
                new FlowDocumentBody(body.inputs(), body.steps(), new DataValue.RecordValue(initial),
                    body.canvas(), body.inline(), body.execution(), body.compilation()))), ComputerRuntime.MemoryPolicy.RESET),
                ComputerRuntime.InstallResult.APPLIED, "Install the same public switch program with explicit addresses");
            bench.a.setPaused(false);
            bench.waitForCompletion(10000, result -> {
                bench.assertStopped(result, "observed_drained", "finished");
                h.assertValueEqual(result.field("filter_result"), text("filtered_hold"), "Both filters verified: " + result.fields());
                h.assertValueEqual(result.field("item_verify"), text("verified"), "Actual brass funnel endpoint verified");
                h.assertValueEqual(result.field("fluid_verify"), text("verified"), "Actual smart pipe endpoint verified");
                var itemFilter = BlockEntityBehaviour.get(bench.level, bench.funnel, FilteringBehaviour.TYPE);
                var fluidFilter = BlockEntityBehaviour.get(bench.level, pipe, FilteringBehaviour.TYPE);
                h.assertTrue(itemFilter.test(new ItemStack(Items.IRON_INGOT)) && !itemFilter.test(new ItemStack(Items.GOLD_INGOT)),
                    "Native Create funnel sees the installed whitelist");
                h.assertTrue(fluidFilter.test(new FluidStack(Fluids.WATER, 250)) && !fluidFilter.test(new FluidStack(Fluids.LAVA, 250)),
                    "Native smart pipe sees the installed whitelist");
                h.assertTrue(itemFilter.getFilter().isEmpty() && fluidFilter.getFilter().isEmpty(), "No physical filter items produced");
                h.assertValueEqual(result.field("machine_result"), text("machines_checked_hold"), "Exact device stage: " + result.fields());
                h.assertValueEqual(result.field("deployer_write"), text("applied"), "One authorized native mode update");
                h.assertValueEqual(result.field("deployer_verify"), text("verified"), "Native mode was independently read back");
                h.assertValueEqual(result.field("arm_verify"), text("verified"), "Read the expected native point pair");
                h.assertValueEqual(result.field("confirmed_route"), expectedRoute, "Chose the second native output, not a default route");
                h.assertTrue(!hand.biocomputingUseMode() && hand.biocomputingLocked() && hand.biocomputingProcessId() == null,
                    "The actual Deployer changed mode while locked, without starting a processing action");
                var armAccess = (ArmControl.Access)arm;
                var after = new CompoundTag();
                arm.writeInteractionPoints(after);
                h.assertValueEqual(after, original, "No rewrite of native Arm placement points");
                h.assertTrue(armAccess.biocomputingHeldCopy().isEmpty() && armAccess.biocomputingAction() == null,
                    "Reading the route is not an Arm move");
                h.assertTrue(!bench.sawPermit && bench.barrel().isEmpty() && bench.water() == 0, "No reopen or new material");
                System.out.println("Line-machine-switch machines_checked_hold: native filters and mode applied; selected route verified; no movement or reopen");
                h.succeed();
            });
        });
    }

    @GameTest(template = "biocomputing_line_workcell", timeoutTicks = 14000)
    public static void selectsOneProcessAtThePhysicalTerminalBeforeConfiguringTheWorkcell(GameTestHelper h) {
        if (Boolean.getBoolean("biocomputing.qa.lineDrainFastTick")) h.getLevel().getServer().tickRateManager().setTickRate(80);
        var bench = new Bench(h);
        var itemPort = bench.funnel.east();
        bench.set(itemPort, CBBlocks.VIRTUAL_FILTER.get().defaultBlockState().setValue(VirtualFilterBlock.FACING, Direction.WEST));
        bench.cable(itemPort.east());
        bench.cable(itemPort.east().south());
        var fluidPort = bench.at(19, -1);
        var pipe = fluidPort.north();
        bench.set(fluidPort, CBBlocks.VIRTUAL_FILTER.get().defaultBlockState().setValue(VirtualFilterBlock.FACING, Direction.NORTH));
        bench.set(pipe, AllBlocks.SMART_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        var armPos = bench.armPort.north();
        var normalRoute = prepareNativeRoutes(bench, armPos);
        var chosenRoute = new DataValue.RecordValue(Map.of("input", normalRoute.field("input"),
            "output", pointSpec(0, new BlockPos(1, 0, 0), "create:depot")));
        var terminal = bench.at(14, 1);
        bench.set(terminal, CBBlocks.TERMINAL_UI.get().defaultBlockState().setValue(TerminalUiBlock.FACING, Direction.SOUTH));
        var operator = new PanelOperator(bench.level);
        h.runAfterDelay(30, () -> {
            bench.authorize();
            VirtualFilterSerialGameTests.grantDirect(h, itemPort);
            VirtualFilterSerialGameTests.grantDirect(h, fluidPort);
            var pos = Vec3.atCenterOf(terminal);
            operator.moveTo(pos);
            h.assertValueEqual(bench.level.getBlockState(terminal).useWithoutItem(bench.level, operator,
                new BlockHitResult(pos, Direction.UP, terminal, false)), InteractionResult.CONSUME, "Open the physical terminal menu");
            operator.request(TerminalUiRequest.Action.MANAGE, -1, "");
            operator.request(TerminalUiRequest.Action.GRANT, 0, "");
            h.assertValueEqual(operator.menu.code(), "configured", "Grant the Cluster's serial source independently");
            operator.request(TerminalUiRequest.Action.VIEW, -1, "");
            var doc = example("line_process_panel_a");
            var body = doc.flow().orElseThrow();
            var inputs = new LinkedHashMap<>(body.initial().fields());
            inputs.putAll(bench.inputs);
            inputs.put("terminal_pos", address(terminal));
            inputs.put("process_one", routeProfile(inputs.get("process_one"), itemPort, fluidPort, normalRoute));
            inputs.put("process_two", routeProfile(inputs.get("process_two"), itemPort, fluidPort, chosenRoute));
            bench.b.setPaused(false);
            bench.a.reset();
            h.assertValueEqual(bench.a.installDocument(new ProgramDocument(doc.archive(), doc.positions(), doc.terminal(), Optional.of(
                new FlowDocumentBody(body.inputs(), body.steps(), new DataValue.RecordValue(inputs),
                    body.canvas(), body.inline(), body.execution(), body.compilation()))), ComputerRuntime.MemoryPolicy.RESET),
                ComputerRuntime.InstallResult.APPLIED, "Install the editable panel example with only explicit workcell addresses");
            bench.a.setPaused(false);
            selectSecondProcess(h, bench, operator, 1800, () -> bench.waitForCompletion(10000, result -> {
                h.assertValueEqual(result.field("chosen_process"), text("process_two"), "The actual click chose the second profile");
                h.assertValueEqual(result.field("machine_result"), text("machines_checked_hold"), "Selected profile verified");
                h.assertValueEqual(result.field("confirmed_route"), chosenRoute, "Verified the first original output point");
                h.assertValueEqual(result.field("deployer_write"), text("unchanged"), "Skipped the unchosen punch configuration");
                bench.assertStopped(result, "observed_drained", "finished");
                var item = BlockEntityBehaviour.get(bench.level, bench.funnel, FilteringBehaviour.TYPE);
                var fluid = BlockEntityBehaviour.get(bench.level, pipe, FilteringBehaviour.TYPE);
                h.assertTrue(item.test(new ItemStack(Items.GOLD_INGOT)) && !item.test(new ItemStack(Items.IRON_INGOT)),
                    "Actual funnel accepts only the selected profile's item");
                h.assertTrue(fluid.test(new FluidStack(Fluids.LAVA, 250)) && !fluid.test(new FluidStack(Fluids.WATER, 250)),
                    "Actual smart pipe accepts only the selected profile's fluid");
                h.assertTrue(item.getFilter().isEmpty() && fluid.getFilter().isEmpty() && !bench.sawPermit,
                    "Profile changes create no items and do not release feed");
                System.out.println("Line-process-panel: clicked second physical button; native item/fluid rules and chosen arm route verified; no reopen");
                h.succeed();
            }));
        });
    }

    @GameTest(template = "biocomputing_line_workcell", timeoutTicks = 12000)
    public static void releasesOnlyAfterRemovingTheRealOutletBlockage(GameTestHelper h) {
        if (Boolean.getBoolean("biocomputing.qa.lineDrainFastTick")) h.getLevel().getServer().tickRateManager().setTickRate(80);
        var bench = new Bench(h);
        var feed = new FeedPulseFixture(bench);
        h.runAfterDelay(30, () -> {
            bench.b.setPaused(false);
            for (Probe probe : List.of(feed.first, feed.second, feed.ready)) bench.authorize(probe);
            feed.source().setItem(0, new ItemStack(Items.IRON_INGOT));
            h.assertValueEqual(feed.sourceFluid().getTankInventory().fill(new FluidStack(Fluids.WATER, 1000),
                IFluidHandler.FluidAction.EXECUTE), 1000, "One real bucket in the supply tank");
            feed.blocked().setItem(26, new ItemStack(Items.COBBLESTONE, 64));
            h.runAfterDelay(12, () -> {
                h.assertValueEqual(feed.readyPower(), 0, "The actual barrel comparator trips the independent interlock");
                feed.run(result -> {
                    h.assertValueEqual(result.field("resume_status"), text("outlet_blocked"), "The actual last slot blocks the request");
                    h.assertValueEqual(result.field("resume_checkpoint"), text("outlet_two"), "The first empty barrel cannot mask a blocked second outlet");
                    h.assertTrue(feed.edges == 0 && !bench.sawOpen && bench.locked() && !bench.opened(),
                        "No SIO pulse, no native door unlock while the barrel is blocked");
                    h.assertTrue(feed.source().getItem(0).is(Items.IRON_INGOT) && feed.received() == 0,
                        "The actual input stock stays put");
                    h.assertTrue(feed.sourceFluid().getTankInventory().getFluidAmount() == 1000
                        && feed.sinkFluid().getTankInventory().getFluidAmount() == 0, "Closed valve blocks a powered fluid line");
                    h.assertTrue(Math.abs(feed.pump().getSpeed()) > 0, "Actual pump has kinetic power before requesting feed");
                    feed.checkIndependentMask(() -> clearAndResumeFeed(h, bench, feed));
                });
            });
        });
    }

    @GameTest(template = "biocomputing_line_workcell", timeoutTicks = 52000)
    public static void changesTheNativeIntakeWhileRunningBothProfilesOnTheSameWorkcell(GameTestHelper h) {
        if (Boolean.getBoolean("biocomputing.qa.lineDrainFastTick")) h.getLevel().getServer().tickRateManager().setTickRate(80);
        var workcell = new TwoProfileIntake(new Bench(h));
        h.runAfterDelay(30, workcell::run);
    }

    @GameTest(template = "biocomputing_line_workcell", timeoutTicks = 26000)
    public static void displaysTheCompletedNativeMoveAtTheSamePhysicalTerminal(GameTestHelper h) {
        if (Boolean.getBoolean("biocomputing.qa.lineDrainFastTick")) h.getLevel().getServer().tickRateManager().setTickRate(80);
        var workcell = new TwoProfileIntake(new Bench(h), true);
        h.runAfterDelay(30, workcell::run);
    }

    private static final class TwoProfileIntake {
        private final Bench bench;
        private final FeedPulseFixture feed;
        private final GameTestHelper h;
        private final BlockPos itemPort, fluidPort, terminal, itemBuffer, arm, routedArmPort;
        private final Probe fluidGate, items, fluid;
        private final DataValue.RecordValue routeOne, routeTwo;
        private final PanelOperator operator;
        private final boolean showResult;
        private final Set<DataValue> deliveredActions = new HashSet<>();
        private UUID installation;

        TwoProfileIntake(Bench bench) {
            this(bench, false);
        }

        TwoProfileIntake(Bench bench, boolean showResult) {
            this.bench = bench;
            this.showResult = showResult;
            this.h = bench.h;
            feed = new FeedPulseFixture(bench);
            itemPort = bench.funnel.east();
            fluidPort = bench.at(9, 5);
            bench.set(itemPort, CBBlocks.VIRTUAL_FILTER.get().defaultBlockState().setValue(VirtualFilterBlock.FACING, Direction.WEST));
            bench.set(fluidPort, CBBlocks.VIRTUAL_FILTER.get().defaultBlockState().setValue(VirtualFilterBlock.FACING, Direction.WEST));
            // The original falling item lands straight on the native arm's only input.
            // No intervening unmeasured hopper, belt or chute can retain an old batch.
            itemBuffer = feed.catchHopper;
            bench.set(itemBuffer, AllBlocks.DEPOT.getDefaultState());
            items = bench.probe(itemBuffer.east(), Direction.WEST, BuiltinReadComponents.BLOCK, RedstoneReadComponent.ID);
            fluidGate = bench.probe(bench.valve.east(), Direction.WEST, BuiltinReadComponents.BLOCK);
            fluid = bench.probe(feed.fluidOut.east(), Direction.WEST, BuiltinReadComponents.FLUID);
            // The real pump/tank now occupy two cells of the original cable run.
            // Pass underneath them without connecting the data cable to the interlock rail.
            for (int x = 3; x <= 10; x++) bench.cable(bench.at(x, 8).below());
            for (int x = 5; x <= 10; x++) bench.cable(bench.at(x, 6).below());
            bench.cable(itemPort.east());

            // A native arm meshes with a neighboring vertical cog, not a shaft
            // attached directly underneath. Keep it clear of the gate's redstone dust.
            arm = bench.at(5, 5).above();
            routedArmPort = arm.south();
            bench.set(arm, AllBlocks.MECHANICAL_ARM.getDefaultState());
            var drive = arm.east();
            bench.set(drive, AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
            bench.motor(drive.below(), Direction.UP, 8);
            bench.adapter(routedArmPort);
            bench.cable(routedArmPort.south());
            bench.cable(routedArmPort.south().below());
            var firstOutput = feed.first.target().above();
            var secondOutput = feed.second.target().above();
            for (var output : List.of(firstOutput, secondOutput))
                bench.set(output, AllBlocks.BRASS_FUNNEL.getDefaultState().setValue(FunnelBlock.FACING, Direction.UP)
                    .setValue(FunnelBlock.EXTRACTING, false));
            routeOne = configureNativeRoute(bench, arm, itemBuffer, firstOutput, secondOutput);
            var outputType = ArmInteractionPointType.getPrimaryType(bench.level, firstOutput, bench.level.getBlockState(firstOutput));
            routeTwo = new DataValue.RecordValue(Map.of("input", routeOne.field("input"),
                "output", pointSpec(0, firstOutput.subtract(arm), CreateBuiltInRegistries.ARM_INTERACTION_POINT_TYPE.getKey(outputType).toString())));
            terminal = bench.at(14, 1);
            bench.set(terminal, CBBlocks.TERMINAL_UI.get().defaultBlockState().setValue(TerminalUiBlock.FACING, Direction.SOUTH));
            operator = new PanelOperator(bench.level);
        }

        void run() {
            bench.b.setPaused(false);
            for (Probe reader : List.of(bench.readers.getFirst(), fluidGate, items, fluid, feed.first, feed.second, feed.ready))
                bench.authorize(reader);
            var settings = new MotionPanelSettingsFixture(bench.level);
            settings.grant(h, routedArmPort);
            settings.grant(h, bench.handPort);
            var nativeArm = (ArmBlockEntity)bench.level.getBlockEntity(arm);
            h.assertTrue(Math.abs(nativeArm.getSpeed()) > 0, "The actual arm has native drive");
            h.assertTrue(!((ArmControl.Access)nativeArm).biocomputingLocked(), "The arm is not powered by the independent gate lock");
            VirtualFilterSerialGameTests.grantDirect(h, itemPort);
            VirtualFilterSerialGameTests.grantDirect(h, fluidPort);
            var pos = Vec3.atCenterOf(terminal);
            operator.moveTo(pos);
            h.assertValueEqual(bench.level.getBlockState(terminal).useWithoutItem(bench.level, operator,
                new BlockHitResult(pos, Direction.UP, terminal, false)), InteractionResult.CONSUME, "Open the real terminal");
            operator.request(TerminalUiRequest.Action.MANAGE, -1, "");
            operator.request(TerminalUiRequest.Action.GRANT, 0, "");
            h.assertValueEqual(operator.menu.code(), "configured", "Authorize the actual relay from the terminal menu");
            operator.request(TerminalUiRequest.Action.VIEW, -1, "");
            install();
            System.out.println("Line-two-profile-intake: powered arm, actual ports authorized, published document installed");

            feed.source().setItem(0, new ItemStack(Items.IRON_INGOT));
            feed.source().setItem(26, new ItemStack(Items.GOLD_INGOT));
            fill(Fluids.WATER);
            runProfile("process_one", routeOne, Items.IRON_INGOT, Fluids.WATER, 1, () -> {
                h.assertTrue(feed.source().getItem(0).isEmpty() && feed.source().getItem(26).is(Items.GOLD_INGOT),
                    "First rule left the original unselected gold in its slot");
                if (showResult) {
                    h.succeed();
                    return;
                }
                var delivered = receiver(feed.second).removeItem(0, 1);
                h.assertTrue(delivered.is(Items.IRON_INGOT) && delivered.getCount() == 1, "Take the original first output from its buffer");
                var water = feed.sinkFluid().getTankInventory().drain(1000, IFluidHandler.FluidAction.EXECUTE);
                h.assertTrue(water.getFluid() == Fluids.WATER && water.getAmount() == 1000,
                    "Collect the first batch before filling the same feed tank with lava");
                fill(Fluids.LAVA);
                runProfile("process_two", routeTwo, Items.GOLD_INGOT, Fluids.LAVA, 2, () -> {
                    h.assertTrue(feed.source().isEmpty(), "No source item remains or was copied to another buffer");
                    System.out.println("Line-two-profile-intake: one original iron / 1000 mB water, then one original gold / "
                        + "1000 mB lava; same terminal and installation, native arm delivered to each selected outlet once");
                    h.succeed();
                });
            });
        }

        private void fill(Fluid fluidType) {
            h.assertValueEqual(feed.sourceFluid().getTankInventory().fill(new FluidStack(fluidType, 1000),
                IFluidHandler.FluidAction.EXECUTE), 1000, "Supply one real bucket of " + fluidType);
        }

        private BarrelBlockEntity receiver(Probe outlet) { return (BarrelBlockEntity)bench.level.getBlockEntity(outlet.target()); }

        private void install() {
            var doc = example(showResult ? "line_resume_panel_report_a" : "line_resume_panel_a");
            var body = doc.flow().orElseThrow();
            var initial = new LinkedHashMap<>(body.initial().fields());
            initial.putAll(bench.inputs);
            var layout = new LinkedHashMap<>(((DataValue.RecordValue)initial.get("layout")).fields());
            layout.put("items", items.binding());
            layout.put("fluid", fluid.binding());
            layout.put("fluid_gate", fluidGate.binding());
            layout.put("arm", address(routedArmPort));
            initial.put("layout", new DataValue.RecordValue(layout));
            initial.put("terminal_pos", address(terminal));
            initial.put("process_one", routeProfile(initial.get("process_one"), itemPort, fluidPort, routeOne));
            initial.put("process_two", routeProfile(initial.get("process_two"), itemPort, fluidPort, routeTwo));
            initial.put("outlet_one", feed.first.binding());
            initial.put("outlet_two", feed.second.binding());
            initial.put("interlock_probe", feed.ready.binding());
            initial.put("release_armed", new DataValue.BooleanValue(true));
            initial.put("route_enabled", new DataValue.BooleanValue(true));
            bench.a.reset();
            h.assertValueEqual(bench.a.installDocument(new ProgramDocument(doc.archive(), doc.positions(), doc.terminal(), Optional.of(
                new FlowDocumentBody(body.inputs(), body.steps(), new DataValue.RecordValue(initial),
                    body.canvas(), body.inline(), body.execution(), body.compilation()))), ComputerRuntime.MemoryPolicy.RESET),
                ComputerRuntime.InstallResult.APPLIED, "Install the complete public document with only physical bindings edited");
            installation = bench.a.installedProgram().orElseThrow().installation();
        }

        private void runProfile(String choice, DataValue.RecordValue route, Item expectedItem, Fluid expectedFluid, int round,
                Runnable next) {
            operator.request(TerminalUiRequest.Action.REFRESH, -1, "");
            long previousRevision = ((DataValue.IntegerValue)operator.menu.view().field("revision")).value();
            bench.a.reset();
            h.assertValueEqual(bench.a.installedProgram().orElseThrow().installation(), installation,
                "Restart the same program without reinstalling or editing it between process choices");
            bench.a.setPaused(false);
            // With the existing relay kept running across both choices, the second
            // round can still be in the normal outlet exchange after 15,000 ticks.
            // This is a fixture observation deadline, not a device wait or rate change.
            selectProcess(h, bench, operator, choice, previousRevision, 1800, () -> bench.waitForCompletion(23000, result -> {
                h.assertValueEqual(result.field("chosen_process"), text(choice), "Only the new terminal choice is consumed");
                h.assertValueEqual(result.field("status"), text("observed_drained"), "The actual workcell was observed stopped");
                h.assertValueEqual(result.field("filter_result"), text("filtered_hold"), "Both physical filters were read back");
                h.assertValueEqual(result.field("machine_result"), text("machines_checked_hold"), "Machine checks completed in this run");
                h.assertValueEqual(result.field("confirmed_route"), route, "The selected existing pair of native points was verified");
                h.assertValueEqual(result.field("resume_status"), text("release_pulsed"),
                    "Release follows those checks, not a fixture-supplied result: " + result.field("resume_reply"));
                h.assertValueEqual(result.field("route_status"), text("transfer_completed"),
                    "The program observes the selected arm action complete, not merely a feed pulse: " + result.field("route_reply"));
                if (showResult) {
                    h.assertValueEqual(result.field("report_status"), text("published"), "The original terminal accepted the result");
                    operator.request(TerminalUiRequest.Action.REFRESH, -1, "");
                    var page = (DataValue.RecordValue)operator.menu.view().field("page");
                    var view = TerminalProjection.project(TerminalCodec.decode(page.field("layout")),
                        (DataValue.RecordValue)page.field("roots"), Map.of(), 10, 64);
                    h.assertValueEqual(view.children().getFirst().value().orElseThrow(), text("工艺切换结果"),
                        "The actual terminal menu displays the new page");
                    var results = view.children().get(1).children();
                    var fields = List.of("chosen_process", "status", "checkpoint", "filter_result", "filter_checkpoint",
                        "machine_result", "machine_checkpoint", "resume_status", "resume_checkpoint", "route_status");
                    var labels = List.of("选择的工艺", "停料与排空", "排空检查点", "过滤规则", "过滤检查点",
                        "设备核对", "设备检查点", "复产信号", "复产检查点", "机械臂搬运");
                    for (int i = 0; i < fields.size(); i++) {
                        var field = fields.get(i);
                        var value = results.get(i).value().orElseThrow();
                        h.assertValueEqual(value, text(labels.get(i) + "：" + asText(result.field(field))),
                            "Actual menu shows the current workcell result: " + field);
                    }
                    System.out.println("Line-operator-report: same physical terminal displayed the selected process and observed native transfer result");
                }
                var access = (ArmControl.Access)bench.level.getBlockEntity(arm);
                var action = result.field("route_action");
                h.assertTrue(action instanceof DataValue.TextValue && deliveredActions.add(action),
                    "Each chosen profile has a distinct completed receipt");
                h.assertValueEqual(action, text(Objects.requireNonNull(access.biocomputingAction()).toString()), "Same native action");
                h.runAfterDelay(20, () -> {
                    h.assertTrue(feed.edges == round && feed.pulseEdges == round && bench.power() == 0
                        && bench.locked() && !bench.opened(), "Exactly one pulse per new round; both actual gates closed");
                    var selected = receiver(round == 1 ? feed.second : feed.first);
                    var other = receiver(round == 1 ? feed.first : feed.second);
                    var stack = selected.getItem(0);
                    var pickup = (DepotBlockEntity)bench.level.getBlockEntity(itemBuffer);
                    h.assertTrue(stack.is(expectedItem) && stack.getCount() == 1 && other.isEmpty()
                        && pickup.getHeldItem().isEmpty() && access.biocomputingHeldCopy().isEmpty(),
                        "One item passed from the actual filter into the native route and selected barrel only; alternative stayed empty");
                    var tank = feed.sinkFluid().getTankInventory();
                    h.assertTrue(tank.getFluid().getFluid() == expectedFluid && tank.getFluidAmount() == 1000
                        && feed.sourceFluid().getTankInventory().getFluidAmount() == 0,
                        "The actual smart pipe, valve and pump passed exactly one selected bucket: " + tank.getFluid());
                    System.out.println("Line-two-profile-intake: " + choice + " delivered " + expectedItem + " / 1000 mB " + expectedFluid);
                    next.run();
                });
            }));
        }
    }

    private static void clearAndResumeFeed(GameTestHelper h, Bench bench, FeedPulseFixture feed) {
        var removed = feed.blocked().removeItem(26, 64);
        h.assertTrue(removed.is(Items.COBBLESTONE) && removed.getCount() == 64, "Clear the real receiver without losing its old stock");
        h.runAfterDelay(14, () -> {
            h.assertValueEqual(feed.readyPower(), 15, "Physical interlock becomes ready after clearing");
            h.assertTrue(feed.edges == 0 && bench.locked() && !bench.opened(), "Clearing alone does not replay an earlier attempt");
            feed.unlocked = false;
            feed.valveOpen = false;
            feed.run(resumed -> {
                h.assertValueEqual(resumed.field("resume_status"), text("release_pulsed"), "A new run emits a trigger after taking fresh measurements");
                h.runAfterDelay(260, () -> {
                    h.assertTrue(feed.edges == 1 && feed.pulseEdges == 1, "Exactly one request edge and native one-shot");
                    h.assertTrue(feed.unlocked && feed.valveOpen, "Observed the actual funnel and valve open: " + feed.observation());
                    h.assertTrue(bench.locked() && !bench.opened() && bench.power() == 0 && !feed.pulsing(),
                        "Independent one-shot expired, leaving both native gates closed");
                    h.assertTrue(feed.source().isEmpty() && feed.received() == 1, "One supplied iron ingot reached the actual collection hopper");
                    h.assertTrue(feed.blocked().isEmpty() && removed.getCount() == 64,
                        "No old outlet material was moved or duplicated");
                    var sourceFluid = feed.sourceFluid().getTankInventory();
                    var sinkFluid = feed.sinkFluid().getTankInventory();
                    h.assertTrue(sinkFluid.getFluidAmount() > 0 && sinkFluid.getFluid().getFluid() == Fluids.WATER
                        && sourceFluid.getFluidAmount() + sinkFluid.getFluidAmount() == 1000,
                        "Passed real water, conserved the supplied bucket: source=" + sourceFluid.getFluidAmount()
                            + ", sink=" + sinkFluid.getFluidAmount());
                    System.out.println("Line-feed-pulse: blocked outlet no edge; one explicit fresh run, one native pulse; "
                        + "one original iron ingot and " + sinkFluid.getFluidAmount() + " mB water delivered");
                    h.succeed();
                });
            });
        });
    }

    private static final class FeedPulseFixture {
        private final Bench bench;
        final BlockPos diode, catchHopper, fluidIn, fluidOut, pumpPos;
        final Probe first, second, ready;
        boolean previous, previousPulse, unlocked, valveOpen;
        int edges, pulseEdges, highTicks, lockedGatePowerDuringPulse = 15;

        FeedPulseFixture(Bench bench) {
            this.bench = bench;
            // Create's Pulse Repeater delays the edge and emits two ticks even if its input stays high.
            // The following Pulse Extender stretches only that short native pulse, not the Computer level.
            bench.set(bench.dust, AllBlocks.PULSE_REPEATER.getDefaultState().setValue(BrassDiodeBlock.FACING, Direction.NORTH));
            Objects.requireNonNull(BlockEntityBehaviour.get(bench.level, bench.dust, ScrollValueBehaviour.TYPE),
                "Native edge delay").setValue(2);
            diode = bench.at(2, 2);
            bench.set(diode, AllBlocks.PULSE_EXTENDER.getDefaultState().setValue(BrassDiodeBlock.FACING, Direction.NORTH));
            Objects.requireNonNull(BlockEntityBehaviour.get(bench.level, diode, ScrollValueBehaviour.TYPE),
                "Native open interval").setValue(100);
            for (int x : List.of(3, 7)) bench.set(bench.at(x, 2), Blocks.BARREL.defaultBlockState());
            first = bench.probe(bench.at(3, 1), Direction.SOUTH, BuiltinReadComponents.BLOCK, RedstoneReadComponent.ID);
            second = bench.probe(bench.at(7, 1), Direction.SOUTH, BuiltinReadComponents.BLOCK, RedstoneReadComponent.ID);
            comparator(bench.at(4, 2), Direction.WEST);
            comparator(bench.at(6, 2), Direction.EAST);
            // Both native comparators feed the same blocker rail. The repeater prevents
            // any feedback from the gate inverter into this independently observed rail.
            bench.wire(bench.at(5, 2));
            bench.repeater(bench.at(5, 3));
            bench.set(bench.at(5, 1).below(), Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
                .setValue(RedstoneWallTorchBlock.FACING, Direction.NORTH));
            bench.wire(bench.at(6, 1).below());
            bench.cable(bench.at(8, 0).below());
            bench.cable(bench.at(8, 1).below());
            ready = bench.probe(bench.at(7, 1).below(), Direction.WEST, BuiltinReadComponents.BLOCK);
            catchHopper = bench.funnel.below();
            bench.set(bench.funnel, bench.level.getBlockState(bench.funnel).setValue(FunnelBlock.EXTRACTING, true));
            bench.set(catchHopper, Blocks.HOPPER.defaultBlockState());

            // One sealed, powered native fluid path through the same redstone-gated workcell.
            fluidIn = bench.at(8, 4);
            fluidOut = bench.at(8, 8);
            pumpPos = bench.at(8, 7);
            // UP separates the existing X drive axle from the north/south fluid run.
            bench.set(bench.valve, bench.level.getBlockState(bench.valve).setValue(DirectionalAxisKineticBlock.FACING, Direction.UP));
            bench.h.assertValueEqual(FluidValveBlock.getPipeAxis(bench.level.getBlockState(bench.valve)), Direction.Axis.Z,
                "Fixture: fluid valve has actual native connections to both tanks");
            bench.set(fluidIn, AllBlocks.FLUID_TANK.getDefaultState());
            bench.set(fluidOut, AllBlocks.FLUID_TANK.getDefaultState());
            bench.set(bench.at(8, 5), AllBlocks.SMART_FLUID_PIPE.getDefaultState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
            bench.set(pumpPos, AllBlocks.MECHANICAL_PUMP.getDefaultState()
                .setValue(PumpBlock.FACING, Direction.SOUTH));
            bench.set(pumpPos.above(), AllBlocks.COGWHEEL.getDefaultState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
            bench.motor(pumpPos.above().north(), Direction.SOUTH);
            bench.h.onEachTick(() -> {
                boolean now = bench.power() == 15, active = pulsing();
                if (now && !previous) edges++;
                if (active && !previousPulse) pulseEdges++;
                if (active) {
                    highTicks++;
                    int rail = bench.level.getBlockState(bench.at(5, 4)).getValue(RedStoneWireBlock.POWER);
                    lockedGatePowerDuringPulse = Math.min(lockedGatePowerDuringPulse, rail);
                }
                previous = now;
                previousPulse = active;
                unlocked |= !bench.locked();
                valveOpen |= bench.opened();
            });
        }

        private void comparator(BlockPos pos, Direction face) {
            bench.set(pos.below(), Blocks.STONE.defaultBlockState());
            bench.set(pos, Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, face));
        }
        String observation() {
            return bench.evidence() + ", nativePulseTicks=" + highTicks + ", minGateLockRail=" + lockedGatePowerDuringPulse
                + ", sawFunnelUnlocked=" + unlocked + ", sawValveOpen=" + valveOpen;
        }
        int readyPower() { return bench.level.getBlockState(ready.target()).getValue(RedStoneWireBlock.POWER); }
        boolean pulsing() { return bench.level.getBlockState(diode).getValue(BrassDiodeBlock.POWERING); }
        BarrelBlockEntity source() { return (BarrelBlockEntity)bench.level.getBlockEntity(bench.funnel.above()); }
        BarrelBlockEntity blocked() { return (BarrelBlockEntity)bench.level.getBlockEntity(second.target()); }
        FluidTankBlockEntity sourceFluid() { return (FluidTankBlockEntity)bench.level.getBlockEntity(fluidIn); }
        FluidTankBlockEntity sinkFluid() { return (FluidTankBlockEntity)bench.level.getBlockEntity(fluidOut); }
        PumpBlockEntity pump() { return (PumpBlockEntity)bench.level.getBlockEntity(pumpPos); }
        int received() {
            var box = (HopperBlockEntity)bench.level.getBlockEntity(catchHopper);
            int count = 0;
            for (int i = 0; i < box.getContainerSize(); i++) {
                ItemStack stack = box.getItem(i);
                if (stack.is(Items.IRON_INGOT)) count += stack.getCount();
            }
            return count;
        }

        void checkIndependentMask(Runnable after) {
            var h = bench.h;
            // Separate fixture-only fault injection. Use the native authorized SIO output,
            // not a fake serialized gate state and not the normal release driver.
            var stuck = new FlowProgram(Map.of("level", DataType.Scalar.INTEGER),
                List.of(new FlowProgram.SignalWrite("fault_fixture", "p0", "level"))).withAssembly();
            var fixture = new ProgramWorkspace();
            fixture.setFlow(Optional.of(FlowDocumentBody.fromProgram(stuck, new DataValue.RecordValue(Map.of("level", number(15))))));
            bench.a.reset();
            h.assertValueEqual(bench.a.installDocument(fixture.snapshot(), ComputerRuntime.MemoryPolicy.RESET),
                ComputerRuntime.InstallResult.APPLIED, "Fixture can electrically drive the same cable even while blocked");
            unlocked = false;
            valveOpen = false;
            bench.a.setPaused(false);
            h.runAfterDelay(170, () -> {
                h.assertTrue(bench.power() == 15 && edges == 1 && pulseEdges == 1 && !pulsing(),
                    "The native pulse expires and does not retrigger even with the input stuck high: " + observation());
                h.assertTrue(!unlocked && !valveOpen && bench.locked() && !bench.opened(),
                    "Actual comparator keeps both gates closed even if an arbitrary program drives the permit");
                h.assertTrue(source().getItem(0).is(Items.IRON_INGOT) && received() == 0
                    && sourceFluid().getTankInventory().getFluidAmount() == 1000
                    && sinkFluid().getTankInventory().getFluidAmount() == 0,
                    "Native interlock prevents movement of both real input materials");
                System.out.println("Line-feed-interlock: native pulse expires with trigger held high; blocked receiver kept both gates closed");
                bench.a.reset();
                h.runAfterDelay(14, () -> {
                    h.assertTrue(bench.power() == 0 && !pulsing() && !previous && !previousPulse,
                        "Physically drop the fault input before starting a new attempt");
                    edges = 0;
                    pulseEdges = 0;
                    highTicks = 0;
                    lockedGatePowerDuringPulse = 15;
                    after.run();
                });
            });
        }

        void run(Consumer<DataValue.RecordValue> done) {
            // Isolate the exact published release procedure for the hardware regression.
            // The previous full terminal test covers how machine_result is actually acquired.
            var doc = example("line_resume_panel_a");
            var flow = doc.flow().orElseThrow().program(doc.archive());
            var procedure = flow.steps().stream().filter(FlowProgram.Procedure.class::isInstance)
                .map(FlowProgram.Procedure.class::cast).filter(candidate -> candidate.key().id().equals("line_resume_panel_a"))
                .findFirst().orElseThrow();
            var arguments = new LinkedHashMap<String, String>();
            procedure.inputs().keySet().forEach(name -> arguments.put(name, name));
            var program = new FlowProgram(procedure.inputs(), List.of(
                new FlowProgram.Invoke("resume_fixture", procedure.key(), new FlowCallBindings(arguments, procedure.returns())),
                procedure, new FlowProgram.EndProcedure("end_resume_fixture"))).withAssembly();
            var initial = new DataValue.RecordValue(Map.of("machine_result", text("machines_checked_hold"),
                "release_armed", new DataValue.BooleanValue(true), "outlet_one", first.binding(), "outlet_two", second.binding(),
                "interlock_probe", ready.binding(), "route_enabled", new DataValue.BooleanValue(false),
                "layout", bench.inputs.get("layout"), "machine_reply", DataValue.NullValue.INSTANCE,
                "confirmed_route", DataValue.NullValue.INSTANCE));
            var workspace = new ProgramWorkspace();
            workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program, initial)));
            bench.a.reset();
            bench.h.assertValueEqual(bench.a.installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET),
                ComputerRuntime.InstallResult.APPLIED, "Install the published hardware continuation with real fixture addresses");
            bench.a.setPaused(false);
            bench.waitForCompletion(4200, done);
        }
    }

    private static DataValue.RecordValue routeProfile(DataValue value, BlockPos item, BlockPos fluid, DataValue route) {
        var fields = new LinkedHashMap<>(((DataValue.RecordValue)value).fields());
        fields.put("item_filter", redirect(fields.get("item_filter"), item));
        fields.put("fluid_filter", redirect(fields.get("fluid_filter"), fluid));
        fields.put("arm_route", route);
        return new DataValue.RecordValue(fields);
    }

    private static void selectSecondProcess(GameTestHelper h, Bench bench, PanelOperator operator, int remaining, Runnable after) {
        selectProcess(h, bench, operator, "process_two", -1, remaining, after);
    }

    private static void selectProcess(GameTestHelper h, Bench bench, PanelOperator operator, String choice,
            long previousRevision, int remaining, Runnable after) {
        h.runAfterDelay(4, () -> {
            var run = bench.a.flowSnapshot().orElseThrow().state();
            h.assertTrue(run.status() != FlowRuntime.Status.FAILED, "Panel fault: " + run.diagnostic());
            h.assertTrue(!run.signals().outputs().containsKey("p0") && bench.power() == 0, "No device output before a physical click");
            operator.request(TerminalUiRequest.Action.REFRESH, -1, "");
            if (operator.menu.view().field("page") instanceof DataValue.RecordValue page
                && ((DataValue.IntegerValue)operator.menu.view().field("revision")).value() > previousRevision) {
                var view = TerminalProjection.project(TerminalCodec.decode(page.field("layout")),
                    (DataValue.RecordValue)page.field("roots"), Map.of(), 4, 12);
                var button = view.children().stream().filter(v -> v.node().equals(choice)).findFirst().orElseThrow();
                h.assertTrue(button.button().orElseThrow().enabled(), "Chosen process is an actual enabled button");
                operator.request(TerminalUiRequest.Action.CLICK, -1, button.instance());
                h.assertValueEqual(operator.menu.code(), "queued", "Click through the native terminal menu");
                after.run();
            } else if (remaining <= 0) h.fail("The physical terminal did not publish: " + run.status() + ": " + run.locals().fields());
            else selectProcess(h, bench, operator, choice, previousRevision, remaining - 4, after);
        });
    }

    private static final class PanelOperator extends FakePlayer {
        private TerminalUiMenu menu;
        private int nextId;
        PanelOperator(ServerLevel world) { super(world, new GameProfile(UUID.randomUUID(), "line-workcell-player")); }

        @Override public OptionalInt openMenu(MenuProvider provider, Consumer<RegistryFriendlyByteBuf> opening) {
            int id = ++nextId;
            containerMenu = provider.createMenu(id, getInventory(), this);
            var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess());
            try {
                opening.accept(wire);
                menu = CBMenuTypes.TERMINAL_UI.get().create(id, getInventory(), wire);
            } finally { wire.release(); }
            return OptionalInt.of(id);
        }

        void request(TerminalUiRequest.Action action, int choice, String instance) {
            var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess());
            try {
                menu.request(action, choice, instance, "").write(wire);
                new TerminalUiRequest(wire).handle(this);
            } finally { wire.release(); }
        }
    }

    private static DataValue.RecordValue prepareNativeRoutes(Bench bench, BlockPos arm) {
        var source = arm.west(2);
        var first = arm.east(1);
        var second = arm.east(2);
        for (var pos : List.of(source, first, second)) bench.set(pos, AllBlocks.DEPOT.getDefaultState());
        return configureNativeRoute(bench, arm, source, first, second);
    }

    private static DataValue.RecordValue configureNativeRoute(Bench bench, BlockPos arm, BlockPos source, BlockPos first,
            BlockPos second) {
        var points = List.of(source, first, second).stream().map(pos -> {
            var state = bench.level.getBlockState(pos);
            return ArmInteractionPointType.getPrimaryType(bench.level, pos, state).createPoint(bench.level, pos, state);
        }).toList();
        points.getFirst().cycleMode();
        bench.actor.moveTo(Vec3.atCenterOf(arm));
        new ArmPlacementPacket(points, arm).handle(bench.actor);
        var names = CreateBuiltInRegistries.ARM_INTERACTION_POINT_TYPE;
        return new DataValue.RecordValue(Map.of("input", pointSpec(0, source.subtract(arm), names.getKey(points.getFirst().getType()).toString()),
            "output", pointSpec(1, second.subtract(arm), names.getKey(points.getLast().getType()).toString())));
    }
    private static DataValue.RecordValue pointSpec(int index, BlockPos offset, String type) {
        return new DataValue.RecordValue(Map.of("index", number(index), "offset", address(offset), "type", text(type)));
    }

    private static DataValue.RecordValue redirect(DataValue profile, BlockPos port) {
        var original = (DataValue.RecordValue)profile;
        return new DataValue.RecordValue(Map.of("rule", original.field("rule"), "target", address(port)));
    }

    private static final class Bench {
        final GameTestHelper h;
        final ServerLevel level;
        final BlockPos gateway, funnel, valve, dust, gearshift, barrelPos, tankPos, armPort, handPort;
        final ComputerBehaviour a, b;
        final FakePlayer actor;
        final List<Probe> readers = new ArrayList<>();
        final Map<String, DataValue> inputs;
        boolean sawOpen, sawPermit, sawCloseAfterOpen;

        Bench(GameTestHelper h) {
            this.h = h;
            level = h.getLevel();
            var min = h.absolutePos(new BlockPos(1, 5, 1));
            var max = min.offset(6, 4, 4);
            gateway = min.offset(6, 1, 2);
            for (var p : BlockPos.betweenClosed(min, max)) {
                if (p.getX() == min.getX() || p.getX() == max.getX() || p.getY() == min.getY()
                        || p.getY() == max.getY() || p.getZ() == min.getZ() || p.getZ() == max.getZ())
                    set(p, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState());
            }
            set(gateway, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING, Direction.EAST));
            var cluster = (ClusterControllerBlockEntity)level.getBlockEntity(gateway);
            actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "line-drain-op"));
            a = computer(min.offset(1, 1, 2), "a");
            b = computer(gateway.west(), "b");
            actor.moveTo(Vec3.atCenterOf(at(1, 0)));
            var members = Map.of("a", member(a), "b", member(b));
            var link = new XBusWire(new XBusWire.Pin("a", "a", "x0"), new XBusWire.Pin("b", "b", "x0"), true);
            h.assertValueEqual(cluster.configure(new ClusterConfiguration(members, Map.of(), Map.of(), Map.of("exchange", link)), actor),
                "configured", "Closed rectangular Cluster and explicit XBus connection");
            b.configureSerialGateway("s0", Optional.of(SerialGatewayAccess.authorize(
                new ClusterMemberEditorAccess(cluster, b.blockEntity), actor, "s0", "line_io")));
            b.configureSerial(new SerialControlData.Configuration("s0", Direction.EAST, true));
            a.configureSignalGateway("p0", Optional.of(SignalGatewayAccess.authorize(
                new ClusterMemberEditorAccess(cluster, a.blockEntity), actor, "p0", "feed_permit")));
            a.configureSignal(new SignalConnections.Configuration("p0", Direction.EAST, true));
            for (int x = 1; x <= 24; x++) cable(at(x, 0));
            for (int z = 1; z <= 8; z++) cable(at(10, z));
            for (int x = 3; x < 10; x++) cable(at(x, 8));

            // For wall-clock-independent physical proof: a powered repeater turns off the inverter.
            dust = at(2, 1);
            wire(dust);
            repeater(at(2, 2));
            set(at(2, 3), Blocks.STONE.defaultBlockState());
            set(at(2, 4), Blocks.REDSTONE_WALL_TORCH.defaultBlockState().setValue(RedstoneWallTorchBlock.FACING, Direction.SOUTH));
            for (int x = 3; x <= 7; x++) wire(at(x, 4));
            repeater(at(3, 5));
            repeater(at(7, 5));
            funnel = at(3, 6);
            gearshift = at(7, 6);
            valve = at(8, 6);
            // No upstream stock on this component-only bench; the actual buffers are seeded below.
            set(funnel.above(), Blocks.BARREL.defaultBlockState());
            set(funnel, AllBlocks.BRASS_FUNNEL.getDefaultState().setValue(AbstractDirectionalFunnelBlock.FACING, Direction.DOWN));
            set(gearshift, AllBlocks.GEARSHIFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
            set(valve, AllBlocks.FLUID_VALVE.getDefaultState().setValue(DirectionalAxisKineticBlock.FACING, Direction.SOUTH)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true));
            motor(at(6, 6), Direction.EAST);
            readers.add(probe(at(3, 7), Direction.NORTH, BuiltinReadComponents.BLOCK));
            readers.add(probe(at(8, 7), Direction.NORTH, BuiltinReadComponents.BLOCK));

            barrelPos = at(13, -2);
            tankPos = at(16, -2);
            set(barrelPos, Blocks.BARREL.defaultBlockState());
            set(tankPos, AllBlocks.FLUID_TANK.getDefaultState());
            readers.add(probe(at(13, -1), Direction.NORTH, BuiltinReadComponents.BLOCK, RedstoneReadComponent.ID));
            readers.add(probe(at(16, -1), Direction.NORTH, BuiltinReadComponents.FLUID));
            armPort = at(20, -1);
            handPort = at(24, -1);
            adapter(armPort);
            adapter(handPort);
            set(armPort.north(), AllBlocks.MECHANICAL_ARM.getDefaultState());
            var hand = handPort.north();
            set(hand.below(2), AllBlocks.DEPOT.getDefaultState());
            set(hand, AllBlocks.DEPLOYER.getDefaultState().setValue(DirectionalAxisKineticBlock.FACING, Direction.DOWN)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, false));
            set(hand.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());

            inputs = Map.of("layout", new DataValue.RecordValue(Map.of(
                "item_gate", readers.get(0).binding(), "fluid_gate", readers.get(1).binding(),
                "items", readers.get(2).binding(), "fluid", readers.get(3).binding(),
                "arm", address(armPort), "deployer", address(handPort))), "settle_ticks", number(40));
            h.assertValueEqual(b.installDocument(example("serial_exchange"), ComputerRuntime.MemoryPolicy.RESET),
                ComputerRuntime.InstallResult.APPLIED, "Install the existing public serial relay");
            h.onEachTick(() -> {
                sawPermit |= power() == 15;
                sawOpen |= power() == 15 && !locked() && opened();
                sawCloseAfterOpen |= sawOpen && power() == 0 && locked() && !opened();
            });
        }

        void authorize() {
            for (var reader : readers) authorize(reader);
            var settings = new MotionPanelSettingsFixture(level);
            settings.grant(h, armPort);
            settings.grant(h, handPort);
        }

        void authorize(Probe reader) {
            var entity = (BlockReaderBlockEntity)level.getBlockEntity(reader.port());
            var service = entity.getBehaviour(ReaderSerialBehaviour.TYPE);
            actor.moveTo(Vec3.atCenterOf(reader.port()));
            var choices = service.discover(actor);
            h.assertValueEqual(choices.size(), 1, "Only the actual shared wire source is available: " + reader);
            h.assertValueEqual(service.configure(actor, service.revision(), Optional.of(choices.getFirst()), reader.components()),
                "configured", "Explicit physical reader grant: " + reader);
        }

        void run(boolean armFeedFirst, Consumer<DataValue.RecordValue> accept) {
            a.reset();
            h.assertValueEqual(a.installDocument(configuredExample(armFeedFirst), ComputerRuntime.MemoryPolicy.RESET),
                ComputerRuntime.InstallResult.APPLIED, "Install public guard with the bench's explicit addresses");
            a.setPaused(false);
            waitForCompletion(6000, accept);
        }

        private void waitForCompletion(int remaining, Consumer<DataValue.RecordValue> accept) {
            h.runAfterDelay(4, () -> {
                var s = a.flowSnapshot().orElseThrow().state();
                var bridge = b.flowSnapshot().orElseThrow().state();
                h.assertTrue(s.status() != FlowRuntime.Status.FAILED && bridge.status() != FlowRuntime.Status.FAILED,
                    "Guard " + s.diagnostic() + ", relay " + bridge.diagnostic());
                if (s.status() == FlowRuntime.Status.COMPLETE) accept.accept(s.locals());
                else if (remaining <= 0) h.fail("Guard did not finish: " + s.status() + " at tick " + level.getGameTime()
                    + "; checkpoints=" + s.locals().fields().entrySet().stream()
                        .filter(entry -> entry.getKey().endsWith("checkpoint")).toList()
                    + "; " + b.serialDevice().status("s0") + "; " + evidence());
                else waitForCompletion(remaining - 4, accept);
            });
        }

        void assertStopped(DataValue.RecordValue result, String status, String checkpoint) {
            var reply = result.field("reply");
            h.assertTrue(status.equals(asText(result.field("status"))), "Expected " + status + ": " + result.fields()
                + "; reply=" + (reply instanceof DataValue.RecordValue r ? r.fields() : reply) + "; " + evidence());
            h.assertValueEqual(result.field("checkpoint"), text(checkpoint), "Reports the real observation that stopped the switch");
            h.assertValueEqual(result.field("guard_ok"), new DataValue.BooleanValue(status.equals("observed_drained")), "Only fresh empty evidence allows the next step");
            h.assertTrue(power() == 0 && locked() && !opened(), "Both physical intakes remain shut: " + evidence());
            System.out.println("Line-drain " + status + "/" + checkpoint + ": gold=" + barrel().getItem(26).getCount()
                + ", water=" + water() + ", dust=" + power() + ", funnel_locked=" + locked() + ", valve_open=" + opened());
        }

        String evidence() {
            return "dust=" + power() + " funnel=" + level.getBlockState(funnel) + " gearshift="
                + level.getBlockState(gearshift).getValue(GearshiftBlock.POWERED) + " valve=" + level.getBlockState(valve)
                + " openSeen=" + sawOpen + " closeSeen=" + sawCloseAfterOpen;
        }
        boolean locked() { return level.getBlockState(funnel).getOptionalValue(AbstractFunnelBlock.POWERED).orElse(false); }
        boolean opened() { return level.getBlockState(valve).getValue(FluidValveBlock.ENABLED); }
        int power() {
            var state = level.getBlockState(dust);
            return state.is(Blocks.REDSTONE_WIRE) ? state.getValue(RedStoneWireBlock.POWER)
                : level.getSignal(dust.north(), Direction.NORTH); // Actual cable output feeding the edge circuit.
        }
        int water() { return tank().getFluidInTank(0).getAmount(); }
        BarrelBlockEntity barrel() { return (BarrelBlockEntity)level.getBlockEntity(barrelPos); }
        IFluidHandler tank() { return Objects.requireNonNull(level.getCapability(Capabilities.FluidHandler.BLOCK, tankPos, Direction.SOUTH)); }
        BlockPos at(int x, int z) { return gateway.offset(x, 0, z); }
        void set(BlockPos pos, BlockState state) { level.setBlockAndUpdate(pos, state); }
        void cable(BlockPos pos) { set(pos, CBBlocks.DATA_CABLE.get().defaultBlockState()); }
        void wire(BlockPos pos) { set(pos.below(), Blocks.STONE.defaultBlockState()); set(pos, Blocks.REDSTONE_WIRE.defaultBlockState()); }
        void repeater(BlockPos pos) { set(pos.below(), Blocks.STONE.defaultBlockState()); set(pos, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.NORTH)); }
        void adapter(BlockPos pos) { set(pos, CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState().setValue(ComponentSettingsBlock.FACING, Direction.NORTH)); }
        Probe probe(BlockPos pos, Direction face, String... components) {
            set(pos, CBBlocks.BLOCK_READER.get().defaultBlockState().setValue(BlockReaderBlock.FACING, face));
            return new Probe(pos, pos.relative(face), Set.of(components));
        }
        void motor(BlockPos pos, Direction face) { motor(pos, face, 64); }
        void motor(BlockPos pos, Direction face, int rpm) {
            set(pos, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, face));
            var motor = (CreativeMotorBlockEntity)level.getBlockEntity(pos);
            motor.initialize();
            motor.generatedSpeed.setValue(rpm);
            motor.updateGeneratedRotation();
        }
        ComputerBehaviour computer(BlockPos pos, String name) {
            var lower = CBBlocks.COMPUTER.get().defaultBlockState().setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
            set(pos, lower);
            set(pos.above(), lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER));
            var block = (ComputerBlockEntity)level.getBlockEntity(pos);
            var resident = Objects.requireNonNull(EntityType.VILLAGER.create(level));
            resident.setVillagerData(new VillagerData(VillagerType.PLAINS, VillagerProfession.CLERIC, 5));
            var box = new ItemStack(CBItems.CARDBOARD_BOX.get());
            h.assertTrue(CapturedEntityBoxHelper.captureEntity(box, resident), "A real default resident identity");
            actor.moveTo(Vec3.atCenterOf(pos));
            actor.setItemInHand(InteractionHand.MAIN_HAND, box);
            h.assertValueEqual(block.installResident(actor, InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS, "Install resident");
            actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            var host = block.getBehaviour(ComputerBehaviour.TYPE);
            host.installFlow(new FlowProgram(Map.of(), List.of()), new DataValue.RecordValue(Map.of()));
            host.configureEndpoint(new EndpointConfiguration(name, true));
            return host;
        }
        ProgramDocument configuredExample(boolean armFeedFirst) {
            var doc = example("line_drain_a");
            var body = doc.flow().orElseThrow();
            var initial = new LinkedHashMap<>(body.initial().fields());
            initial.putAll(inputs);
            if (!armFeedFirst) return new ProgramDocument(doc.archive(), doc.positions(), doc.terminal(), Optional.of(
                new FlowDocumentBody(body.inputs(), body.steps(), new DataValue.RecordValue(initial),
                    body.canvas(), body.inline(), body.execution(), body.compilation())));

            // Compose an ordinary two-block prelude for the first pass only. The guard's assembly is unchanged.
            var flow = body.program(doc.archive());
            var fields = new LinkedHashMap<>(flow.inputs());
            fields.put("test_permit", DataType.Scalar.INTEGER);
            fields.put("test_warm_up", DataType.Scalar.INTEGER);
            initial.put("test_permit", number(15));
            initial.put("test_warm_up", number(100));
            var steps = new ArrayList<FlowProgram.Step>();
            steps.add(new FlowProgram.SignalWrite("arm_fixture_feed", "p0", "test_permit"));
            steps.add(new FlowProgram.Sleep("wait_fixture_gate", "test_warm_up"));
            steps.addAll(flow.steps());
            var composed = new FlowProgram(fields, steps, flow.expressions(), flow.execution(), flow.compilation());
            return new ProgramDocument(doc.archive(), doc.positions(), doc.terminal(), Optional.of(
                FlowDocumentBody.fromProgram(composed, new DataValue.RecordValue(initial))));
        }
    }

    private record Probe(BlockPos port, BlockPos target, Set<String> components) {
        DataValue.RecordValue binding() { return new DataValue.RecordValue(Map.of("reader", address(port), "block", address(target))); }
    }
    private static ClusterConfiguration.Member member(ComputerBehaviour host) {
        return new ClusterConfiguration.Member(host.communicationIdentity().orElseThrow(), host.blockEntity.getBlockPos());
    }
    private static ProgramDocument example(String name) {
        try (var in = LineDrainWorldGameTests.class.getResourceAsStream("/assets/create_biotech/biocomputing/examples/" + name + ".bcp")) {
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(Objects.requireNonNull(in).readAllBytes(),
                StandardCharsets.UTF_8).strip()), CBConfigs.SERVER.biocomputing.current(true).archive());
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    private static DataValue.RecordValue address(BlockPos p) {
        return new DataValue.RecordValue(Map.of("x", number(p.getX()), "y", number(p.getY()), "z", number(p.getZ())));
    }
    private static DataValue.IntegerValue number(long v) { return new DataValue.IntegerValue(v); }
    private static DataValue.TextValue text(String v) { return new DataValue.TextValue(v); }
    private static String asText(DataValue v) { return v instanceof DataValue.TextValue t ? t.value() : ""; }
}
