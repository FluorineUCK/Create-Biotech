package com.nobodiiiii.createbiotech.content.biocomputing.item;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryResidentCapture;
import com.nobodiiiii.createbiotech.foundation.render.OversizedBlockItemRenderer;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModel;
import com.simibubi.create.foundation.item.render.PartialItemModelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Inventory illustration only. No captured resident, service permissions, or book contents. */
public final class LibraryCoreItemRenderer extends OversizedBlockItemRenderer<LibraryBlockEntity> {
    private static final LibraryResidentCapture.Projection ILLUSTRATION =
        new LibraryResidentCapture.Projection(true, ResourceLocation.withDefaultNamespace("plains"), 1, null);

    @Override protected LibraryBlockEntity createBlockEntity() {
        return new LibraryBlockEntity(BlockPos.ZERO, CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState()) {
            @Override public LibraryResidentCapture.Projection libraryResidentView() { return ILLUSTRATION; }
        };
    }

    @Override protected float getRenderYOffset() { return -0.20f; }

    @Override protected void renderTransformed(ItemStack stack, CustomRenderedItemModel model, PartialItemModelRenderer renderer,
            ItemDisplayContext context, PoseStack pose, MultiBufferSource buffers, int light, int overlay, LibraryBlockEntity preview) {
        renderOriginalModel(model, renderer, pose, light);
        boolean inventory = context == ItemDisplayContext.GUI;
        if (inventory) Lighting.setupForEntityInInventory();
        try { renderBlockEntity(preview, pose, buffers, light, overlay); }
        finally { if (inventory) Lighting.setupFor3DItems(); }
    }
}
