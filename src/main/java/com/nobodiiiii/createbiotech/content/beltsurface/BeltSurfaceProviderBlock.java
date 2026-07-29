package com.nobodiiiii.createbiotech.content.beltsurface;

/**
 * Cheap block-state marker for blocks whose block entity implements {@link BeltSurfaceHost}.
 * <p>
 * Hot funnel paths check this marker before calling {@code getBlockEntity}, so ordinary Create,
 * magma, and power belts never enter the surface-host resolver.
 */
public interface BeltSurfaceProviderBlock {
}
