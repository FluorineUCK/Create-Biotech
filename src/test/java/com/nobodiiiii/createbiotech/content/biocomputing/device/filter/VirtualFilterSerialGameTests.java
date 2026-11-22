package com.nobodiiiii.createbiotech.content.biocomputing.device.filter;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterProtocol.*;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class VirtualFilterSerialGameTests {
    @GameTest(template = "empty", timeoutTicks = 1300)
    public static void configuresDeployerToolFilterWithoutWritingItsItems(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 4, 3));
        var pos = origin.east(3);
        var target = pos.east();
        var sender = computer(h, origin);
        for (int x = 1; x < 3; x++) level.setBlockAndUpdate(origin.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(pos, CBBlocks.VIRTUAL_FILTER.get().defaultBlockState().setValue(VirtualFilterBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(target, AllBlocks.DEPLOYER.getDefaultState()
            .setValue(com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING, Direction.DOWN));
        level.setBlockAndUpdate(target.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(target.below(2), AllBlocks.DEPOT.getDefaultState());
        var controller = (VirtualFilterBlockEntity) level.getBlockEntity(pos);
        var port = controller.getBehaviour(VirtualFilterPort.TYPE);
        var filter = BlockEntityBehaviour.get(level, target, FilteringBehaviour.TYPE);
        var actor = new MenuPlayer(level); actor.moveTo(Vec3.atCenterOf(pos));
        sender.serialDevice().configure("s0", Direction.EAST, true);
        var iron = new ItemStack(Items.IRON_INGOT, 2);
        var gold = new ItemStack(Items.GOLD_INGOT);
        var rule = record(Map.of("domain", text("item"), "mode", text("allow"),
            "matchers", new DataValue.ListValue(List.of(record(Map.of("kind", text("id"), "name", text("minecraft:iron_ingot")))))));
        var address = port.serialAddress();
        h.runAfterDelay(10, () -> {
            var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false);
            h.assertValueEqual(controller.getBlockState().useWithoutItem(level, actor, hit), InteractionResult.CONSUME, "Use the real device menu");
            actor.send(actor.client.request(VirtualFilterRequest.Action.GRANT, 0));
            h.assertValueEqual(actor.client.code(), "configured", "Authorize the actual Deployer tool filter over cable");
            h.runAfterDelay(3, () -> exchange(h, sender, frame(address, "deployer-filter-read", "status", Map.of()), status -> {
                h.assertValueEqual(status.field("code"), text("ok"), "Can query the empty slot");
                var args = Map.of("state", status.field("state"), "rule", (DataValue)rule);
                long previousPolicy = VirtualFilterTarget.configurationRevision(filter);
                exchange(h, sender, frame(address, "deployer-filter-apply", "set", args), applied -> {
                    h.assertValueEqual(applied.field("code"), text("applied"), "Published a rule snapshot");
                    long installedPolicy = VirtualFilterTarget.configurationRevision(filter);
                    h.assertTrue(installedPolicy != previousPolicy, "A rule change gives future reservations a new policy witness");
                    var devices = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, target, Direction.UP);
                    h.assertTrue(devices != null && devices.getSlots() > 0 && devices.getStackInSlot(0).isEmpty() && filter.getFilter().isEmpty(),
                        "Installing a rule creates neither a tool nor a filter item");
                    h.assertTrue(ItemStack.matches(devices.insertItem(0, gold.copy(), false), gold), "Create rejects the disallowed actual item");
                    h.assertTrue(devices.getStackInSlot(0).isEmpty(), "Rejected item never entered the Deployer");
                    h.assertTrue(devices.insertItem(0, iron.copy(), false).isEmpty(), "Create accepts the allowed actual item");
                    h.assertTrue(ItemStack.matches(devices.getStackInSlot(0), iron), "Two actual items supplied, no synthesized contents");
                    var deployer = (com.nobodiiiii.createbiotech.content.biocomputing.device.settings.DeployerControl.Access) level.getBlockEntity(target);
                    var worksite = BlockEntityBehaviour.get(level, target.below(2),
                        com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TYPE);
                    var active = new java.util.concurrent.atomic.AtomicBoolean(true);
                    h.assertTrue(deployer.biocomputingAdmit(UUID.randomUUID(), worksite, active::get), "Open a pending native reservation without material");
                    exchange(h, sender, frame(address, "deployer-filter-busy", "clear", Map.of("state", applied.field("state"))), busy -> {
                        h.assertValueEqual(busy.field("code"), text("busy"), "A pending reservation cannot be altered by a filter write");
                        h.assertValueEqual(VirtualFilterTarget.configurationRevision(filter), installedPolicy, "Rejected edits do not change the active policy witness");
                        h.assertTrue(filter.test(iron) && !filter.test(gold), "Previous rule remains active");
                        active.set(false);
                        h.runAfterDelay(3, () -> {
                            filter.setFilter(gold);
                            exchange(h, sender, frame(address, "deployer-filter-occupied", "set", args), occupied -> {
                                h.assertValueEqual(occupied.field("code"), text("filter_slot_occupied"), "Never overwrite a mounted physical filter");
                                h.assertTrue(ItemStack.matches(filter.getFilter(), gold), "Native filter survives intact");
                                filter.setFilter(ItemStack.EMPTY);
                                h.assertTrue(filter.test(gold) && filter.test(iron), "Removing the physical filter does not revive the old rule");
                                h.assertTrue(ItemStack.matches(devices.getStackInSlot(0), iron), "Filtering configuration did not change the real tool");
                                h.succeed();
                            });
                        });
                    });
                });
            }));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1300)
    public static void configuresActualFunnelOverWireAndRejectsReplacement(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 2, 3));
        var pos = origin.east(3);
        var target = pos.east();
        var sender = computer(h, origin);
        for (int x = 1; x < 3; x++) level.setBlock(origin.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(pos, CBBlocks.VIRTUAL_FILTER.get().defaultBlockState().setValue(VirtualFilterBlock.FACING, Direction.EAST), 3);
        level.setBlock(target.north(), Blocks.STONE.defaultBlockState(), 3);
        var funnelState = AllBlocks.BRASS_FUNNEL.getDefaultState()
            .setValue(com.simibubi.create.content.logistics.funnel.FunnelBlock.FACING, Direction.SOUTH);
        level.setBlock(target, funnelState, 3);
        var controller = (VirtualFilterBlockEntity) level.getBlockEntity(pos);
        var port = controller.getBehaviour(VirtualFilterPort.TYPE);
        var filter = BlockEntityBehaviour.get(level, target, FilteringBehaviour.TYPE);
        var actor = new MenuPlayer(level); actor.moveTo(Vec3.atCenterOf(pos));
        sender.serialDevice().configure("s0", Direction.EAST, true);
        var iron = new ItemStack(Items.IRON_INGOT);
        var gold = new ItemStack(Items.GOLD_INGOT);
        var rule = record(Map.of("domain", text("item"), "mode", text("allow"),
            "matchers", new DataValue.ListValue(List.of(record(Map.of("kind", text("id"), "name", text("minecraft:iron_ingot")))))));
        var address = port.serialAddress();
        h.runAfterDelay(4, () -> {
            var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false);
            h.assertValueEqual(controller.getBlockState().useWithoutItem(level, actor, hit), InteractionResult.CONSUME, "Registered player menu opens");
            h.assertValueEqual(((DataValue.ListValue)actor.client.view().field("choices")).values().size(), 1, "Only the actual cable source");
            actor.send(actor.client.request(VirtualFilterRequest.Action.GRANT, 0));
            h.assertValueEqual(actor.client.code(), "configured", "Menu grants actual endpoint and target");
            h.runAfterDelay(3, () -> exchange(h, sender, frame(address, "read", "status", Map.of()), status -> {
                h.assertValueEqual(status.field("code"), text("ok"), "Status replies over the wire");
                var token = status.field("state");
                var set = Map.of("state", token, "rule", (DataValue)rule);
                exchange(h, sender, frame(address, "set", "set", set), result -> {
                    h.assertValueEqual(result.field("code"), text("applied"), "Rule applied over the wire");
                    h.assertTrue(filter.test(iron) && !filter.test(gold) && filter.getFilter().isEmpty(), "Create reads rule, not a stack");
                    var verify = Map.of("state", result.field("state"), "rule", (DataValue)rule);
                    exchange(h, sender, frame(address, "verify-installed-funnel", "verify", verify), verified -> {
                        h.assertValueEqual(verified.field("code"), text("verified"), "The actual active rule matches");
                        h.assertValueEqual(verified.field("state"), result.field("state"), "Verification did not change the revision");
                        h.assertTrue(filter.test(iron) && !filter.test(gold) && filter.getFilter().isEmpty(), "Readback does not modify the filter");
                        filter.setFilter(gold);
                        actor.send(actor.client.request(VirtualFilterRequest.Action.REFRESH, -1));
                        actor.send(actor.client.request(VirtualFilterRequest.Action.GRANT, 0));
                        h.assertValueEqual(actor.client.code(), "filter_slot_occupied", "Cannot grant direct control of an occupied native slot");
                        exchange(h, sender, frame(address, "occupied", "set", set), occupied -> {
                            h.assertValueEqual(occupied.field("code"), text("filter_slot_occupied"), "Native filter prevents even an old request from overwriting it");
                            h.assertTrue(filter.test(gold) && !filter.test(iron), "Native filter still active");
                            level.removeBlock(target, false);
                            level.setBlock(target, funnelState, 3);
                            exchange(h, sender, frame(address, "replacement", "status", Map.of()), changed -> {
                                h.assertValueEqual(changed.field("code"), text("target_changed"), "Replacement requires new explicit authorization");
                                h.assertTrue(!changed.fields().containsKey("state"), "No credentials for the replacement");
                                var replacement = BlockEntityBehaviour.get(level, target, FilteringBehaviour.TYPE);
                                replacement.setFilter(new ItemStack(com.simibubi.create.AllItems.FILTER.get()));
                                actor.send(actor.client.request(VirtualFilterRequest.Action.REFRESH, -1));
                                actor.send(actor.client.request(VirtualFilterRequest.Action.GRANT_LIST, 0));
                                h.assertValueEqual(actor.client.code(), "configured", "A separate menu action grants the mounted list");
                                exchange(h, sender, listFrame(address, "list_query", "status", Map.of()), listStatus -> {
                                    h.assertValueEqual(listStatus.field("code"), text("ok"), "List protocol is usable");
                                    var listArgs = Map.of("state", listStatus.field("state"), "slot", (DataValue)number(1), "rule", (DataValue)rule);
                                    exchange(h, sender, listFrame(address, "list_set", "set", listArgs), listSet -> {
                                        h.assertValueEqual(listSet.field("code"), text("applied"), "Explicit virtual list entry");
                                        h.assertTrue(replacement.test(iron) && !replacement.test(gold), "Real mounted filter composes the rule");
                                        var mounted = replacement.getFilter().copy();
                                        replacement.setFilter(ItemStack.EMPTY);
                                        replacement.setFilter(mounted);
                                        exchange(h, sender, listFrame(address, "remounted", "status", Map.of()), listChanged -> {
                                            h.assertValueEqual(listChanged.field("code"), text("target_changed"), "Removing and reinstalling requires explicit authorization");
                                            h.assertTrue(replacement.test(iron), "Rule travels only on the real outer Filter");
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

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void thresholdFiltersRealVaultStockWithoutEditingItems(GameTestHelper h) {
        verifyThresholdFilter(h, false);
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void thresholdFiltersRealTankContentsWithoutEditingFluid(GameTestHelper h) {
        verifyThresholdFilter(h, true);
    }

    private static void verifyThresholdFilter(GameTestHelper h, boolean fluid) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 4, 3));
        var adapter = origin.east(3);
        var device = adapter.east();
        var storage = device.east();
        var sender = computer(h, origin);
        sender.serialDevice().configure("s0", Direction.EAST, true);
        for (int x = 1; x <= 2; x++) level.setBlockAndUpdate(origin.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(adapter, CBBlocks.VIRTUAL_FILTER.get().defaultBlockState().setValue(VirtualFilterBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(storage, fluid ? AllBlocks.FLUID_TANK.getDefaultState() : AllBlocks.ITEM_VAULT.getDefaultState());
        level.setBlockAndUpdate(device, AllBlocks.THRESHOLD_SWITCH.getDefaultState()
            .setValue(com.simibubi.create.content.redstone.DirectedDirectionalBlock.TARGET,
                net.minecraft.world.level.block.state.properties.AttachFace.WALL)
            .setValue(com.simibubi.create.content.redstone.DirectedDirectionalBlock.FACING, Direction.EAST));
        var threshold = (com.simibubi.create.content.redstone.thresholdSwitch.ThresholdSwitchBlockEntity)level.getBlockEntity(device);
        var filter = BlockEntityBehaviour.get(threshold, FilteringBehaviour.TYPE);
        var port = ((VirtualFilterBlockEntity)level.getBlockEntity(adapter)).getBehaviour(VirtualFilterPort.TYPE);
        var address = port.serialAddress();
        int available = fluid ? 1250 : 12;
        int matches = fluid ? 0 : 5;
        String kind = fluid ? "fluid" : "item";
        var rule = record(Map.of("domain", text(kind), "mode", text("allow"),
            "matchers", new DataValue.ListValue(List.of(record(Map.of("kind", text("id"),
                "name", text(fluid ? "minecraft:lava" : "minecraft:iron_ingot")))))));
        h.runAfterDelay(12, () -> {
            threshold.offWhenBelow = fluid ? 650 : 6;
            threshold.onWhenAbove = fluid ? 900 : 9;
            Runnable materialUnchanged;
            if (fluid) {
                var tank = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, storage, Direction.UP);
                h.assertTrue(tank != null, "Use a real fluid storage capability");
                h.assertValueEqual(tank.fill(new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, available),
                    net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE), available, "Supply real water");
                materialUnchanged = () -> {
                    var contained = tank.getFluidInTank(0);
                    h.assertTrue(contained.getFluid() == net.minecraft.world.level.material.Fluids.WATER, "Water did not become lava");
                    h.assertValueEqual(contained.getAmount(), available, "No fluid added or lost");
                };
            } else {
                var inventory = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, storage, Direction.WEST);
                h.assertTrue(inventory instanceof com.simibubi.create.foundation.blockEntity.behaviour.inventory.VersionedInventoryWrapper,
                    "Use Create's real cached inventory version");
                var iron = new ItemStack(Items.IRON_INGOT, matches);
                var gold = new ItemStack(Items.GOLD_INGOT, available - matches);
                h.assertTrue(inventory.insertItem(0, iron, false).isEmpty() && inventory.insertItem(1, gold, false).isEmpty(), "Supply real materials");
                materialUnchanged = () -> h.assertTrue(ItemStack.matches(inventory.getStackInSlot(0), iron)
                    && ItemStack.matches(inventory.getStackInSlot(1), gold), "Both real stacks remain unchanged");
            }
            h.runAfterDelay(24, () -> {
                h.assertValueEqual(threshold.getStockLevel(), available, "Initial unfiltered native measurement");
                h.assertTrue(threshold.isPowered(), "Real sampled contents power the native threshold");
                grantDirect(h, adapter);
                h.runAfterDelay(3, () -> exchange(h, sender, frame(address, kind + "-threshold-read", "status", Map.of()), status -> {
                    h.assertValueEqual(status.field("code"), text("ok"), "Read the actual native threshold filter slot");
                    var setup = Map.of("state", status.field("state"), "rule", (DataValue)rule);
                    exchange(h, sender, frame(address, kind + "-threshold-filter", "set", setup), applied -> {
                        h.assertValueEqual(applied.field("code"), text("applied"), "Install a rule without a filter item");
                        h.runAfterDelay(24, () -> {
                            h.assertValueEqual(threshold.getStockLevel(), matches, "Re-sample even though inventory has not changed");
                            h.assertTrue(!threshold.isPowered() && filter.getFilter().isEmpty(), "Native falling edge and an empty physical filter slot");
                            materialUnchanged.run();
                            var installed = Map.of("state", applied.field("state"));
                            exchange(h, sender, frame(address, kind + "-threshold-clear", "clear", installed), cleared -> {
                                h.assertValueEqual(cleared.field("code"), text("cleared"), "Explicitly clear with the current version");
                                h.runAfterDelay(24, () -> {
                                    h.assertValueEqual(threshold.getStockLevel(), available, "Native measurement resumes without manipulating contents");
                                    h.assertTrue(threshold.isPowered(), "Native rising edge");
                                    materialUnchanged.run();
                                    filter.setFilter(fluid ? new ItemStack(Items.WATER_BUCKET) : new ItemStack(Items.GOLD_INGOT));
                                    exchange(h, sender, frame(address, kind + "-threshold-occupied", "set", setup), rejected -> {
                                        h.assertValueEqual(rejected.field("code"), text("filter_slot_occupied"), "Never replace a real sample");
                                        h.assertTrue(filter.getFilter().is(fluid ? Items.WATER_BUCKET : Items.GOLD_INGOT), "Sample kept untouched");
                                        materialUnchanged.run();
                                        h.succeed();
                                    });
                                });
                            });
                        });
                    });
                }));
            });
        });
    }

    public static void grantDirect(GameTestHelper h, BlockPos pos) {
        var level = h.getLevel();
        var actor = new MenuPlayer(level);
        actor.moveTo(Vec3.atCenterOf(pos));
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        h.assertValueEqual(level.getBlockState(pos).useWithoutItem(level, actor, hit), InteractionResult.CONSUME, "Open the actual filter menu");
        h.assertValueEqual(((DataValue.ListValue)actor.client.view().field("choices")).values().size(), 1, "Only the workcell's real serial source");
        actor.send(actor.client.request(VirtualFilterRequest.Action.GRANT, 0));
        h.assertValueEqual(actor.client.code(), "configured", "Grant direct filter scope");
    }

    public static ComputerBehaviour computer(GameTestHelper h, BlockPos pos) {
        var level = h.getLevel();
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        var lower = CBBlocks.COMPUTER.get().defaultBlockState().setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlock(pos, lower, 3);
        level.setBlock(pos.above(), lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER), 3);
        var block = (ComputerBlockEntity)level.getBlockEntity(pos);
        var villager = EntityType.VILLAGER.create(level); villager.setBaby(false);
        var box = new ItemStack(CBItems.CARDBOARD_BOX.get());
        h.assertTrue(CapturedEntityBoxHelper.captureEntity(box, villager), "Supported resident");
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "vf-resident")); actor.moveTo(Vec3.atCenterOf(pos));
        actor.setItemInHand(InteractionHand.MAIN_HAND, box);
        h.assertValueEqual(block.installResident(actor, InteractionHand.MAIN_HAND), ComputerInstallResult.SUCCESS, "Resident installed");
        var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(), record(Map.of()), FlowBlockLayout.DEFAULT,
            Map.of(), FlowProgram.Execution.REGISTER_V1)));
        computer.installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET);
        return computer;
    }
    private static DataValue.RecordValue frame(DataValue.RecordValue address, String request, String op, Map<String, DataValue> args) {
        return record(Map.of("kind", text("virtual_filter_command"), "version", number(1), "target", address, "request", text(request),
            "operation", text(op), "arguments", record(args)));
    }
    private static DataValue.RecordValue listFrame(DataValue.RecordValue address, String request, String op, Map<String, DataValue> args) {
        return record(Map.of("kind", text("virtual_list_filter_command"), "version", number(1), "target", address, "request", text(request),
            "operation", text(op), "arguments", record(args)));
    }
    private static void exchange(GameTestHelper h, ComputerBehaviour sender, DataValue.RecordValue request, Consumer<DataValue.RecordValue> receive) {
        h.assertValueEqual(sender.serialDevice().send("s0", request), "queued", "Uses finite transport");
        h.assertTrue(sender.serialDevice().poll("s0").isEmpty(), "No instantaneous reply");
        await(h, sender, request.field("request"), receive);
    }
    private static void await(GameTestHelper h, ComputerBehaviour sender, DataValue correlation, Consumer<DataValue.RecordValue> receive) {
        h.runAfterDelay(1, () -> {
            var next = sender.serialDevice().poll("s0");
            if (next.isEmpty()) { await(h, sender, correlation, receive); return; }
            var reply = next.orElseThrow();
            h.assertValueEqual(reply.field("request"), correlation, "Reply is correlated");
            receive.accept(reply);
        });
    }
    private static final class MenuPlayer extends FakePlayer {
        VirtualFilterMenu client;
        MenuPlayer(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "vf-control")); }
        @Override public OptionalInt openMenu(MenuProvider provider, Consumer<RegistryFriendlyByteBuf> data) {
            containerMenu = provider.createMenu(1, getInventory(), this);
            var buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try { data.accept(buf); client = CBMenuTypes.VIRTUAL_FILTER.get().create(1, getInventory(), buf); }
            finally { buf.release(); }
            return OptionalInt.of(1);
        }
        void send(VirtualFilterRequest packet) {
            var buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try { packet.write(buf); new VirtualFilterRequest(buf).handle(this); }
            finally { buf.release(); }
        }
    }
}
