package com.nobodiiiii.createbiotech.foundation.item;

import net.minecraft.world.item.ItemStack;

/**
 * Supplies a read-only preview for inventories whose output is produced asynchronously.
 *
 * <p>The preview must not reserve, create, or remove an item. It is only used by extraction
 * algorithms to decide whether they should attempt a simulated extraction.</p>
 */
public interface DeferredExtractionPreviewProvider {

	ItemStack getDeferredExtractionPreview(int slot);
}
