package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputer;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.*;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerDisplayState;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Unit-only access to physical construction and protected persistence; no production setup API. */
public class TestComputer extends LegacyComputerFixture {
    public TestComputer(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); installHeadlessAddress(); }
    private void installHeadlessAddress() {
        try {
            var field = production().coordinatorMember.getClass().getDeclaredField("coordination"); field.setAccessible(true);
            Object coordination = field.get(production().coordinatorMember);
            var accessField = coordination.getClass().getDeclaredField("access"); accessField.setAccessible(true);
            Object original = accessField.get(coordination);
            var access = com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionCoordinator.Access.class;
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(access.getClassLoader(), new Class<?>[] { access }, (p, method, args) -> {
                if (method.getName().equals("address")) return new com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout.Address(
                    net.minecraft.world.level.Level.OVERWORLD, null, ((ProductionComputer) args[0]).owner().getBlockPos());
                try { return method.invoke(original, args); }
                catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
            });
            accessField.set(coordination, proxy);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    public static TestComputer physical(ProductionComputer application) { return (TestComputer) application.owner(); }
    public static ProductionComputer create(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        return new TestComputer(type, pos, state).production();
    }
    @Override public void read(CompoundTag tag, HolderLookup.Provider registries, boolean client) { super.read(tag, registries, client); }
    @Override public void write(CompoundTag tag, HolderLookup.Provider registries, boolean client) { super.write(tag, registries, client); }
    public ComputerInstallResult installResident(ItemStack held, ComputerResidentInstallation.Operations operations) {
        return ComputerResidentInstallation.install(held, this, operations);
    }
    public void installResidentSnapshot(ItemStack snapshot, ProductionResourceProfile profile) {
        // Preserve the existing trusted-install fixture, including deliberately non-box snapshots.
        // Production installation continues to use ComputerResidentInstallation validation.
        try {
            var invalidate = ProductionComputer.class.getDeclaredMethod("invalidateCoordinatorPublication");
            invalidate.setAccessible(true); invalidate.invoke(production());
            var residentField = LegacyComputerFixture.class.getDeclaredField("resident"); residentField.setAccessible(true);
            ((ComputerResident) residentField.get(this)).restoreSnapshot(snapshot);
            var profileField = ProductionComputer.class.getDeclaredField("installedProfile"); profileField.setAccessible(true); profileField.set(production(), profile);
            var listeners = ProductionComputer.class.getDeclaredField("externalListenerConfiguration"); listeners.setAccessible(true);
            listeners.set(production(), com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerConfiguration.empty());
            var validate = ProductionComputer.class.getDeclaredMethod("validateCrossChildren"); validate.setAccessible(true);
            var available = ProductionComputer.class.getDeclaredField("persistenceAvailable"); available.setAccessible(true); available.set(production(), validate.invoke(production()));
            long version = production().radioEndpointGeneration();
            production().ensureRadioEndpointGeneration(version == Long.MAX_VALUE ? version : version + 1);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    public void installRawResident(Tag raw) {
        var saved = new CompoundTag(); write(saved, RegistryAccess.EMPTY, false);
        saved.getCompound("ComputerData").put("Resident", raw.copy());
        saved.getCompound("ComputerData").remove("Profile");
        read(saved, RegistryAccess.EMPTY, false);
    }
    public void setDisplayState(ComputerDisplayState display) {
        try { var field = ProductionComputer.class.getDeclaredField("displayState"); field.setAccessible(true); field.set(production(), display); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        var packet = new CompoundTag(); write(packet, RegistryAccess.EMPTY, true); read(packet, RegistryAccess.EMPTY, true);
    }
}
