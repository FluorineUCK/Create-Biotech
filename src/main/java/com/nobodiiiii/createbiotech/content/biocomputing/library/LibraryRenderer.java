package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.resident.ResidentProjection;
import com.nobodiiiii.createbiotech.content.biocomputing.resident.ResidentRenderHelper;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

/** Client-only resident model. No recipe application or concrete legacy block entity is required. */
public final class LibraryRenderer<T extends BlockEntity & LibraryResidentRenderHost> implements BlockEntityRenderer<T> {
    public static final ResidentRenderHelper.ResidentRenderLayout RESIDENT_LAYOUT =
        new ResidentRenderHelper.ResidentRenderLayout(2 / 16d, 0.72f, ResidentRenderHelper.ResidentPose.STANDING);
    public static final double READER_OFFSET = 2.5 / 16d;
    private final ResidentRenderHelper residents;

    public LibraryRenderer(BlockEntityRendererProvider.Context context) {
        residents = new ResidentRenderHelper(context);
    }

    @Override
    public void render(T be, float partialTick, PoseStack poseStack,
        MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (be.getBlockState().getValue(LibraryCoreBlock.HALF) != DoubleBlockHalf.LOWER) return;
        var state = be.libraryResidentView();
        if (!state.valid()) return;
        VillagerType type = BuiltInRegistries.VILLAGER_TYPE.get(state.type());
        var projection = new ResidentProjection(EntityType.VILLAGER, VillagerProfession.LIBRARIAN,
            state.level(), state.customName());
        var readingSide = be.getBlockState().getValue(LibraryCoreBlock.FACING);
        poseStack.pushPose();
        var look = positionReader(poseStack, readingSide);
        residents.render(be, projection, type == null ? VillagerType.PLAINS : type,
            look, RESIDENT_LAYOUT, partialTick, poseStack, buffer);
        poseStack.popPose();
    }

    /** Stand on the low side of the lectern, looking towards its work surface. */
    public static Direction positionReader(PoseStack pose, Direction readingSide) {
        pose.translate(readingSide.getStepX() * READER_OFFSET, 0, readingSide.getStepZ() * READER_OFFSET);
        return readingSide.getOpposite();
    }

    @Override
    public boolean shouldRenderOffScreen(T be) {
        return be.getBlockState().getValue(LibraryCoreBlock.HALF) == DoubleBlockHalf.LOWER;
    }

    @Override
    public AABB getRenderBoundingBox(T be) {
        return new AABB(be.getBlockPos()).expandTowards(0, 1, 0);
    }
}
