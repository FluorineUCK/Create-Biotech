package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import net.neoforged.neoforge.energy.EnergyStorage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CapabilitySnapshotValuesTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() throws Exception {
        Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null)
            type.getMethod("of", java.util.List.class, java.util.List.class, java.util.List.class, java.util.List.class, java.util.Map.class)
                .invoke(null, java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true); field.setBoolean(null, true);
    }
    @Test void itemsAndFluidsAreCopiedIncludingSerializableComponents() {
        var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER, 3);
        var data = new net.minecraft.nbt.CompoundTag(); data.putString("marker", "before");
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(data));
        var items = new net.neoforged.neoforge.items.ItemStackHandler(1);
        items.setStackInSlot(0, stack);
        var snapshot = (DataValue.ListValue)CapabilitySnapshotValues.inventory(items, net.minecraft.core.RegistryAccess.EMPTY, limits);
        stack.setCount(8); data.putString("marker", "after");
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(data));
        var item = (DataValue.RecordValue)snapshot.values().getFirst();
        assertEquals(new DataValue.IntegerValue(3), item.field("count"));
        var components = (DataValue.RecordValue)item.field("components");
        assertEquals(new DataValue.TextValue("before"), ((DataValue.RecordValue)components.field("minecraft:custom_data")).field("marker"));
        var tank = new net.neoforged.neoforge.fluids.capability.templates.FluidTank(1000);
        var fluid = new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 250);
        tank.setFluid(fluid);
        var fluidSnapshot = (DataValue.ListValue)CapabilitySnapshotValues.fluid(tank, net.minecraft.core.RegistryAccess.EMPTY, limits);
        fluid.setAmount(500);
        var capturedTank = (DataValue.RecordValue)fluidSnapshot.values().getFirst();
        assertEquals(new DataValue.IntegerValue(250), capturedTank.field("amount"));
        assertEquals(new DataValue.IntegerValue(1000), capturedTank.field("capacity"));
    }
    private final ReadLimits limits = new ReadLimits(4096, 32768, 65536);
    @Test void energySnapshotIsFrozenAndDoesNotExtractOrInsert() {
        EnergyStorage storage = new EnergyStorage(1000, 1000, 1000, 300) {
            @Override public int receiveEnergy(int amount, boolean simulate) { throw new AssertionError("read mutated energy"); }
            @Override public int extractEnergy(int amount, boolean simulate) { throw new AssertionError("read mutated energy"); }
        };
        var captured = (DataValue.RecordValue)CapabilitySnapshotValues.energy(storage, limits);
        assertEquals(new DataValue.IntegerValue(300), captured.field("stored"));
        assertEquals(new DataValue.IntegerValue(1000), captured.field("capacity"));
    }
    @Test void rejectsHugeHandlerBeforeReadingAnySlotAndCopiesEnergyOverTime() {
        var huge = new net.neoforged.neoforge.items.ItemStackHandler(0) {
            @Override public int getSlots() { return Integer.MAX_VALUE; }
            @Override public net.minecraft.world.item.ItemStack getStackInSlot(int slot) { throw new AssertionError("unbounded scan"); }
        };
        assertEquals("snapshot_limit", ((DataValue.ErrorValue)CapabilitySnapshotValues.inventory(huge, net.minecraft.core.RegistryAccess.EMPTY, limits)).code());
        var energy = new EnergyStorage(1000, 1000, 1000, 300);
        var before = (DataValue.RecordValue)CapabilitySnapshotValues.energy(energy, limits);
        energy.extractEnergy(200, false);
        assertEquals(new DataValue.IntegerValue(300), before.field("stored"));
    }
    @Test void distinguishesAbsenceProviderFailureAndLimitErrors() {
        assertEquals("unsupported", ((DataValue.ErrorValue)CapabilitySnapshotValues.energy(null, limits)).code());
        EnergyStorage bad = new EnergyStorage(100) {
            @Override public int getEnergyStored() { throw new IllegalStateException("provider failed"); }
        };
        assertEquals("provider_failure", ((DataValue.ErrorValue)CapabilitySnapshotValues.energy(bad, limits)).code());
        assertEquals("snapshot_limit", ((DataValue.ErrorValue)CapabilitySnapshotValues.energy(new EnergyStorage(100), new ReadLimits(1, 1, 1))).code());
    }

    @Test void aggregateEntryBudgetStopsHandlerIterationEarly() {
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var handler = new net.neoforged.neoforge.items.ItemStackHandler(0) {
            @Override public int getSlots() { return 20; }
            @Override public net.minecraft.world.item.ItemStack getStackInSlot(int slot) {
                reads.incrementAndGet();
                return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER);
            }
        };
        var result = CapabilitySnapshotValues.inventory(handler, net.minecraft.core.RegistryAccess.EMPTY,
            new ReadLimits(20, 100000, 100000));
        assertEquals("snapshot_limit", ((DataValue.ErrorValue)result).code());
        assertTrue(reads.get() < 20, "Stop the provider scan when aggregate nested entries exhaust the budget");
    }
}


