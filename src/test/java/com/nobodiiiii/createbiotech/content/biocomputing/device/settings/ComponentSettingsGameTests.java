package com.nobodiiiii.createbiotech.content.biocomputing.device.settings;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.decoration.steamWhistle.WhistleBlock;
import com.simibubi.create.content.decoration.steamWhistle.WhistleBlock.WhistleSize;
import com.simibubi.create.content.decoration.steamWhistle.WhistleBlockEntity;
import com.simibubi.create.content.decoration.steamWhistle.WhistleExtenderBlock;
import com.simibubi.create.content.decoration.steamWhistle.WhistleExtenderBlock.WhistleExtenderShape;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.content.logistics.tunnel.BeltTunnelBlock;
import com.simibubi.create.content.logistics.tunnel.BrassTunnelBlockEntity;
import com.simibubi.create.content.logistics.tunnel.BrassTunnelBlockEntity.SelectionMode;
import com.simibubi.create.content.redstone.diodes.BrassDiodeBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class ComponentSettingsGameTests {
    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void programsNativeSmartChuteBatchWithoutBypassingTheRedstoneLock(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 5, 3));
        var output = origin.east(3);
        var target = output.east();
        var lock = target.north();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(target.above(), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(target.below(), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(lock, Blocks.REDSTONE_BLOCK.defaultBlockState());
        var powered = net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED;
        level.setBlockAndUpdate(target, AllBlocks.SMART_CHUTE.getDefaultState().setValue(powered, true));
        var source = (net.minecraft.world.level.block.entity.BarrelBlockEntity)level.getBlockEntity(target.above());
        var sink = (net.minecraft.world.level.block.entity.BarrelBlockEntity)level.getBlockEntity(target.below());
        var filter = BlockEntityBehaviour.get(level, target, com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour.TYPE);
        var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
        var iron = net.minecraft.world.item.Items.IRON_INGOT;
        var gold = net.minecraft.world.item.Items.GOLD_INGOT;
        var sample = new net.minecraft.world.item.ItemStack(iron);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.runAfterDelay(10, () -> {
            h.assertTrue(filter.setFilter(sample.copy()) && filter.isActive() && filter.acceptsValueSettings(),
                "The actual locked smart chute offers a native quantity board when extracting from a barrel");
            source.setItem(0, new net.minecraft.world.item.ItemStack(iron, 5));
            source.setItem(1, new net.minecraft.world.item.ItemStack(gold, 2));
            adapter.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "The smart chute is a valid physical target");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "chute-batch-status", "status", java.util.Map.of(), read -> {
                h.assertValueEqual(read.field("code"), text("ok"), "Read the actual chute's extraction setting");
                var state = (DataValue.RecordValue)read.field("state");
                h.assertValueEqual(state.field("field"), text("extraction_batch"), "Reuse the documented extraction packet");
                var desired = ComponentSettingsProtocol.record(java.util.Map.of(
                    "amount", ComponentSettingsProtocol.number(3), "mode", text("exactly")));
                exchange(h, sender, port, "chute-exactly-three", "set", java.util.Map.of(
                    "token", state.field("token"), "field", state.field("field"), "value", desired), applied -> {
                    h.assertValueEqual(applied.field("code"), text("applied"), "Apply through the native board");
                    h.assertValueEqual(filter.getValueSettings().row(), 1, "Create board selects Exactly");
                    h.assertValueEqual(filter.getValueSettings().value(), 3, "Create board selects three");
                    h.assertTrue(net.minecraft.world.item.ItemStack.matches(filter.getFilter(), sample), "Keep the physical filter sample");
                    h.assertTrue(level.getBlockState(target).getValue(powered) && source.getItem(0).getCount() == 5 && sink.isEmpty(),
                        "Configuration must not unlock the chute or touch stock");
                    level.setBlockAndUpdate(lock, Blocks.AIR.defaultBlockState());
                    h.startSequence().thenWaitUntil(() -> h.assertValueEqual(sink.getItem(0).getCount(), 3,
                        "The native chute transports an exact batch when physically unlocked")).thenExecuteAfter(40, () -> {
                        h.assertTrue(sink.getItem(0).is(iron) && sink.getItem(0).getCount() == 3, "Only the original three iron reach the receiving barrel");
                        h.assertTrue(source.getItem(0).is(iron) && source.getItem(0).getCount() == 2
                            && source.getItem(1).is(gold) && source.getItem(1).getCount() == 2,
                            "Native Exact mode leaves the short remainder and other stock untouched");
                    }).thenSucceed();
                });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void programsNativeBeltFunnelUpToWithoutTakingStockWhileLocked(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 5, 4));
        var output = origin.east(3);
        var target = output.east();
        var lock = target.above();
        var beltPos = target.below();
        var first = beltPos.west(2);
        var last = beltPos.east(2);
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var shaft = AllBlocks.SHAFT.getDefaultState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.Z);
        level.setBlockAndUpdate(first, shaft);
        level.setBlockAndUpdate(last, shaft);
        BeltConnectorItem.createBelts(level, first, last);
        level.setBlockAndUpdate(first.west(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(last.east(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(first.north(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(CreativeMotorBlock.FACING, Direction.SOUTH));
        level.setBlockAndUpdate(target.north(), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(lock, Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(target, AllBlocks.BRASS_BELT_FUNNEL.getDefaultState()
            .setValue(com.simibubi.create.content.logistics.funnel.BeltFunnelBlock.HORIZONTAL_FACING, Direction.SOUTH)
            .setValue(com.simibubi.create.content.logistics.funnel.BeltFunnelBlock.SHAPE,
                com.simibubi.create.content.logistics.funnel.BeltFunnelBlock.Shape.PUSHING)
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED, true));
        var source = (net.minecraft.world.level.block.entity.BarrelBlockEntity)level.getBlockEntity(target.north());
        var belt = (BeltBlockEntity)level.getBlockEntity(beltPos);
        var funnel = (com.simibubi.create.content.logistics.funnel.FunnelBlockEntity)level.getBlockEntity(target);
        var filter = BlockEntityBehaviour.get(funnel, com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour.TYPE);
        var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
        var iron = net.minecraft.world.item.Items.IRON_INGOT;
        var gold = net.minecraft.world.item.Items.GOLD_INGOT;
        var filterSample = new net.minecraft.world.item.ItemStack(iron);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.runAfterDelay(16, () -> {
            var inventory = belt.getControllerBE().getInventory();
            h.assertTrue(belt.getSpeed() != 0, "The native belt has power");
            h.assertTrue(filter.isActive() && filter.acceptsValueSettings() && filter.setFilter(filterSample.copy()),
                "A brass output funnel on a real belt offers native extraction quantity");
            filter.setValueSettings(actor,
                new com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings(1, 3), false);
            source.setItem(0, new net.minecraft.world.item.ItemStack(iron, 2));
            source.setItem(1, new net.minecraft.world.item.ItemStack(gold, 2));
            adapter.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "The brass belt funnel can be explicitly authorized");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "belt-funnel-status", "status", java.util.Map.of(), queried -> {
                var state = (DataValue.RecordValue)queried.field("state");
                var batch = (DataValue.RecordValue)state.field("value");
                h.assertValueEqual(batch.field("mode"), text("exactly"), "Read the real starting mode");
                var next = ComponentSettingsProtocol.record(java.util.Map.of("amount", ComponentSettingsProtocol.number(3),
                    "mode", text("up_to")));
                exchange(h, sender, port, "belt-funnel-upto", "set", java.util.Map.of("field", state.field("field"),
                    "token", state.field("token"), "value", next), applied -> {
                    h.assertValueEqual(applied.field("code"), text("applied"), "Use the existing versioned F7 interface");
                    h.assertValueEqual(filter.getValueSettings().row(), 0, "Only the native mode changed");
                    h.assertValueEqual(filter.getValueSettings().value(), 3, "Quantity stayed at three");
                    h.assertTrue(net.minecraft.world.item.ItemStack.matches(filter.getFilter(), filterSample), "The native filter remains in its slot");
                    h.assertTrue(inventory.getTransportedItems().isEmpty() && source.getItem(0).getCount() == 2,
                        "Applying the setting while locked must not extract from the source");
                    level.setBlockAndUpdate(lock, Blocks.AIR.defaultBlockState());
                    h.startSequence().thenWaitUntil(() -> {
                        var carried = inventory.getTransportedItems();
                        h.assertTrue(carried.stream().anyMatch(stack -> stack.stack.is(iron) && stack.stack.getCount() == 2),
                            "The real moving belt receives the short matching batch after unlock");
                    }).thenExecuteAfter(40, () -> {
                        var carried = inventory.getTransportedItems();
                        h.assertValueEqual(carried.stream().mapToInt(stack -> stack.stack.getCount()).sum(), 2, "Exactly two original items on the belt");
                        h.assertTrue(carried.stream().allMatch(stack -> stack.stack.is(iron)), "The native filter rejects the original gold");
                        h.assertTrue(source.getItem(0).isEmpty() && source.getItem(1).is(gold) && source.getItem(1).getCount() == 2,
                            "The actual source barrel and output balance");
                    }).thenSucceed();
                });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void programsNativeBrassFunnelBatchWithoutEditingItsFilter(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 5, 3));
        var output = origin.east(3);
        var target = output.east();
        var lock = target.north();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(target.above(), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(target.below(), AllBlocks.DEPOT.getDefaultState());
        level.setBlockAndUpdate(lock, Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(target, AllBlocks.BRASS_FUNNEL.getDefaultState()
            .setValue(com.simibubi.create.content.logistics.funnel.FunnelBlock.FACING, Direction.DOWN)
            .setValue(com.simibubi.create.content.logistics.funnel.FunnelBlock.EXTRACTING, true)
            .setValue(com.simibubi.create.content.logistics.funnel.FunnelBlock.POWERED, true));
        var source = (net.minecraft.world.level.block.entity.BarrelBlockEntity)level.getBlockEntity(target.above());
        var sink = (DepotBlockEntity)level.getBlockEntity(target.below());
        var funnel = (com.simibubi.create.content.logistics.funnel.FunnelBlockEntity)level.getBlockEntity(target);
        var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
        var filter = BlockEntityBehaviour.get(funnel, com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour.TYPE);
        var iron = net.minecraft.world.item.Items.IRON_INGOT;
        var gold = net.minecraft.world.item.Items.GOLD_INGOT;
        var sample = new net.minecraft.world.item.ItemStack(iron);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.runAfterDelay(10, () -> {
            h.assertTrue(filter.isActive() && filter.acceptsValueSettings() && filter.setFilter(sample.copy()),
                "The fixture uses a real locked extraction funnel with a native quantity board");
            source.setItem(0, new net.minecraft.world.item.ItemStack(iron, 5));
            source.setItem(1, new net.minecraft.world.item.ItemStack(gold, 2));
            adapter.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "The actual brass funnel is a configurable serial target");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "funnel-batch-read", "status", java.util.Map.of(), read -> {
                h.assertValueEqual(read.field("code"), text("ok"), "Read the native quantity and mode over real cable");
                var state = (DataValue.RecordValue)read.field("state");
                h.assertValueEqual(state.field("field"), text("extraction_batch"), "Expose the native extraction board as one setting");
                var desired = ComponentSettingsProtocol.record(java.util.Map.of(
                    "amount", ComponentSettingsProtocol.number(3), "mode", text("exactly")));
                var invalid = ComponentSettingsProtocol.record(java.util.Map.of(
                    "amount", ComponentSettingsProtocol.number(filter.getMaxStackSize() + 1), "mode", text("exactly")));
                exchange(h, sender, port, "funnel-invalid-batch", "set", java.util.Map.of(
                    "token", state.field("token"), "field", state.field("field"), "value", invalid), rejected -> {
                    h.assertValueEqual(rejected.field("code"), text("value_out_of_range"), "Reject rather than clamp");
                    filter.setValueSettings(actor, new com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings(0, 4), false);
                    exchange(h, sender, port, "funnel-stale-batch", "set", java.util.Map.of(
                        "token", state.field("token"), "field", state.field("field"), "value", desired), stale -> {
                        h.assertValueEqual(stale.field("code"), text("version_conflict"), "A later local change invalidates the observation");
                        h.assertValueEqual(filter.getValueSettings().value(), 4, "Keep the local edit");
                        exchange(h, sender, port, "funnel-batch-reread", "status", java.util.Map.of(), refreshed -> {
                            var current = (DataValue.RecordValue)refreshed.field("state");
                            exchange(h, sender, port, "funnel-exactly-three", "set", java.util.Map.of(
                                "token", current.field("token"), "field", current.field("field"), "value", desired), applied -> {
                                h.assertValueEqual(applied.field("code"), text("applied"), "Apply the actual board settings");
                                h.assertValueEqual(filter.getValueSettings().row(), 1, "Create board selects Exactly");
                                h.assertValueEqual(filter.getValueSettings().value(), 3, "Create board selects three items");
                                h.assertTrue(net.minecraft.world.item.ItemStack.matches(filter.getFilter(), sample), "Original filter sample is untouched");
                                h.assertValueEqual(source.getItem(0).getCount(), 5, "Writing settings does not extract stock");
                                h.assertTrue(sink.getHeldItem().isEmpty(), "No item is emitted by the configuration action");
                                level.setBlockAndUpdate(lock, Blocks.AIR.defaultBlockState());
                                h.startSequence().thenWaitUntil(() ->
                                        h.assertValueEqual(sink.getHeldItem().getCount(), 3, "Native funnel extracts exactly one batch"))
                                    .thenExecuteAfter(40, () -> {
                                        h.assertTrue(sink.getHeldItem().is(iron), "Only the original matching iron reached the depot");
                                        h.assertValueEqual(source.getItem(0).getCount(), 2, "Native Exact mode will not take a short batch");
                                        h.assertTrue(source.getItem(1).is(gold) && source.getItem(1).getCount() == 2, "Other original stock is intact");
                                    }).thenSucceed();
                            });
                        });
                    });
                });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void updatesElevatorContactSettingsWithoutCallingTheLift(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 4, 3));
        var output = origin.east(3);
        var target = output.east();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(target, AllBlocks.ELEVATOR_CONTACT.getDefaultState());
        var contact = (com.simibubi.create.content.contraptions.elevator.ElevatorContactBlockEntity)level.getBlockEntity(target);
        var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.runAfterDelay(10, () -> {
            var nativeState = contact.getBlockState();
            var nativeDoorMode = contact.doorControls.mode;
            adapter.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorized endpoint supports elevator floor settings");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "floor-read", "status", java.util.Map.of(), read -> {
                var state = (DataValue.RecordValue)read.field("state");
                h.assertValueEqual(state.field("field"), text("floor_names"), "Observe the native names together");
                var originalNames = contact.getNames();
                var invalid = ComponentSettingsProtocol.record(java.util.Map.of("short", text("ABCDE"), "long", text("Workshop")));
                exchange(h, sender, port, "floor-invalid", "set", java.util.Map.of("token", state.field("token"),
                    "field", text("floor_names"), "value", invalid), rejected -> {
                    h.assertValueEqual(rejected.field("code"), text("value_out_of_range"), "Reject a label beyond the native editor limit");
                    h.assertValueEqual(contact.getNames(), originalNames, "Neither name changed");
                    var desired = ComponentSettingsProtocol.record(java.util.Map.of("short", text("B2"), "long", text("Workshop")));
                    exchange(h, sender, port, "floor-write", "set", java.util.Map.of("token", state.field("token"),
                        "field", text("floor_names"), "value", desired), applied -> {
                        h.assertValueEqual(applied.field("code"), text("applied"), "Use the native name update through the actual wire");
                        h.assertValueEqual(contact.shortName, "B2", "Native floor identifier");
                        h.assertValueEqual(contact.longName, "Workshop", "Native floor display name");
                        h.assertValueEqual(contact.doorControls.mode, nativeDoorMode, "Renaming does not overwrite door configuration");
                        exchange(h, sender, port, "door-read", "status", java.util.Map.of("field", text("door_control")), door -> {
                            var doorState = (DataValue.RecordValue)door.field("state");
                            exchange(h, sender, port, "door-write", "set", java.util.Map.of("token", doorState.field("token"),
                                "field", text("door_control"), "value", text("north")), changed -> {
                                h.assertValueEqual(changed.field("code"), text("applied"), "Set the native door direction policy");
                                h.assertValueEqual(contact.doorControls.mode, com.simibubi.create.content.decoration.slidingDoor.DoorControl.NORTH,
                                    "Configuration only selects eligible doors");
                                h.assertValueEqual(contact.getBlockState(), nativeState, "Configuration does not call the lift or change any redstone state");
                                h.assertTrue(!contact.activateBlock, "No pending floor activation");
                                h.assertValueEqual(contact.longName, "Workshop", "Door preference did not replace the names");
                                h.succeed();
                            });
                        });
                    });
                });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void configuresOnlyNativeLinearPlacementPolicies(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 5, 3));
        var output = origin.east(3);
        var target = output.east();
        var motorPos = target.north();
        var piston = target.east(4);
        var sticky = piston.south(3);
        var elevator = sticky.east(3);
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(target, AllBlocks.ROPE_PULLEY.getDefaultState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.Z));
        level.setBlockAndUpdate(piston, AllBlocks.MECHANICAL_PISTON.getDefaultState());
        level.setBlockAndUpdate(sticky, AllBlocks.STICKY_MECHANICAL_PISTON.getDefaultState());
        level.setBlockAndUpdate(elevator, AllBlocks.ELEVATOR_PULLEY.getDefaultState());
        var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
        var pulley = (com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity)level.getBlockEntity(target);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.runAfterDelay(10, () -> {
            h.assertTrue(ComponentSettings.observe(level, elevator, "movement_mode").isEmpty(),
                "Elevator pulley inherits a fixed mode, not a player's placement control");
            adapter.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "An exposed native pulley setting is reachable through the authorized serial endpoint");
            for (var pos : java.util.List.of(piston, sticky)) {
                var nativePiston = (com.simibubi.create.content.contraptions.piston.MechanicalPistonBlockEntity)level.getBlockEntity(pos);
                var policy = ComponentSettings.observe(level, pos, "movement_mode").orElseThrow();
                h.assertValueEqual(policy.value(), text("move_place"), "Piston uses native translation settings");
                h.assertTrue(ComponentSettings.plan(level, pos, policy, text("move_never_place")).apply(), "Native piston accepts the explicit setting");
                h.assertValueEqual(BlockEntityBehaviour.get(nativePiston, ScrollValueBehaviour.TYPE).getValue(), 2, "Native piston option changed");
                h.assertTrue(!nativePiston.running && !nativePiston.assembleNextTick && nativePiston.movedContraption == null,
                    "Setting a piston policy does not send a movement request");
            }
            h.runAfterDelay(3, () -> exchange(h, sender, port, "pulley-policy", "status", java.util.Map.of(), reply -> {
                var reading = (DataValue.RecordValue)reply.field("state");
                h.assertValueEqual(reading.field("value"), text("move_place"), "Rope pulley uses linear rather than rotary placement policies");
                exchange(h, sender, port, "pulley-set", "set", java.util.Map.of("token", reading.field("token"),
                    "field", text("movement_mode"), "value", text("move_never_place")), applied -> {
                    h.assertValueEqual(applied.field("code"), text("applied"), "Authorized serial request applies the pulley mode");
                    h.assertTrue(!pulley.running && !pulley.assembleNextTick && pulley.movedContraption == null,
                        "Configuring the pulley does not move or assemble anything");
                    h.assertValueEqual(BlockEntityBehaviour.get(pulley, ScrollValueBehaviour.TYPE).getValue(), 2, "Use the native pulley setting");
                    level.setBlockAndUpdate(target.below(), Blocks.GOLD_BLOCK.defaultBlockState());
                    level.setBlockAndUpdate(motorPos, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                        .setValue(com.simibubi.create.content.kinetics.motor.CreativeMotorBlock.FACING, Direction.SOUTH));
                    var motor = (com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity)level.getBlockEntity(motorPos);
                    motor.generatedSpeed.setValue(1);
                    h.startSequence().thenWaitUntil(() -> h.assertTrue(pulley.running && pulley.movedContraption != null,
                        "Native drive has assembled and moves the real load")).thenExecute(() ->
                            exchange(h, sender, port, "pulling-policy", "status", java.util.Map.of(), active -> {
                                var current = (DataValue.RecordValue)active.field("state");
                                exchange(h, sender, port, "pulling-set", "set", java.util.Map.of("token", current.field("token"),
                                    "field", text("movement_mode"), "value", text("move_place_returned")), busy -> {
                                    h.assertValueEqual(busy.field("code"), text("busy"), "Cannot change the policy of an attached pulley");
                                    h.assertValueEqual(BlockEntityBehaviour.get(pulley, ScrollValueBehaviour.TYPE).getValue(), 2,
                                        "Busy request leaves the native option untouched");
                                    pulley.disassemble();
                                    level.removeBlock(motorPos, false);
                                    h.succeed();
                                });
                            }));
                });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1300)
    public static void configuresCreateBearingsAndReversesAnAssembledWindmill(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 4, 3));
        var output = origin.east(3);
        var target = output.east();
        var shaftPos = target.south();
        var mechanicalPos = target.east(4);
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(target, AllBlocks.WINDMILL_BEARING.getDefaultState()
            .setValue(com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(shaftPos, AllBlocks.SHAFT.getDefaultState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.Z));
        level.setBlockAndUpdate(mechanicalPos, AllBlocks.MECHANICAL_BEARING.getDefaultState());
        var sailCount = com.simibubi.create.infrastructure.config.AllConfigs.server().kinetics.minimumWindmillSails.get();
        for (int i = 0; i < sailCount; i++)
            level.setBlockAndUpdate(target.north().above(i), AllBlocks.SAIL.getDefaultState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, Direction.SOUTH));
        var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        var windmill = (com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity)level.getBlockEntity(target);
        var shaft = (com.simibubi.create.content.kinetics.base.KineticBlockEntity)level.getBlockEntity(shaftPos);
        h.runAfterDelay(10, () -> {
            var movement = ComponentSettings.observe(level, mechanicalPos).orElse(null);
            h.assertTrue(movement != null, "Native mechanical bearing has a configurable placement policy");
            h.assertValueEqual(movement.field().name(), "movement_mode", "Do not mistake the placement policy for rotation direction");
            h.assertTrue(ComponentSettings.plan(level, mechanicalPos, movement, text("rotate_place_returned")).apply(),
                "Set the native returned-position policy");
            var mechanical = (com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity)level.getBlockEntity(mechanicalPos);
            h.assertTrue(!mechanical.isRunning() && mechanical.getMovedContraption() == null, "Changing a policy does not assemble the bearing");
            h.assertTrue(ComponentSettings.observe(level, mechanicalPos, "rotation_direction").isEmpty(), "No rotation-direction setting on this bearing");
            h.assertTrue(!windmill.isRunning() && shaft.getSpeed() == 0, "Unassembled windmill has no free power");
            windmill.assemble(); // Prepare a genuine sail contraption; the serial configuration must not reassemble it.
            h.assertTrue(windmill.isRunning(), "Fixture uses a real windmill assembly with enough sails");
            var attached = windmill.getMovedContraption();
            h.assertValueEqual(((com.simibubi.create.content.contraptions.bearing.BearingContraption)attached.getContraption()).getSailBlocks(),
                sailCount, "Native contraption holds the original sails");
            h.startSequence().thenWaitUntil(() -> h.assertTrue(shaft.getSpeed() != 0, "Windmill physically drives the rear shaft"))
                .thenExecute(() -> {
                    float originalSpeed = shaft.getSpeed();
                    adapter.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
                    actor.grant();
                    h.assertValueEqual(actor.client.code(), "configured", "Use the existing physical output and source grant");
                    h.runAfterDelay(3, () -> exchange(h, sender, port, "windmill-status", "status", java.util.Map.of(), read -> {
                        var snapshot = (DataValue.RecordValue)read.field("state");
                        h.assertValueEqual(snapshot.field("field"), text("rotation_direction"), "Native windmill has its own direction control");
                        var direction = text("clockwise").equals(snapshot.field("value")) ? text("counter_clockwise") : text("clockwise");
                        var args = java.util.Map.of("token", snapshot.field("token"), "field", (DataValue)text("rotation_direction"), "value", direction);
                        exchange(h, sender, port, "windmill-reverse", "set", args, applied -> {
                            h.assertValueEqual(applied.field("code"), text("applied"), "Authorized serial command reaches the native setter");
                            h.assertTrue(windmill.getMovedContraption() == attached, "Changing direction did not replace the native contraption");
                            h.assertValueEqual(shaft.getSpeed(), -originalSpeed, "Real shaft reverses without changing speed magnitude");
                            exchange(h, sender, port, "windmill-repeat", "set", args, stale -> {
                                h.assertValueEqual(stale.field("code"), text("version_conflict"), "Do not replay the setting token");
                                h.assertValueEqual(shaft.getSpeed(), -originalSpeed, "Late replay leaves the shaft alone");
                                windmill.disassemble(); // Native fixture cleanup before the test world is reused.
                                h.succeed();
                            });
                        });
                    }));
                });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void deployerModeChangesOnlyAtRestWithPhysicalLock(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 4, 3));
        var output = origin.east(3);
        var target = output.east();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        var material = depot(level, target.below(2));
        level.setBlockAndUpdate(target, AllBlocks.DEPLOYER.getDefaultState()
            .setValue(com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING, Direction.DOWN));
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var block = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = block.getBehaviour(ComponentSettingsPort.TYPE);
        var deployer = (com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity)level.getBlockEntity(target);
        var access = (DeployerControl.Access)deployer;
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        var query = java.util.Map.<String, DataValue>of("field", text("mode"));
        h.runAfterDelay(10, () -> {
            block.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize via the real output menu");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "unlocked-read", "status", query, read -> {
                h.assertValueEqual(read.field("code"), text("ok"), "Read the native Deployer mode through the public settings driver");
                var mode = (DataValue.RecordValue)read.field("state");
                h.assertValueEqual(mode.field("value"), text("use"), "Original native mode");
                exchange(h, sender, port, "unlocked-write", "set", deployerModeArgs(mode, "punch"), rejected -> {
                    h.assertValueEqual(rejected.field("code"), text("physical_lock_required"), "An unlocked Deployer must not switch");
                    h.assertTrue(access.biocomputingUseMode(), "No mode change without the lock");
                    level.setBlockAndUpdate(target.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                    exchange(h, sender, port, "locked-read", "status", query, locked -> {
                        var current = (DataValue.RecordValue)locked.field("state");
                        deployer.changeMode();
                        deployer.changeMode();
                        exchange(h, sender, port, "stale-mode", "set", deployerModeArgs(current, "punch"), stale -> {
                            h.assertValueEqual(stale.field("code"), text("version_conflict"), "Manual mode changes invalidate the old observation even if the mode matches again");
                            exchange(h, sender, port, "fresh-mode", "status", query, fresh -> {
                                var arguments = deployerModeArgs((DataValue.RecordValue)fresh.field("state"), "punch");
                                exchange(h, sender, port, "switch-mode", "set", arguments, applied -> {
                                    h.assertValueEqual(applied.field("code"), text("applied"), "Native mode changed");
                                    h.assertValueEqual(DeployerControl.nativeMode(deployer), "PUNCH", "Actual new mode");
                                    h.assertTrue(access.biocomputingLocked() && access.biocomputingIdle(), "Still physically locked and idle");
                                    h.assertTrue(access.biocomputingProcessId() == null && material.getHeldItem().isEmpty(),
                                        "Changing mode does not create an action or affect the worksite");
                                    exchange(h, sender, port, "repeat-mode", "set", arguments, repeat -> {
                                        h.assertValueEqual(repeat.field("code"), text("version_conflict"), "Do not replay a mode change");
                                        h.succeed();
                                    });
                                });
                            });
                        });
                    });
                });
            }));
        });
    }

    private static java.util.Map<String, DataValue> deployerModeArgs(DataValue.RecordValue status, String mode) {
        return java.util.Map.of("token", status.field("token"), "field", text("mode"), "value", text(mode));
    }

    @GameTest(template = "empty", timeoutTicks = 2300)
    public static void deployerProcessesExactlyOneNativeRecipeWhilePhysicallyLocked(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 4, 3));
        var output = origin.east(3);
        var target = output.east();
        var materialPos = target.below(2);
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        var originalMaterial = depot(level, materialPos);
        var state = AllBlocks.DEPLOYER.getDefaultState()
            .setValue(com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock.FACING, Direction.DOWN)
            .setValue(com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, false);
        level.setBlockAndUpdate(target, state);
        level.setBlockAndUpdate(target.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        var drivePos = target.north();
        level.setBlockAndUpdate(drivePos, AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(com.simibubi.create.content.kinetics.motor.CreativeMotorBlock.FACING, Direction.SOUTH));
        var motor = (com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity)level.getBlockEntity(drivePos);
        motor.initialize();
        motor.generatedSpeed.setValue(64);
        motor.updateGeneratedRotation();
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var block = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = block.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        var deployer = (com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity)level.getBlockEntity(target);
        var work = new net.minecraft.world.item.ItemStack(Blocks.STRIPPED_OAK_LOG, 2);
        h.runAfterDelay(10, () -> {
            deployer.getPlayer().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.item.ItemStack(AllItemsForDeployer(), 2));
            originalMaterial.setHeldItem(work);
            h.assertTrue(deployer.getSpeed() != 0 && level.hasNeighborSignal(target), "Real motor and physical redstone lock");
            h.assertTrue(deployer.getRecipe(work) != null, "Real Create andesite casing recipe");
            block.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize using the output menu");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "deployer-read", "deployer_status", java.util.Map.of(), read -> {
                h.assertValueEqual(read.field("code"), text("ok"), "Read the actual deployer");
                var snapshot = (DataValue.RecordValue)read.field("state");
                h.assertValueEqual(snapshot.field("scope"), text("belt_processing"), "Only the transport processing path");
                h.assertValueEqual(originalMaterial.getHeldItem().getCount(), 2, "Locked without a request");
                level.removeBlock(materialPos, false);
                var material = depot(level, materialPos);
                material.setHeldItem(work.copy());
                exchange(h, sender, port, "deployer-replaced-before-admission", "deployer_process",
                    java.util.Map.of("token", snapshot.field("token")), rejected -> {
                        h.assertValueEqual(rejected.field("code"), text("version_conflict"),
                            "The token is for the observed depot, not any other depot at the same position");
                        h.assertValueEqual(material.getHeldItem().getCount(), 2, "Replacement depot was not touched");
                        exchange(h, sender, port, "deployer-observe-tool", "deployer_status", java.util.Map.of(), observedTool -> {
                            var oldTool = (DataValue.RecordValue)observedTool.field("state");
                            deployer.getPlayer().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                                new net.minecraft.world.item.ItemStack(AllItemsForDeployer(), 3));
                            exchange(h, sender, port, "deployer-tool-changed-before-admission", "deployer_process",
                                java.util.Map.of("token", oldTool.field("token")), staleTool -> {
                                    h.assertValueEqual(staleTool.field("code"), text("version_conflict"),
                                        "A changed tool stack needs a fresh reservation even if still eligible");
                                    h.assertValueEqual(material.getHeldItem().getCount(), 2, "Tool replacement was not allowed to start a recipe");
                                    deployer.getPlayer().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                                        new net.minecraft.world.item.ItemStack(AllItemsForDeployer(), 2));
                                    exchange(h, sender, port, "deployer-fresh-worksite", "deployer_status", java.util.Map.of(), refreshed -> {
                                        var observed = (DataValue.RecordValue)refreshed.field("state");
                                        var filter = BlockEntityBehaviour.get(level, target,
                                            com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour.TYPE);
                                        h.assertTrue(filter.setFilter(new net.minecraft.world.item.ItemStack(AllItemsForDeployer()))
                                            && filter.setFilter(net.minecraft.world.item.ItemStack.EMPTY), "Native filter is mounted then removed");
                                        exchange(h, sender, port, "deployer-stale-policy", "deployer_process",
                                            java.util.Map.of("token", observed.field("token")), stalePolicy -> {
                                                h.assertValueEqual(stalePolicy.field("code"), text("version_conflict"),
                                                    "An equivalent current filter stack is not proof that policy stayed the same");
                                                h.assertValueEqual(material.getHeldItem().getCount(), 2, "Stale policy did not start a recipe");
                                                exchange(h, sender, port, "deployer-read-current-policy", "deployer_status", java.util.Map.of(), readPolicy ->
                                                    verifyDeployerProcess(h, sender, port, deployer, materialPos, (DataValue.RecordValue)readPolicy.field("state")));
                                            });
                                    });
                                });
                        });
                    });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void deployerProcessesWithSameTickControllerAuthorization(GameTestHelper h) {
        var level = h.getLevel();
        var target = h.absolutePos(new BlockPos(3, 4, 3));
        var worksitePos = target.below(2);
        var material = depot(level, worksitePos);
        var state = AllBlocks.DEPLOYER.getDefaultState()
            .setValue(com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock.FACING, Direction.DOWN)
            .setValue(com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, false);
        level.setBlockAndUpdate(target, state);
        level.setBlockAndUpdate(target.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        var drivePos = target.north();
        level.setBlockAndUpdate(drivePos, AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(CreativeMotorBlock.FACING, Direction.SOUTH));
        var motor = (com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity)level.getBlockEntity(drivePos);
        motor.initialize();
        motor.generatedSpeed.setValue(64);
        motor.updateGeneratedRotation();
        var deployer = (com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity)level.getBlockEntity(target);
        var access = (DeployerControl.Access)deployer;
        // Both Create block entities have registered their tickers before this actual controller.
        var controllerPos = target.east(2);
        level.setBlockAndUpdate(controllerPos, com.nobodiiiii.createbiotech.registry.CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState());
        var controller = (com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity)level.getBlockEntity(controllerPos);
        var work = new net.minecraft.world.item.ItemStack(Blocks.STRIPPED_OAK_LOG, 2);
        h.runAfterDelay(12, () -> {
            deployer.getPlayer().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.item.ItemStack(AllItemsForDeployer(), 2));
            var worksite = DeployerControl.target(deployer, null);
            h.assertValueEqual(DeployerControl.readiness(deployer, worksite), "ok", "The actual locked device is ready");
            h.assertTrue(controller.physicalIoTickAvailable(), "Admission happens after the actual controller tick");
            h.assertTrue(deployer.getRecipe(work) != null, "The workpiece uses an actual Create recipe");
            var action = java.util.UUID.randomUUID();
            h.assertTrue(access.biocomputingAdmit(action, worksite, controller::physicalIoTickAvailable),
                "Use the same current-tick prerequisite as a Cluster serial grant");
            h.startSequence()
                .thenIdle(4)
                .thenExecute(() -> {
                    h.assertValueEqual(access.biocomputingProcess(), "waiting_for_material",
                        "An empty worksite must not retire an authorized reservation because the controller ticks later");
                    material.setHeldItem(work);
                })
                .thenWaitUntil(() -> h.assertValueEqual(access.biocomputingProcess(), "processed",
                    "The same authorized reservation processes the native workpiece"))
                .thenExecute(() -> {
                    h.assertValueEqual(access.biocomputingProcessId(), action, "Same action, no automatic retry");
                    h.assertValueEqual(deployer.getPlayer().getMainHandItem().getCount(), 1, "Exactly one real ingredient consumed");
                    var storage = level.getCapability(Capabilities.ItemHandler.BLOCK, worksitePos, Direction.UP);
                    int casing = 0, logs = 0;
                    for (int i = 0; i < storage.getSlots(); i++) {
                        var item = storage.getStackInSlot(i);
                        if (AllBlocks.ANDESITE_CASING.isIn(item)) casing += item.getCount();
                        if (item.is(Blocks.STRIPPED_OAK_LOG.asItem())) logs += item.getCount();
                    }
                    h.assertValueEqual(casing, 1, "One recipe output");
                    h.assertValueEqual(logs, 1, "The second workpiece remains unchanged");
                }).thenSucceed();
        });
    }

    private static void verifyDeployerProcess(GameTestHelper h,
            com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour sender, ComponentSettingsPort port,
            com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity deployer, BlockPos materialPos, DataValue.RecordValue snapshot) {
        var level = h.getLevel();
        var args = java.util.Map.of("token", snapshot.field("token"));
        exchange(h, sender, port, "deployer-work", "deployer_process", args, admitted -> {
            h.assertValueEqual(admitted.field("code"), text("accepted"), "Reserve one cycle without bypassing the native recipe");
            exchange(h, sender, port, "deployer-replay", "deployer_process", args, replay -> {
                h.assertValueEqual(replay.field("code"), text("version_conflict"), "No replay");
                h.assertValueEqual(deployer.getPlayer().getMainHandItem().getCount(), 1, "One tool ingredient consumed");
                var handler = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, materialPos, Direction.UP);
                int casing = 0, logs = 0;
                for (int i = 0; i < handler.getSlots(); i++) {
                    var item = handler.getStackInSlot(i);
                    if (AllBlocks.ANDESITE_CASING.isIn(item)) casing += item.getCount();
                    if (item.is(Blocks.STRIPPED_OAK_LOG.asItem())) logs += item.getCount();
                }
                h.assertValueEqual(casing, 1, "The real depot receives one actual recipe output");
                h.assertValueEqual(logs, 1, "Second material remains unprocessed");
                exchange(h, sender, port, "deployer-done", "deployer_status", java.util.Map.of(), done -> {
                    var result = (DataValue.RecordValue)done.field("state");
                    h.assertValueEqual(result.field("process"), text("processed"), "Completion from native conversion, not animation");
                    h.assertValueEqual(result.field("action"), ((DataValue.RecordValue)admitted.field("state")).field("action"), "Same action");
                    verifyDeployerTargetReplacement(h, sender, port, deployer, materialPos, result);
                });
            });
        });
    }

    private static void verifyDeployerTargetReplacement(GameTestHelper h,
            com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour sender, ComponentSettingsPort port,
            com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity deployer,
            BlockPos materialPos, DataValue.RecordValue previous) {
        var level = h.getLevel();
        var access = (DeployerControl.Access)deployer;
        var lever = Blocks.LEVER.defaultBlockState().setValue(net.minecraft.world.level.block.LeverBlock.FACE,
            net.minecraft.world.level.block.state.properties.AttachFace.FLOOR);
        level.setBlockAndUpdate(materialPos.below(), Blocks.STONE.defaultBlockState());
        var replySeen = new java.util.concurrent.atomic.AtomicBoolean();
        h.startSequence()
            .thenWaitUntil(() -> h.assertValueEqual(access.biocomputingPhase(), "expanding", "Original transport starts the second action"))
            .thenExecute(() -> level.setBlockAndUpdate(materialPos, lever))
            .thenWaitUntil(() -> h.assertValueEqual(access.biocomputingPhase(), "waiting", "Native arm retracts without a workpiece"))
            .thenWaitUntil(() -> h.assertTrue(replySeen.get(), "Wait for the half-duplex acceptance reply before sending the next frame"))
            .thenExecute(() -> {
                h.assertTrue(level.getBlockState(materialPos).is(Blocks.LEVER), "No replacement of an unrelated world block");
                h.assertTrue(!level.getBlockState(materialPos).getValue(net.minecraft.world.level.block.LeverBlock.POWERED),
                    "Processing authorization must never operate a replacement lever");
                h.assertValueEqual(deployer.getPlayer().getMainHandItem().getCount(), 1, "No second material is consumed");
                exchange(h, sender, port, "deployer-stale-target", "deployer_status", java.util.Map.of(), done -> {
                    var state = (DataValue.RecordValue)done.field("state");
                    h.assertValueEqual(state.field("process"), text("not_processed"), "Replacement is not a successful processing cycle");
                    h.assertValueEqual(state.field("availability"), text("processing_target_required"), "The replacement cannot receive another permit");
                    h.succeed();
                });
            });
        // The serial acceptance reply may arrive after the native arm has already extended.
        exchange(h, sender, port, "deployer-before-replacement", "deployer_process",
            java.util.Map.of("token", previous.field("token")), started -> {
                h.assertValueEqual(started.field("code"), text("accepted"), "The original worksite was still valid at admission");
                replySeen.set(true);
            });
    }

    private static net.minecraft.world.item.Item AllItemsForDeployer() {
        return com.simibubi.create.AllItems.ANDESITE_ALLOY.get();
    }

    @GameTest(template = "empty", timeoutTicks = 2300)
    public static void deployerReloadWhileExtendedDoesNotReplayNativeRecipe(GameTestHelper h) {
        var level = h.getLevel();
        var senderPos = h.absolutePos(new BlockPos(2, 4, 3));
        var output = senderPos.east(3);
        var target = output.east();
        var materialPos = target.below(2);
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, senderPos);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(senderPos.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        var material = depot(level, materialPos);
        var state = AllBlocks.DEPLOYER.getDefaultState()
            .setValue(com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock.FACING, Direction.DOWN)
            .setValue(com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, false);
        level.setBlockAndUpdate(target, state);
        level.setBlockAndUpdate(target.north(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(com.simibubi.create.content.kinetics.motor.CreativeMotorBlock.FACING, Direction.SOUTH));
        level.setBlockAndUpdate(target.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        var deployer = (com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity)level.getBlockEntity(target);
        var access = (DeployerControl.Access)deployer;
        var log = new net.minecraft.world.item.ItemStack(Blocks.STRIPPED_OAK_LOG);
        h.runAfterDelay(15, () -> {
            h.assertTrue(deployer.getSpeed() != 0 && level.hasNeighborSignal(target), "The native drive and redstone lock are live");
            deployer.getPlayer().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.item.ItemStack(AllItemsForDeployer(), 2));
            adapter.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize the output over the native menu");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "extended-read", "deployer_status", java.util.Map.of(), read -> {
                h.assertValueEqual(read.field("code"), text("ok"), "Obtain a current one-shot token");
                var status = (DataValue.RecordValue)read.field("state");
                exchange(h, sender, port, "extended-admit", "deployer_process", java.util.Map.of("token", status.field("token")), accepted -> {
                    h.assertValueEqual(accepted.field("code"), text("accepted"), "Receive the serial admission before inserting a workpiece");
                    var action = ((DataValue.RecordValue)accepted.field("state")).field("action");
                    h.assertValueEqual(access.biocomputingProcess(), "waiting_for_material", "No completed work yet");
                    exchange(h, sender, port, "reserved-mode-read", "status", java.util.Map.of("field", text("mode")), mode -> {
                        h.assertValueEqual(mode.field("code"), text("ok"), "A busy setting remains readable");
                        var current = (DataValue.RecordValue)mode.field("state");
                        h.assertValueEqual(current.field("busy"), new DataValue.BooleanValue(true), "An accepted reservation is busy before the hand extends");
                        exchange(h, sender, port, "reserved-mode-write", "set", deployerModeArgs(current, "punch"), rejected -> {
                            h.assertValueEqual(rejected.field("code"), text("busy"), "Do not change how a pending operation executes");
                            h.assertTrue(access.biocomputingUseMode(), "The pending operation keeps the native use mode");
                            h.assertValueEqual(text(access.biocomputingProcessId().toString()), action, "Mode request did not replace the active action");
                            material.setHeldItem(log.copy());
                            h.startSequence()
                                .thenWaitUntil(() -> h.assertTrue(access.biocomputingPhase().equals("expanding")
                                    && access.biocomputingProcess().equals("processing"), "Native extension starts with the real workpiece"))
                                .thenExecute(() -> {
                                    h.assertTrue(net.minecraft.world.item.ItemStack.matches(material.getHeldItem(), log),
                                        "The action is in flight, not already converted");
                                    var saved = deployer.saveWithFullMetadata(level.registryAccess());
                                    var nativeState = deployer.getBlockState();
                                    level.removeBlockEntity(target);
                                    var restored = (com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity)
                                        net.minecraft.world.level.block.entity.BlockEntity.loadStatic(target, nativeState, saved, level.registryAccess());
                                    h.assertTrue(restored != null, "Reload the same native device from its persisted data");
                                    level.setBlockEntity(restored);
                                    var recovery = (DeployerControl.Access)restored;
                                    h.assertValueEqual(recovery.biocomputingPhase(), "expanding", "Native animation state survives in the saved fixture");
                                    h.succeedWhen(() -> {
                                        h.assertTrue(recovery.biocomputingIdle() && recovery.biocomputingLocked(),
                                            "Native animation has retired under the unchanged redstone lock");
                                        h.assertValueEqual(text(recovery.biocomputingProcessId().toString()), action, "Same receipt identity, not a new operation");
                                        h.assertValueEqual(recovery.biocomputingProcess(), "interrupted", "No replay of the captured transport action");
                                        h.assertTrue(net.minecraft.world.item.ItemStack.matches(material.getHeldItem(), log), "Unmodified workpiece");
                                        h.assertValueEqual(restored.getPlayer().getMainHandItem().getCount(), 2, "Neither alloy is consumed");
                                        var handler = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, materialPos, Direction.UP);
                                        for (int i = 0; i < handler.getSlots(); i++)
                                            h.assertTrue(!AllBlocks.ANDESITE_CASING.isIn(handler.getStackInSlot(i)), "No hidden recipe output");
                                    });
                                });
                        });
                    });
                });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 2300)
    public static void deployerProcessesOneItemOnAMovingBelt(GameTestHelper h) {
        verifyNativeDeployerBelt(h, false);
    }

    @GameTest(template = "empty", timeoutTicks = 2300)
    public static void deployerRevocationWhileExtendingDoesNotConvertCapturedBeltItem(GameTestHelper h) {
        verifyNativeDeployerBelt(h, true);
    }

    private static void verifyNativeDeployerBelt(GameTestHelper h, boolean revokeDuringExtension) {
        var level = h.getLevel();
        var senderPos = h.absolutePos(new BlockPos(2, 4, 3));
        var output = senderPos.east(3);
        var deployerPos = output.east();
        var middle = deployerPos.below(2);
        var first = middle.west(3);
        var last = middle.east(3);
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, senderPos);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(senderPos.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        var shaft = AllBlocks.SHAFT.getDefaultState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.Z);
        level.setBlockAndUpdate(first, shaft);
        level.setBlockAndUpdate(last, shaft);
        com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem.createBelts(level, first, last);
        level.setBlockAndUpdate(first.west(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(last.east(), Blocks.STONE.defaultBlockState());
        var motor = AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(com.simibubi.create.content.kinetics.motor.CreativeMotorBlock.FACING, Direction.SOUTH);
        level.setBlockAndUpdate(first.north(), motor);
        level.setBlockAndUpdate(deployerPos, AllBlocks.DEPLOYER.getDefaultState()
            .setValue(com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock.FACING, Direction.DOWN)
            .setValue(com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, false));
        level.setBlockAndUpdate(deployerPos.north(), motor);
        var deployerMotor = (com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity)level.getBlockEntity(deployerPos.north());
        deployerMotor.initialize();
        deployerMotor.generatedSpeed.setValue(64);
        deployerMotor.updateGeneratedRotation();
        level.setBlockAndUpdate(deployerPos.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        var deployer = (com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity)level.getBlockEntity(deployerPos);
        h.runAfterDelay(15, () -> {
            var belt = (com.simibubi.create.content.kinetics.belt.BeltBlockEntity)level.getBlockEntity(middle);
            h.assertTrue(belt.getSpeed() != 0 && deployer.getSpeed() != 0, "Two native kinetic sources drive the belt and deployer");
            var upstream = belt.getMovementFacing() == Direction.EAST ? first : last;
            var beltInput = BlockEntityBehaviour.get(level, upstream, com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour.TYPE);
            var inventory = belt.getControllerBE().getInventory();
            deployer.getPlayer().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(AllItemsForDeployer(), 2));
            adapter.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize the real serial port");
            if (revokeDuringExtension) {
                verifyBeltCancellationAfterCapture(h, sender, port, actor, deployer, belt, beltInput, inventory);
                return;
            }
            h.runAfterDelay(3, () -> exchange(h, sender, port, "belt-query", "deployer_status", java.util.Map.of(), read -> {
                var state = (DataValue.RecordValue)read.field("state");
                exchange(h, sender, port, "belt-process", "deployer_process", java.util.Map.of("token", state.field("token")), accepted -> {
                    h.assertValueEqual(accepted.field("code"), text("accepted"), "Permit can wait for moving stock");
                    var work = new net.minecraft.world.item.ItemStack(Blocks.STRIPPED_OAK_LOG, 2);
                    h.assertTrue(beltInput.handleInsertion(work, Direction.UP, false).isEmpty(), "Use native insertion at upstream pulley");
                    h.runAfterDelay(160, () -> {
                        int casings = 0, logs = 0;
                        for (var carried : inventory.getTransportedItems()) {
                            if (AllBlocks.ANDESITE_CASING.isIn(carried.stack)) casings += carried.stack.getCount();
                            else if (carried.stack.is(Blocks.STRIPPED_OAK_LOG.asItem())) logs += carried.stack.getCount();
                            else h.fail("Unexpected belt output: " + carried.stack);
                            h.assertTrue(belt.getDirectionAwareBeltMovementSpeed() > 0 ? carried.beltPosition > belt.index + 1
                                : carried.beltPosition < belt.index, "Stock leaves the worksite along the real belt: "
                                    + carried.stack + " at " + carried.beltPosition + " vs. segment " + belt.index
                                    + "; velocity=" + belt.getDirectionAwareBeltMovementSpeed());
                        }
                        h.assertValueEqual(casings, 1, "One solid casing travels downstream");
                        h.assertValueEqual(logs, 1, "One untreated log travels downstream");
                        h.assertValueEqual(deployer.getPlayer().getMainHandItem().getCount(), 1, "One native alloy consumed");
                        exchange(h, sender, port, "belt-result", "deployer_status", java.util.Map.of(), result -> {
                            var done = (DataValue.RecordValue)result.field("state");
                            h.assertValueEqual(done.field("process"), text("processed"), "Actual belt conversion completed");
                            h.assertValueEqual(done.field("action"), ((DataValue.RecordValue)accepted.field("state")).field("action"), "Matching receipt");
                            verifyBeltCancellation(h, sender, port, actor, deployer, beltInput, inventory, done);
                        });
                    });
                });
            }));
        });
    }

    private static void verifyBeltCancellationAfterCapture(GameTestHelper h,
            com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour sender, ComponentSettingsPort port,
            MenuPlayer actor, com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity deployer,
            com.simibubi.create.content.kinetics.belt.BeltBlockEntity belt,
            com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour input,
            com.simibubi.create.content.kinetics.belt.transport.BeltInventory inventory) {
        var access = (DeployerControl.Access)deployer;
        var log = new net.minecraft.world.item.ItemStack(Blocks.STRIPPED_OAK_LOG);
        h.runAfterDelay(3, () -> exchange(h, sender, port, "belt-capture-read", "deployer_status", java.util.Map.of(), read -> {
            h.assertValueEqual(read.field("code"), text("ok"), "Read the current worksite through the serial port");
            var snapshot = (DataValue.RecordValue)read.field("state");
            exchange(h, sender, port, "belt-capture-start", "deployer_process", java.util.Map.of("token", snapshot.field("token")), accepted -> {
                h.assertValueEqual(accepted.field("code"), text("accepted"), "Reserve on the actual powered belt");
                var action = ((DataValue.RecordValue)accepted.field("state")).field("action");
                h.assertTrue(input.handleInsertion(log.copy(), Direction.UP, false).isEmpty(), "Insert one item through the native upstream pulley");
                h.startSequence()
                    .thenWaitUntil(() -> h.assertTrue(access.biocomputingPhase().equals("expanding")
                        && access.biocomputingProcess().equals("processing"), "Revoke only after the native arm captures the actual item"))
                    .thenExecute(() -> {
                        h.assertValueEqual(inventory.getTransportedItems().size(), 1, "One actual workpiece");
                        h.assertTrue(net.minecraft.world.item.ItemStack.matches(inventory.getTransportedItems().getFirst().stack, log),
                            "The captured log has not yet been converted");
                        actor.request(ComponentSettingsRequest.Action.REVOKE);
                        h.assertValueEqual(actor.client.code(), "configured", "Revoke via the actual device menu");
                        actor.grant();
                        h.assertValueEqual(actor.client.code(), "configured", "Restore the same source grant before the arm can reach the workpiece");
                        h.runAfterDelay(160, () -> {
                            h.assertTrue(access.biocomputingLocked() && deployer.getSpeed() != 0, "Neither native lock nor drive was altered");
                            h.assertValueEqual(deployer.getPlayer().getMainHandItem().getCount(), 2, "The revoked operation consumes no alloy");
                            h.assertValueEqual(inventory.getTransportedItems().size(), 1, "No output was created or item lost");
                            var carried = inventory.getTransportedItems().getFirst();
                            h.assertTrue(net.minecraft.world.item.ItemStack.matches(carried.stack, log), "The workpiece remains a raw log");
                            h.assertTrue(belt.getDirectionAwareBeltMovementSpeed() > 0 ? carried.beltPosition > belt.index + 1
                                : carried.beltPosition < belt.index, "The native conveyor releases the unprocessed workpiece downstream");
                            exchange(h, sender, port, "belt-capture-result", "deployer_status", java.util.Map.of(), result -> {
                                var done = (DataValue.RecordValue)result.field("state");
                                h.assertValueEqual(done.field("action"), action, "Reauthorization does not replace the old receipt");
                                h.assertValueEqual(done.field("process"), text("not_processed"), "Report that native conversion did not occur");
                                h.succeed();
                            });
                        });
                    });
            });
        }));
    }

    private static void verifyBeltCancellation(GameTestHelper h,
            com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour sender, ComponentSettingsPort port,
            MenuPlayer actor, com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity deployer,
            com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour input,
            com.simibubi.create.content.kinetics.belt.transport.BeltInventory inventory, DataValue.RecordValue previous) {
        exchange(h, sender, port, "belt-revoke-work", "deployer_process", java.util.Map.of("token", previous.field("token")), accepted -> {
            h.assertValueEqual(accepted.field("code"), text("accepted"), "Reserve on an empty worksite before revoking");
            var action = ((DataValue.RecordValue)accepted.field("state")).field("action");
            var access = (DeployerControl.Access)deployer;
            h.assertValueEqual(access.biocomputingProcess(), "waiting_for_material", "Revoke an actual pending action");
            actor.request(ComponentSettingsRequest.Action.REVOKE);
            h.assertValueEqual(actor.client.code(), "configured", "Revoke through the player's menu");
            var log = new net.minecraft.world.item.ItemStack(Blocks.STRIPPED_OAK_LOG);
            h.assertTrue(input.handleInsertion(log, Direction.UP, false).isEmpty(), "A fresh input arrives after revocation");
            h.runAfterDelay(160, () -> {
                h.assertValueEqual(deployer.getPlayer().getMainHandItem().getCount(), 1, "Revoked action cannot consume the remaining alloy");
                int casings = 0, logs = 0;
                for (var carried : inventory.getTransportedItems()) {
                    if (AllBlocks.ANDESITE_CASING.isIn(carried.stack)) casings += carried.stack.getCount();
                    else if (carried.stack.is(Blocks.STRIPPED_OAK_LOG.asItem())) logs += carried.stack.getCount();
                    else h.fail("Unexpected belt output after revocation: " + carried.stack);
                }
                h.assertValueEqual(casings, 1, "Only the original action produces a casing");
                h.assertValueEqual(logs, 2, "Fresh material passes without being consumed");
                actor.grant();
                h.assertValueEqual(actor.client.code(), "configured", "Explicit reauthorization only");
                // Let the serial scheduler observe the reopened port before queuing a new frame.
                h.runAfterDelay(3, () -> exchange(h, sender, port, "belt-revoke-result", "deployer_status", java.util.Map.of(), reply -> {
                    var state = (DataValue.RecordValue)reply.field("state");
                    h.assertValueEqual(state.field("process"), text("interrupted"), "No automatic resumption under the new grant");
                    h.assertValueEqual(state.field("action"), action, "Interruption belongs to the revoked reservation");
                    h.succeed();
                }));
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 2300)
    public static void armMovesNativeStockOnlyToItsSelectedOutputAndWaitsWhenFull(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 3, 3));
        var output = origin.east(3);
        var target = output.east();
        var input = target.north(2);
        var other = target.south(2);
        var destination = target.east(2);
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        var source = depot(level, input);
        var alternative = depot(level, other);
        // The funnel's real backing inventory can accept only part of what the arm already holds.
        var chestPos = destination.east();
        level.setBlockAndUpdate(chestPos, Blocks.BARREL.defaultBlockState());
        var chosen = (net.minecraft.world.level.block.entity.BarrelBlockEntity)level.getBlockEntity(chestPos);
        level.setBlockAndUpdate(destination, AllBlocks.BRASS_FUNNEL.getDefaultState()
            .setValue(com.simibubi.create.content.logistics.funnel.FunnelBlock.FACING, Direction.WEST)
            .setValue(com.simibubi.create.content.logistics.funnel.FunnelBlock.EXTRACTING, false));
        level.setBlockAndUpdate(target, AllBlocks.MECHANICAL_ARM.getDefaultState());
        var cog = target.south();
        level.setBlockAndUpdate(cog, AllBlocks.COGWHEEL.getDefaultState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.Y));
        level.setBlockAndUpdate(cog.below(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(com.simibubi.create.content.kinetics.motor.CreativeMotorBlock.FACING, Direction.UP));
        var motor = (com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity)level.getBlockEntity(cog.below());
        motor.initialize();
        motor.generatedSpeed.setValue(16);
        motor.updateGeneratedRotation();
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var block = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = block.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        var pickup = armPoint(level, input);
        pickup.cycleMode();
        new com.simibubi.create.content.kinetics.mechanicalArm.ArmPlacementPacket(
            java.util.List.of(pickup, armPoint(level, other), armPoint(level, destination)), target).handle(actor);
        var gold = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT, 3);
        var obstruction = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64);
        h.runAfterDelay(12, () -> {
            block.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Grant the real connected source");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "read-route", "arm_prepare", java.util.Map.of(), status -> {
                h.assertValueEqual(status.field("code"), text("ok"), "The bounded preparation does not require a full arm snapshot");
                var state = (DataValue.RecordValue)status.field("state");
                h.assertValueEqual(state.fields().keySet(), java.util.Set.of("token"), "The preparation reply carries only the one-shot token");
                var request = java.util.Map.of("token", state.field("token"),
                    "input", (DataValue)new DataValue.IntegerValue(0), "output", new DataValue.IntegerValue(1));
                exchange(h, sender, port, "move", "arm_move", request, accepted -> {
                    h.assertValueEqual(accepted.field("code"), text("accepted"), "Admit the chosen native route");
                    source.setHeldItem(gold);
                    var arm = (com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity)level.getBlockEntity(target);
                    var observed = (ArmControl.Access)arm;
                    h.startSequence().thenWaitUntil(() -> h.assertValueEqual(observed.biocomputingHeldCopy().getCount(), 3, "Native pickup completes"))
                        .thenExecute(() -> {
                            for (int i = 0; i < chosen.getContainerSize(); i++) chosen.setItem(i, obstruction.copy());
                        })
                        .thenExecuteAfter(140, () -> {
                            h.assertTrue(source.getHeldItem().isEmpty(), "Native source was consumed exactly once");
                            h.assertValueEqual(observed.biocomputingHeldCopy().getCount(), 3, "Full destination retains carried items");
                            h.assertTrue(alternative.getHeldItem().isEmpty(), "Occupied target never redirects to a configured alternative");
                            chosen.setItem(0, gold.copyWithCount(63));
                        })
                        .thenWaitUntil(() -> h.assertValueEqual(chosen.getItem(0).getCount(), 64, "Native funnel deposits just one item"))
                        .thenExecuteAfter(140, () -> {
                            h.assertValueEqual(observed.biocomputingRouteState(), "waiting_for_output", "A partial deposit does not finish the action");
                            h.assertValueEqual(observed.biocomputingHeldCopy().getCount(), 2, "The arm keeps exactly the remainder");
                            h.assertTrue(source.getHeldItem().isEmpty() && alternative.getHeldItem().isEmpty(),
                                "Neither a second pickup nor a fallback deposit occurs");
                            exchange(h, sender, port, "blocked-redirect", "arm_prepare", java.util.Map.of(), busy -> {
                                h.assertValueEqual(busy.field("code"), text("busy"), "A second route is refused before returning a token");
                                h.assertValueEqual(busy.field("state"), DataValue.NullValue.INSTANCE, "Busy never carries an admission token");
                                h.assertValueEqual(observed.biocomputingAction().toString(),
                                    ((DataValue.TextValue)((DataValue.RecordValue)accepted.field("state")).field("action")).value(),
                                    "The original action is not replaced");
                                chosen.removeItemNoUpdate(1);
                                chosen.setChanged();
                            });
                        })
                        .thenWaitUntil(() -> h.assertValueEqual(chosen.getItem(1).getCount(), 2, "Remaining items go through the same funnel"))
                        .thenExecute(() -> {
                            h.assertTrue(source.getHeldItem().isEmpty() && observed.biocomputingHeldCopy().isEmpty()
                                && alternative.getHeldItem().isEmpty(), "Nothing duplicated or rerouted");
                            h.assertTrue(chosen.getItem(1).is(net.minecraft.world.item.Items.GOLD_INGOT), "Actual items arrive");
                            chosen.clearContent();
                            exchange(h, sender, port, "read-after-move", "arm_status", java.util.Map.of(), done -> {
                                var after = (DataValue.RecordValue)done.field("state");
                                h.assertValueEqual(after.field("transfer"), text("transfer_completed"), "Completion is separate from acceptance");
                                h.assertValueEqual(after.field("action"), ((DataValue.RecordValue)accepted.field("state")).field("action"), "Same action");
                                var next = java.util.Map.of("token", after.field("token"), "input", (DataValue)new DataValue.IntegerValue(0),
                                    "output", new DataValue.IntegerValue(1));
                                exchange(h, sender, port, "move-before-reload", "arm_move", next, second -> {
                                    h.assertValueEqual(second.field("code"), text("accepted"), "New token admits another transfer");
                                    source.setHeldItem(gold.copyWithCount(2));
                                    h.startSequence().thenWaitUntil(() -> h.assertValueEqual(observed.biocomputingHeldCopy().getCount(), 2, "Second pickup"))
                                        .thenExecute(() -> {
                                            var saved = arm.saveWithFullMetadata(level.registryAccess());
                                            var stateAtSave = arm.getBlockState();
                                            level.removeBlockEntity(target);
                                            var rebuilt = (com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity)
                                                net.minecraft.world.level.block.entity.BlockEntity.loadStatic(target, stateAtSave, saved, level.registryAccess());
                                            level.setBlockEntity(rebuilt);
                                            var restored = (ArmControl.Access)rebuilt;
                                            h.runAfterDelay(180, () -> {
                                                h.assertValueEqual(restored.biocomputingRouteState(), "recovery_required", "Held items require recovery after reload");
                                                h.assertValueEqual(restored.biocomputingHeldCopy().getCount(), 2, "Native held stack survives");
                                                h.assertTrue(source.getHeldItem().isEmpty() && chosen.isEmpty() && alternative.getHeldItem().isEmpty(),
                                                    "Recovery neither repeats pickup nor deposits at any output");
                                                level.setBlockAndUpdate(cog, Blocks.AIR.defaultBlockState());
                                                h.runAfterDelay(40, () -> {
                                                    h.assertTrue(rebuilt.getSpeed() == 0, "Physically disconnect the recovered arm from its power source");
                                                    h.assertValueEqual(ArmControl.summary(rebuilt).field("reason"), text("recovery_required"),
                                                        "The player-facing reason must show manual recovery even without power");
                                                    h.assertValueEqual(restored.biocomputingHeldCopy().getCount(), 2,
                                                        "Power loss does not remove the saved stack");
                                                    h.succeed();
                                                });
                                            });
                                        });
                                });
                            });
                        });
                });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1400)
    public static void armPrepareCannotAdmitASamePositionDeviceReplacement(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 3, 3));
        var adapterPos = origin.east(3);
        var armPos = adapterPos.east();
        var inputPos = armPos.north(2);
        var outputPos = armPos.south(2);
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        var pickup = depot(level, inputPos);
        var observedOutput = depot(level, outputPos);
        level.setBlockAndUpdate(armPos, AllBlocks.MECHANICAL_ARM.getDefaultState());
        var arm = (com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity)level.getBlockEntity(armPos);
        var access = (ArmControl.Access)arm;
        var cog = armPos.south();
        level.setBlockAndUpdate(cog, AllBlocks.COGWHEEL.getDefaultState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.Y));
        level.setBlockAndUpdate(cog.below(), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
        var drive = (com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity)level.getBlockEntity(cog.below());
        drive.initialize();
        drive.generatedSpeed.setValue(16);
        drive.updateGeneratedRotation();
        level.setBlockAndUpdate(adapterPos, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(adapterPos);
        var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(adapterPos);
        actor.moveTo(center);
        var configuredInput = armPoint(level, inputPos);
        configuredInput.cycleMode();
        new com.simibubi.create.content.kinetics.mechanicalArm.ArmPlacementPacket(
            java.util.List.of(configuredInput, armPoint(level, outputPos)), armPos).handle(actor);
        h.runAfterDelay(12, () -> {
            adapter.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, adapterPos, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize the adjacent real arm");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "arm-observe-original-depot", "arm_prepare", java.util.Map.of(), read -> {
                h.assertValueEqual(read.field("code"), text("ok"), "Observe the ready arm before replacing its empty depot");
                var state = (DataValue.RecordValue)read.field("state");
                var old = java.util.Map.of("token", state.field("token"), "input", (DataValue)ComponentSettingsProtocol.number(0),
                    "output", ComponentSettingsProtocol.number(0));
                var point = access.biocomputingOutputs().getFirst();
                level.removeBlock(outputPos, false);
                var replacement = depot(level, outputPos);
                h.assertTrue(replacement != observedOutput && observedOutput.isRemoved(), "Replace a real depot, not just its inventory");
                exchange(h, sender, port, "arm-try-old-depot-token", "arm_move", old, refused -> {
                    h.assertTrue(access.biocomputingOutputs().getFirst() == point, "Native placement still names the same point and index");
                    h.assertValueEqual(refused.field("code"), text("point_changed"), "The old observation must not authorize the new depot");
                    h.assertTrue(access.biocomputingAction() == null && access.biocomputingHeldCopy().isEmpty(),
                        "Refusal creates no action and moves no material");
                    exchange(h, sender, port, "arm-reread-replacement", "arm_status", java.util.Map.of(), reread -> {
                        h.assertValueEqual(reread.field("code"), text("ok"), "Read the replacement as a new observation");
                        var fresh = (DataValue.RecordValue)reread.field("state");
                        var next = java.util.Map.of("token", fresh.field("token"), "input", (DataValue)ComponentSettingsProtocol.number(0),
                            "output", ComponentSettingsProtocol.number(0));
                        exchange(h, sender, port, "arm-move-to-confirmed-replacement", "arm_move", next, accepted -> {
                            h.assertValueEqual(accepted.field("code"), text("accepted"), "A fresh observation can admit the replacement");
                            pickup.setHeldItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT));
                            h.startSequence().thenWaitUntil(() -> h.assertTrue(replacement.getHeldItem().is(net.minecraft.world.item.Items.GOLD_INGOT),
                                "The one original item reaches the newly observed depot")).thenExecute(() -> {
                                    h.assertTrue(pickup.getHeldItem().isEmpty() && access.biocomputingHeldCopy().isEmpty(),
                                        "The source and arm retain no copies");
                                    h.assertValueEqual(replacement.getHeldItem().getCount(), 1, "Exactly one item is transferred");
                                }).thenSucceed();
                        });
                    });
                });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1100)
    public static void armCompletesWithSameTickControllerAuthorization(GameTestHelper h) {
        verifyArmAtControllerBarrier(h, false);
    }

    @GameTest(template = "empty", timeoutTicks = 1100)
    public static void queuedArmCannotUsePermissionRevokedBeforeItsPostTick(GameTestHelper h) {
        verifyArmAtControllerBarrier(h, true);
    }

    private static void verifyArmAtControllerBarrier(GameTestHelper h, boolean revokeAtBarrier) {
        var level = h.getLevel();
        var armPos = h.absolutePos(new BlockPos(2, 3, 3));
        var inputPos = armPos.north(2);
        var outputPos = armPos.south(2);
        var input = depot(level, inputPos);
        var output = depot(level, outputPos);
        level.setBlockAndUpdate(armPos, AllBlocks.MECHANICAL_ARM.getDefaultState());
        var arm = (com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity)level.getBlockEntity(armPos);
        var managed = (ArmControl.Access)arm;
        var cog = armPos.south();
        level.setBlockAndUpdate(cog, AllBlocks.COGWHEEL.getDefaultState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.Y));
        level.setBlockAndUpdate(cog.below(), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
        var drive = (com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity)level.getBlockEntity(cog.below());
        drive.initialize();
        drive.generatedSpeed.setValue(16);
        drive.updateGeneratedRotation();
        var actor = new MenuPlayer(level);
        actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(armPos));
        var pickup = armPoint(level, inputPos);
        pickup.cycleMode();
        new com.simibubi.create.content.kinetics.mechanicalArm.ArmPlacementPacket(
            java.util.List.of(pickup, armPoint(level, outputPos)), armPos).handle(actor);
        // Register the actual controller after the arm so its block entity ticks later, as in the player save.
        var controllerPos = armPos.east(2);
        level.setBlockAndUpdate(controllerPos, com.nobodiiiii.createbiotech.registry.CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState());
        var controller = (com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity)level.getBlockEntity(controllerPos);
        h.runAfterDelay(12, () -> {
            h.assertTrue(controller.physicalIoTickAvailable(), "The actual controller has completed the admission tick");
            var status = ArmControl.read(arm, actor, ArmControl.availability(arm, managed));
            var plan = ArmControl.plan(arm, actor, status, new DataValue.RecordValue(java.util.Map.of(
                "input", new DataValue.IntegerValue(0), "output", new DataValue.IntegerValue(0))));
            h.assertValueEqual(plan.code(), "ok", "Normal point validation has admitted the two native depots");
            var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
            h.assertTrue(managed.biocomputingAdmit(plan.route(), () -> allowed.get() && controller.physicalIoTickAvailable()),
                "Use the same current-world-tick authority prerequisite as a Cluster serial grant");
            if (revokeAtBarrier) {
                input.setHeldItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT));
                var admittedAt = level.getGameTime();
                var stateAtBarrier = new java.util.concurrent.atomic.AtomicReference<String>();
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    net.neoforged.bus.api.EventPriority.NORMAL, net.neoforged.neoforge.event.tick.LevelTickEvent.Post.class,
                    new java.util.function.Consumer<net.neoforged.neoforge.event.tick.LevelTickEvent.Post>() {
                        @Override public void accept(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
                            if (event.getLevel() != level || level.getGameTime() <= admittedAt) return;
                            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(this);
                            stateAtBarrier.set(managed.biocomputingRouteState());
                            allowed.set(false); // after native block ticks, before managed work at LOW
                        }
                    });
                h.startSequence().thenWaitUntil(() ->
                    h.assertValueEqual(managed.biocomputingRouteState(), "access_denied", "The pending action rechecks authorization"))
                    .thenExecute(() -> {
                        h.assertValueEqual(stateAtBarrier.get(), "routing", "No early pickup or retirement before the barrier");
                        h.assertValueEqual(managed.biocomputingAction(), plan.route().id(), "Denied the same action");
                        h.assertTrue(!managed.biocomputingRouting(), "Denied route is no longer active");
                        h.assertTrue(input.getHeldItem().is(net.minecraft.world.item.Items.GOLD_INGOT)
                            && input.getHeldItem().getCount() == 1, "The original item remains at the source");
                        h.assertTrue(output.getHeldItem().isEmpty() && managed.biocomputingHeldCopy().isEmpty(),
                            "No extraction, delivery or copy after revocation");
                    }).thenSucceed();
                return;
            }
            h.startSequence().thenWaitUntil(() -> h.assertTrue(!managed.biocomputingRouteState().equals("routing"),
                "Wait for the native arm to first evaluate its route")).thenExecute(() -> {
                h.assertValueEqual(managed.biocomputingRouteState(), "source_empty",
                    "An accepted route must wait for material, not fail just because the controller ticks later");
                input.setHeldItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT));
            }).thenWaitUntil(() -> h.assertValueEqual(managed.biocomputingRouteState(), "transfer_completed",
                "The original route must report completion, not just leave an item at the same destination via ordinary Create"))
                .thenExecute(() -> {
                    h.assertValueEqual(managed.biocomputingAction(), plan.route().id(), "The same action has completed");
                    h.assertTrue(output.getHeldItem().is(net.minecraft.world.item.Items.GOLD_INGOT)
                        && output.getHeldItem().getCount() == 1, "Exactly one real item reaches the selected depot");
                    h.assertTrue(input.getHeldItem().isEmpty() && managed.biocomputingHeldCopy().isEmpty(),
                        "No copy remains on the source or the arm");
                }).thenSucceed();
        });
    }

    private static com.simibubi.create.content.logistics.depot.DepotBlockEntity depot(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        level.setBlockAndUpdate(pos, AllBlocks.DEPOT.getDefaultState());
        return (com.simibubi.create.content.logistics.depot.DepotBlockEntity)level.getBlockEntity(pos);
    }

    private static com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint armPoint(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType.getPrimaryType(level, pos, state).createPoint(level, pos, state);
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void configuresOnlyAnIdleSingleBrassTunnelAndPreservesItsRealStock(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 4, 5));
        var output = origin.east(3);
        var target = output.east();
        var beltPos = target.below();
        var peer = target.north();
        var depotPos = beltPos.south();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        for (var center : java.util.List.of(beltPos, peer.below())) {
            var shaft = AllBlocks.SHAFT.getDefaultState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.Z);
            level.setBlockAndUpdate(center.west(3), shaft);
            level.setBlockAndUpdate(center.east(3), shaft);
            BeltConnectorItem.createBelts(level, center.west(3), center.east(3));
        }
        level.setBlockAndUpdate(beltPos.west(4), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(beltPos.east(4), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(depotPos, AllBlocks.DEPOT.getDefaultState());
        var base = AllBlocks.BRASS_TUNNEL.getDefaultState().setValue(BeltTunnelBlock.HORIZONTAL_AXIS, Direction.Axis.X);
        level.setBlockAndUpdate(target, base.setValue(BeltTunnelBlock.SHAPE, BeltTunnelBlock.Shape.T_RIGHT));
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var config = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = config.getBehaviour(ComponentSettingsPort.TYPE);
        var tunnel = (BrassTunnelBlockEntity)level.getBlockEntity(target);
        var belt = (BeltBlockEntity)level.getBlockEntity(beltPos);
        var depot = (DepotBlockEntity)level.getBlockEntity(depotPos);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.runAfterDelay(12, () -> {
            h.assertTrue(tunnel.hasDistributionBehaviour() && !tunnel.isConnected(true) && !tunnel.isConnected(false),
                "Fixture uses a native T-junction with two usable outputs, not a straight tunnel");
            config.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize the actual branching tunnel");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "tunnel-first-status", "status", java.util.Map.of(), queried -> {
                h.assertValueEqual(queried.field("code"), text("ok"), "Query the native sorter over the authorized wire");
                var state = (DataValue.RecordValue)queried.field("state");
                h.assertValueEqual(state.field("field"), text("distribution_mode"), "Describe the native sorter");
                h.assertValueEqual(state.field("value"), text("split"), "Read the native default");
                level.setBlockAndUpdate(peer, base);
                var other = (BrassTunnelBlockEntity)level.getBlockEntity(peer);
                h.assertTrue(tunnel.isConnected(true) || tunnel.isConnected(false), "The native group forms after the query");
                var peerMode = BlockEntityBehaviour.get(other, ScrollValueBehaviour.TYPE);
                var initialPeerMode = peerMode.getValue();
                exchange(h, sender, port, "tunnel-now-grouped", "set", java.util.Map.of(
                    "field", (DataValue)text("distribution_mode"), "token", state.field("token"), "value", text("round_robin")), grouped -> {
                        h.assertValueEqual(grouped.field("code"), text("busy"), "Single-tunnel authorization never propagates to a peer");
                        h.assertValueEqual(peerMode.getValue(), initialPeerMode, "Other tunnel's native mode remains intact");
                        h.assertValueEqual(BlockEntityBehaviour.get(tunnel, ScrollValueBehaviour.TYPE).getValue(),
                            SelectionMode.SPLIT.ordinal(), "Set did not sneak through the group");
                        level.setBlockAndUpdate(peer, Blocks.AIR.defaultBlockState());
                        exchange(h, sender, port, "tunnel-now-single", "status", java.util.Map.of(), idle -> {
                            var unconnected = (DataValue.RecordValue)idle.field("state");
                            exchange(h, sender, port, "tunnel-round-robin", "set", java.util.Map.of(
                                "field", (DataValue)text("distribution_mode"), "token", unconnected.field("token"), "value", text("round_robin")), applied -> {
                                    h.assertValueEqual(applied.field("code"), text("applied"), "Set the native option on just this worksite");
                                    h.assertValueEqual(BlockEntityBehaviour.get(tunnel, ScrollValueBehaviour.TYPE).getValue(),
                                        SelectionMode.ROUND_ROBIN.ordinal(), "Native sorter now uses round robin");
                                    var intake = level.getCapability(Capabilities.ItemHandler.BLOCK, target, Direction.UP);
                                    var supplied = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT, 4);
                                    h.assertTrue(intake != null && intake.insertItem(0, supplied.copy(), false).isEmpty(),
                                        "The native capability accepts exactly the physical fixture stack");
                                    h.assertTrue(belt.getSpeed() == 0 && !tunnel.canTakeItems(), "An unpowered belt leaves its truly staged stock in place");
                                    exchange(h, sender, port, "tunnel-held", "status", java.util.Map.of(), held -> {
                                        var heldState = (DataValue.RecordValue)held.field("state");
                                        h.assertValueEqual(heldState.field("busy"), new DataValue.BooleanValue(true), "Report the pending physical batch");
                                        exchange(h, sender, port, "tunnel-held-write", "set", java.util.Map.of(
                                            "field", (DataValue)text("distribution_mode"), "token", heldState.field("token"), "value", text("split")), rejected -> {
                                                h.assertValueEqual(rejected.field("code"), text("busy"), "Do not switch modes during a pending transfer");
                                                h.assertTrue(net.minecraft.world.item.ItemStack.matches(tunnel.getStackToDistribute(), supplied),
                                                    "Refusal does not consume, split or reroute the real batch");
                                                level.setBlockAndUpdate(beltPos.west(3).south(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                                                    .setValue(CreativeMotorBlock.FACING, Direction.NORTH));
                                                h.startSequence().thenWaitUntil(() -> {
                                                    h.assertTrue(belt.getSpeed() != 0 && tunnel.canTakeItems(), "Native motor resumes the real belt");
                                                    int onBelt = belt.getControllerBE().getInventory().getTransportedItems().stream()
                                                        .filter(carried -> net.minecraft.world.item.ItemStack.isSameItemSameComponents(carried.stack, supplied))
                                                        .mapToInt(carried -> carried.stack.getCount()).sum();
                                                    int atDepot = depot.getHeldItem().getCount();
                                                    // Create may eject the stack into the world at a valid open outlet.
                                                    int ejected = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                                                        new net.minecraft.world.phys.AABB(beltPos).inflate(8), item -> net.minecraft.world.item.ItemStack
                                                            .isSameItemSameComponents(item.getItem(), supplied))
                                                        .stream().mapToInt(item -> item.getItem().getCount()).sum();
                                                    h.assertTrue(onBelt + atDepot + ejected == 4
                                                        && (onBelt == 4 || atDepot == 4 || ejected == 4),
                                                        "Round robin preserves the batch at one native output; belt=" + onBelt
                                                            + ", depot=" + atDepot + ", ejected=" + ejected);
                                                    h.assertValueEqual(BlockEntityBehaviour.get(tunnel, ScrollValueBehaviour.TYPE).getValue(),
                                                        SelectionMode.ROUND_ROBIN.ordinal(), "The refused mode was never queued");
                                                }).thenSucceed();
                                            });
                                    });
                                });
                        });
                    });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void configuresTheNativeWhistleOctaveWithoutPoweringItOrChangingThePipeLength(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 4, 3));
        var output = origin.east(3);
        var target = output.east();
        var ext = target.above();
        var source = target.east();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(target.below(), AllBlocks.FLUID_TANK.getDefaultState());
        level.setBlockAndUpdate(target, AllBlocks.STEAM_WHISTLE.getDefaultState()
            .setValue(WhistleBlock.SIZE, WhistleSize.SMALL).setValue(WhistleBlock.WALL, false));
        level.setBlockAndUpdate(ext, AllBlocks.STEAM_WHISTLE_EXTENSION.getDefaultState()
            .setValue(WhistleExtenderBlock.SHAPE, WhistleExtenderShape.SINGLE).setValue(WhistleExtenderBlock.SIZE, WhistleSize.SMALL));
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var config = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = config.getBehaviour(ComponentSettingsPort.TYPE);
        var whistle = (WhistleBlockEntity)level.getBlockEntity(target);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.runAfterDelay(12, () -> {
            int before = whistle.getPitchId();
            h.assertValueEqual(before % 100, 1, "The native whistle reads the real half-length extension");
            config.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize the actual steam whistle");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "whistle-status", "status", java.util.Map.of(), queried -> {
                h.assertValueEqual(queried.field("code"), text("ok"), "Read the native whistle octave");
                var state = (DataValue.RecordValue)queried.field("state");
                h.assertValueEqual(state.field("field"), text("whistle_size"), "Report the native setting");
                h.assertValueEqual(state.field("value"), text("small"), "Read the current native size");
                level.setBlockAndUpdate(source, Blocks.REDSTONE_BLOCK.defaultBlockState());
                h.assertTrue(level.getBlockState(target).getValue(WhistleBlock.POWERED), "The signal comes from actual redstone");
                exchange(h, sender, port, "whistle-powered-set", "set", java.util.Map.of(
                    "field", (DataValue)text("whistle_size"), "token", state.field("token"), "value", text("large")), denied -> {
                        h.assertValueEqual(denied.field("code"), text("busy"), "An active redstone signal fences a previously observed setting");
                        h.assertTrue(whistle.getPitchId() == before && level.getBlockState(target).getValue(WhistleBlock.POWERED),
                            "The refusal leaves the octave and actual signal unchanged");
                        level.setBlockAndUpdate(source, Blocks.AIR.defaultBlockState());
                        exchange(h, sender, port, "whistle-idle", "status", java.util.Map.of(), idle -> {
                            var next = (DataValue.RecordValue)idle.field("state");
                            h.assertValueEqual(next.field("value"), text("small"), "Clearing the signal does not apply the old request");
                            exchange(h, sender, port, "whistle-idle-set", "set", java.util.Map.of(
                                "field", (DataValue)text("whistle_size"), "token", next.field("token"), "value", text("large")), applied -> {
                                    h.assertValueEqual(applied.field("code"), text("applied"), "Change the octave, not the pipe length");
                                    h.assertTrue(level.getBlockEntity(target) == whistle && !level.getBlockState(target).getValue(WhistleBlock.POWERED),
                                        "Keep the same device idle");
                                    h.assertValueEqual(level.getBlockState(target).getValue(WhistleBlock.SIZE), WhistleSize.LARGE, "Use the native size property");
                                    var pipe = level.getBlockState(ext);
                                    h.assertTrue(AllBlocks.STEAM_WHISTLE_EXTENSION.has(pipe) && pipe.getValue(WhistleExtenderBlock.SIZE) == WhistleSize.LARGE
                                        && pipe.getValue(WhistleExtenderBlock.SHAPE) == WhistleExtenderShape.SINGLE && level.getBlockState(ext.above()).isAir(),
                                        "Native shape updates resize the same existing pipe without creating, removing or extending it");
                                    h.assertValueEqual(whistle.getPitchId(), before + 200, "Native pitch follows the new octave with identical tuning");
                                    h.succeed();
                                });
                        });
                    });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void configuresNativeSteamEngineRotationWithoutBypassingBoiler(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 6, 4));
        var output = origin.east(3);
        var target = output.east();
        var tankPos = target.north().below(2);
        var heatPos = tankPos.below();
        var shaftPos = target.south(2);
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(heatPos, AllBlocks.BLAZE_BURNER.getDefaultState().setValue(
            com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HEAT_LEVEL,
            com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel.SMOULDERING));
        for (int y = 0; y < 4; y++) level.setBlockAndUpdate(tankPos.above(y), AllBlocks.FLUID_TANK.getDefaultState());
        var fuel = com.simibubi.create.content.processing.burner.BlazeBurnerBlock.tryInsert(level.getBlockState(heatPos), level,
            heatPos, com.simibubi.create.AllItems.CREATIVE_BLAZE_CAKE.asStack(), true, false, false);
        h.assertTrue(fuel.getResult().consumesAction(), "Supply heat via the native burner interaction, not by setting a transient block state");
        var shaft = AllBlocks.SHAFT.getDefaultState().setValue(
            net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.X);
        level.setBlockAndUpdate(shaftPos, shaft);
        level.setBlockAndUpdate(shaftPos.east(), shaft);
        var engineState = AllBlocks.STEAM_ENGINE.getDefaultState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.ATTACH_FACE,
                net.minecraft.world.level.block.state.properties.AttachFace.WALL)
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
        level.setBlockAndUpdate(target, engineState);
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var config = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = config.getBehaviour(ComponentSettingsPort.TYPE);
        var tankPart = (com.simibubi.create.content.fluids.tank.FluidTankBlockEntity)level.getBlockEntity(tankPos);
        var powered = (com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity)level.getBlockEntity(shaftPos);
        var downstream = (com.simibubi.create.content.kinetics.base.KineticBlockEntity)level.getBlockEntity(shaftPos.east());
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.startSequence().thenWaitUntil(() -> {
            var tank = tankPart.getControllerBE();
            h.assertTrue(tank != null && tank.getTotalTankSize() == 4 && tank.boiler.isActive() && tank.boiler.activeHeat > 0
                && powered.initialTicks == 0, "Native boiler and linked shaft are ready");
        }).thenExecute(() -> {
            h.assertTrue(downstream.getSpeed() == 0, "A hot boiler without water cannot drive the real axle");
            config.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize the actual steam engine");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "steam-dry-state", "status", java.util.Map.of(), queried -> {
                h.assertValueEqual(queried.field("code"), text("ok"), "Read the native rotation option");
                var state = (DataValue.RecordValue)queried.field("state");
                h.assertValueEqual(state.field("field"), text("rotation_direction"), "Describe the native rotation field");
                h.assertValueEqual(state.field("value"), text("clockwise"), "Read the default direction");
                exchange(h, sender, port, "steam-dry-change", "set", java.util.Map.of("token", state.field("token"),
                    "field", (DataValue)text("rotation_direction"), "value", text("counter_clockwise")), dry -> {
                        h.assertValueEqual(dry.field("code"), text("applied"), "A dry engine may store the direction without steam");
                        h.assertTrue(downstream.getSpeed() == 0, "Selecting a direction does not generate power");
                        var tank = tankPart.getControllerBE();
                        supplyBoilerWater(h, tank, () -> level.getBlockState(heatPos).is(AllBlocks.BLAZE_BURNER.get()));
                        h.startSequence().thenWaitUntil(() -> h.assertTrue(powered.isPoweredBy(target)
                            && tank.boiler.waterSupply >= 10 && downstream.getSpeed() != 0, "Real water and heat now drive the axle"))
                            .thenExecute(() -> {
                                float before = downstream.getSpeed();
                                var efficiency = powered.engineEfficiency;
                                exchange(h, sender, port, "steam-running-state", "status", java.util.Map.of(), running -> {
                                    var next = (DataValue.RecordValue)running.field("state");
                                    exchange(h, sender, port, "steam-running-reverse", "set", java.util.Map.of(
                                        "field", (DataValue)text("rotation_direction"), "token", next.field("token"),
                                        "value", text("clockwise")), changed -> {
                                            h.assertValueEqual(changed.field("code"), text("applied"), "Change the native setting, not actual speed");
                                            h.startSequence().thenWaitUntil(() -> h.assertTrue(downstream.getSpeed() == -before,
                                                "The engine's own tick reverses its axle without changing magnitude"))
                                                .thenExecute(() -> {
                                                    h.assertTrue(level.getBlockState(target) == engineState && powered.isPoweredBy(target)
                                                        && powered.engineEfficiency == efficiency && tank.boiler.activeHeat > 0
                                                        && tank.boiler.waterSupply >= 10, "The same real boiler still supplies the same native engine");
                                                    h.succeed();
                                                });
                                        });
                                });
                            });
                    });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void configuresOnlyOneChassisAndChangesItsNativeAttachmentRange(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 3, 3));
        var output = origin.east(3);
        var target = output.east();
        var adjacent = target.above();
        var chassisBlock = AllBlocks.LINEAR_CHASSIS.get();
        var base = chassisBlock.defaultBlockState().setValue(
            net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.X);
        var glued = base.setValue(chassisBlock.getGlueableSide(base, Direction.EAST), true);
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(adjacent, base);
        level.setBlockAndUpdate(target, glued);
        level.setBlockAndUpdate(target.east(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(target.east(2), Blocks.COPPER_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var config = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = config.getBehaviour(ComponentSettingsPort.TYPE);
        var chassis = (com.simibubi.create.content.contraptions.chassis.ChassisBlockEntity)level.getBlockEntity(target);
        var neighbour = (com.simibubi.create.content.contraptions.chassis.ChassisBlockEntity)level.getBlockEntity(adjacent);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.runAfterDelay(12, () -> {
            h.assertTrue(chassis.collectChassisGroup().contains(neighbour), "The neighbouring chassis participates in native bulk edits");
            h.assertTrue(chassis.getIncludedBlockPositions(Direction.EAST, false).contains(target.east(2)),
                "The default native range includes the second payload");
            var oldNeighbour = neighbour.getRange();
            var originalState = level.getBlockState(target);
            config.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize the physical chassis, not the group");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "chassis-query", "status", java.util.Map.of(), queried -> {
                h.assertValueEqual(queried.field("code"), text("ok"), "Query the installed range");
                var state = (DataValue.RecordValue)queried.field("state");
                h.assertValueEqual(state.field("field"), text("attachment_range"), "Describe the native chassis range");
                var format = (DataValue.RecordValue)state.field("format");
                var maximum = (DataValue.IntegerValue)format.field("max");
                h.assertValueEqual(state.field("value"), new DataValue.IntegerValue(chassis.getRange()), "Report actual native range");
                var arguments = java.util.Map.of("field", (DataValue)text("attachment_range"), "token", state.field("token"));
                var invalid = new java.util.HashMap<>(arguments);
                invalid.put("value", new DataValue.IntegerValue(maximum.value() + 1));
                exchange(h, sender, port, "chassis-over-max", "set", invalid, rejected -> {
                    h.assertValueEqual(rejected.field("code"), text("value_out_of_range"), "Do not clamp beyond the native slider");
                    h.assertTrue(chassis.getIncludedBlockPositions(Direction.EAST, false).contains(target.east(2)),
                        "Rejecting the update preserves the original payload range");
                    exchange(h, sender, port, "chassis-refresh", "status", java.util.Map.of(), refreshed -> {
                        var next = (DataValue.RecordValue)refreshed.field("state");
                        exchange(h, sender, port, "chassis-single-write", "set", java.util.Map.of(
                            "field", (DataValue)text("attachment_range"), "token", next.field("token"), "value", new DataValue.IntegerValue(1)), applied -> {
                                h.assertValueEqual(applied.field("code"), text("applied"), "Apply to the authorized chassis only");
                                h.assertValueEqual(chassis.getRange(), 1, "The original Create control was updated");
                                h.assertValueEqual(neighbour.getRange(), oldNeighbour, "Do not apply a native bulk edit");
                                var included = chassis.getIncludedBlockPositions(Direction.EAST, false);
                                h.assertTrue(included.contains(target.east()) && !included.contains(target.east(2)),
                                    "Native attachment selection now stops after the first block");
                                h.assertTrue(level.getBlockState(target) == originalState
                                    && level.getBlockState(target.east()).is(Blocks.STONE)
                                    && level.getBlockState(target.east(2)).is(Blocks.COPPER_BLOCK),
                                    "Setting the range does not change the glue face or move any blocks");
                                h.succeed();
                            });
                    });
                });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void configuresOnlyOneRollerWithoutPropagatingToItsNeighbours(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 3, 3));
        var output = origin.east(3);
        var target = output.east();
        var neighbour = target.north();
        var ground = target.below();
        var rollerState = AllBlocks.MECHANICAL_ROLLER.getDefaultState().setValue(
            com.simibubi.create.content.contraptions.actors.roller.RollerBlock.FACING, Direction.EAST);
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(ground, Blocks.COPPER_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(target, rollerState);
        var roller = (com.simibubi.create.content.contraptions.actors.roller.RollerBlockEntity)level.getBlockEntity(target);
        var material = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE);
        h.assertTrue(roller.filtering.setFilter(material), "Fixture uses a real Create paving material filter");
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var config = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = config.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.runAfterDelay(12, () -> {
            config.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize only the exact physical roller");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "roller-query", "status", java.util.Map.of(), queried -> {
                h.assertValueEqual(queried.field("code"), text("ok"), "Query the roller over finite serial");
                var state = (DataValue.RecordValue)queried.field("state");
                h.assertValueEqual(state.field("field"), text("roller_mode"), "Describe the native roller mode");
                h.assertValueEqual(state.field("value"), text("tunnel_pave"), "Read the native default");
                h.assertValueEqual(state.field("busy"), new DataValue.BooleanValue(false), "The original roller is isolated");
                // Couple a real second roller after the authorized observation, before applying the request.
                level.setBlockAndUpdate(neighbour, rollerState);
                var adjacent = (com.simibubi.create.content.contraptions.actors.roller.RollerBlockEntity)level.getBlockEntity(neighbour);
                h.assertTrue(adjacent != null && adjacent.getBlockState() == roller.getBlockState(), "The pair has the state that propagates native writes");
                var originalAdjacentFilter = adjacent.filtering.getFilter().copy();
                var originalAdjacentMode = adjacent.mode.getValue();
                exchange(h, sender, port, "roller-pair-write", "set", java.util.Map.of("field", (DataValue)text("roller_mode"),
                    "token", state.field("token"), "value", text("wide_fill")), rejected -> {
                        h.assertValueEqual(rejected.field("code"), text("busy"), "A side effect outside the authorized roller must not run");
                        h.assertValueEqual(roller.mode.getValue(), 0, "Refusal preserves the original target");
                        h.assertValueEqual(adjacent.mode.getValue(), originalAdjacentMode, "Refusal preserves the unauthorised roller's mode");
                        h.assertTrue(net.minecraft.world.item.ItemStack.matches(adjacent.filtering.getFilter(), originalAdjacentFilter),
                            "Refusal preserves the unauthorised roller's filter");
                        level.setBlockAndUpdate(neighbour, Blocks.AIR.defaultBlockState());
                        exchange(h, sender, port, "roller-isolated", "status", java.util.Map.of(), isolated -> {
                            var next = (DataValue.RecordValue)isolated.field("state");
                            exchange(h, sender, port, "roller-wide-fill", "set", java.util.Map.of("field", (DataValue)text("roller_mode"),
                                "token", next.field("token"), "value", text("wide_fill")), applied -> {
                                    h.assertValueEqual(applied.field("code"), text("applied"), "Set the original Create knob after physical isolation");
                                    h.assertValueEqual(roller.mode.getValue(), 2, "Set the native wide fill option");
                                    h.assertTrue(net.minecraft.world.item.ItemStack.matches(roller.filtering.getFilter(), material)
                                        && level.getBlockState(ground).is(Blocks.COPPER_BLOCK),
                                        "Configuration does not change filter, stock or terrain");
                                    h.succeed();
                                });
                        });
                });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void configuresNativeCartAssemblyWithoutChangingAnExistingContraption(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 3, 3));
        var output = origin.east(3);
        var target = output.east();
        var assembler = AllBlocks.CART_ASSEMBLER.get();
        var railType = com.simibubi.create.content.contraptions.mounted.CartAssemblerBlock.RAIL_TYPE;
        var powered = com.simibubi.create.content.contraptions.mounted.CartAssemblerBlock.POWERED;
        var regular = com.simibubi.create.content.contraptions.mounted.CartAssembleRailType.REGULAR;
        var payloadPos = target.above();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(target.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(target, assembler.defaultBlockState().setValue(railType, regular));
        level.setBlockAndUpdate(target.north(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(payloadPos, Blocks.COPPER_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var config = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = config.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.runAfterDelay(12, () -> {
            config.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize the actual cart assembler");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "cart-mode", "status", java.util.Map.of(), queried -> {
                h.assertValueEqual(queried.field("code"), text("ok"), "Query the live assembler");
                var state = (DataValue.RecordValue)queried.field("state");
                h.assertValueEqual(state.field("field"), text("movement_mode"), "Describe the native cart rotation mode");
                h.assertValueEqual(state.field("value"), text("rotate"), "Read the native default option");
                var liveRail = level.getBlockState(target);
                h.assertTrue(liveRail.getValue(powered), "Real redstone arms native assembly");
                exchange(h, sender, port, "cart-lock", "set", java.util.Map.of("field", (DataValue)text("movement_mode"),
                    "token", state.field("token"), "value", text("rotation_locked")), locked -> {
                        h.assertValueEqual(locked.field("code"), text("applied"), "Set the native rotation lock policy");
                        h.assertTrue(level.getBlockState(target) == liveRail && level.getBlockState(payloadPos).is(Blocks.COPPER_BLOCK),
                            "Configuration changes neither the rail, power, nor the actual payload");
                        var cart = net.minecraft.world.entity.EntityType.MINECART.create(level);
                        h.assertTrue(cart != null, "Native test cart exists");
                        cart.setPos(target.getX() + 0.5, target.getY() + 0.0625, target.getZ() + 0.5);
                        h.assertTrue(level.addFreshEntity(cart), "Place a real cart on the activated rail");
                        assembler.onMinecartPass(liveRail, level, target, cart);
                        h.startSequence().thenWaitUntil(() -> h.assertTrue(
                            cart.getFirstPassenger() instanceof com.simibubi.create.content.contraptions.OrientedContraptionEntity
                                && level.getBlockState(payloadPos).isAir(), "Native rail passage assembles the actual block onto the cart"))
                            .thenExecute(() -> {
                                var moving = (com.simibubi.create.content.contraptions.OrientedContraptionEntity)cart.getFirstPassenger();
                                var payload = (com.simibubi.create.content.contraptions.mounted.MountedContraption)moving.getContraption();
                                h.assertValueEqual(payload.rotationMode.name(), "ROTATION_LOCKED", "Native assembly captured the selected mode");
                                h.assertValueEqual(payload.getBlocks().values().stream().filter(b -> b.state().is(Blocks.COPPER_BLOCK)).count(),
                                    1L, "Exactly one real payload moved");
                                exchange(h, sender, port, "cart-next", "status", java.util.Map.of(), next -> {
                                    var nextState = (DataValue.RecordValue)next.field("state");
                                    exchange(h, sender, port, "cart-next-mode", "set", java.util.Map.of("field", (DataValue)text("movement_mode"),
                                        "token", nextState.field("token"), "value", text("rotate_paused")), applied -> {
                                            h.assertValueEqual(applied.field("code"), text("applied"), "Change the setting for future assemblies");
                                            h.assertTrue(cart.getFirstPassenger() == moving && moving.getContraption() == payload
                                                && level.getBlockState(payloadPos).isAir(), "No unsolicited reassembly or disassembly");
                                            h.assertValueEqual(payload.rotationMode.name(), "ROTATION_LOCKED", "The already assembled cart keeps its policy");
                                            h.assertValueEqual(ComponentSettings.observe(level, target, "movement_mode").orElseThrow().value(),
                                                text("rotate_paused"), "The assembler exposes the new native setting");
                                            h.succeed();
                                        });
                                    });
                                });
                    });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void configuresNativeValveTurnWithoutCrankingItsShaft(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 3, 3));
        var output = origin.east(3);
        var target = output.east();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING;
        var blueValve = AllBlocks.DYED_VALVE_HANDLES.get(net.minecraft.world.item.DyeColor.BLUE);
        level.setBlockAndUpdate(target, blueValve.getDefaultState().setValue(facing, Direction.UP));
        level.setBlockAndUpdate(target.below(), AllBlocks.SHAFT.getDefaultState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.Y));
        var copper = target.north(2);
        level.setBlockAndUpdate(copper, AllBlocks.COPPER_VALVE_HANDLE.getDefaultState().setValue(facing, Direction.UP));
        var valve = (com.simibubi.create.content.kinetics.crank.ValveHandleBlockEntity)level.getBlockEntity(target);
        var shaft = (com.simibubi.create.content.kinetics.base.KineticBlockEntity)level.getBlockEntity(target.below());
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var block = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = block.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.runAfterDelay(12, () -> {
            block.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize the actual dyed valve");
            h.assertTrue(ComponentSettings.observe(level, copper, "turn_angle").isPresent(), "The copper original exposes the same control");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "valve-angle", "status", java.util.Map.of(), read -> {
                h.assertValueEqual(read.field("code"), text("ok"), "Query the native scroll setting over the real wire");
                var state = (DataValue.RecordValue)read.field("state");
                h.assertValueEqual(state.field("field"), text("turn_angle"), "This is the next-turn preset");
                h.assertValueEqual(state.field("value"), new DataValue.IntegerValue(45), "Read the Create default");
                var format = (DataValue.RecordValue)state.field("format");
                h.assertValueEqual(format.field("min"), new DataValue.IntegerValue(-180), "Native minimum");
                h.assertValueEqual(format.field("max"), new DataValue.IntegerValue(180), "Native maximum");
                h.assertValueEqual(format.field("zero_allowed"), new DataValue.BooleanValue(false), "The form describes the native nonzero rule");
                exchange(h, sender, port, "valve-zero", "set", java.util.Map.of("field", (DataValue)text("turn_angle"),
                    "token", state.field("token"), "value", new DataValue.IntegerValue(0)), invalid -> {
                    h.assertValueEqual(invalid.field("code"), text("value_out_of_range"), "A zero angle is rejected, not clamped");
                    h.assertValueEqual(valve.angleInput.getValue(), 45, "Rejection keeps the old angle");
                    exchange(h, sender, port, "valve-reverse", "set", java.util.Map.of("field", (DataValue)text("turn_angle"),
                        "token", state.field("token"), "value", new DataValue.IntegerValue(-180)), changed -> {
                        h.assertValueEqual(changed.field("code"), text("applied"), "Set the reverse native preset");
                        h.assertValueEqual(valve.angleInput.getValue(), -180, "A signed value reaches the native knob");
                        h.assertTrue(valve.showValue() && valve.getGeneratedSpeed() == 0 && shaft.getSpeed() == 0 && shaft.sequenceContext == null,
                            "Settings do not crank the actual shaft or create a motion context");
                        blueValve.get().clicked(level, target, level.getBlockState(target), actor, net.minecraft.world.InteractionHand.MAIN_HAND);
                        var busy = ComponentSettings.observe(level, target, "turn_angle").orElseThrow();
                        h.assertTrue(busy.busy() && !valve.showValue(), "The real native activation hides the adjustment while moving");
                        h.assertValueEqual(ComponentSettings.plan(level, target, busy, new DataValue.IntegerValue(90)).code(), "busy",
                            "Do not change the preset while the crank is turning");
                        h.assertTrue(shaft.getSpeed() < 0 && shaft.getSpeed() == valve.getGeneratedSpeed() && shaft.sequenceContext != null,
                            "Only the actual native crank drives the connected shaft in the chosen direction");
                        h.startSequence().thenWaitUntil(() -> h.assertTrue(valve.showValue() && shaft.getSpeed() == 0,
                            "The native turn ends without a second actuation"))
                            .thenExecute(() -> h.assertValueEqual(valve.angleInput.getValue(), -180, "The configured preset remains"))
                            .thenSucceed();
                    });
                });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void configuresNativeClockworkHandsWithoutAssemblingTheClock(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 3, 3));
        var output = origin.east(3);
        var target = output.east();
        var shaft = target.above();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING;
        level.setBlockAndUpdate(target, AllBlocks.CLOCKWORK_BEARING.getDefaultState().setValue(facing, Direction.UP));
        level.setBlockAndUpdate(shaft, Blocks.COPPER_BLOCK.defaultBlockState());
        var clock = (com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity)level.getBlockEntity(target);
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var block = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = block.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        var option = BlockEntityBehaviour.get(clock, ScrollValueBehaviour.TYPE);
        h.runAfterDelay(12, () -> {
            block.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize access to the physical clockwork bearing");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "clock-mode", "status", java.util.Map.of(), read -> {
                h.assertValueEqual(read.field("code"), text("ok"), "Read the native clockwork setting");
                var state = (DataValue.RecordValue)read.field("state");
                h.assertValueEqual(state.field("field"), text("clock_hands"), "The adapter exposes the clockwork setting only");
                h.assertValueEqual(state.field("value"), text("hour_first"), "Read the native default mode");
                exchange(h, sender, port, "clock-mode-24", "set", java.util.Map.of("field", (DataValue)text("clock_hands"),
                    "token", state.field("token"), "value", text("hour_first_24")), changed -> {
                    h.assertValueEqual(changed.field("code"), text("applied"), "Switch the idle clock to its 24-hour native mode");
                    h.assertValueEqual(((com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour<?>)option).get().name(),
                        "HOUR_FIRST_24", "The native mode changes, not a separate shadow setting");
                    h.assertTrue(!clock.isRunning() && clock.getSpeed() == 0 && level.getBlockState(shaft).is(Blocks.COPPER_BLOCK),
                        "Changing mode did not start assembling or move the attached block");
                    var motorPos = target.below();
                    level.setBlockAndUpdate(motorPos, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(facing, Direction.UP));
                    var motor = (com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity)level.getBlockEntity(motorPos);
                    motor.initialize();
                    motor.generatedSpeed.setValue(16);
                    motor.updateGeneratedRotation();
                    h.startSequence().thenWaitUntil(() -> h.assertTrue(clock.isRunning() && level.getBlockState(shaft).isAir(),
                        "The actual Create drive assembles the attached clock arm"))
                        .thenExecute(() -> exchange(h, sender, port, "clock-running", "status", java.util.Map.of(), running -> {
                            var runningState = (DataValue.RecordValue)running.field("state");
                            exchange(h, sender, port, "clock-running-change", "set", java.util.Map.of("field", (DataValue)text("clock_hands"),
                                "token", runningState.field("token"), "value", text("minute_first")), busy -> {
                                h.assertValueEqual(busy.field("code"), text("busy"), "A deployed clock keeps the previously configured mode");
                                h.assertTrue(clock.isRunning() && level.getBlockState(shaft).isAir() && clock.getSpeed() != 0,
                                    "The refused request does not disassemble or stall the existing clock");
                                h.assertValueEqual(((com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour<?>)option).get().name(),
                                    "HOUR_FIRST_24", "No change was accepted while moving");
                                h.succeed();
                            });
                        }));
                });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void configuresNativeEjectorBatchWithoutCreatingStock(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 3, 3));
        var output = origin.east(3);
        var target = output.east();
        var landing = target.east(3);
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        var receiver = depot(level, landing);
        level.setBlockAndUpdate(target, AllBlocks.WEIGHTED_EJECTOR.getDefaultState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        var ejector = (com.simibubi.create.content.logistics.depot.EjectorBlockEntity)level.getBlockEntity(target);
        ejector.setTarget(3, 0);
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var block = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = block.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        var nativeSetting = BlockEntityBehaviour.get(ejector, ScrollValueBehaviour.TYPE);
        var gold = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT);
        h.runAfterDelay(12, () -> {
            block.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize the real ejector port");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "ejector-not-charged", "status", java.util.Map.of(), pending -> {
                h.assertValueEqual(pending.field("code"), text("ok"), "Read the real ejector on the wire");
                var first = (DataValue.RecordValue)pending.field("state");
                h.assertValueEqual(first.field("field"), text("batch_size"), "Batch setting is not a launch command");
                h.assertValueEqual(first.field("value"), new DataValue.IntegerValue(0), "Native wildcard is the zero option");
                var change = java.util.Map.of("field", (DataValue)text("batch_size"),
                    "token", first.field("token"), "value", new DataValue.IntegerValue(2));
                exchange(h, sender, port, "ejector-busy", "set", change, busy -> {
                    h.assertValueEqual(busy.field("code"), text("busy"), "Do not reconfigure an uncharged mechanism");
                    h.assertValueEqual(nativeSetting.getValue(), 0, "No implicit deferred setting");
                    var motorPos = target.north();
                    level.setBlockAndUpdate(motorPos, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                        .setValue(com.simibubi.create.content.kinetics.motor.CreativeMotorBlock.FACING, Direction.SOUTH));
                    var motor = (com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity)level.getBlockEntity(motorPos);
                    motor.initialize();
                    motor.generatedSpeed.setValue(64);
                    motor.updateGeneratedRotation();
                    h.startSequence().thenWaitUntil(() -> h.assertValueEqual(ejector.getState(),
                        com.simibubi.create.content.logistics.depot.EjectorBlockEntity.State.CHARGED, "Charge using native kinetic power"))
                        .thenExecute(() -> exchange(h, sender, port, "ejector-charged", "status", java.util.Map.of(), read -> {
                            var state = (DataValue.RecordValue)read.field("state");
                            var set = java.util.Map.of("field", (DataValue)text("batch_size"), "token", state.field("token"),
                                "value", new DataValue.IntegerValue(2));
                            exchange(h, sender, port, "ejector-batch", "set", set, accepted -> {
                                h.assertValueEqual(accepted.field("code"), text("applied"), "Update the bounded native setting");
                                h.assertValueEqual(nativeSetting.getValue(), 2, "Native batch size changes");
                                h.assertValueEqual(ejector.getTargetPosition(), landing, "Native placement destination does not change");
                                h.assertTrue(receiver.getHeldItem().isEmpty(), "A setting alone creates no cargo");
                                var input = level.getCapability(Capabilities.ItemHandler.BLOCK, target, Direction.UP);
                                h.assertTrue(input != null && input.insertItem(0, gold.copy(), false).isEmpty(), "Native ejector accepts the first ingot");
                                h.startSequence().thenExecuteAfter(25, () -> {
                                    h.assertValueEqual(input.getStackInSlot(0).getCount(), 1, "Below the threshold the ingot stays");
                                    h.assertTrue(receiver.getHeldItem().isEmpty(), "No early launch");
                                    h.assertTrue(input.insertItem(0, gold.copy(), false).isEmpty(), "Native insertion adds the second ingot");
                                }).thenWaitUntil(() -> h.assertValueEqual(receiver.getHeldItem().getCount(), 2, "Native machine delivers the two ingots"))
                                    .thenExecute(() -> h.assertTrue(receiver.getHeldItem().is(net.minecraft.world.item.Items.GOLD_INGOT)
                                        && input.getStackInSlot(0).isEmpty(), "Cargo type and count are conserved")).thenSucceed();
                            });
                        }));
                });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void configuresNativeArmSelectionWithoutBypassingItsTransfer(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 3, 3));
        var output = origin.east(3);
        var target = output.east();
        var input = target.north(2);
        var firstPos = target.east(2);
        var secondPos = target.south(2);
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        var source = depot(level, input);
        var first = depot(level, firstPos);
        var second = depot(level, secondPos);
        level.setBlockAndUpdate(target, AllBlocks.MECHANICAL_ARM.getDefaultState());
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var block = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = block.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        var pickup = armPoint(level, input);
        pickup.cycleMode();
        new com.simibubi.create.content.kinetics.mechanicalArm.ArmPlacementPacket(
            java.util.List.of(pickup, armPoint(level, firstPos), armPoint(level, secondPos)), target).handle(actor);
        var arm = (com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity)level.getBlockEntity(target);
        var access = (ArmControl.Access)arm;
        var gold = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT);
        var obstruction = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64);
        h.runAfterDelay(12, () -> {
            block.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize the actual arm port");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "selection", "status", java.util.Map.of(), read -> {
                h.assertValueEqual(read.field("code"), text("ok"), "Read native selection over the actual wire");
                var state = (DataValue.RecordValue)read.field("state");
                h.assertValueEqual(state.field("field"), text("selection_mode"), "The setting is named apart from arm_move");
                h.assertValueEqual(state.field("value"), text("round_robin"), "Native default policy");
                var set = java.util.Map.of("field", (DataValue)text("selection_mode"), "token", state.field("token"),
                    "value", text("forced_round_robin"));
                exchange(h, sender, port, "bad-policy", "set", java.util.Map.of("field", (DataValue)text("selection_mode"),
                    "token", state.field("token"), "value", text("invalid")), bad -> {
                    h.assertValueEqual(bad.field("code"), text("value_out_of_range"), "Ignore unknown policies");
                    exchange(h, sender, port, "new-policy", "set", set, changed -> {
                        h.assertValueEqual(changed.field("code"), text("applied"), "Configuring does not require synthetic drive");
                        h.assertTrue(access.biocomputingHeldCopy().isEmpty() && access.biocomputingAction() == null,
                            "Setting the policy does not start an explicit route or create material");
                        exchange(h, sender, port, "old-policy-token", "set", set, stale -> {
                            h.assertValueEqual(stale.field("code"), text("version_conflict"), "The accepted token cannot be reused");
                            first.setHeldItem(obstruction);
                            source.setHeldItem(gold);
                            var cog = target.south();
                            level.setBlockAndUpdate(cog, AllBlocks.COGWHEEL.getDefaultState()
                                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.Y));
                            level.setBlockAndUpdate(cog.below(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                                .setValue(com.simibubi.create.content.kinetics.motor.CreativeMotorBlock.FACING, Direction.UP));
                            var motor = (com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity)level.getBlockEntity(cog.below());
                            motor.initialize();
                            motor.generatedSpeed.setValue(16);
                            motor.updateGeneratedRotation();
                            h.startSequence().thenWaitUntil(() -> h.assertValueEqual(access.biocomputingHeldCopy().getCount(), 1,
                                "The driven arm takes real inventory")).thenExecuteAfter(140, () -> {
                                    h.assertTrue(first.getHeldItem().is(net.minecraft.world.item.Items.COBBLESTONE)
                                        && second.getHeldItem().isEmpty() && access.biocomputingHeldCopy().getCount() == 1,
                                        "Forced native rotation waits for the first output instead of skipping to the second");
                                    exchange(h, sender, port, "held-status", "status", java.util.Map.of(), held -> {
                                        var busyState = (DataValue.RecordValue)held.field("state");
                                        h.assertValueEqual(busyState.field("value"), text("forced_round_robin"), "Read the new native policy");
                                        exchange(h, sender, port, "held-set", "set", java.util.Map.of("field", (DataValue)text("selection_mode"),
                                            "token", busyState.field("token"), "value", text("prefer_first")), busy -> {
                                            h.assertValueEqual(busy.field("code"), text("busy"), "Do not change selection during a transfer");
                                            first.setHeldItem(net.minecraft.world.item.ItemStack.EMPTY);
                                            h.startSequence().thenWaitUntil(() -> h.assertTrue(first.getHeldItem().is(net.minecraft.world.item.Items.GOLD_INGOT),
                                                "Native transfer resumes when the selected output is cleared")).thenExecute(() ->
                                                h.assertTrue(first.getHeldItem().getCount() == 1 && source.getHeldItem().isEmpty()
                                                    && second.getHeldItem().isEmpty() && access.biocomputingHeldCopy().isEmpty(),
                                                    "One real item was moved; none copied or redirected")).thenSucceed();
                                        });
                                    });
                                });
                        });
                    });
                });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1300)
    public static void armReportsOnlyConfiguredInteractionPointsOverWire(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 2, 3));
        var output = origin.east(3);
        var target = output.east();
        var input = target.north(2);
        var destination = target.south(2);
        var unrelatedDepot = target.east(2);
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        for (var pos : java.util.List.of(input, destination, unrelatedDepot)) level.setBlockAndUpdate(pos, AllBlocks.DEPOT.getDefaultState());
        level.setBlockAndUpdate(target, AllBlocks.MECHANICAL_ARM.getDefaultState());
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var block = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = block.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        var sourcePoint = com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType
            .getPrimaryType(level, input, level.getBlockState(input)).createPoint(level, input, level.getBlockState(input));
        sourcePoint.cycleMode();
        var outputPoint = com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType
            .getPrimaryType(level, destination, level.getBlockState(destination)).createPoint(level, destination, level.getBlockState(destination));
        new com.simibubi.create.content.kinetics.mechanicalArm.ArmPlacementPacket(java.util.List.of(sourcePoint, outputPoint), target).handle(actor);
        h.runAfterDelay(12, () -> {
            block.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize the native arm through the actual output block");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "arm", "arm_status", java.util.Map.of(), read -> {
                h.assertValueEqual(read.field("code"), text("ok"), "Read on the finite wire");
                var state = (DataValue.RecordValue)read.field("state");
                h.assertValueEqual(state.field("phase"), text("search_inputs"), "Native phase");
                h.assertValueEqual(state.field("reason"), text("no_drive"), "No synthetic power or success");
                h.assertValueEqual(state.field("held"), DataValue.NullValue.INSTANCE, "An unpowered empty arm holds nothing");
                assertArmPoint(h, state, "inputs", input.subtract(target), "ready");
                assertArmPoint(h, state, "outputs", destination.subtract(target), "ready");
                exchange(h, sender, port, "arm-native", "arm_points_nbt", java.util.Map.of(), exported -> {
                    h.assertValueEqual(exported.field("code"), text("ok"), "Native NBT export through the same authorized serial port");
                    var arm = (com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity)level.getBlockEntity(target);
                    var original = new net.minecraft.nbt.CompoundTag();
                    arm.writeInteractionPoints(original);
                    var codec = new com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec.Limits(4096, 65536,
                        new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(4096, 65536, 65536));
                    var copy = com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec.toTag(
                        (DataValue.NbtValue)exported.field("state"), codec);
                    var nativePoints = original.getList("InteractionPoints", net.minecraft.nbt.Tag.TAG_COMPOUND);
                    var exportPoints = ((net.minecraft.nbt.CompoundTag)copy).getList("InteractionPoints", net.minecraft.nbt.Tag.TAG_COMPOUND);
                    h.assertValueEqual(exportPoints, nativePoints, "Relative native Pos, Mode and Type must be preserved exactly");
                    h.assertTrue(!((net.minecraft.nbt.CompoundTag)copy).contains("HeldItem"), "Export is not a complete block entity dump");
                    level.removeBlock(destination, false);
                    exchange(h, sender, port, "arm-removed", "arm_status", java.util.Map.of(), removed -> {
                        assertArmPoint(h, (DataValue.RecordValue)removed.field("state"), "outputs", destination.subtract(target), "point_missing");
                        h.succeed();
                    });
                });
            }));
        });
    }

    private static void assertArmPoint(GameTestHelper h, DataValue.RecordValue state, String group, BlockPos offset, String status) {
        var values = ((DataValue.ListValue)state.field(group)).values();
        h.assertValueEqual(values.size(), 1, "Only the configured " + group + " are returned, not other nearby depots");
        var point = (DataValue.RecordValue)values.getFirst();
        h.assertValueEqual(point.field("offset"), ComponentSettingsProtocol.record(java.util.Map.of(
            "x", ComponentSettingsProtocol.number(offset.getX()), "y", ComponentSettingsProtocol.number(offset.getY()),
            "z", ComponentSettingsProtocol.number(offset.getZ()))), "Offsets are relative to the arm");
        h.assertValueEqual(point.field("status"), text(status), "Point availability is explicit");
    }

    @GameTest(template = "empty", timeoutTicks = 2300)
    public static void sequencerAdmitsOneNativeMotionWithoutReplacingItsPreset(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 2, 3));
        var output = origin.east(3);
        var target = output.east();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        var state = AllBlocks.SEQUENCED_GEARSHIFT.getDefaultState()
            .setValue(com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlock.HORIZONTAL_AXIS, Direction.Axis.Z)
            .setValue(com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlock.VERTICAL, false);
        level.setBlockAndUpdate(target, state);
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var block = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = block.getBehaviour(ComponentSettingsPort.TYPE);
        var sequencer = (com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity)level.getBlockEntity(target);
        var preset = java.util.List.copyOf(sequencer.getInstructions());
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.runAfterDelay(5, () -> {
            block.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Same authorized output supports the motion action");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "unpowered", "motion_status", java.util.Map.of(), unpowered -> {
                var check = (DataValue.RecordValue)unpowered.field("state");
                var request = motionArgs(check, "angle", 360);
                exchange(h, sender, port, "no-drive", "motion_start", request, rejected -> {
                    h.assertValueEqual(rejected.field("code"), text("no_drive"), "Does not create rotation without a power source");
                    var motor = target.north();
                    var shaft = target.south();
                    level.setBlockAndUpdate(shaft, AllBlocks.SHAFT.getDefaultState()
                        .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.Z));
                    level.setBlockAndUpdate(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                        .setValue(com.simibubi.create.content.kinetics.motor.CreativeMotorBlock.FACING, Direction.SOUTH));
                    var drive = (com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity)level.getBlockEntity(motor);
                    drive.initialize();
                    drive.generatedSpeed.setValue(2);
                    drive.updateGeneratedRotation();
                    h.runAfterDelay(8, () -> exchange(h, sender, port, "powered", "motion_status", java.util.Map.of(), ready -> {
                        var args = motionArgs((DataValue.RecordValue)ready.field("state"), "angle", 360);
                        exchange(h, sender, port, "turn", "motion_start", args, started -> {
                            h.assertValueEqual(started.field("code"), text("accepted"), "A request starts the native instruction");
                            h.assertTrue(!sequencer.isIdle(), "Create gearshift is actually running");
                            h.assertValueEqual(java.util.List.copyOf(sequencer.getInstructions()), preset, "Native preset is untouched during the action");
                            var driven = (com.simibubi.create.content.kinetics.base.KineticBlockEntity)level.getBlockEntity(shaft);
                            h.assertTrue(driven.getSpeed() != 0, "Motion is transferred through the real shaft");
                            exchange(h, sender, port, "replayed", "motion_start", args, replay -> {
                                h.assertValueEqual(replay.field("code"), text("version_conflict"), "Consumed start token cannot replay");
                                exchange(h, sender, port, "check-running", "motion_status", java.util.Map.of(), running -> {
                                    var runningState = (DataValue.RecordValue)running.field("state");
                                    h.assertValueEqual(runningState.field("phase"), text("running"), "Read the tracked native motion");
                                    exchange(h, sender, port, "competing", "motion_start", motionArgs(runningState, "distance", 5), busy -> {
                                        h.assertValueEqual(busy.field("code"), text("busy"), "Never replace the running instruction");
                                        h.runAfterDelay(630, () -> exchange(h, sender, port, "done", "motion_status", java.util.Map.of(), done -> {
                                            var result = (DataValue.RecordValue)done.field("state");
                                            h.assertValueEqual(result.field("phase"), text("sequence_completed"), "Native sequencer retired its action");
                                            h.assertValueEqual(result.field("arrival"), text("not_measured"), "No fake positional feedback");
                                            h.assertValueEqual(java.util.List.copyOf(sequencer.getInstructions()), preset, "Original native preset remains");
                                            exchange(h, sender, port, "linear", "motion_start", motionArgs(result, "distance", 3), linear -> {
                                                h.assertValueEqual(linear.field("code"), text("accepted"), "A fresh read admits a distance action");
                                                h.assertValueEqual(sequencer.sequenceContext.instruction(),
                                                    com.simibubi.create.content.kinetics.transmission.sequencer.SequencerInstructions.TURN_DISTANCE,
                                                    "Native downstream receives a distance constraint, not a position write");
                                                h.assertTrue(driven.getSpeed() != 0 && driven.sequenceContext != null,
                                                    "The output shaft is still driven by the active distance instruction");
                                                var persisted = sequencer.saveWithFullMetadata(level.registryAccess());
                                                var blockState = sequencer.getBlockState();
                                                var persistedShaft = driven.saveWithFullMetadata(level.registryAccess());
                                                var shaftState = driven.getBlockState();
                                                level.removeBlockEntity(target);
                                                level.removeBlockEntity(shaft);
                                                var restored = (com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity)
                                                    net.minecraft.world.level.block.entity.BlockEntity.loadStatic(target, blockState, persisted, level.registryAccess());
                                                var restoredShaft = (com.simibubi.create.content.kinetics.base.KineticBlockEntity)
                                                    net.minecraft.world.level.block.entity.BlockEntity.loadStatic(shaft, shaftState, persistedShaft, level.registryAccess());
                                                level.setBlockEntity(restored);
                                                level.setBlockEntity(restoredShaft);
                                                h.assertTrue(restoredShaft.getSpeed() != 0 && restoredShaft.sequenceContext != null,
                                                    "The downstream saved rotation and distance context are restored together with the gearshift");
                                                h.runAfterDelay(8, () -> {
                                                    var recovery = SequencedMotion.state(restored, null);
                                                    h.assertValueEqual(recovery.field("phase"), text("interrupted"), "Reload records an uncertain motion as interrupted");
                                                    h.assertValueEqual(recovery.field("action"), ((DataValue.RecordValue)linear.field("state")).field("action"),
                                                        "The interrupted action remains identifiable");
                                                    h.assertTrue(restored.isIdle(), "Reload does not start the preset in place of the interrupted action");
                                                    h.assertValueEqual(drive.getSpeed(), 2f, "Recovery leaves the real input motor running");
                                                    h.assertValueEqual(restoredShaft.getSpeed(), 0f, "The interrupted instruction no longer drives the output shaft");
                                                    h.assertTrue(restoredShaft.sequenceContext == null, "The output shaft no longer carries the interrupted distance instruction");
                                                    h.assertValueEqual(com.simibubi.create.content.kinetics.transmission.sequencer.Instruction.serializeAll(restored.getInstructions()),
                                                        com.simibubi.create.content.kinetics.transmission.sequencer.Instruction.serializeAll(preset), "Stored native preset survives");
                                                    exchange(h, sender, port, "stale", "motion_start", motionArgs(result, "distance", 3), stale -> {
                                                        h.assertValueEqual(stale.field("code"), text("target_changed"), "The old grant cannot write to a rebuilt target");
                                                        h.succeed();
                                                    });
                                                });
                                            });
                                        }));
                                    });
                                });
                            });
                        });
                    }));
                });
            }));
        });
    }

    private static java.util.Map<String, DataValue> motionArgs(DataValue.RecordValue status, String mode, int amount) {
        return java.util.Map.of("token", status.field("token"), "mode", text(mode),
            "amount", new DataValue.IntegerValue(amount), "speed", new DataValue.IntegerValue(1));
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void speedTargetDoesNotGeneratePowerAndNativeDriveReachesIt(GameTestHelper h) {
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(3, 2, 3));
        var controllerState = AllBlocks.ROTATION_SPEED_CONTROLLER.getDefaultState().setValue(
            com.simibubi.create.content.kinetics.speedController.SpeedControllerBlock.HORIZONTAL_AXIS, Direction.Axis.Z);
        level.setBlockAndUpdate(pos, controllerState);
        var controller = (com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity)level.getBlockEntity(pos);
        h.runAfterDelay(4, () -> {
            var observed = ComponentSettings.observe(level, pos).orElse(null);
            h.assertTrue(observed != null, "Rotation speed controller exposes the native setpoint");
            var negative = new DataValue.IntegerValue(-24);
            h.assertTrue(ComponentSettings.plan(level, pos, observed, negative).apply(), "Apply signed speed setpoint");
            h.assertValueEqual(controller.targetSpeed.getValue(), -24, "Native setpoint");
            var stopped = ComponentSettings.motion(level, pos).orElseThrow();
            h.assertValueEqual(stopped.field("actual_rpm"), new DataValue.DecimalValue(0), "No native drive means no actual speed");
            h.assertValueEqual(stopped.field("reached"), new DataValue.BooleanValue(false), "An applied setpoint is not a reached speed");
            var cog = pos.above();
            level.setBlockAndUpdate(cog, AllBlocks.LARGE_COGWHEEL.getDefaultState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.X));
            var motor = cog.west();
            level.setBlockAndUpdate(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(com.simibubi.create.content.kinetics.motor.CreativeMotorBlock.FACING, Direction.EAST));
            var drive = (com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity)level.getBlockEntity(motor);
            drive.initialize();
            drive.generatedSpeed.setValue(32);
            drive.updateGeneratedRotation();
            h.runAfterDelay(20, () -> {
                var powered = ComponentSettings.motion(level, pos).orElseThrow();
                h.assertValueEqual(powered.field("actual_rpm"), new DataValue.DecimalValue(-24), "Native drive reaches requested speed and direction");
                h.assertValueEqual(powered.field("reached"), new DataValue.BooleanValue(true), "Reached measured independently");
                var now = ComponentSettings.observe(level, pos).orElseThrow();
                var limits = (ComponentSettings.IntRange)now.field().format();
                h.assertValueEqual(ComponentSettings.plan(level, pos, now, new DataValue.IntegerValue((long)limits.maximum()+1)).code(),
                    "value_out_of_range", "Read the native configured range; never clamp");
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1300)
    public static void configuresAcrossCableOnMovingSableStructure(GameTestHelper h) {
        if (!net.neoforged.fml.ModList.get().isLoaded("sable")) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required profile must load Sable");
            h.succeed();
            return;
        }
        MovingRig.run(h);
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void recalibratesActualGimbalSignalsOnAnAssembledPlot(GameTestHelper h) {
        var sensor = net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getOptional(net.minecraft.resources.ResourceLocation.parse("simulated:gimbal_sensor"));
        if (!net.neoforged.fml.ModList.get().isLoaded("sable") || sensor.isEmpty()) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required profile must load the gimbal and Sable");
            h.succeed();
            return;
        }
        MovingRig.gimbal(h, sensor.orElseThrow());
    }

    @GameTest(template = "empty", timeoutTicks = 1400)
    public static void preservesNativeNameplateAndStructureNameOnAPlot(GameTestHelper h) {
        var plate = net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getOptional(net.minecraft.resources.ResourceLocation.parse("simulated:white_nameplate"));
        if (!net.neoforged.fml.ModList.get().isLoaded("sable") || plate.isEmpty()) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required profile must load the nameplate and Sable");
            h.succeed();
            return;
        }
        MovingRig.nameplate(h, plate.orElseThrow());
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void partialFrameDoesNotApplyOnAPlotAfterReconnecting(GameTestHelper h) {
        if (!net.neoforged.fml.ModList.get().isLoaded("sable")) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required profile must load Sable");
            h.succeed();
            return;
        }
        MovingRig.cutPlotCable(h);
    }

    // Keep optional Sable runtime classes out of the no-Sable GameTest entry point.
    private static final class MovingRig {
        static void nameplate(GameTestHelper h, net.minecraft.world.level.block.Block plate) {
            var level = h.getLevel();
            var base = h.absolutePos(new BlockPos(2, 4, 3));
            var supports = java.util.stream.IntStream.range(0, 5).mapToObj(base::east).toList();
            supports.forEach(pos -> level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState()));
            var rig = dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(level, base, supports,
                new dev.ryanhcode.sable.companion.math.BoundingBox3i(base, base.east(4)));
            rig.setName("TEST STRUCTURE");
            var origin = rig.getPlot().getCenterBlock().above();
            var output = origin.east(3);
            var target = output.east();
            var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
            sender.serialDevice().configure("s0", Direction.EAST, true);
            for (int n = 1; n <= 2; n++)
                level.setBlockAndUpdate(origin.east(n), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
            level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
                .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
            level.setBlockAndUpdate(target.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(target.north(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(target, plate.defaultBlockState().setValue(
                net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, Direction.SOUTH));
            var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
            var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
            var actor = new MenuPlayer(level);
            var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
            h.runAfterDelay(10, () -> {
                var space = com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat.getSpaceId(level, target);
                h.assertValueEqual(space, rig.getUniqueId(), "The guarded plate belongs to the actual Sable plot");
                var nativePlate = level.getBlockEntity(target);
                h.assertTrue(nativePlate instanceof com.simibubi.create.content.equipment.clipboard.ClipboardCloneable,
                    "Use the native plate");
                var before = nativePlate.saveWithoutMetadata(level.registryAccess());
                h.assertTrue(before.getInt("Width") == 1 && !before.getBoolean("Waxed"),
                    "Neither grouping nor waxing accounts for this refusal");
                actor.moveTo(com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat.toWorld(level, output, center));
                var hit = new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false);
                h.assertValueEqual(adapter.getBlockState().useWithoutItem(level, actor, hit),
                    net.minecraft.world.InteractionResult.CONSUME, "Open the adapter at the projected physical position");
                actor.grant();
                h.assertValueEqual(actor.client.code(), "configured", "The configured line reaches the real local adapter");
                // The normal world tick must first publish the newly authorized attachment.
                h.runAfterDelay(3, () -> exchange(h, sender, port, "plot-nameplate-read", "status", java.util.Map.of(), read -> {
                    h.assertValueEqual(read.field("code"), text("ok"), "The plate can still be inspected");
                    var view = (DataValue.RecordValue)read.field("state");
                    h.assertValueEqual(view.field("field"), text("label_text"), "The restricted setting is still identifiable");
                    h.assertValueEqual(view.field("busy"), new DataValue.BooleanValue(true),
                        "A plate on the plot is not writable even if standalone and unwaxed");
                    exchange(h, sender, port, "plot-nameplate-refused", "set", java.util.Map.of(
                        "token", view.field("token"), "field", text("label_text"), "value", text("NEW LABEL")), refused -> {
                        h.assertValueEqual(refused.field("code"), text("busy"), "Do not grant remote edits to the body name");
                        h.assertValueEqual(rig.getName(), "TEST STRUCTURE", "The moving body keeps its native name");
                        h.assertValueEqual(nativePlate.saveWithoutMetadata(level.registryAccess()), before, "The plate is unchanged");
                        h.succeed();
                    });
                }));
            });
        }

        static void gimbal(GameTestHelper h, net.minecraft.world.level.block.Block sensor) {
            var level = h.getLevel();
            var base = h.absolutePos(new BlockPos(2, 4, 3));
            var support = java.util.stream.IntStream.range(0, 5).mapToObj(base::east).toList();
            support.forEach(pos -> level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState()));
            var rig = dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(level, base, support,
                new dev.ryanhcode.sable.companion.math.BoundingBox3i(base, base.east(4)));
            var senderPos = rig.getPlot().getCenterBlock().above();
            var output = senderPos.east(3);
            var target = senderPos.east(4);
            var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, senderPos);
            sender.serialDevice().configure("s0", Direction.EAST, true);
            for (int n = 1; n <= 2; n++)
                level.setBlockAndUpdate(senderPos.east(n), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
            level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
                .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
            level.setBlockAndUpdate(target, sensor.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.X));
            var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
            var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
            var actor = new MenuPlayer(level);
            var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
            var pose = new org.joml.Quaterniond().rotationXYZ(Math.toRadians(20), 0, Math.toRadians(20));
            var primary = new java.util.function.IntSupplier() {
                public int getAsInt() { return Math.max(level.getSignal(target, Direction.EAST), level.getSignal(target, Direction.WEST)); }
            };
            var secondary = new java.util.function.IntSupplier() {
                public int getAsInt() { return Math.max(level.getSignal(target, Direction.NORTH), level.getSignal(target, Direction.SOUTH)); }
            };
            h.runAfterDelay(5, () -> {
                var before = com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat.toWorld(level, output, center);
                actor.moveTo(before);
                var hit = new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false);
                h.assertValueEqual(adapter.getBlockState().useWithoutItem(level, actor, hit),
                    net.minecraft.world.InteractionResult.CONSUME, "Open the adapter at the actual projected position");
                actor.grant();
                h.assertValueEqual(actor.client.code(), "configured", "Authorize the physical cable within the plot");
                var body = dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(rig);
                var airborne = new org.joml.Vector3d(rig.logicalPose().position()).add(0, 20, 1);
                body.teleport(airborne, pose);
                h.startSequence().thenWaitUntil(() -> {
                    var moved = com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat.toWorld(level, output, center);
                    h.assertTrue(moved.distanceToSqr(before) > 0.01 && primary.getAsInt() > 0 && secondary.getAsInt() > 0,
                        "The real inclined plot produces native signals on both axes");
                }).thenExecute(() -> exchange(h, sender, port, "tilted-read", "status", java.util.Map.of(), reply -> {
                    h.assertValueEqual(reply.field("code"), text("ok"), "Calibration is readable after moving the structure");
                    var observed = (DataValue.RecordValue)reply.field("state");
                    h.assertValueEqual(observed.field("value"), gimbalAngles(45, 45), "Tilt changes signals, not the configured calibration");
                    var desired = gimbalAngles(0, 45);
                    exchange(h, sender, port, "tilted-calibration", "set", java.util.Map.of("token", observed.field("token"),
                        "field", text("angle_calibration"), "value", desired), applied -> {
                        h.assertValueEqual(applied.field("code"), text("applied"), "Apply the pair in the same authorized moving space");
                        h.assertValueEqual(ComponentSettings.observe(level, target, "angle_calibration").orElseThrow().value(), desired,
                            "Native settings retain only the selected calibration change");
                        body.teleport(airborne, pose);
                        h.startSequence().thenWaitUntil(() -> h.assertTrue(primary.getAsInt() == 0 && secondary.getAsInt() > 0,
                            "At the same physical tilt, only the zero-calibrated axis has stopped signalling")).thenSucceed();
                    });
                }));
            });
        }

        static void cutPlotCable(GameTestHelper h) {
            var level = h.getLevel();
            var base = h.absolutePos(new BlockPos(2, 4, 3));
            var supports = java.util.stream.IntStream.range(0, 5).mapToObj(base::east).toList();
            supports.forEach(pos -> level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState()));
            var rig = dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(level, base, supports,
                new dev.ryanhcode.sable.companion.math.BoundingBox3i(base, base.east(4)));
            var senderPos = rig.getPlot().getCenterBlock().above();
            var output = senderPos.east(3);
            var target = senderPos.east(4);
            var cut = senderPos.east();
            var cable = com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState();
            var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, senderPos);
            sender.serialDevice().configure("s0", Direction.EAST, true);
            level.setBlockAndUpdate(cut, cable);
            level.setBlockAndUpdate(cut.east(), cable);
            level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
                .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
            level.setBlockAndUpdate(target, Blocks.REPEATER.defaultBlockState());
            var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
            var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
            var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
            var actor = new MenuPlayer(level);
            h.runAfterDelay(5, () -> {
                actor.moveTo(com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat.toWorld(level, output, center));
                h.assertValueEqual(adapter.getBlockState().useWithoutItem(level, actor,
                    new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false)),
                    net.minecraft.world.InteractionResult.CONSUME, "Open the attached adapter at its projected position");
                actor.grant();
                h.assertValueEqual(actor.client.code(), "configured", "Authorize the actual plot cable");
                h.runAfterDelay(2, () -> exchange(h, sender, port, "plot-read", "status", java.util.Map.of(), reply -> {
                    h.assertValueEqual(reply.field("code"), text("ok"), "The actual repeater is ready");
                    var state = (DataValue.RecordValue)reply.field("state");
                    var write = command(port, "cut-write", "set", java.util.Map.of("token", state.field("token"), "field", text("delay"),
                        "value", new DataValue.IntegerValue(4)));
                    h.assertValueEqual(sender.serialDevice().send("s0", write), "queued", "Schedule a native setting write");
                    h.startSequence().thenWaitUntil(() -> h.assertValueEqual(sender.serialDevice().status("s0").transmit(),
                        "transmitting", "Cut the real wire before the whole write frame arrives")).thenExecute(() -> {
                            level.removeBlock(cut, false);
                            h.runAfterDelay(2, () -> {
                                h.assertValueEqual(sender.serialDevice().status("s0").transmit(), "interrupted", "Sender observes the broken frame");
                                h.assertValueEqual(level.getBlockState(target).getValue(RepeaterBlock.DELAY), 1, "A partial command has no native effect");
                                h.assertValueEqual(port.device().status("s0").connection(), "ready", "A cable stub still touches the adapter");
                                actor.moveTo(com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat.toWorld(level, output, center));
                                actor.request(ComponentSettingsRequest.Action.REFRESH);
                                h.assertValueEqual(actor.client.view().field("line"), text("disconnected"),
                                    "The player sees the granted source is unreachable, not the carrier state of the local stub");
                                h.assertTrue(actor.client.view().field("binding") instanceof DataValue.RecordValue,
                                    "Losing the line does not erase the player's grant");
                                level.setBlockAndUpdate(cut, cable);
                                h.runAfterDelay(60, () -> {
                                    h.assertTrue(sender.serialDevice().poll("s0").isEmpty(), "Reconnection does not reply to the cut command");
                                    h.assertValueEqual(level.getBlockState(target).getValue(RepeaterBlock.DELAY), 1, "No replay after reconnect");
                                    actor.moveTo(com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat.toWorld(level, output, center));
                                    actor.request(ComponentSettingsRequest.Action.REFRESH);
                                    h.assertValueEqual(actor.client.view().field("line"), text("ready"), "The same source is reachable again");
                                    exchange(h, sender, port, "recovery-read", "status", java.util.Map.of(), fresh -> {
                                        var renewed = (DataValue.RecordValue)fresh.field("state");
                                        exchange(h, sender, port, "recovery-write", "set", java.util.Map.of("token", renewed.field("token"),
                                            "field", text("delay"), "value", new DataValue.IntegerValue(3)), done -> {
                                            h.assertValueEqual(done.field("code"), text("applied"), "A new request works after reconnect without widening the grant");
                                            h.assertValueEqual(level.getBlockState(target).getValue(RepeaterBlock.DELAY), 3, "Only the fresh request changes the native delay");
                                            h.succeed();
                                        });
                                    });
                                });
                            });
                    });
                }));
            });
        }


        static void run(GameTestHelper h) {
            var level = h.getLevel();
            var base = h.absolutePos(new BlockPos(2, 4, 3));
            var supports = new java.util.ArrayList<BlockPos>();
            for (int x = 0; x < 5; x++) {
                var support = base.east(x);
                supports.add(support);
                level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
            }
            var rig = dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(level, base, supports,
                new dev.ryanhcode.sable.companion.math.BoundingBox3i(base, base.east(4)));
            var rawBase = rig.getPlot().getCenterBlock();
            var senderPos = rawBase.above();
            var output = senderPos.east(3);
            var target = senderPos.east(4);
            h.assertTrue(level.getBlockState(rawBase).is(Blocks.STONE), "Assembly moved the support into the plot");
            var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, senderPos);
            sender.serialDevice().configure("s0", Direction.EAST, true);
            for (int x = 1; x <= 2; x++)
                level.setBlockAndUpdate(senderPos.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
            level.setBlockAndUpdate(target, Blocks.REPEATER.defaultBlockState());
            level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
                .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
            var block = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
            var port = block.getBehaviour(ComponentSettingsPort.TYPE);
            var actor = new MenuPlayer(level);
            var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
            var hit = new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false);
            var first = com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat.toWorld(level, output, center);
            h.assertTrue(first.distanceToSqr(center) > 64, "Physical position is not the raw storage position");
            h.runAfterDelay(5, () -> {
                var at = com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat.toWorld(level, output, center);
                actor.moveTo(at);
                h.assertValueEqual(block.getBlockState().useWithoutItem(level, actor, hit),
                    net.minecraft.world.InteractionResult.CONSUME, "Player at physical position opens menu");
                actor.grant();
                h.assertValueEqual(actor.client.code(), "configured", "Authorize the actual source cable in this plot");
                var body = dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(rig);
                body.addLinearAndAngularVelocity(new org.joml.Vector3d(0, 3, 0), new org.joml.Vector3d());
                h.runAfterDelay(6, () -> {
                    var moved = com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat.toWorld(level, output, center);
                    h.assertTrue(moved.distanceToSqr(at) > 0.01, "The actual rigid body moved between authorization and transmission");
                    exchange(h, sender, port, "moving-read", "status", java.util.Map.of(), read -> {
                        h.assertValueEqual(read.field("code"), text("ok"), "Read over the plot cable after movement");
                        var observed = (DataValue.RecordValue)read.field("state");
                        exchange(h, sender, port, "moving-set", "set", java.util.Map.of("token", observed.field("token"),
                            "field", (DataValue)text("delay"), "value", new DataValue.IntegerValue(3)), applied -> {
                            h.assertValueEqual(applied.field("code"), text("applied"), "Edit passes on the same plot after movement");
                            h.assertValueEqual(level.getBlockState(target).getValue(RepeaterBlock.DELAY), 3, "Plot component was updated");
                            h.succeed();
                        });
                    });
                });
            });
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void optionalSimulatedDevicesUseTheirNativeSettings(GameTestHelper h) {
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        var altitudeId = net.minecraft.resources.ResourceLocation.parse("simulated:altitude_sensor");
        if (!registry.containsKey(altitudeId)) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required profile must load Simulated");
            h.succeed();
            return;
        }
        var level = h.getLevel();
        var sensor = h.absolutePos(new BlockPos(2, 2, 2));
        var receiver = sensor.east(2);
        var inductor = sensor.south(2);
        level.setBlockAndUpdate(sensor, registry.get(altitudeId).defaultBlockState());
        var receiverState = registry.get(net.minecraft.resources.ResourceLocation.parse("simulated:modulating_linked_receiver")).defaultBlockState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, Direction.UP);
        level.setBlockAndUpdate(receiver.below(), Blocks.STONE.defaultBlockState());
        h.assertTrue(receiverState.canSurvive(level, receiver), "Fixture: receiver has a solid back");
        level.setBlockAndUpdate(receiver, receiverState);
        var inductorId = net.minecraft.resources.ResourceLocation.parse("simulated:redstone_inductor");
        h.assertTrue(registry.containsKey(inductorId), "Fixture: inductor registered");
        level.setBlockAndUpdate(inductor.below(), Blocks.STONE.defaultBlockState());
        var inductorState = registry.get(inductorId).defaultBlockState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        h.assertTrue(inductorState.canSurvive(level, inductor), "Fixture: inductor has the native diode's solid base");
        level.setBlockAndUpdate(inductor, inductorState);
        h.runAfterDelay(8, () -> {
            var range = ComponentSettings.observe(level, sensor).orElse(null);
            h.assertTrue(range != null, "Altitude sensor publishes its actual calibration");
            var reverse = ComponentSettingsProtocol.record(java.util.Map.of("zero_at", new DataValue.DecimalValue(0.75),
                "full_at", new DataValue.DecimalValue(0.25)));
            h.assertTrue(ComponentSettings.plan(level, sensor, range, reverse).apply(), "Reversed scale is legal");
            h.assertValueEqual(ComponentSettings.observe(level, sensor).orElseThrow().value(), reverse, "Native altitude calibration readback");
            var equal = ComponentSettingsProtocol.record(java.util.Map.of("zero_at", new DataValue.DecimalValue(0.5),
                "full_at", new DataValue.DecimalValue(0.5)));
            h.assertValueEqual(ComponentSettings.plan(level, sensor, ComponentSettings.observe(level, sensor).orElseThrow(), equal).code(),
                "invalid_setting_pair", "Equal endpoints would divide by zero");
            var bounds = ComponentSettings.observe(level, receiver).orElse(null);
            h.assertTrue(bounds != null, "Installed receiver exposes the distance band");
            var distances = ComponentSettingsProtocol.record(java.util.Map.of("minimum", new DataValue.IntegerValue(5), "maximum", new DataValue.IntegerValue(42)));
            h.assertTrue(ComponentSettings.plan(level, receiver, bounds, distances).apply(), "Use native distance band");
            h.assertValueEqual(ComponentSettings.observe(level, receiver).orElseThrow().value(), distances, "Native receiver readback");
            var invalid = ComponentSettingsProtocol.record(java.util.Map.of("minimum", new DataValue.IntegerValue(42), "maximum", new DataValue.IntegerValue(5)));
            h.assertValueEqual(ComponentSettings.plan(level, receiver, ComponentSettings.observe(level, receiver).orElseThrow(), invalid).code(),
                "invalid_setting_pair", "Do not swap user-selected distances");
            var inductorEntity = level.getBlockEntity(inductor);
            h.assertTrue(inductorEntity instanceof SimulatedSettingsAccess.Inductor,
                "Fixture: inductor exposes its native behavior, state=" + level.getBlockState(inductor) + ", entity=" + inductorEntity);
            var nativeDelay = ((SimulatedSettingsAccess.Inductor)inductorEntity).biocomputingInputDelay();
            h.assertTrue(nativeDelay != null, "Fixture: the native behavior is initialized");
            var interval = ComponentSettings.observe(level, inductor).orElse(null);
            h.assertTrue(interval != null, "Installed inductor exposes the interval");
            h.assertValueEqual(interval.field().unit(), "game_tick", "Inductor works in game ticks");
            h.assertTrue(ComponentSettings.plan(level, inductor, interval, new DataValue.IntegerValue(21)).apply(), "Use native scroll setting");
            h.assertValueEqual(nativeDelay.getValue(), 21, "Native setting readback");
            h.assertTrue(!level.hasNeighborSignal(inductor), "Configuration does not add a power source");
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void inductorInversionChangesNativeOutputWithoutRewritingSignal(GameTestHelper h) {
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        var id = net.minecraft.resources.ResourceLocation.parse("simulated:redstone_inductor");
        if (!registry.containsKey(id)) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required profile must load the inductor");
            h.succeed();
            return;
        }
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 4, 3));
        var output = origin.east(3);
        var target = output.east();
        var lamp = target.south();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++) level.setBlockAndUpdate(origin.east(x),
            com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(target.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(lamp, Blocks.REDSTONE_LAMP.defaultBlockState());
        level.setBlockAndUpdate(target.north(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        var state = registry.get(id).defaultBlockState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        level.setBlockAndUpdate(target, state);
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        var lit = net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT;
        var enabled = new DataValue.BooleanValue(true);
        var disabled = new DataValue.BooleanValue(false);
        h.startSequence().thenWaitUntil(() -> {
            h.assertValueEqual(level.getSignal(target, Direction.NORTH), 15, "Native input must reach full strength");
            h.assertTrue(level.getBlockState(lamp).getValue(lit), "The output lamp must be lit before inversion");
        }).thenExecute(() -> {
            var interval = ComponentSettings.observe(level, target).orElseThrow().value();
            adapter.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize the physical serial source using the output menu");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "inductor-read", "status", java.util.Map.of("field", text("inverted")), read -> {
                h.assertValueEqual(read.field("code"), text("ok"), "Read the native polarity using the public settings port");
                var snapshot = (DataValue.RecordValue)read.field("state");
                h.assertValueEqual(snapshot.field("value"), disabled, "Native polarity starts off");
                exchange(h, sender, port, "inductor-invert", "set", java.util.Map.of("token", snapshot.field("token"),
                    "field", text("inverted"), "value", enabled), applied -> {
                        h.assertValueEqual(applied.field("code"), text("applied"), "Apply one authorized polarity setting");
                        h.assertValueEqual(level.getSignal(target, Direction.NORTH), 0, "The native output inverts its measured 15");
                        h.assertValueEqual(level.getBlockEntity(target).saveWithoutMetadata(level.registryAccess()).getInt("OutputSignal"), 15,
                            "Polarity does not overwrite the accumulated native signal");
                        h.assertValueEqual(ComponentSettings.observe(level, target).orElseThrow().value(), interval,
                            "The default interval and its value remain unchanged");
                        h.assertTrue(level.getBlockState(target.north()).is(Blocks.REDSTONE_BLOCK), "Do not alter the input");
                        h.runAfterDelay(6, () -> {
                            h.assertTrue(!level.getBlockState(lamp).getValue(lit), "The normal downstream lamp observes the new native output");
                            exchange(h, sender, port, "inductor-stale", "set", java.util.Map.of("token", snapshot.field("token"),
                                "field", text("inverted"), "value", disabled), stale -> {
                                    h.assertValueEqual(stale.field("code"), text("version_conflict"), "A prior token cannot set a new polarity");
                                    h.assertValueEqual(level.getSignal(target, Direction.NORTH), 0, "Rejected edits do not change the signal");
                                    h.succeed();
                                });
                        });
                    });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1300)
    public static void gimbalCalibrationUsesBothNativeAxesAndRejectsStalePair(GameTestHelper h) {
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        var id = net.minecraft.resources.ResourceLocation.parse("simulated:gimbal_sensor");
        if (!registry.containsKey(id)) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required profile must load the gimbal sensor");
            h.succeed();
            return;
        }
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 4, 3));
        var output = origin.east(3);
        var pos = output.east();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(pos, registry.get(id).defaultBlockState());
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var block = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = block.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.runAfterDelay(10, () -> {
            var nativeSettings = BlockEntityBehaviour.get(level, pos, ScrollValueBehaviour.TYPE);
            h.assertTrue(nativeSettings != null, "Native gimbal is present");
            block.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize a real source using the common output menu");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "gimbal-read", "status", java.util.Map.of(), read -> {
                var snapshot = (DataValue.RecordValue)read.field("state");
                h.assertValueEqual(snapshot.field("field"), text("angle_calibration"), "The two axes are one configuration pair");
                h.assertValueEqual(snapshot.field("value"), gimbalAngles(45, 45), "Read both native defaults, independent of last face");
                chooseNativeGimbalAxis(nativeSettings, level.getBlockState(pos), true);
                nativeSettings.setValue(-33);
                var desired = gimbalAngles(-24, 71);
                exchange(h, sender, port, "gimbal-stale", "set", java.util.Map.of("token", snapshot.field("token"),
                    "field", text("angle_calibration"), "value", desired), stale -> {
                    h.assertValueEqual(stale.field("code"), text("version_conflict"), "A manual edit of one axis invalidates the pair");
                    exchange(h, sender, port, "gimbal-read-new", "status", java.util.Map.of(), refreshed -> {
                        var current = (DataValue.RecordValue)refreshed.field("state");
                        h.assertValueEqual(current.field("value"), gimbalAngles(-33, 45), "The first edit remains and the other axis is unchanged");
                        exchange(h, sender, port, "gimbal-out-of-range", "set", java.util.Map.of("token", current.field("token"),
                            "field", text("angle_calibration"), "value", gimbalAngles(-24, 91)), invalid -> {
                            h.assertValueEqual(invalid.field("code"), text("value_out_of_range"), "Neither axis changes when one is invalid");
                            h.assertValueEqual(nativeSettings.getValue(), -33, "Primary axis was not partly updated");
                            exchange(h, sender, port, "gimbal-set", "set", java.util.Map.of("token", current.field("token"),
                                "field", text("angle_calibration"), "value", desired), applied -> {
                                h.assertValueEqual(applied.field("code"), text("applied"), "Both values are applied in one authorized operation");
                                h.assertValueEqual(nativeSettings.getValue(), -24, "Remote setting did not change the manually selected axis");
                                chooseNativeGimbalAxis(nativeSettings, level.getBlockState(pos), false);
                                h.assertValueEqual(nativeSettings.getValue(), 71, "Other native axis sees the other calibration");
                                for (var direction : Direction.Plane.HORIZONTAL)
                                    h.assertValueEqual(level.getSignal(pos, direction), 0, "Changing a calibration does not synthesize motion or redstone");
                                h.succeed();
                            });
                        });
                    });
                });
            }));
        });
    }

    private static DataValue.RecordValue gimbalAngles(int primary, int secondary) {
        return ComponentSettingsProtocol.record(java.util.Map.of("primary", ComponentSettingsProtocol.number(primary),
            "secondary", ComponentSettingsProtocol.number(secondary)));
    }

    private static void chooseNativeGimbalAxis(ScrollValueBehaviour settings, net.minecraft.world.level.block.state.BlockState state,
            boolean primary) {
        var face = primary == (state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_AXIS) == Direction.Axis.X)
            ? Direction.NORTH : Direction.EAST;
        ((com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform.Sided)settings.getSlotPositioning()).fromSide(face);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void velocityCalibrationPreservesTheNativeMagnitudeAndDirection(GameTestHelper h) {
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        var id = net.minecraft.resources.ResourceLocation.parse("simulated:velocity_sensor");
        if (!registry.containsKey(id)) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required profile must load the velocity sensor");
            h.succeed();
            return;
        }
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlockAndUpdate(pos, registry.get(id).defaultBlockState());
        h.runAfterDelay(10, () -> {
            var nativeSettings = BlockEntityBehaviour.get(level, pos, ScrollValueBehaviour.TYPE);
            var before = ComponentSettings.observe(level, pos).orElse(null);
            h.assertTrue(before != null && nativeSettings != null, "Expose the real velocity sensor's calibration");
            h.assertValueEqual(before.field().name(), "velocity_calibration", "One observation contains both native settings");
            h.assertValueEqual(before.value(), velocityCalibration(10, "away"), "Default direction follows the native UI row");
            var actor = new MenuPlayer(level);
            var zero = velocityCalibration(0, "towards");
            h.assertValueEqual(ComponentSettings.plan(level, pos, before, zero, actor).code(), "value_out_of_range", "Reject zero scale");
            var valid = velocityCalibration(18, "towards");
            h.assertTrue(ComponentSettings.plan(level, pos, before, valid, actor).apply(), "Set speed and direction via the native UI setter");
            h.assertValueEqual(nativeSettings.getValueSettings().row(), 1, "Native toward row");
            h.assertValueEqual(nativeSettings.getValueSettings().value(), 18, "Native speed in m/s");
            h.assertValueEqual(nativeSettings.getValue(), 18, "Native signed scale for the toward row");
            var after = ComponentSettings.observe(level, pos).orElseThrow();
            h.assertTrue(ComponentSettings.plan(level, pos, after, velocityCalibration(18, "away"), actor).apply(),
                "Direction can change without modifying speed");
            h.assertValueEqual(nativeSettings.getValue(), -18, "Native signed scale for the away row");
            for (var direction : Direction.values())
                h.assertValueEqual(level.getSignal(pos, direction), 0, "Configuration does not write motion or redstone");
            h.succeed();
        });
    }

    private static DataValue.RecordValue velocityCalibration(int speed, String direction) {
        return ComponentSettingsProtocol.record(java.util.Map.of("full_speed", ComponentSettingsProtocol.number(speed),
            "direction", text(direction)));
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void opticalSensorExposesNativeRangeButNotItsFilterOrSignal(GameTestHelper h) {
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        var id = net.minecraft.resources.ResourceLocation.parse("simulated:optical_sensor");
        if (!registry.containsKey(id)) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required profile must load the optical sensor");
            h.succeed();
            return;
        }
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlockAndUpdate(pos, registry.get(id).defaultBlockState());
        h.runAfterDelay(8, () -> {
            var nativeRange = BlockEntityBehaviour.get(level, pos, ScrollValueBehaviour.TYPE);
            var nativeFilter = BlockEntityBehaviour.get(level, pos, com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour.TYPE);
            h.assertTrue(nativeRange != null && nativeFilter != null, "The real sensor has both controls");
            nativeFilter.setFilter(new net.minecraft.world.item.ItemStack(Blocks.STONE));
            var range = ComponentSettings.observe(level, pos).orElse(null);
            h.assertTrue(range != null, "The shared component settings output exposes the sensor range");
            h.assertValueEqual(range.field().name(), "max_detection_range", "Range is a configuration value, not the current measured distance");
            h.assertValueEqual(range.field().unit(), "block", "Length in native block units");
            var limits = (ComponentSettings.IntRange)range.field().format();
            int next = nativeRange.getValue() == limits.minimum() ? limits.maximum() : limits.minimum();
            h.assertTrue(next != nativeRange.getValue(), "The native fixture has two distinct values");
            h.assertValueEqual(ComponentSettings.plan(level, pos, range, new DataValue.IntegerValue((long)limits.maximum() + 1)).code(),
                "value_out_of_range", "Do not silently clamp an invalid write");
            h.assertTrue(ComponentSettings.plan(level, pos, range, new DataValue.IntegerValue(next)).apply(), "Change the native setting");
            h.assertValueEqual(nativeRange.getValue(), next, "Native sensor reads that maximum distance");
            h.assertTrue(nativeFilter.getFilter().is(Blocks.STONE.asItem()), "Existing filter item remains untouched");
            h.assertTrue(ComponentSettings.observe(level, pos, "filter").isEmpty(), "Filter insertion is not a settings write");
            h.assertTrue(ComponentSettings.observe(level, pos, "powered").isEmpty(), "Program cannot override measured signal");
            h.succeed();
        });
    }


    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void changesNativeThrottlePolarityWithoutSettingItsLevel(GameTestHelper h) {
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        var id = net.minecraft.resources.ResourceLocation.parse("simulated:throttle_lever");
        if (!registry.containsKey(id)) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required profile must load the throttle lever");
            h.succeed();
            return;
        }
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 4, 3));
        var output = origin.east(3);
        var lever = output.east();
        var dust = lever.east();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));

        var face = net.minecraft.world.level.block.state.properties.BlockStateProperties.ATTACH_FACE;
        var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
        var inverted = (net.minecraft.world.level.block.state.properties.BooleanProperty)registry.get(id).getStateDefinition().getProperty("inverted");
        var power = net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER;
        level.setBlockAndUpdate(lever.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(dust.below(), Blocks.STONE.defaultBlockState());
        var original = registry.get(id).defaultBlockState()
            .setValue(face, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR).setValue(facing, Direction.EAST);
        level.setBlockAndUpdate(lever, original);
        level.setBlockAndUpdate(dust, Blocks.REDSTONE_WIRE.defaultBlockState());
        var leverEntity = level.getBlockEntity(lever);
        var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        var actor = new MenuPlayer(level);
        actor.moveTo(center);

        h.runAfterDelay(8, () -> {
            try {
                // Prepare the fixture with the same native handler used while gripping the lever.
                var step = leverEntity.getClass().getMethod("changeState", boolean.class);
                for (int i = 0; i < 5; i++) step.invoke(leverEntity, false);
            } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
            h.runAfterDelay(20, () -> {
                h.assertValueEqual(level.getBlockState(dust).getValue(power), 5, "Native lever powers the dust before remote configuration");
                adapter.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
                actor.grant();
                h.assertValueEqual(actor.client.code(), "configured", "Authorize the actual lever configuration over cable");
                h.runAfterDelay(3, () -> exchange(h, sender, port, "throttle-read", "status", java.util.Map.of(), read -> {
                    h.assertValueEqual(read.field("code"), text("ok"), "Query the native boolean rather than its live level");
                    var state = (DataValue.RecordValue)read.field("state");
                    h.assertValueEqual(state.field("field"), text("inverted"), "Default is the only native setting");
                    h.assertValueEqual(state.field("value"), new DataValue.BooleanValue(false), "Native initial polarity");
                    h.assertTrue(ComponentSettings.observe(level, lever, "signal").isEmpty(), "No configuration for live signal");
                    var token = state.field("token");
                    exchange(h, sender, port, "throttle-bad-value", "set", java.util.Map.of("token", token,
                        "field", text("inverted"), "value", ComponentSettingsProtocol.number(15)), bad -> {
                            h.assertValueEqual(bad.field("code"), text("type_mismatch"), "An integer cannot set live redstone");
                            h.assertValueEqual(level.getBlockState(dust).getValue(power), 5, "Invalid request cannot change native output");
                            exchange(h, sender, port, "throttle-invert", "set", java.util.Map.of("token", token,
                                "field", text("inverted"), "value", new DataValue.BooleanValue(true)), applied -> {
                                    h.assertValueEqual(applied.field("code"), text("applied"), "Use the native wrench action");
                                    h.runAfterDelay(10, () -> {
                                        h.assertValueEqual(level.getBlockState(lever), original.setValue(inverted, true),
                                            "Only the native polarity changes");
                                        h.assertTrue(level.getBlockEntity(lever) == leverEntity, "Keep the original physical lever");
                                        h.assertValueEqual(level.getBlockState(dust).getValue(power), 10,
                                            "Native neighbor update mirrors the same five-step lever position");
                                        exchange(h, sender, port, "throttle-stale", "set", java.util.Map.of("token", token,
                                            "field", text("inverted"), "value", new DataValue.BooleanValue(false)), stale -> {
                                                h.assertValueEqual(stale.field("code"), text("version_conflict"), "A used token cannot undo the setting");
                                                h.assertValueEqual(level.getBlockState(dust).getValue(power), 10, "Rejected replay leaves actual power intact");
                                                h.succeed();
                                            });
                                    });
                                });
                        });
                }));
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void adjustsNativeLaserReachWithoutWritingPower(GameTestHelper h) {
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        var pointerId = net.minecraft.resources.ResourceLocation.parse("simulated:laser_pointer");
        if (!registry.containsKey(pointerId)) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required profile must load the laser pointer");
            h.succeed();
            return;
        }
        var sensorId = net.minecraft.resources.ResourceLocation.parse("simulated:laser_sensor");
        h.assertTrue(registry.containsKey(sensorId), "A real Simulated laser sensor is registered");
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 4, 3));
        var output = origin.east(3);
        var pointer = output.east();
        var receiver = pointer.south(4);
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING;
        var power = net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED;
        level.setBlockAndUpdate(receiver, registry.get(sensorId).defaultBlockState()
            .setValue(com.simibubi.create.content.redstone.DirectedDirectionalBlock.TARGET,
                net.minecraft.world.level.block.state.properties.AttachFace.WALL)
            .setValue(com.simibubi.create.content.redstone.DirectedDirectionalBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(pointer, registry.get(pointerId).defaultBlockState().setValue(facing, Direction.SOUTH));
        level.setBlockAndUpdate(pointer.north(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.runAfterDelay(24, () -> {
            h.assertTrue(level.getBlockState(pointer).getValue(power), "The actual pointer receives redstone");
            h.assertTrue(level.getBlockState(receiver).getValue(power), "The actual beam reaches the opposite sensor");
            var original = level.getBlockState(pointer);
            adapter.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "The authorized native pointer supports beam range configuration");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "beam-read", "status", java.util.Map.of(), read -> {
                var state = (DataValue.RecordValue)read.field("state");
                h.assertValueEqual(state.field("field"), text("max_beam_range"), "Inspect the range, not the live laser power");
                h.assertValueEqual(state.field("unit"), text("block"), "Native units");
                var nativeRange = BlockEntityBehaviour.get(level, pointer, ScrollValueBehaviour.TYPE);
                h.assertTrue(nativeRange != null, "The laser has an actual range control");
                var bounds = (com.nobodiiiii.createbiotech.mixin.BiocomputingScrollRangeAccess)nativeRange;
                int previous = nativeRange.getValue();
                var token = state.field("token");
                h.assertTrue(bounds.createBiotech$min() < 4 && previous >= 4, "Fixture distances straddle the native range");
                exchange(h, sender, port, "beam-invalid", "set", java.util.Map.of("token", token,
                    "field", text("max_beam_range"), "value", new DataValue.IntegerValue((long)bounds.createBiotech$max() + 1)), invalid -> {
                    h.assertValueEqual(invalid.field("code"), text("value_out_of_range"), "Reject values beyond the native limit");
                    h.assertValueEqual(nativeRange.getValue(), previous, "Invalid request does not alter the emitter");
                    exchange(h, sender, port, "beam-shorten", "set", java.util.Map.of("token", token,
                        "field", text("max_beam_range"), "value", new DataValue.IntegerValue(bounds.createBiotech$min())), applied -> {
                        h.assertValueEqual(applied.field("code"), text("applied"), "Use the real native scroll setter");
                        h.runAfterDelay(24, () -> {
                            h.assertTrue(!level.getBlockState(receiver).getValue(power), "The sensor stops receiving the now-short beam");
                            h.assertValueEqual(level.getBlockState(pointer), original, "Range does not change native power, polarity or facing");
                            h.assertTrue(level.getBlockState(pointer.north()).is(Blocks.REDSTONE_BLOCK), "Real power source remains untouched");
                            verifyNativeLaserPolarity(h, sender, port, pointer, receiver, original, previous);
                        });
                    });
                });
            }));
        });
    }



    private static void verifyNativeLaserPolarity(GameTestHelper h,
            com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour sender,
            ComponentSettingsPort port, BlockPos pointer, BlockPos receiver,
            net.minecraft.world.level.block.state.BlockState original, int previousRange) {
        var level = h.getLevel();
        var powered = net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED;
        var inverted = net.minecraft.world.level.block.state.properties.BlockStateProperties.INVERTED;
        exchange(h, sender, port, "read-short-beam", "status", java.util.Map.of(), rangeRead -> {
            var rangeToken = ((DataValue.RecordValue)rangeRead.field("state")).field("token");
            exchange(h, sender, port, "restore-beam", "set", java.util.Map.of("token", rangeToken,
                "field", text("max_beam_range"), "value", new DataValue.IntegerValue(previousRange)), restored -> {
                h.assertValueEqual(restored.field("code"), text("applied"), "Restore original range independently");
                h.runAfterDelay(24, () -> {
                    h.assertTrue(level.getBlockState(receiver).getValue(powered), "Receiver lights again at the normal range");
                    exchange(h, sender, port, "read-beam-polarity", "status", java.util.Map.of("field", text("inverted")), read -> {
                        h.assertValueEqual(read.field("code"), text("ok"), "Observe the separate native polarity control");
                        var state = (DataValue.RecordValue)read.field("state");
                        var token = state.field("token");
                        exchange(h, sender, port, "invert-beam", "set", java.util.Map.of("token", token,
                            "field", text("inverted"), "value", new DataValue.BooleanValue(true)), applied -> {
                            h.assertValueEqual(applied.field("code"), text("applied"), "Use the native polarity change");
                            h.runAfterDelay(24, () -> {
                                h.assertValueEqual(level.getBlockState(pointer), original.setValue(inverted, true),
                                    "Only the native polarity bit may change");
                                h.assertTrue(!level.getBlockState(receiver).getValue(powered), "A powered inverted emitter no longer reaches the sensor");
                                h.assertValueEqual(BlockEntityBehaviour.get(level, pointer, ScrollValueBehaviour.TYPE).getValue(),
                                    previousRange, "Polarity does not replace the range");
                                exchange(h, sender, port, "replay-beam-polarity", "set", java.util.Map.of("token", token,
                                    "field", text("inverted"), "value", new DataValue.BooleanValue(false)), replay -> {
                                    h.assertValueEqual(replay.field("code"), text("version_conflict"), "Reject a consumed polarity token");
                                    h.assertTrue(level.getBlockState(pointer).getValue(inverted), "Polarity still holds after rejected replay");
                                    level.removeBlock(pointer.north(), false);
                                    h.runAfterDelay(24, () -> {
                                        h.assertTrue(!level.getBlockState(pointer).getValue(powered), "No fabricated external input");
                                        h.assertTrue(level.getBlockState(receiver).getValue(powered), "The same inverted emitter now reaches the sensor");
                                        h.succeed();
                                    });
                                });
                            });
                        });
                    });
                });
            });
        });
    }


    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aeroVentAndPropellerBearingsExposeOnlyPlayerSettings(GameTestHelper h) {
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        var ventId = net.minecraft.resources.ResourceLocation.parse("aeronautics:steam_vent");
        if (!registry.containsKey(ventId)) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required profile must include the steam vent");
            h.succeed();
            return;
        }
        var level = h.getLevel();
        var vent = h.absolutePos(new BlockPos(2, 2, 3));
        var normal = vent.east(2);
        var gyro = normal.east(2);
        level.setBlockAndUpdate(vent.below(), AllBlocks.FLUID_TANK.getDefaultState());
        h.assertTrue(registry.get(ventId).defaultBlockState().canSurvive(level, vent), "Steam vent is mounted on a native fluid tank");
        level.setBlockAndUpdate(vent, registry.get(ventId).defaultBlockState());
        for (var entry : java.util.Map.of(normal, "propeller_bearing", gyro, "gyroscopic_propeller_bearing").entrySet()) {
            var id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("aeronautics", entry.getValue());
            h.assertTrue(registry.containsKey(id), "Required bearing is registered");
            level.setBlockAndUpdate(entry.getKey(), registry.get(id).defaultBlockState());
        }
        var drill = vent.south(2);
        var cutter = drill.east(2);
        for (var entry : java.util.Map.of(drill, "borehead_bearing", cutter, "rockcutting_wheel").entrySet()) {
            var id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("offroad", entry.getValue());
            h.assertTrue(registry.containsKey(id), "Required Offroad component is registered");
            level.setBlockAndUpdate(entry.getKey(), registry.get(id).defaultBlockState());
        }
        h.runAfterDelay(8, () -> {
            var volume = ComponentSettings.observe(level, vent).orElse(null);
            h.assertTrue(volume != null, "Steam vent exposes its native target volume");
            h.assertValueEqual(volume.field().name(), "target_volume", "Use the same semantic setting as the burner");
            var limits = (ComponentSettings.IntRange)volume.field().format();
            h.assertTrue(limits.maximum() > limits.minimum(), "Vent has live native limits");
            var value = new DataValue.IntegerValue(volume.value().equals(new DataValue.IntegerValue(limits.minimum()))
                ? limits.maximum() : limits.minimum());
            h.assertTrue(ComponentSettings.plan(level, vent, volume, value).apply(), "Set the actual vent control");
            h.assertValueEqual(ComponentSettings.observe(level, vent).orElseThrow().value(), value, "Target changes without a steam source");
            h.assertTrue(ComponentSettings.observe(level, vent, "steam_contents").isEmpty(), "Do not expose a gas quantity setter");
            for (var pos : java.util.List.of(normal, gyro)) {
                var orientation = ComponentSettings.observe(level, pos).orElseThrow();
                h.assertValueEqual(orientation.field().name(), "thrust_direction", "Only expose handedness");
                h.assertValueEqual(orientation.value(), text("right_handed"), "Read the native default");
                h.assertValueEqual(ComponentSettings.plan(level, pos, orientation, text("sideways")).code(), "value_out_of_range", "No arbitrary vector");
                h.assertTrue(ComponentSettings.plan(level, pos, orientation, text("left_handed")).apply(), "Use native option and callback");
                var bearing = (com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity)level.getBlockEntity(pos);
                h.assertTrue(!bearing.isRunning() && bearing.getMovedContraption() == null, "A setting alone does not assemble or start movement");
                h.assertValueEqual(ComponentSettings.observe(level, pos).orElseThrow().value(), text("left_handed"), "Read back native choice");
                h.assertTrue(ComponentSettings.observe(level, pos, "rotation_speed").isEmpty(), "Do not expose runtime rotation state");
            }
            h.assertTrue(ComponentSettings.noPlayerSettings(level.getBlockState(drill)), "Borehead explicitly removed the inherited movement control");
            h.assertTrue(ComponentSettings.noPlayerSettings(level.getBlockState(cutter)), "Rock wheel has no player settings");
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void fueledPortableEngineReversesDrivenShaftWithNativeFuel(GameTestHelper h) {
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        var blue = net.minecraft.resources.ResourceLocation.parse("simulated:blue_portable_engine");
        var red = net.minecraft.resources.ResourceLocation.parse("simulated:red_portable_engine");
        if (!registry.containsKey(blue)) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required fixture must load the portable engine");
            h.succeed();
            return;
        }
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 4, 3));
        var output = origin.east(3);
        var target = output.east();
        var shaftPos = target.north();
        var otherColor = target.south(2);
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++) level.setBlockAndUpdate(origin.east(x),
            com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
        level.setBlockAndUpdate(target, registry.get(blue).defaultBlockState().setValue(facing, Direction.NORTH));
        level.setBlockAndUpdate(otherColor, registry.get(red).defaultBlockState().setValue(facing, Direction.NORTH));
        level.setBlockAndUpdate(shaftPos, AllBlocks.SHAFT.getDefaultState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.Z));
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var engine = (com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity)level.getBlockEntity(target);
        var idle = (com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity)level.getBlockEntity(otherColor);
        var shaft = (com.simibubi.create.content.kinetics.base.KineticBlockEntity)level.getBlockEntity(shaftPos);
        var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.runAfterDelay(8, () -> {
            var initial = ComponentSettings.observe(level, target);
            h.assertTrue(initial.isPresent(), "The engine exposes its native rotation direction");
            h.assertValueEqual(initial.orElseThrow().field().name(), "rotation_direction", "Use the standard rotation setting");
            h.assertValueEqual(ComponentSettings.observe(level, otherColor).orElseThrow().device(), red,
                "A dyed block preserves its own identity and shares the original setting");
            h.assertTrue(ComponentSettings.observe(level, target, "burn_time").isEmpty(), "Do not expose fuel state for editing");
            var opposite = text(initial.orElseThrow().value().equals(text("clockwise")) ? "counter_clockwise" : "clockwise");
            var idleSetting = ComponentSettings.observe(level, otherColor).orElseThrow();
            h.assertTrue(ComponentSettings.plan(level, otherColor, idleSetting, opposite).apply(),
                "An unfueled engine can select its direction");
            h.assertTrue(idle.getGeneratedSpeed() == 0 && idle.getSpeed() == 0, "A direction setting is not a source of power");
            var fuel = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, target, Direction.SOUTH);
            h.assertTrue(fuel != null, "Fixture must use the actual engine fuel capability");
            var fuelItem = net.minecraft.world.item.Items.COAL_BLOCK;
            h.assertTrue(fuel.insertItem(0, new net.minecraft.world.item.ItemStack(fuelItem, 2), false).isEmpty(),
                "Supply exactly two ordinary fuel items through its inventory");
            h.startSequence().thenWaitUntil(() -> h.assertTrue(engine.getSpeed() != 0 && shaft.getSpeed() != 0
                && fuel.getStackInSlot(0).getCount() == 1, "The engine burns a real item and drives its downstream shaft"))
                .thenExecute(() -> {
                    adapter.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
                    actor.grant();
                    h.assertValueEqual(actor.client.code(), "configured", "Authorize the actual cable source");
                    h.runAfterDelay(3, () -> exchange(h, sender, port, "engine-status", "status", java.util.Map.of(), reply -> {
                        h.assertValueEqual(reply.field("code"), text("ok"), "Read the native engine option over finite serial");
                        var state = (DataValue.RecordValue)reply.field("state");
                        var before = shaft.getSpeed();
                        var remaining = engine.saveWithoutMetadata(level.registryAccess()).getInt("BurnTime");
                        var args = java.util.Map.of("token", state.field("token"), "field", text("rotation_direction"), "value", opposite);
                        exchange(h, sender, port, "engine-set", "set", args, applied -> {
                            h.assertValueEqual(applied.field("code"), text("applied"), "Update the existing fuel-powered engine");
                            h.startSequence().thenWaitUntil(() -> h.assertValueEqual(shaft.getSpeed(), -before,
                                "The actual downstream shaft reverses at the same speed"))
                                .thenExecute(() -> {
                                    h.assertTrue(level.getBlockEntity(target) == engine, "The setting does not replace the engine");
                                    int after = engine.saveWithoutMetadata(level.registryAccess()).getInt("BurnTime");
                                    h.assertTrue(after > 0 && after < remaining, "The original burn timer keeps ticking without refill");
                                    h.assertTrue(fuel.getStackInSlot(0).is(fuelItem) && fuel.getStackInSlot(0).getCount() == 1,
                                        "The untouched spare fuel remains in the engine");
                                    exchange(h, sender, port, "engine-stale", "set", args, stale -> {
                                        h.assertValueEqual(stale.field("code"), text("version_conflict"), "A used setting token cannot replay");
                                        h.assertValueEqual(shaft.getSpeed(), -before, "Replaying the token cannot change the shaft");
                                        h.succeed();
                                    });
                                });
                        });
                    }));
                });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void swivelBearingLockPolicyUsesNativeSignalAndRetainsAssembly(GameTestHelper h) {
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        var id = net.minecraft.resources.ResourceLocation.parse("simulated:swivel_bearing");
        if (!registry.containsKey(id)) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required fixture must load the swivel bearing");
            h.succeed();
            return;
        }
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 4, 3));
        var output = origin.east(3);
        var target = output.east();
        var power = target.north();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++) level.setBlockAndUpdate(origin.east(x),
            com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(target, registry.get(id).defaultBlockState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, Direction.UP));
        level.setBlockAndUpdate(target.above(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var bearing = level.getBlockEntity(target);
        var locked = net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED;
        var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.runAfterDelay(8, () -> {
            var observed = ComponentSettings.observe(level, target);
            h.assertTrue(observed.isPresent(), "Expose the swivel bearing's native lock policy");
            h.assertValueEqual(observed.orElseThrow().field().name(), "locking_mode", "Only expose native locking policy");
            h.assertValueEqual(observed.orElseThrow().value(), text("locked_default"), "Read the original native option");
            h.assertValueEqual(ComponentSettings.plan(level, target, observed.orElseThrow(), text("angle_override")).code(),
                "value_out_of_range", "Arbitrary lock modes are rejected");
            h.assertTrue(ComponentSettings.observe(level, target, "target_angle").isEmpty(), "Target angle is not programmable");
            invokeSwivel(bearing, "assemble"); // Native fixture setup only; the serial port cannot assemble a body.
            var body = (java.util.UUID)invokeSwivel(bearing, "getSubLevelID");
            var plate = (BlockPos)invokeSwivel(bearing, "getPlatePos");
            h.assertTrue(body != null && plate != null && Boolean.TRUE.equals(invokeSwivel(bearing, "isAssembled")),
                "A real Sable sublevel and its plate must be attached to the bearing");
            h.assertTrue(level.getBlockState(target).getValue(locked), "By default a bearing without signal is locked");
            adapter.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize the actual serial source");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "swivel-status", "status", java.util.Map.of(), reply -> {
                h.assertValueEqual(reply.field("code"), text("ok"), "Read the original setting over the cable");
                var state = (DataValue.RecordValue)reply.field("state");
                var args = java.util.Map.of("token", state.field("token"), "field", text("locking_mode"), "value", text("unlocked_default"));
                exchange(h, sender, port, "swivel-set", "set", args, applied -> {
                    h.assertValueEqual(applied.field("code"), text("applied"), "The native policy changes over the finite channel");
                    h.startSequence().thenWaitUntil(() -> h.assertTrue(!level.getBlockState(target).getValue(locked),
                        "No input now leaves the bearing unlocked"))
                        .thenExecute(() -> {
                            h.assertTrue(!level.hasNeighborSignal(target), "Configuration does not create a redstone signal");
                            level.setBlockAndUpdate(power, Blocks.REDSTONE_BLOCK.defaultBlockState());
                        }).thenWaitUntil(() -> h.assertTrue(level.getBlockState(target).getValue(locked),
                            "An actual signal locks the bearing according to the new native policy"))
                        .thenExecute(() -> {
                            h.assertValueEqual(invokeSwivel(bearing, "getSubLevelID"), body, "Keep the original attached body");
                            h.assertValueEqual(invokeSwivel(bearing, "getPlatePos"), plate, "Keep the original plate");
                            h.assertTrue(Boolean.TRUE.equals(invokeSwivel(bearing, "isAssembled")), "Locking does not disassemble the bearing");
                            exchange(h, sender, port, "swivel-stale", "set", args, stale -> {
                                h.assertValueEqual(stale.field("code"), text("version_conflict"), "A used token cannot replay a policy write");
                                h.assertValueEqual(ComponentSettings.observe(level, target).orElseThrow().value(), text("unlocked_default"),
                                    "Keep the actual changed option");
                                invokeSwivel(bearing, "disassemble");
                                h.succeed();
                            });
                        });
                });
            }));
        });
    }

    private static Object invokeSwivel(net.minecraft.world.level.block.entity.BlockEntity bearing, String method) {
        try {
            return bearing.getClass().getMethod(method).invoke(bearing);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Native Simulated swivel fixture: " + method, e);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void accumulatorSettingsUseNativeCountAndPolarity(GameTestHelper h) {
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        var id = net.minecraft.resources.ResourceLocation.parse("simulated:redstone_accumulator");
        if (!registry.containsKey(id)) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required profile must load the accumulator");
            h.succeed();
            return;
        }
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 4, 3));
        var output = origin.east(3);
        var target = output.east();
        var lamp = target.south();
        var power = target.north();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++) level.setBlockAndUpdate(origin.east(x),
            com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(target.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(lamp, Blocks.REDSTONE_LAMP.defaultBlockState());
        level.setBlockAndUpdate(target, registry.get(id).defaultBlockState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        var interval = new DataValue.IntegerValue(26);
        var lit = net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT;
        h.runAfterDelay(8, () -> {
            var settings = ComponentSettings.observe(level, target);
            h.assertTrue(settings.isPresent(), "The native accumulator exposes its player-configurable interval");
            h.assertValueEqual(ComponentSettings.fields(settings.orElseThrow()), java.util.List.of("interval", "inverted"),
                "Expose only the two native player settings");
            h.assertValueEqual(ComponentSettings.plan(level, target, settings.orElseThrow(), new DataValue.IntegerValue(1)).code(),
                "value_out_of_range", "Native minimum is not silently clamped");
            h.assertTrue(ComponentSettings.observe(level, target, "output_signal").isEmpty(), "Do not expose the current counter");
            adapter.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Player grants the actual finite serial source");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "accumulator-interval", "status", java.util.Map.of(), read -> {
                h.assertValueEqual(read.field("code"), text("ok"), "Read interval through public serial");
                var snapshot = (DataValue.RecordValue)read.field("state");
                exchange(h, sender, port, "accumulator-set", "set", java.util.Map.of("token", snapshot.field("token"),
                    "field", text("interval"), "value", interval), applied -> {
                        h.assertValueEqual(applied.field("code"), text("applied"), "Write the native interval");
                        h.assertValueEqual(level.getSignal(target, Direction.NORTH), 0, "Changing an idle interval does not add a count");
                        level.setBlockAndUpdate(power, Blocks.REDSTONE_BLOCK.defaultBlockState());
                        h.startSequence().thenExecuteAfter(16, () ->
                            h.assertValueEqual(level.getSignal(target, Direction.NORTH), 0, "Input held beyond the old interval must not tick early"))
                            .thenWaitUntil(() -> h.assertValueEqual(level.getSignal(target, Direction.NORTH), 1, "Native count ticks at the new interval"))
                            .thenExecute(() -> {
                                level.setBlockAndUpdate(power, Blocks.AIR.defaultBlockState());
                                h.assertTrue(level.getBlockState(lamp).getValue(lit), "Downstream lamp sees the first native count");
                                exchange(h, sender, port, "accumulator-polarity", "status", java.util.Map.of("field", text("inverted")), polarity -> {
                                    h.assertValueEqual(polarity.field("code"), text("ok"), "Read the separate polarity setting");
                                    var state = (DataValue.RecordValue)polarity.field("state");
                                    int nativeProgress = level.getBlockEntity(target).saveWithoutMetadata(level.registryAccess()).getInt("DelayTicks");
                                    var args = java.util.Map.of("token", state.field("token"), "field", (DataValue)text("inverted"),
                                        "value", new DataValue.BooleanValue(true));
                                    exchange(h, sender, port, "accumulator-invert", "set", args, inverted -> {
                                        h.assertValueEqual(inverted.field("code"), text("applied"), "Use native polarity, not an output write");
                                        h.assertValueEqual(level.getSignal(target, Direction.NORTH), 14, "The real output is now 15 - 1");
                                        var nbt = level.getBlockEntity(target).saveWithoutMetadata(level.registryAccess());
                                        h.assertValueEqual(nbt.getInt("OutputSignal"), 1, "The native accumulated count is unchanged");
                                        h.assertValueEqual(nbt.getInt("DelayTicks"), nativeProgress, "Retain native idle timing progress");
                                        h.assertValueEqual(ComponentSettings.observe(level, target).orElseThrow().value(), interval,
                                            "Polarity does not alter interval");
                                        exchange(h, sender, port, "accumulator-stale", "set", args, stale -> {
                                            h.assertValueEqual(stale.field("code"), text("version_conflict"), "A used setting token cannot replay");
                                            h.succeed();
                                        });
                                    });
                                });
                            });
                    });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void heatedAeroVentAppliesSerialTargetWithoutBypassingBoiler(GameTestHelper h) {
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        var id = net.minecraft.resources.ResourceLocation.parse("aeronautics:steam_vent");
        if (!registry.containsKey(id)) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required fixture must load " + id);
            h.succeed();
            return;
        }
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 6, 5));
        var output = origin.east(3);
        var ventPos = output.east();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++) {
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        }
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        for (int y = 1; y <= 4; y++) level.setBlockAndUpdate(ventPos.below(y), AllBlocks.FLUID_TANK.getDefaultState());
        var heatPos = ventPos.below(5);
        level.setBlockAndUpdate(heatPos, AllBlocks.BLAZE_BURNER.getDefaultState().setValue(
            com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HEAT_LEVEL,
            com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel.KINDLED));
        level.setBlockAndUpdate(ventPos, registry.get(id).defaultBlockState());
        var power = ventPos.north();
        level.setBlockAndUpdate(power, Blocks.REDSTONE_BLOCK.defaultBlockState());
        var vent = level.getBlockEntity(ventPos);
        var top = (com.simibubi.create.content.fluids.tank.FluidTankBlockEntity)level.getBlockEntity(ventPos.below());
        var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.startSequence().thenWaitUntil(() -> {
            var tank = top.getControllerBE();
            h.assertTrue(tank != null && tank.getTotalTankSize() == 4 && tank.boiler.isActive() && tank.boiler.activeHeat > 0,
                "Native heated four-block boiler must form");
        }).thenExecute(() -> {
            var tank = top.getControllerBE();
            h.assertTrue(nativeGasOutput(vent) == 0, "Heat and a redstone signal without any water cannot provide steam");
            // Supply bounded water through Create's public fluid input, not by replacing boiler internals.
            supplyBoilerWater(h, tank, () -> level.getBlockState(heatPos).is(AllBlocks.BLAZE_BURNER.get()));
            adapter.getBlockState().useWithoutItem(level, actor,
                new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize this serial source");
        }).thenWaitUntil(() -> h.assertTrue(nativeGasOutput(vent) > 0 && top.getControllerBE().boiler.waterSupply >= 10,
            "Native boiler must sample the supplied water before the vent can produce steam"))
            .thenExecute(() -> exchange(h, sender, port, "hot-vent-status", "status", java.util.Map.of(), reply -> {
                var state = (DataValue.RecordValue)reply.field("state");
                var oldTarget = ((DataValue.IntegerValue)state.field("value")).value();
                var target = new DataValue.IntegerValue(oldTarget / 2);
                var before = nativeGasOutput(vent);
                h.assertTrue(oldTarget > 1 && before > 0, "Verify nonzero steam at the existing target");
                var args = java.util.Map.of("token", state.field("token"), "field", (DataValue)text("target_volume"),
                    "value", target);
                exchange(h, sender, port, "hot-vent-set", "set", args, applied -> {
                    h.assertValueEqual(applied.field("code"), text("applied"), "Set the real vent target by finite serial");
                    h.assertValueEqual(ComponentSettings.observe(level, ventPos).orElseThrow().value(), target,
                        "The native scroll setting holds the new target");
                    h.assertTrue(Math.abs(nativeGasOutput(vent) - before * target.value() / oldTarget) < 0.01,
                        "Native gas output follows the new target at unchanged heat, water and signal");
                    level.setBlockAndUpdate(power, Blocks.AIR.defaultBlockState());
                    h.startSequence().thenWaitUntil(() -> h.assertTrue(nativeGasOutput(vent) == 0,
                        "Removing native redstone must shut off steam even while heated and fed"))
                        .thenExecute(() -> {
                            level.setBlockAndUpdate(heatPos, Blocks.STONE.defaultBlockState());
                            level.setBlockAndUpdate(power, Blocks.REDSTONE_BLOCK.defaultBlockState());
                        }).thenWaitUntil(() -> {
                            h.assertTrue(top.getControllerBE().boiler.activeHeat == 0, "Native boiler detects heat removal");
                            h.assertTrue(nativeGasOutput(vent) == 0, "The setting must not substitute for a missing heat source");
                            h.assertValueEqual(ComponentSettings.observe(level, ventPos).orElseThrow().value(), target,
                                "Neither native shutoff erases the configured target");
                        }).thenSucceed();
                });
            }));
    }

    private static void supplyBoilerWater(GameTestHelper h,
            com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank,
            java.util.function.BooleanSupplier supplyEnabled) {
        if (!supplyEnabled.getAsBoolean()) return;
        var input = h.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,
            tank.getBlockPos(), Direction.SOUTH);
        h.assertTrue(input != null, "Native boiler has a fluid input");
        var water = new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 100);
        h.assertValueEqual(input.fill(water, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE),
            100, "The boiler accepts this fixture's measured water supply");
        h.runAfterDelay(5, () -> supplyBoilerWater(h, tank, supplyEnabled));
    }

    private static double nativeGasOutput(net.minecraft.world.level.block.entity.BlockEntity vent) {
        try {
            return ((Number)vent.getClass().getMethod("getGasOutput").invoke(vent)).doubleValue();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to read native Aero steam output", e);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void poweredAeroPropellerReversesNativeThrustWithoutReassembly(GameTestHelper h) {
        poweredAeroThrust(h, "propeller_bearing");
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void poweredAeroGyroPropellerReversesNativeThrustWithoutReassembly(GameTestHelper h) {
        poweredAeroThrust(h, "gyroscopic_propeller_bearing");
    }

    private static void poweredAeroThrust(GameTestHelper h, String blockName) {
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        var id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("aeronautics", blockName);
        if (!registry.containsKey(id)) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required fixture must load " + id);
            h.succeed();
            return;
        }
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 5, 5));
        var output = origin.east(3);
        var target = output.east();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(target, registry.get(id).defaultBlockState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, Direction.NORTH));
        level.setBlockAndUpdate(target.south(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(com.simibubi.create.content.kinetics.motor.CreativeMotorBlock.FACING, Direction.NORTH));
        var motor = (com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity)level.getBlockEntity(target.south());
        motor.initialize();
        motor.generatedSpeed.setValue(16);
        motor.updateGeneratedRotation();
        var sail = AllBlocks.SAIL.getDefaultState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, Direction.SOUTH);
        for (int offset = -1; offset <= 1; offset++) level.setBlockAndUpdate(target.north().offset(offset, 0, 0), sail);
        var bearing = (com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity)level.getBlockEntity(target);
        var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.runAfterDelay(10, () -> {
            if (!bearing.isRunning()) bearing.assemble(); // Fixture setup via the native assembly, never via the setting port.
            h.assertTrue(bearing.isRunning(), "Native propeller fixture actually assembled");
            var rotor = bearing.getMovedContraption();
            h.startSequence().thenWaitUntil(() -> h.assertTrue(bearing.getSpeed() != 0 && Math.abs(nativeThrust(bearing)) > 0.001,
                "An actual powered rotor must produce nonzero native thrust"))
                .thenExecute(() -> {
                    adapter.getBlockState().useWithoutItem(level, actor,
                        new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
                    actor.grant();
                    h.assertValueEqual(actor.client.code(), "configured", "Authorize the actual serial source");
                    h.runAfterDelay(3, () -> exchange(h, sender, port, "propeller-status", "status", java.util.Map.of(), reply -> {
                        var state = (DataValue.RecordValue)reply.field("state");
                        h.assertValueEqual(state.field("value"), text("right_handed"), "Read the powered bearing's real option");
                        double oldThrust = nativeThrust(bearing);
                        float oldSpeed = bearing.getSpeed();
                        var args = java.util.Map.of("token", state.field("token"), "field", (DataValue)text("thrust_direction"),
                            "value", text("left_handed"));
                        exchange(h, sender, port, "propeller-set", "set", args, applied -> {
                            h.assertValueEqual(applied.field("code"), text("applied"), "Use a bounded serial setting write under power");
                            h.assertTrue(bearing.isRunning() && bearing.getMovedContraption() == rotor,
                                "The setting must not reassemble, detach or stop the rotor");
                            h.assertValueEqual(bearing.getSpeed(), oldSpeed, "Do not change the real kinetic input");
                            double reversed = nativeThrust(bearing);
                            h.assertTrue(Math.signum(reversed) == -Math.signum(oldThrust)
                                && Math.abs(reversed + oldThrust) < Math.abs(oldThrust) * 0.02,
                                "Native thrust must reverse at the same drive speed: " + oldThrust + " to " + reversed);
                            exchange(h, sender, port, "propeller-stale", "set", args, stale -> {
                                h.assertValueEqual(stale.field("code"), text("version_conflict"), "Do not replay the setting token");
                                h.assertTrue(bearing.getMovedContraption() == rotor && Math.signum(nativeThrust(bearing)) == Math.signum(reversed),
                                    "Stale writes do not change thrust or replace the rotor");
                                bearing.disassemble();
                                h.succeed();
                            });
                        });
                    }));
                });
        });
    }

    private static double nativeThrust(com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity bearing) {
        try { return ((Number)bearing.getClass().getMethod("getThrust").invoke(bearing)).doubleValue(); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Locked optional Aero build must expose native thrust", failure); }
    }


    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void steeringLimitUsesNativeManualMotionButDoesNotDriveTheWheel(GameTestHelper h) {
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        var id = net.minecraft.resources.ResourceLocation.parse("simulated:steering_wheel");
        if (!registry.containsKey(id)) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required profile must load the steering wheel");
            h.succeed();
            return;
        }
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 4, 3));
        var output = origin.east(3);
        var pos = output.east();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(pos, registry.get(id).defaultBlockState());
        var wheel = (com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity)level.getBlockEntity(pos);
        var stateBefore = level.getBlockState(pos);
        var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.runAfterDelay(10, () -> {
            var nativeLimit = BlockEntityBehaviour.get(wheel, ScrollValueBehaviour.TYPE);
            h.assertTrue(nativeLimit != null, "The native wheel has a scroll limit");
            var bounds = (com.nobodiiiii.createbiotech.mixin.BiocomputingScrollRangeAccess)nativeLimit;
            var original = nativeLimit.getValue();
            h.assertTrue(original > 60 && bounds.createBiotech$min() <= 60, "The fixture has room to lower the limit");
            adapter.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Allow this physical wheel through its authorized port");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "wheel-status", "status", java.util.Map.of(), reply -> {
                var observed = (DataValue.RecordValue)reply.field("state");
                h.assertValueEqual(observed.field("field"), text("travel_angle"), "The declared field is the manual travel limit");
                h.assertValueEqual(observed.field("unit"), text("degree"), "Use the native angle unit");
                h.assertTrue(ComponentSettings.observe(level, pos, "target_angle").isEmpty(), "No remote steering target");
                h.assertTrue(ComponentSettings.observe(level, pos, "speed").isEmpty(), "No remote rotation command");
                var token = observed.field("token");
                exchange(h, sender, port, "invalid-limit", "set", java.util.Map.of("token", token, "field", text("travel_angle"),
                    "value", new DataValue.IntegerValue((long)bounds.createBiotech$max() + 1)), denied -> {
                    h.assertValueEqual(denied.field("code"), text("value_out_of_range"), "Do not silently clamp the configuration");
                    h.assertValueEqual(nativeLimit.getValue(), original, "Rejected requests leave the limit alone");
                    exchange(h, sender, port, "set-travel-limit", "set", java.util.Map.of("token", token,
                        "field", text("travel_angle"), "value", new DataValue.IntegerValue(60)), applied -> {
                            h.assertValueEqual(applied.field("code"), text("applied"), "Configure the target via actual bounded serial");
                            h.assertValueEqual(nativeLimit.getValue(), 60, "The real native range changed");
                            h.assertValueEqual(level.getBlockState(pos), stateBefore, "The adapter did not reorient the wheel");
                            h.assertValueEqual(wheel.getSpeed(), 0f, "Configuration generates no shaft rotation");
                            h.assertValueEqual(wheel.getGeneratedSpeed(), 0f, "Configuration is not a steering action");
                            var atRest = ComponentSettings.observe(level, pos).orElseThrow();
                            // Exercise the method used by Simulated for an actual steering input, not by our adapter.
                            try {
                                wheel.getClass().getMethod("updateTargetAngle", float.class).invoke(wheel, 120f);
                                h.assertValueEqual(wheel.getClass().getField("targetAngle").getFloat(wheel), 60f,
                                    "Native steering clamps manual demand to the new limit");
                            } catch (ReflectiveOperationException e) {
                                throw new AssertionError("Expected Simulated steering API was not available", e);
                            }
                            h.assertTrue(wheel.getGeneratedSpeed() != 0, "Real native steering drives the wheel");
                            h.assertValueEqual(ComponentSettings.plan(level, pos, atRest, new DataValue.IntegerValue(90)).code(),
                                "busy", "A configuration write cannot alter the limit during native drive");
                            h.assertValueEqual(nativeLimit.getValue(), 60, "The in-flight manual action keeps its original limit");
                            h.succeed();
                        });
                });
            }));
        });
    }


    @GameTest(template = "empty", timeoutTicks = 100)
    public static void optionalSimulatedTorsionSpringExposesItsConfiguredTravelAngle(GameTestHelper h) {
        var id = net.minecraft.resources.ResourceLocation.parse("simulated:torsion_spring");
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        if (!registry.containsKey(id)) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required profile must load the torsion spring");
            h.succeed();
            return;
        }
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(3, 2, 3));
        level.setBlockAndUpdate(pos, registry.get(id).defaultBlockState());
        h.runAfterDelay(5, () -> {
            var nativeAngle = BlockEntityBehaviour.get(level, pos, ScrollValueBehaviour.TYPE);
            h.assertTrue(nativeAngle != null, "The real torsion spring has an angle setting");
            var observed = ComponentSettings.observe(level, pos).orElse(null);
            h.assertTrue(observed != null, "The output must expose the native travel-angle setting");
            h.assertValueEqual(observed.field().name(), "travel_angle", "Configuration is distinct from actual motion");
            h.assertValueEqual(observed.field().unit(), "degree", "Use the native unit");
            h.assertValueEqual(observed.field().format(), new ComponentSettings.IntRange(1, 360), "Native angle range");
            h.assertValueEqual(observed.value(), new DataValue.IntegerValue(nativeAngle.getValue()), "Expose the actual setting");
            h.assertValueEqual(ComponentSettings.plan(level, pos, observed, new DataValue.IntegerValue(361)).code(),
                "value_out_of_range", "Do not silently clamp an out-of-range request");
            h.assertTrue(ComponentSettings.plan(level, pos, observed, new DataValue.IntegerValue(135)).apply(),
                "Update the same native setting used by the player");
            h.assertValueEqual(nativeAngle.getValue(), 135, "Native spring received the new configured angle");
            h.assertValueEqual(ComponentSettings.plan(level, pos, observed, new DataValue.IntegerValue(180)).code(),
                "version_conflict", "An older observation cannot overwrite the new setting");
            h.assertTrue(ComponentSettings.observe(level, pos, "actual_angle").isEmpty(), "Do not expose writes to physical position");
            h.assertTrue(ComponentSettings.observe(level, pos, "powered").isEmpty(), "Do not expose writes to redstone state");
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void optionalOffroadWheelMountExposesOnlyItsNativeSuspensionSetting(GameTestHelper h) {
        var id = net.minecraft.resources.ResourceLocation.parse("offroad:wheel_mount");
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        if (!registry.containsKey(id)) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required profile must load Offroad");
            h.succeed();
            return;
        }
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(3, 2, 3));
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos, registry.get(id).defaultBlockState());
        h.runAfterDelay(5, () -> {
            var before = ComponentSettings.observe(level, pos).orElse(null);
            h.assertTrue(before != null, "Installed wheel mount exposes its native suspension strength");
            h.assertValueEqual(before.field().name(), "suspension_strength", "Native control, not extension or steering state");
            var nativeControl = BlockEntityBehaviour.get(level, pos, ScrollValueBehaviour.TYPE);
            var range = (ComponentSettings.IntRange)before.field().format();
            h.assertValueEqual(range.minimum(), 5, "Locked Offroad minimum");
            h.assertValueEqual(range.maximum(), 180, "Locked Offroad maximum");
            h.assertValueEqual(ComponentSettings.plan(level, pos, before, new DataValue.IntegerValue(181)).code(),
                "value_out_of_range", "Reject rather than silently clamping");
            h.assertTrue(ComponentSettings.plan(level, pos, before, new DataValue.IntegerValue(55)).apply(),
                "Set the same native control that the player uses");
            h.assertValueEqual(nativeControl.getValue(), 55, "Actual native setting updated");
            h.assertTrue(ComponentSettings.observe(level, pos, "steering").isEmpty(), "Do not fake a steering signal");
            h.assertTrue(ComponentSettings.observe(level, pos, "extension").isEmpty(), "Do not set physical suspension travel");
            h.assertTrue(!level.hasNeighborSignal(pos), "Settings do not produce a redstone signal");
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void optionalAeroBurnerUsesItsConfiguredVolumeRange(GameTestHelper h) {
        var id = net.minecraft.resources.ResourceLocation.parse("aeronautics:adjustable_burner");
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        if (!registry.containsKey(id)) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "Aero-required profile did not load the real burner");
            h.succeed();
            return;
        }
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(3, 2, 3));
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos, registry.get(id).defaultBlockState());
        h.runAfterDelay(5, () -> {
            var before = ComponentSettings.observe(level, pos).orElse(null);
            h.assertTrue(before != null, "Aeronautics burner has an explicit settings adapter");
            h.assertValueEqual(before.field().name(), "target_volume", "Expose native volume setting only");
            h.assertValueEqual(before.field().unit(), "cubic_metre", "Declared unit");
            var range = (ComponentSettings.IntRange)before.field().format();
            h.assertTrue(range.maximum() > range.minimum(), "Use the actual server-configured range");
            h.assertValueEqual(ComponentSettings.plan(level, pos, before, new DataValue.IntegerValue((long)range.maximum() + 1)).code(),
                "value_out_of_range", "Reject rather than letting Aero clamp");
            int next = before.value().equals(new DataValue.IntegerValue(range.minimum())) ? range.maximum() : range.minimum();
            h.assertTrue(ComponentSettings.plan(level, pos, before, new DataValue.IntegerValue(next)).apply(), "Call the real Aero setting");
            h.assertValueEqual(ComponentSettings.observe(level, pos).orElseThrow().value(), new DataValue.IntegerValue(next), "Native readback");
            h.assertTrue(!level.hasNeighborSignal(pos), "No synthetic redstone power");
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void thresholdGroupUsesRealInventoryAndNativeEdges(GameTestHelper h) {
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(3, 2, 3));
        var barrelPos = pos.east();
        level.setBlockAndUpdate(barrelPos, Blocks.BARREL.defaultBlockState());
        var barrel = (net.minecraft.world.level.block.entity.BarrelBlockEntity)level.getBlockEntity(barrelPos);
        var gold = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT, 12);
        barrel.setItem(0, gold);
        var state = AllBlocks.THRESHOLD_SWITCH.getDefaultState()
            .setValue(com.simibubi.create.content.redstone.DirectedDirectionalBlock.TARGET, net.minecraft.world.level.block.state.properties.AttachFace.WALL)
            .setValue(com.simibubi.create.content.redstone.DirectedDirectionalBlock.FACING, Direction.EAST);
        level.setBlockAndUpdate(pos, state);
        var threshold = (com.simibubi.create.content.redstone.thresholdSwitch.ThresholdSwitchBlockEntity)level.getBlockEntity(pos);
        h.runAfterDelay(8, () -> {
            var before = ComponentSettings.observe(level, pos).orElse(null);
            h.assertTrue(before != null, "Threshold switch exposes grouped settings");
            h.assertValueEqual(before.field().unit(), "item", "Native inventory is measured in items");
            h.assertValueEqual(threshold.getStockLevel(), 12, "Real measured amount");
            h.assertTrue(!threshold.isPowered(), "Initially below the default threshold");
            var rejected = ComponentSettings.plan(level, pos, before, thresholdPair(10, 10));
            h.assertValueEqual(rejected.code(), "invalid_threshold_order", "Do not accept a contradictory hysteresis pair");
            h.assertTrue(!rejected.apply() && threshold.offWhenBelow == 64 && threshold.onWhenAbove == 128, "Invalid write leaves both unchanged");
            h.assertTrue(ComponentSettings.plan(level, pos, before, thresholdPair(5, 10)).apply(), "Update both values together");
            h.assertValueEqual(threshold.getStockLevel(), 12, "Settings do not replace the measurement");
            h.assertTrue(!threshold.isPowered(), "No synthetic output before the native edge");
            var invert = new DataValue.BooleanValue(true);
            var polarity = ComponentSettings.observe(level, pos, "inverted").orElse(null);
            h.assertTrue(polarity != null, "Threshold switch exposes its separate native inversion control");
            h.assertTrue(ComponentSettings.fields(before).contains("inverted"), "The public settings list advertises inversion");
            h.assertValueEqual(ComponentSettings.plan(level, pos, polarity, invert).code(), "busy",
                "Native setInverted would apply the pending edge early; refuse it while an edge is queued");
            h.runAfterDelay(5, () -> {
                h.assertTrue(threshold.isPowered(), "Real stock reaches the new threshold through the native edge");
                h.assertValueEqual(barrel.getItem(0).getCount(), 12, "Nothing created or consumed");
                barrel.removeItem(0, 8);
                h.runAfterDelay(20, () -> {
                    h.assertTrue(!threshold.isPowered(), "Native observation drives the off edge when stock falls below the lower threshold");
                    h.assertValueEqual(barrel.getItem(0).getCount(), 4, "Only the explicit test removal changed the stock");
                    var idlePolarity = ComponentSettings.observe(level, pos, "inverted").orElseThrow();
                    h.assertValueEqual(idlePolarity.value(), new DataValue.BooleanValue(false), "Busy rejection never queues");
                    h.assertTrue(ComponentSettings.plan(level, pos, idlePolarity, invert).apply(), "Use native inversion once the pending edge ends");
                    h.assertTrue(threshold.isInverted() && threshold.isPowered(), "Only the native output polarity is reversed");
                    h.assertTrue(!threshold.getState(), "Logical stock threshold result remains below the cutoff");
                    h.assertValueEqual(ComponentSettings.observe(level, pos).orElseThrow().value(), thresholdPair(5, 10),
                        "Both configured bounds remain unchanged");
                    h.assertValueEqual(threshold.getStockLevel(), 4, "Inversion does not change the measured quantity");
                    h.succeed();
                });
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void thresholdFluidInversionDoesNotAdvancePendingNativeEdge(GameTestHelper h) {
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(3, 2, 3));
        var tank = pos.east();
        level.setBlockAndUpdate(tank, AllBlocks.FLUID_TANK.getDefaultState());
        level.setBlockAndUpdate(pos, AllBlocks.THRESHOLD_SWITCH.getDefaultState()
            .setValue(com.simibubi.create.content.redstone.DirectedDirectionalBlock.TARGET,
                net.minecraft.world.level.block.state.properties.AttachFace.WALL)
            .setValue(com.simibubi.create.content.redstone.DirectedDirectionalBlock.FACING, Direction.EAST));
        var threshold = (com.simibubi.create.content.redstone.thresholdSwitch.ThresholdSwitchBlockEntity)level.getBlockEntity(pos);
        h.runAfterDelay(5, () -> {
            var fluid = level.getCapability(Capabilities.FluidHandler.BLOCK, tank, Direction.UP);
            h.assertTrue(fluid != null, "The actual tank exposes a fluid capability");
            h.assertValueEqual(fluid.fill(new FluidStack(Fluids.WATER, 1250), FluidAction.EXECUTE), 1250, "Fill the tank through its native input");
            h.runAfterDelay(20, () -> {
                var before = ComponentSettings.observe(level, pos).orElseThrow();
                h.assertValueEqual(before.field().unit(), "millibucket", "Advertise the actual fluid unit");
                h.assertValueEqual(threshold.getStockLevel(), 1250, "Measure actual water rather than item units");
                h.assertTrue(threshold.isPowered(), "Existing native redstone edge has fired");
                var bounds = thresholdPair(1400, 1600);
                h.assertTrue(ComponentSettings.plan(level, pos, before, bounds).apply(), "Set both fluid thresholds");
                h.assertTrue(!threshold.getState() && threshold.isPowered(), "Fluid level schedules a falling edge, but output is still high");
                var invert = new DataValue.BooleanValue(true);
                var pending = ComponentSettings.plan(level, pos, ComponentSettings.observe(level, pos, "inverted").orElseThrow(), invert);
                h.assertValueEqual(pending.code(), "busy", "Do not jump the pending fluid edge");
                h.assertTrue(!pending.apply() && threshold.isPowered(), "Busy rejection leaves the scheduled output alone");
                h.runAfterDelay(5, () -> {
                    h.assertTrue(!threshold.isPowered() && !threshold.isInverted(), "The native edge fired, and the refused write did not queue");
                    var idle = ComponentSettings.observe(level, pos, "inverted").orElseThrow();
                    h.assertTrue(ComponentSettings.plan(level, pos, idle, invert).apply(), "The explicitly retried write can invert the idle output");
                    h.assertTrue(threshold.isPowered() && !threshold.getState(), "Only the redstone output is inverted");
                    h.assertValueEqual(ComponentSettings.observe(level, pos).orElseThrow().value(), bounds, "Inversion preserves both fluid thresholds");
                    var remaining = fluid.getFluidInTank(0);
                    h.assertTrue(remaining.is(Fluids.WATER) && remaining.getAmount() == 1250,
                        "Changing configuration does not change the fluid");
                    h.succeed();
                });
            });
        });
    }

    private static DataValue.RecordValue thresholdPair(int off, int on) {
        return ComponentSettingsProtocol.record(java.util.Map.of("off_below", new DataValue.IntegerValue(off),
            "on_above", new DataValue.IntegerValue(on)));
    }

    @GameTest(template = "empty", timeoutTicks = 1300)
    public static void onePortSelectsNamedNativeFieldsAndBindsEachToken(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 2, 3));
        var output = origin.east(3);
        var target = output.east();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(target.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(target, AllBlocks.PULSE_EXTENDER.getDefaultState().setValue(BrassDiodeBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var block = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = block.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.runAfterDelay(4, () -> {
            var hit = new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false);
            block.getBlockState().useWithoutItem(level, actor, hit);
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Authorize one source on the same general-purpose port");
            // Wait for the newly opened adapter to be present in the medium snapshot before transmitting.
            h.runAfterDelay(3, () -> exchange(h, sender, port, "default", "status", java.util.Map.of(), read -> {
                h.assertValueEqual(read.field("code"), text("ok"), "Old clients can still query with no fields");
                var defaultState = (DataValue.RecordValue)read.field("state");
                h.assertValueEqual(defaultState.field("field"), text("interval"), "Original default setting retained");
                h.assertValueEqual(defaultState.field("fields"), new DataValue.ListValue(java.util.List.of(text("interval"), text("inverted"))),
                    "Driver can discover the named settings");
                var wrongField = java.util.Map.of("token", defaultState.field("token"), "field", (DataValue)text("inverted"),
                    "value", new DataValue.BooleanValue(true));
                exchange(h, sender, port, "wrong-field", "set", wrongField, rejected -> {
                    h.assertValueEqual(rejected.field("code"), text("unknown_setting"), "Interval token cannot set inversion");
                    exchange(h, sender, port, "read-inversion", "status", java.util.Map.of("field", text("inverted")), named -> {
                        h.assertValueEqual(named.field("code"), text("ok"), "Query a different native setting without rewiring");
                        var selected = (DataValue.RecordValue)named.field("state");
                        h.assertValueEqual(selected.field("value"), new DataValue.BooleanValue(false), "Native inversion was not changed by rejected request");
                        var inversion = java.util.Map.of("token", selected.field("token"), "field", (DataValue)text("inverted"),
                            "value", new DataValue.BooleanValue(true));
                        exchange(h, sender, port, "apply-inversion", "set", inversion, applied -> {
                            h.assertValueEqual(applied.field("code"), text("applied"), "Named conditional write reaches the actual diode");
                            h.assertTrue(level.getBlockState(target).getValue(BrassDiodeBlock.INVERTED), "Read native block state");
                            h.assertValueEqual(level.getSignal(target, Direction.NORTH), 15, "Native inversion changes real redstone");
                            h.succeed();
                        });
                    });
                });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1300)
    public static void actualSerialPortAuthorizesOnePlacementAndRejectsOldReads(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 2, 3));
        var output = origin.east(3);
        var target = output.east();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        for (int x = 1; x < 3; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(target.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(target, Blocks.REPEATER.defaultBlockState());
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var block = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = block.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(output));
        sender.serialDevice().configure("s0", Direction.EAST, true);
        h.runAfterDelay(4, () -> {
            h.assertValueEqual(port.view().field("line"), text("closed"), "New output starts closed");
            var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(output), Direction.UP, output, false);
            h.assertValueEqual(block.getBlockState().useWithoutItem(level, actor, hit),
                net.minecraft.world.InteractionResult.CONSUME, "Registered menu opens");
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Grant is a captured, reachable port selection");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "read", "status", java.util.Map.of(), read -> {
                h.assertValueEqual(read.field("code"), text("ok"), "Settings published over real serial");
                var observed = (DataValue.RecordValue)read.field("state");
                h.assertValueEqual(observed.field("value"), new DataValue.IntegerValue(1), "Actual current delay");
                var args = java.util.Map.of("token", observed.field("token"), "field", (DataValue)text("delay"),
                    "value", new DataValue.IntegerValue(4));
                exchange(h, sender, port, "apply", "set", args, applied -> {
                    h.assertValueEqual(applied.field("code"), text("applied"), "Real configuration applied");
                    h.assertValueEqual(level.getBlockState(target).getValue(RepeaterBlock.DELAY), 4, "Target changed via wire");
                    exchange(h, sender, port, "repeat", "set", args, old -> {
                        h.assertValueEqual(old.field("code"), text("version_conflict"), "No replay of a consumed token");
                        level.removeBlock(target, false);
                        level.setBlockAndUpdate(target, Blocks.REPEATER.defaultBlockState());
                        exchange(h, sender, port, "replacement", "status", java.util.Map.of(), replaced -> {
                            h.assertValueEqual(replaced.field("code"), text("target_changed"), "Even identical new repeater needs authorization");
                            h.assertValueEqual(replaced.field("state"), DataValue.NullValue.INSTANCE, "No token for replacement");
                            h.succeed();
                        });
                    });
                });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void setsOnlyNativeStandaloneNameplateText(GameTestHelper h) {
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        var id = net.minecraft.resources.ResourceLocation.parse("simulated:white_nameplate");
        if (!registry.containsKey(id)) {
            h.assertTrue(!Boolean.getBoolean("createbiotech.verifyAero"), "The Aero-required profile must load native nameplates");
            h.succeed();
            return;
        }
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 4, 3));
        var output = origin.east(3);
        var target = output.east();
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        var initial = registry.get(id).defaultBlockState().setValue(
            net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, Direction.SOUTH);
        level.setBlockAndUpdate(target.north(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(target.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(target, initial);
        level.setBlockAndUpdate(output, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var adapter = (ComponentSettingsBlockEntity)level.getBlockEntity(output);
        var port = adapter.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        var center = net.minecraft.world.phys.Vec3.atCenterOf(output);
        actor.moveTo(center);
        h.runAfterDelay(10, () -> {
            var nativePlate = level.getBlockEntity(target);
            h.assertTrue(nativePlate instanceof com.simibubi.create.content.equipment.clipboard.ClipboardCloneable,
                "Use a real Simulated nameplate");
            var before = nativePlate.saveWithoutMetadata(level.registryAccess());
            h.assertValueEqual(before.getInt("Width"), 1, "The player is configuring one isolated panel");
            adapter.getBlockState().useWithoutItem(level, actor, new net.minecraft.world.phys.BlockHitResult(center, Direction.UP, output, false));
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Bind the native panel to an actual serial port");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "nameplate-read", "status", java.util.Map.of(), read -> {
                h.assertValueEqual(read.field("code"), text("ok"), "Read the native text over the public settings port");
                var snapshot = (DataValue.RecordValue)read.field("state");
                h.assertValueEqual(snapshot.field("field"), text("label_text"), "Only the editable text is exposed");
                h.assertValueEqual(snapshot.field("value"), text(""), "Blank plate has no text");
                var args = java.util.Map.of("token", snapshot.field("token"), "field", text("label_text"), "value", text("READY"));
                exchange(h, sender, port, "nameplate-write", "set", args, applied -> {
                    h.assertValueEqual(applied.field("code"), text("applied"), "An authorized edit must reach the physical panel");
                    var nativeData = nativePlate.saveWithoutMetadata(level.registryAccess());
                    var originalFields = before.copy();
                    nativeData.remove("Name");
                    originalFields.remove("Name");
                    h.assertValueEqual(nativeData, originalFields, "No dye, glow, wax, shape or controller links are modified");
                    h.assertValueEqual(nativePlate.saveWithoutMetadata(level.registryAccess()).getString("Name"),
                        "READY", "Inspect the native durable text, not the adapter reply");
                    exchange(h, sender, port, "nameplate-stale", "set", args, stale -> {
                        h.assertValueEqual(stale.field("code"), text("version_conflict"), "An accepted native edit cannot replay");
                        var beside = target.east();
                        level.setBlockAndUpdate(beside.north(), Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(beside.below(), Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(beside, initial);
                        h.runAfterDelay(10, () -> {
                            h.assertValueEqual(nativePlate.saveWithoutMetadata(level.registryAccess()).getInt("Width"),
                                2, "A second physical plate participates in native text propagation");
                            exchange(h, sender, port, "nameplate-group", "status", java.util.Map.of(), group -> {
                                var grouped = (DataValue.RecordValue)group.field("state");
                                h.assertValueEqual(grouped.field("busy"), new DataValue.BooleanValue(true), "Grouped plate is not writable");
                                var blocked = java.util.Map.of("token", grouped.field("token"), "field", text("label_text"), "value", text("BLOCKED"));
                                exchange(h, sender, port, "nameplate-group-denied", "set", blocked, denied -> {
                                    h.assertValueEqual(denied.field("code"), text("busy"), "Never propagate a write into an adjacent panel");
                                    h.assertValueEqual(level.getBlockEntity(beside).saveWithoutMetadata(level.registryAccess()).getString("Name"),
                                        "READY", "The second plate keeps the native name");
                                    level.removeBlock(beside, false);
                                    h.runAfterDelay(10, () -> {
                                        h.assertValueEqual(nativePlate.saveWithoutMetadata(level.registryAccess()).getInt("Width"),
                                            1, "The native panel is standalone again");
                                        exchange(h, sender, port, "nameplate-group-stale", "set", blocked, retired -> {
                                            h.assertValueEqual(retired.field("code"), text("version_conflict"), "A grouped query cannot be deferred until writable");
                                            var wax = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.HONEYCOMB);
                                            actor.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, wax);
                                            var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(target),
                                                Direction.SOUTH, target, false);
                                            var waxed = level.getBlockState(target).useItemOn(wax, level, actor, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
                                            h.assertTrue(waxed.consumesAction() && nativePlate.saveWithoutMetadata(level.registryAccess()).getBoolean("Waxed"),
                                                "Use the native player protection, not a simulated flag");
                                            exchange(h, sender, port, "nameplate-wax-status", "status", java.util.Map.of(), waxReply -> {
                                                var waxSnapshot = (DataValue.RecordValue)waxReply.field("state");
                                                h.assertValueEqual(waxSnapshot.field("busy"), new DataValue.BooleanValue(true), "Report native waxing");
                                                exchange(h, sender, port, "nameplate-wax-denied", "set",
                                                    java.util.Map.of("token", waxSnapshot.field("token"), "field", text("label_text"), "value", text("LOCKED")),
                                                    locked -> {
                                                        h.assertValueEqual(locked.field("code"), text("busy"), "No write to the native waxed plate");
                                                        var saved = nativePlate.saveWithoutMetadata(level.registryAccess());
                                                        h.assertTrue(saved.getBoolean("Waxed") && saved.getString("Name").equals("READY"),
                                                            "Native text and wax remain intact after refusals");
                                                        h.succeed();
                                                    });
                                            });
                                        });
                                    });
                                });
                            });
                        });
                    });
                });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 2500)
    public static void configuresNativeDisplayLinkRowWithoutOverwritingTheBoard(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 2, 3));
        var adapter = origin.east(3);
        var linkPos = origin.east(4);
        var boardPos = linkPos.east(2);
        var otherPos = linkPos.south(2);
        var sender = com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterSerialGameTests.computer(h, origin);
        for (int x = 1; x < 3; x++)
            level.setBlockAndUpdate(origin.east(x), com.nobodiiiii.createbiotech.registry.CBBlocks.DATA_CABLE.get().defaultBlockState());
        var linkState = AllBlocks.DISPLAY_LINK.getDefaultState()
            .setValue(com.simibubi.create.content.redstone.displayLink.DisplayLinkBlock.FACING, Direction.UP);
        var textOutput = com.nobodiiiii.createbiotech.registry.CBBlocks.TEXT_OUTPUT.get().defaultBlockState();
        level.setBlockAndUpdate(linkPos.below(), textOutput);
        level.setBlockAndUpdate(otherPos.below(), textOutput);
        level.setBlockAndUpdate(linkPos, linkState);
        level.setBlockAndUpdate(otherPos, linkState);
        var link = (com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity)level.getBlockEntity(linkPos);
        var other = (com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity)level.getBlockEntity(otherPos);
        link.target(boardPos); other.target(boardPos); other.targetLine = 1;
        link.activeSource = other.activeSource = com.nobodiiiii.createbiotech.registry.CBDisplaySources.TEXT_OUTPUT.get();
        level.setBlockAndUpdate(boardPos, AllBlocks.DISPLAY_BOARD.getDefaultState());
        level.setBlockAndUpdate(boardPos.above(), AllBlocks.DISPLAY_BOARD.getDefaultState());
        level.setBlockAndUpdate(adapter, com.nobodiiiii.createbiotech.registry.CBBlocks.COMPONENT_SETTINGS.get().defaultBlockState()
            .setValue(ComponentSettingsBlock.FACING, Direction.EAST));
        var block = (ComponentSettingsBlockEntity)level.getBlockEntity(adapter);
        var port = block.getBehaviour(ComponentSettingsPort.TYPE);
        var actor = new MenuPlayer(level);
        actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(adapter));
        sender.serialDevice().configure("s0", Direction.EAST, true);
        h.runAfterDelay(8, () -> {
            var tile = (com.simibubi.create.content.trains.display.FlapDisplayBlockEntity)level.getBlockEntity(boardPos);
            var board = tile.getController();
            h.assertTrue(board != null && board.getLines().size() == 4, "Real four-row display assembled");
            com.nobodiiiii.createbiotech.content.biocomputing.device.display.TextOutputGameTests.publishFixturePage(
                h, linkPos.below(), java.util.List.of("LIVE", "DO NOT USE"));
            board.applyTextManually(0, net.minecraft.network.chat.Component.literal("OLD"));
            board.applyTextManually(3, net.minecraft.network.chat.Component.literal("KEEP"));
            com.simibubi.create.api.behaviour.display.DisplayTarget.reserve(1, board,
                new com.simibubi.create.content.redstone.displayLink.DisplayLinkContext(level, other));
            var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(adapter), Direction.UP, adapter, false);
            h.assertValueEqual(block.getBlockState().useWithoutItem(level, actor, hit),
                net.minecraft.world.InteractionResult.CONSUME, "Registered menu opens");
            actor.grant();
            h.assertValueEqual(actor.client.code(), "configured", "Bound display link supports a reachable explicit grant");
            h.runAfterDelay(3, () -> exchange(h, sender, port, "row-read", "status", java.util.Map.of(), read -> {
                h.assertValueEqual(read.field("code"), text("ok"), "Read translated native row over real serial");
                var view = (DataValue.RecordValue)read.field("state");
                h.assertValueEqual(view.field("field"), text("start_row"), "Only the native start row is configurable");
                h.assertValueEqual(view.field("value"), new DataValue.IntegerValue(1), "Expose one-based row numbers");
                var binding = (DataValue.RecordValue)view.field("binding");
                h.assertValueEqual(binding.field("rows"), new DataValue.IntegerValue(4), "Report the actual bound board height");
                exchange(h, sender, port, "row-occupied", "set", rowArgs(view, 2), busy -> {
                    h.assertValueEqual(busy.field("code"), text("busy"), "Never take the other link's starting row");
                    h.assertValueEqual(link.targetLine, 0, "Rejected preparation cannot mutate the link");
                    exchange(h, sender, port, "row-apply", "set", rowArgs(view, 3), applied -> {
                        h.assertValueEqual(applied.field("code"), text("applied"), "Known vacant row before a manual barrier");
                        h.assertValueEqual(link.targetLine, 2, "Changed exactly the native starting row");
                        h.assertValueEqual(link.getTargetPosition(), boardPos, "Never rebind the display link");
                        h.assertValueEqual(board.getLines().get(0).getSections().getFirst().getText().getString(), "OLD", "Preserve old row");
                        h.assertValueEqual(board.getLines().get(3).getSections().getFirst().getText().getString(), "KEEP", "Preserve manual row");
                        com.nobodiiiii.createbiotech.content.biocomputing.device.display.TextOutputGameTests.powerBoard(level, board);
                        h.assertTrue(board.isSpeedRequirementFulfilled(), "Native board is powered by real kinetics");
                        link.tickSource();
                        h.assertValueEqual(board.getLines().get(2).getSections().getFirst().getText().getString(), "LIVE", "Native refresh uses the new row");
                        h.assertValueEqual(board.getLines().get(0).getSections().getFirst().getText().getString(), "OLD", "Old row still visible");
                        h.assertValueEqual(board.getLines().get(3).getSections().getFirst().getText().getString(), "KEEP", "Second source row cannot cross manual barrier");
                        board.applyTextManually(2, net.minecraft.network.chat.Component.literal("HAND"));
                        link.tickSource();
                        h.assertValueEqual(board.getLines().get(2).getSections().getFirst().getText().getString(), "HAND", "Later manual claim also protects the first row");
                        exchange(h, sender, port, "read-before-rebind", "status", java.util.Map.of(), before -> {
                            h.assertValueEqual(before.field("code"), text("ok"), "Read actual binding");
                            var snapshot = (DataValue.RecordValue)before.field("state");
                            link.target(boardPos.above()); // Native edit of the addressed tile, even though the controller is the same.
                            exchange(h, sender, port, "set-after-rebind", "set", rowArgs(snapshot, 4), rejected -> {
                                h.assertValueEqual(rejected.field("code"), text("version_conflict"), "Binding changed after query");
                                h.assertValueEqual(link.targetLine, 2, "No write after rebind");
                                h.succeed();
                            });
                        });
                    });
                });
            }));
        });
    }

    private static java.util.Map<String, DataValue> rowArgs(DataValue.RecordValue observation, int row) {
        return java.util.Map.of("token", observation.field("token"), "field", text("start_row"),
            "value", new DataValue.IntegerValue(row));
    }

    private static DataValue.TextValue text(String value) { return ComponentSettingsProtocol.text(value); }
    private static void exchange(GameTestHelper h, com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour sender,
            ComponentSettingsPort port, String request, String operation, java.util.Map<String, DataValue> args,
            java.util.function.Consumer<DataValue.RecordValue> accept) {
        h.assertValueEqual(sender.serialDevice().send("s0", command(port, request, operation, args)), "queued", "Must traverse the finite channel");
        await(h, sender, port, request, 480, accept);
    }
    private static DataValue.RecordValue command(ComponentSettingsPort port, String request, String operation,
            java.util.Map<String, DataValue> args) {
        return ComponentSettingsProtocol.record(java.util.Map.of("kind", text("component_settings_command"), "version",
            new DataValue.IntegerValue(1), "target", port.address(), "request", text(request), "operation", text(operation),
            "arguments", ComponentSettingsProtocol.record(args)));
    }

    private static void await(GameTestHelper h, com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour sender,
            ComponentSettingsPort port, String request, int remaining, java.util.function.Consumer<DataValue.RecordValue> accept) {
        h.runAfterDelay(1, () -> {
            var reply = sender.serialDevice().poll("s0");
            if (reply.isEmpty()) {
                h.assertTrue(remaining > 0, "No reply to " + request + "; sender=" + sender.serialDevice().status("s0")
                    + "; adapter=" + port.device().status("s0") + "; last=" + port.view().field("last"));
                await(h, sender, port, request, remaining - 1, accept);
                return;
            }
            h.assertValueEqual(reply.get().field("request"), text(request), "Reply correlation");
            accept.accept(reply.get());
        });
    }
    private static final class MenuPlayer extends net.neoforged.neoforge.common.util.FakePlayer {
        ComponentSettingsMenu client;
        MenuPlayer(net.minecraft.server.level.ServerLevel level) {
            super(level, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "component-test"));
        }
        @Override public java.util.OptionalInt openMenu(net.minecraft.world.MenuProvider provider,
                java.util.function.Consumer<net.minecraft.network.RegistryFriendlyByteBuf> data) {
            containerMenu = provider.createMenu(1, getInventory(), this);
            var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try {
                data.accept(buf);
                client = com.nobodiiiii.createbiotech.registry.CBMenuTypes.COMPONENT_SETTINGS.get().create(1, getInventory(), buf);
            } finally { buf.release(); }
            return java.util.OptionalInt.of(1);
        }
        void grant() {
            request(ComponentSettingsRequest.Action.GRANT);
        }
        void request(ComponentSettingsRequest.Action action) {
            var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try {
                client.request(action, action == ComponentSettingsRequest.Action.GRANT ? 0 : -1).write(buf);
                new ComponentSettingsRequest(buf).handle(this);
            } finally { buf.release(); }
        }
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void pulseDevicesKeepNativeEdgesAndExposeInversion(GameTestHelper h) {
        var level = h.getLevel();
        var repeater = h.absolutePos(new BlockPos(2, 2, 2));
        var extender = repeater.east(3);
        for (var pos : java.util.List.of(repeater, extender)) {
            level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
            var diode = (pos.equals(repeater) ? AllBlocks.PULSE_REPEATER : AllBlocks.PULSE_EXTENDER).getDefaultState();
            level.setBlockAndUpdate(pos, diode.setValue(BrassDiodeBlock.FACING, Direction.NORTH));
        }
        var sixteen = new DataValue.IntegerValue(16);
        var shortDelay = new DataValue.IntegerValue(4);
        var enabled = new DataValue.BooleanValue(true);
        h.runAfterDelay(5, () -> {
            for (var pos : java.util.List.of(repeater, extender)) {
                var interval = ComponentSettings.observe(level, pos).orElseThrow();
                h.assertTrue(ComponentSettings.plan(level, pos, interval, sixteen).apply(), "Set the idle native interval");
                h.assertTrue(!level.getBlockState(pos).getValue(BrassDiodeBlock.POWERING), "Setting an idle duration emits no pulse");
                level.setBlockAndUpdate(pos.north(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                var pending = ComponentSettings.observe(level, pos).orElseThrow();
                h.assertValueEqual(ComponentSettings.plan(level, pos, pending, shortDelay).code(), "busy",
                    "Do not alter an input edge already scheduled by Create");
            }
            h.runAfterDelay(8, () -> {
                h.assertTrue(!level.getBlockState(repeater).getValue(BrassDiodeBlock.POWERING), "Repeater is still charging");
                h.assertTrue(level.getBlockState(extender).getValue(BrassDiodeBlock.POWERING), "Extender raised its real output");
                for (var pos : java.util.List.of(repeater, extender)) {
                    var interval = ComponentSettings.observe(level, pos).orElseThrow();
                    h.assertValueEqual(ComponentSettings.plan(level, pos, interval, shortDelay).code(), "busy", "Active interval is not truncated");
                    level.removeBlock(pos.north(), false);
                }
                h.runAfterDelay(9, () -> {
                    h.assertTrue(level.getBlockState(repeater).getValue(BrassDiodeBlock.POWERING), "Original delay still emits the pulse");
                    h.assertTrue(level.getBlockState(extender).getValue(BrassDiodeBlock.POWERING), "Original duration still holds");
                    var inverted = ComponentSettings.observe(level, extender, "inverted").orElseThrow();
                    h.assertValueEqual(ComponentSettings.plan(level, extender, inverted, text("true")).code(), "type_mismatch",
                        "Inversion requires a boolean");
                    h.assertTrue(ComponentSettings.plan(level, extender, inverted, enabled).apply(), "Toggle native inversion without resetting the pulse");
                    h.assertTrue(level.getBlockState(extender).getValue(BrassDiodeBlock.POWERING), "Inversion leaves the native pulse phase intact");
                    h.assertValueEqual(level.getSignal(extender, Direction.NORTH), 0, "Active inverted output is low");
                    h.runAfterDelay(15, () -> {
                        h.assertTrue(!level.getBlockState(repeater).getValue(BrassDiodeBlock.POWERING), "The original pulse ends");
                        h.assertTrue(!level.getBlockState(extender).getValue(BrassDiodeBlock.POWERING), "The original duration ends");
                        h.assertValueEqual(level.getSignal(extender, Direction.NORTH), 15, "Inverted idle level is high");
                        h.assertValueEqual(ComponentSettings.observe(level, extender).orElseThrow().value(), sixteen, "Inversion did not edit interval");
                        h.assertTrue(ComponentSettings.plan(level, extender, ComponentSettings.observe(level, extender).orElseThrow(), shortDelay).apply(),
                            "Busy rejection never queues; a new idle request is accepted");
                        h.succeed();
                    });
                });
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void configuresNativeSettingsWithoutWritingOutputs(GameTestHelper h) {
        var level = h.getLevel();
        var repeater = h.absolutePos(new BlockPos(2, 2, 2));
        var comparator = repeater.east(4);
        var timer = repeater.south(4);
        for (var target : java.util.List.of(repeater, comparator, timer))
            level.setBlockAndUpdate(target.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(repeater, Blocks.REPEATER.defaultBlockState());
        level.setBlockAndUpdate(comparator, Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(comparator.north(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(comparator.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(timer, AllBlocks.PULSE_TIMER.getDefaultState());
        h.runAfterDelay(8, () -> {
            var delay = ComponentSettings.observe(level, repeater).orElseThrow();
            h.assertValueEqual(delay.field().unit(), "redstone_tick", "Native repeater units");
            h.assertValueEqual(ComponentSettings.plan(level, repeater, delay, new DataValue.IntegerValue(5)).code(), "value_out_of_range",
                "Invalid settings are not clamped");
            var edit = ComponentSettings.plan(level, repeater, delay, new DataValue.IntegerValue(4));
            h.assertTrue(edit.apply(), "Apply validated repeater setting");
            h.assertValueEqual(level.getBlockState(repeater).getValue(RepeaterBlock.DELAY), 4, "Real delay state changes");
            h.assertValueEqual(ComponentSettings.plan(level, repeater, delay, new DataValue.IntegerValue(3)).code(), "version_conflict",
                "Do not overwrite a changed setting");
            level.scheduleTick(repeater, Blocks.REPEATER, 20);
            h.assertValueEqual(ComponentSettings.plan(level, repeater, ComponentSettings.observe(level, repeater).orElseThrow(),
                new DataValue.IntegerValue(3)).code(), "busy", "Pending native edge is not rescheduled");

            var mode = ComponentSettings.observe(level, comparator).orElseThrow();
            h.assertValueEqual(level.getSignal(comparator, Direction.NORTH), 15, "Compare mode emits with equal inputs");
            h.assertTrue(ComponentSettings.plan(level, comparator, mode, new DataValue.TextValue("subtract")).apply(), "Change native comparator mode");
            h.assertValueEqual(level.getBlockState(comparator).getValue(ComparatorBlock.MODE), ComparatorMode.SUBTRACT, "Mode is subtract");
            h.assertValueEqual(level.getSignal(comparator, Direction.NORTH), 0, "Output is recalculated from real inputs");

            var interval = ComponentSettings.observe(level, timer).orElseThrow();
            h.assertValueEqual(interval.field().unit(), "game_tick", "Create interval has different units");
            var pulse = ComponentSettings.plan(level, timer, interval, new DataValue.IntegerValue(42));
            var powering = level.getBlockState(timer).getValue(BrassDiodeBlock.POWERING);
            h.assertTrue(pulse.apply(), "Apply via Create's configured interval");
            h.assertValueEqual(BlockEntityBehaviour.get(level, timer, ScrollValueBehaviour.TYPE).getValue(), 42, "Native interval setter used");
            h.assertValueEqual(level.getBlockState(timer).getValue(BrassDiodeBlock.POWERING), powering, "No synthetic edge");
            h.succeed();
        });
    }
}
