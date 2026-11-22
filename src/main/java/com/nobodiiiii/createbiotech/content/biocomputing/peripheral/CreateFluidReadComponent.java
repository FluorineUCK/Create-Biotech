package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;

/** Projects the native flow markers on the target block, not a tank or a flow-rate meter. */
final class CreateFluidReadComponent implements ReadComponentProvider<BlockReadContext> {
    static final String ID = "create_biotech:create_fluid_transport";
    private static final DataType TEXT = DataType.Scalar.TEXT;
    private static final DataType MAYBE_TEXT = new DataType.OptionalType(TEXT);
    private static final DataType PORT = new DataType.RecordType(Map.of(
        "face", TEXT, "stage", TEXT, "direction", MAYBE_TEXT, "fluid", MAYBE_TEXT));
    private static final ReadComponentDescriptor DESCRIPTOR = new ReadComponentDescriptor(ID,
        "create_biotech.biocomputing.component.create_fluid_transport", Set.of("block"),
        new DataType.RecordType(Map.of("ports", new DataType.ListType(PORT))),
        ReadComponentDescriptor.UpdateHint.TICK, true);

    @Override public ReadComponentDescriptor descriptor() { return DESCRIPTOR; }
    @Override public boolean supports(BlockReadContext context) { return behaviour(context) != null; }

    @Override public DataValue read(BlockReadContext context) {
        var transport = behaviour(context);
        if (transport == null) return CapabilitySnapshotValues.unsupported();
        var initialized = transport.interfaces;
        // Create's getFlow and getConnection initialize on demand. Return before either is invoked
        // if the native device has not yet initialized; this projection runs on the logical server thread.
        if (initialized == null) return CapabilitySnapshotValues.error("not_initialized");
        var ports = new ArrayList<DataValue>();
        for (var face : Direction.values()) {
            if (!initialized.containsKey(face)) continue;
            var flow = transport.getFlow(face);
            boolean flowing = flow != null && !flow.fluid.isEmpty();
            ports.add(new DataValue.RecordValue(Map.of(
                "face", new DataValue.TextValue(face.getName()),
                "stage", new DataValue.TextValue(!flowing ? "idle" : flow.complete ? "flowing" : "filling"),
                "direction", flowing ? new DataValue.TextValue(flow.inbound ? "inbound" : "outbound") : DataValue.NullValue.INSTANCE,
                "fluid", flowing ? new DataValue.TextValue(BuiltInRegistries.FLUID.getKey(flow.fluid.getFluid()).toString())
                    : DataValue.NullValue.INSTANCE)));
        }
        return new DataValue.RecordValue(Map.of("ports", new DataValue.ListValue(ports)));
    }

    private static FluidTransportBehaviour behaviour(BlockReadContext context) {
        if (!(context.entity() instanceof SmartBlockEntity smart) || smart.isRemoved()
            || !BuiltInRegistries.BLOCK.getKey(context.state().getBlock()).getNamespace().equals("create")) return null;
        return smart.getBehaviour(FluidTransportBehaviour.TYPE);
    }
}
