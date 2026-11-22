package com.nobodiiiii.createbiotech.mixin.compat.sable;

import com.nobodiiiii.createbiotech.content.biocomputing.device.settings.DynamicScrollRange;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerValueBehaviour", remap = false)
public abstract class AeroBurnerScrollRangeMixin implements DynamicScrollRange {
    @Shadow private Supplier<Integer> minSupplier;
    @Shadow private Supplier<Integer> maxSupplier;

    @Override public int biocomputingMinimum() { return minSupplier.get(); }
    @Override public int biocomputingMaximum() { return maxSupplier.get(); }
}
