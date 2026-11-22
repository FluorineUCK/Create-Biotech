package com.nobodiiiii.createbiotech.content.biocomputing.device.filter;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.logistics.filter.ListFilterItem;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class VirtualFilterNativeGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nativeClipboardAndBreakDoNotMaterializeVirtualSamples(GameTestHelper h) {
        var level = h.getLevel();
        var sourcePos = h.absolutePos(new BlockPos(2, 2, 2));
        var destinationPos = sourcePos.east(2);
        var funnel = AllBlocks.BRASS_FUNNEL.getDefaultState().setValue(FunnelBlock.FACING, Direction.SOUTH);
        for (var pos : List.of(sourcePos, destinationPos)) {
            level.setBlockAndUpdate(pos.north(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(pos, funnel);
        }
        var source = BlockEntityBehaviour.get(level, sourcePos, FilteringBehaviour.TYPE);
        var destination = BlockEntityBehaviour.get(level, destinationPos, FilteringBehaviour.TYPE);
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(destinationPos.getX() + 0.5, destinationPos.getY(), destinationPos.getZ() + 1.5);
        var registries = level.registryAccess();
        var gold = new ItemStack(Items.GOLD_INGOT);
        var iron = new ItemStack(Items.IRON_INGOT);
        var copper = new ItemStack(Items.COPPER_INGOT);
        var target = VirtualFilterTarget.of(source).orElseThrow();
        h.assertValueEqual(target.set(target.version(), rule("item", "minecraft:iron_ingot"), 4), "applied",
            "Install a direct rule on the physical source");

        var direct = new CompoundTag();
        h.assertTrue(source.writeToClipboard(registries, direct, Direction.SOUTH), "Native clipboard exports the source settings");
        h.assertTrue(ItemStack.parseOptional(registries, direct.getCompound("Filter")).isEmpty(),
            "Direct rule contributes no physical filter to the clipboard");
        h.assertTrue(destination.readFromClipboard(registries, direct, player, Direction.SOUTH, false),
            "Native paste still works");
        h.assertTrue(destination.getFilter().isEmpty() && destination.test(copper) && !source.test(copper),
            "A direct rule is neither pasted nor removed by clipboard operations");
        h.assertTrue(source.getRequiredItems().isEmpty(), "Schematic requirements do not bill a direct rule as an item");

        var outer = new ItemStack(AllItems.FILTER.get());
        outer.set(AllDataComponents.FILTER_ITEMS, ItemContainerContents.fromItems(List.of(gold)));
        h.assertValueEqual(VirtualListFilter.set(outer, 1, 0, rule("item", "minecraft:iron_ingot"), 4), "applied",
            "Add a virtual slot beside a genuine sample");
        h.assertTrue(source.setFilter(outer), "Native placement uses one genuine outer filter");
        var sourceBeforeCopy = source.getFilter().copy();
        var list = new CompoundTag();
        h.assertTrue(source.writeToClipboard(registries, list, Direction.SOUTH), "Save the configured outer filter to the native clipboard");
        h.assertTrue(!destination.readFromClipboard(registries, list, player, Direction.SOUTH, false)
            && destination.getFilter().isEmpty(), "In survival a clipboard does not create the outer filter");
        player.getInventory().setItem(0, new ItemStack(AllItems.FILTER.get()));
        h.assertTrue(destination.readFromClipboard(registries, list, player, Direction.SOUTH, false)
            && player.getInventory().getItem(0).isEmpty(), "Pasting spends exactly the provided physical filter");
        var installed = destination.getFilter();
        var slots = ((ListFilterItem) installed.getItem()).getFilterItemHandler(installed);
        h.assertTrue(slots.getStackInSlot(0).is(Items.GOLD_INGOT) && slots.getStackInSlot(1).isEmpty(),
            "Only the native sample inhabits the native list");
        h.assertTrue(destination.test(gold) && destination.test(iron) && !destination.test(copper),
            "Rule data still works on the paid-for filter");
        h.assertTrue(level.destroyBlock(destinationPos, true), "Break the destination through Create's native removal");
        var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(destinationPos).inflate(0.75));
        h.assertValueEqual(drops.stream().filter(item -> AllItems.FILTER.isIn(item.getItem())).mapToInt(item -> item.getItem().getCount()).sum(),
            1, "Exactly one outer filter is returned");
        h.assertTrue(drops.stream().noneMatch(item -> item.getItem().is(Items.IRON_INGOT) || item.getItem().is(Items.GOLD_INGOT)),
            "Neither virtual rule nor sample data is dropped as a standalone item");
        h.assertTrue(ItemStack.matches(source.getFilter(), sourceBeforeCopy), "Clipboard and destruction did not alter the installed source filter");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void brokenNestedRuleCannotBeInvertedOrBypassedByNativeSamples(GameTestHelper h) {
        var nested = new ItemStack(com.simibubi.create.AllItems.FILTER.get());
        var archive = new net.minecraft.nbt.CompoundTag();
        archive.putInt("Format", 999);
        nested.set(com.nobodiiiii.createbiotech.registry.CBDataComponents.BIOCOMPUTING_FILTER_RULES.get(),
            net.minecraft.world.item.component.CustomData.of(archive));
        var outer = new ItemStack(com.simibubi.create.AllItems.FILTER.get());
        var gold = new ItemStack(Items.GOLD_INGOT);
        outer.set(com.simibubi.create.AllDataComponents.FILTER_ITEMS,
            net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(gold, nested)));
        var wrapper = com.simibubi.create.content.logistics.filter.FilterItemStack.of(outer);
        h.assertTrue(!wrapper.test(h.getLevel(), gold), "An earlier matching real sample must not hide the broken branch");
        outer.set(com.simibubi.create.AllDataComponents.FILTER_ITEMS_BLACKLIST, true);
        wrapper = com.simibubi.create.content.logistics.filter.FilterItemStack.of(outer);
        h.assertTrue(!wrapper.test(h.getLevel(), new ItemStack(Items.IRON_INGOT)), "Outer blacklist must not invert a broken nested rule");
        h.assertTrue(!wrapper.test(h.getLevel(), new FluidStack(Fluids.WATER, 1000)), "Same boundary for fluids");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void listFilterCombinesVirtualAndNativeEntriesWithoutCreatingSampleStacks(GameTestHelper h) {
        var level = h.getLevel();
        var filter = new ItemStack(com.simibubi.create.AllItems.FILTER.get());
        var gold = new ItemStack(Items.GOLD_INGOT);
        var iron = new ItemStack(Items.IRON_INGOT);
        var copper = new ItemStack(Items.COPPER_INGOT);
        var slots = (com.simibubi.create.content.logistics.filter.ListFilterItem)filter.getItem();
        filter.set(com.simibubi.create.AllDataComponents.FILTER_ITEMS,
            net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(gold)));
        h.assertValueEqual(VirtualListFilter.set(filter, 0, 0, rule("item", "minecraft:iron_ingot"), 4), "filter_slot_occupied",
            "An occupied list entry remains native");
        h.assertValueEqual(VirtualListFilter.set(filter, 1, 0, rule("item", "minecraft:iron_ingot"), 4), "applied",
            "A real list filter can hold one extra virtual rule");
        var wrapper = com.simibubi.create.content.logistics.filter.FilterItemStack.of(filter);
        h.assertTrue(wrapper.test(level, gold) && wrapper.test(level, iron) && !wrapper.test(level, copper), "Whitelist composes both kinds");
        var items = slots.getFilterItemHandler(filter);
        h.assertTrue(items.getStackInSlot(0).is(Items.GOLD_INGOT) && items.getStackInSlot(1).isEmpty(), "No virtual ItemStack in native list");
        filter.set(com.simibubi.create.AllDataComponents.FILTER_ITEMS_BLACKLIST, true);
        wrapper = com.simibubi.create.content.logistics.filter.FilterItemStack.of(filter);
        h.assertTrue(!wrapper.test(level, gold) && !wrapper.test(level, iron) && wrapper.test(level, copper), "Blacklist also composes both kinds");
        var saved = filter.save(level.registryAccess());
        var restored = ItemStack.parse(level.registryAccess(), saved).orElseThrow();
        h.assertValueEqual(VirtualListFilter.revision(restored), 1L, "Dedicated component survives a stack round trip");
        h.assertValueEqual(VirtualListFilter.set(restored, 2, 0, rule("item", "minecraft:copper_ingot"), 4), "version_conflict", "No stale update");
        restored.set(com.simibubi.create.AllDataComponents.FILTER_ITEMS,
            net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(gold, copper)));
        VirtualListFilter.reconcile(restored);
        restored.set(com.simibubi.create.AllDataComponents.FILTER_ITEMS,
            net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(gold)));
        wrapper = com.simibubi.create.content.logistics.filter.FilterItemStack.of(restored);
        h.assertTrue(wrapper.test(level, iron), "Removed sample cannot resurrect overwritten virtual rule");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void poweredSmartPipeChangesFlowWithoutCreatingFluid(GameTestHelper h) {
        var level = h.getLevel();
        var pipe = h.absolutePos(new BlockPos(3, 2, 3));
        var pump = pipe.east();
        var upstream = pipe.west();
        var downstream = pump.east();
        var tank = AllBlocks.FLUID_TANK.getDefaultState();
        level.setBlock(upstream, tank, 3);
        level.setBlock(downstream, tank, 3);
        var source = ((com.simibubi.create.content.fluids.tank.FluidTankBlockEntity)level.getBlockEntity(upstream)).getTankInventory();
        var sink = ((com.simibubi.create.content.fluids.tank.FluidTankBlockEntity)level.getBlockEntity(downstream)).getTankInventory();
        // Prepare fluid through the normal tank handler. The tested operation never touches either tank.
        h.assertValueEqual(source.fill(new FluidStack(Fluids.WATER, 1000), net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE), 1000, "One bucket of water supplied");
        level.setBlock(pipe, AllBlocks.SMART_FLUID_PIPE.getDefaultState().setValue(
            net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.EAST), 3);
        var filter = BlockEntityBehaviour.get(level, pipe, FilteringBehaviour.TYPE);
        var target = VirtualFilterTarget.of(filter).orElseThrow();
        h.assertValueEqual(target.set(target.version(), rule("fluid", "minecraft:lava"), 4), "applied", "First reject water");
        level.setBlock(pump, AllBlocks.MECHANICAL_PUMP.getDefaultState()
            .setValue(com.simibubi.create.content.fluids.pump.PumpBlock.FACING, net.minecraft.core.Direction.EAST), 3);
        var cog = pump.above();
        level.setBlock(cog, AllBlocks.COGWHEEL.getDefaultState().setValue(
            net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, net.minecraft.core.Direction.Axis.X), 3);
        var motorPos = cog.west();
        level.setBlock(motorPos, AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(com.simibubi.create.content.kinetics.motor.CreativeMotorBlock.FACING, net.minecraft.core.Direction.EAST), 3);
        var motor = (com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity)level.getBlockEntity(motorPos);
        motor.initialize(); motor.generatedSpeed.setValue(64); motor.updateGeneratedRotation();
        h.runAfterDelay(70, () -> {
            var poweredPump = (com.simibubi.create.content.fluids.pump.PumpBlockEntity)level.getBlockEntity(pump);
            h.assertTrue(Math.abs(poweredPump.getSpeed()) > 0, "Fixture: mechanical pump must receive actual kinetic power");
            h.assertTrue(com.simibubi.create.content.fluids.pipes.SmartFluidPipeBlock.isOpenAt(level.getBlockState(pipe), net.minecraft.core.Direction.EAST),
                "Fixture: smart pipe physically connects to the pump");
            h.assertValueEqual(source.getFluidAmount(), 1000, "Initial rule blocks actual flow");
            h.assertValueEqual(sink.getFluidAmount(), 0, "No water leaves through a lava-only smart pipe");
            h.assertValueEqual(target.set(target.version(), rule("fluid", "minecraft:water"), 4), "applied", "Wake the native pipe network");
            h.runAfterDelay(100, () -> {
                h.assertTrue(sink.getFluidAmount() > 0 && sink.getFluid().getFluid() == Fluids.WATER, "Powered network moves real water after rule update");
                h.assertValueEqual(source.getFluidAmount() + sink.getFluidAmount(), 1000, "Total real water remains unchanged");
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 150)
    public static void swappingVirtualRuleWakesNativeFunnelAndConservesItems(GameTestHelper h) {
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(3, 2, 3));
        var source = pos.north();
        level.setBlock(source, net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState(), 3);
        var barrel = (net.minecraft.world.level.block.entity.BarrelBlockEntity)level.getBlockEntity(source);
        level.setBlock(pos, AllBlocks.BRASS_FUNNEL.getDefaultState()
            .setValue(com.simibubi.create.content.logistics.funnel.FunnelBlock.FACING, net.minecraft.core.Direction.SOUTH)
            .setValue(com.simibubi.create.content.logistics.funnel.FunnelBlock.EXTRACTING, true), 3);
        var filter = BlockEntityBehaviour.get(level, pos, FilteringBehaviour.TYPE);
        var slot = VirtualFilterTarget.of(filter).orElseThrow();
        h.assertValueEqual(slot.set(slot.version(), rule("item", "minecraft:copper_ingot"), 4), "applied", "Start with a rule that matches nothing in the barrel");
        barrel.setItem(0, new ItemStack(Items.GOLD_INGOT));
        barrel.setItem(1, new ItemStack(Items.IRON_INGOT));
        var output = new net.minecraft.world.phys.AABB(pos).inflate(2);
        h.runAfterDelay(40, () -> {
            h.assertTrue(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, output).isEmpty(),
                "No material passes the initial virtual rule");
            h.assertValueEqual(slot.set(slot.version(), rule("item", "minecraft:iron_ingot"), 4), "applied", "Change rule without touching source inventory");
            h.runAfterDelay(40, () -> {
                var items = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, output);
                h.assertValueEqual(items.stream().mapToInt(e -> e.getItem().getCount()).sum(), 1, "Exactly one actual item emitted");
                h.assertTrue(items.getFirst().getItem().is(Items.IRON_INGOT) && barrel.getItem(1).isEmpty(), "Iron moved out of the original barrel");
                h.assertTrue(barrel.getItem(0).is(Items.GOLD_INGOT) && barrel.getItem(0).getCount() == 1, "Gold remains, not duplicated");
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nativeFilterTakesPrecedenceAndDoesNotReactivateAVirtualSnapshot(GameTestHelper h) {
        var level = h.getLevel();
        var funnel = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(funnel.north(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(funnel, AllBlocks.BRASS_FUNNEL.getDefaultState()
            .setValue(com.simibubi.create.content.logistics.funnel.FunnelBlock.FACING, net.minecraft.core.Direction.SOUTH), 3);
        var pipe = funnel.offset(2, 0, 0);
        level.setBlock(pipe, AllBlocks.SMART_FLUID_PIPE.getDefaultState(), 3);
        var item = BlockEntityBehaviour.get(level, funnel, FilteringBehaviour.TYPE);
        var fluid = BlockEntityBehaviour.get(level, pipe, FilteringBehaviour.TYPE);
        h.assertTrue(item != null && fluid != null, "Real Create filter behaviours");
        var items = VirtualFilterTarget.of(item).orElseThrow();
        var fluids = VirtualFilterTarget.of(fluid).orElseThrow();
        var iron = new ItemStack(Items.IRON_INGOT);
        var gold = new ItemStack(Items.GOLD_INGOT);
        var water = new FluidStack(Fluids.WATER, 1000);
        var lava = new FluidStack(Fluids.LAVA, 1000);

        h.assertTrue(item.setFilter(gold), "Install native item sample");
        h.assertValueEqual(items.set(items.version(), rule("item", "minecraft:iron_ingot"), 4), "filter_slot_occupied", "Native filter rejects virtual installation");
        h.assertTrue(item.test(gold) && !item.test(iron), "Native item rule stays active");
        item.setFilter(ItemStack.EMPTY);
        h.assertValueEqual(items.set(items.version(), rule("item", "minecraft:iron_ingot"), 4), "applied", "Apply to empty brass funnel");
        h.assertTrue(item.test(iron) && !item.test(gold) && item.getFilter().isEmpty(), "Virtual rule affects native predicate without creating a stack");
        h.assertValueEqual(fluids.set(fluids.version(), rule("fluid", "minecraft:water"), 4), "applied", "Apply to empty smart pipe");
        h.assertTrue(fluid.test(water) && !fluid.test(lava) && fluid.getFilter().isEmpty(), "Native fluid predicate uses virtual rule");
        var portable = new net.minecraft.nbt.CompoundTag();
        fluid.writeSafe(portable, level.registryAccess());
        h.assertTrue(portable.contains("Filter") && !portable.contains(VirtualFilterTarget.ARCHIVE_KEY),
            "Native schematic export retains native settings, not the live rule");
        var stale = items.version();
        item.setFilter(gold);
        item.setFilter(ItemStack.EMPTY);
        h.assertTrue(item.test(gold) && item.test(iron), "Native removal cannot resurrect the prior rule");
        h.assertValueEqual(items.set(stale, rule("item", "minecraft:iron_ingot"), 4), "version_conflict", "Old request cannot restore rule");
        var entity = level.getBlockEntity(pipe);
        var saved = entity.saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(pipe);
        var restored = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pipe, level.getBlockState(pipe), saved, level.registryAccess());
        h.assertTrue(restored != null, "Restore real Create pipe");
        level.setBlockEntity(restored);
        var reloaded = BlockEntityBehaviour.get(restored, FilteringBehaviour.TYPE);
        h.assertTrue(reloaded.test(water) && !reloaded.test(lava) && reloaded.getFilter().isEmpty(), "Virtual rule persisted separately from the item slot");
        h.assertTrue(!VirtualFilterTarget.of(reloaded).orElseThrow().identity().equals(fluids.identity()), "A cold target cannot accept a prior identity");
        var broken = saved.copy();
        broken.getCompound(VirtualFilterTarget.ARCHIVE_KEY).putInt("Format", 999);
        restored.loadWithComponents(broken, level.registryAccess());
        h.assertTrue(!reloaded.test(water) && !reloaded.test(lava), "Unreadable virtual rule never becomes an implicit match-all");
        h.assertValueEqual(restored.saveWithFullMetadata(level.registryAccess()).get(VirtualFilterTarget.ARCHIVE_KEY),
            broken.get(VirtualFilterTarget.ARCHIVE_KEY), "Unknown archive retained");
        h.assertTrue(reloaded.getFilter().isEmpty(), "Nothing to extract or refund from the native filter slot");
        h.succeed();
    }

    private static DataValue.RecordValue rule(String domain, String id) {
        return new DataValue.RecordValue(Map.of("domain", text(domain), "mode", text("allow"),
            "matchers", new DataValue.ListValue(List.of(new DataValue.RecordValue(Map.of("kind", text("id"), "name", text(id)))))));
    }

    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
}
