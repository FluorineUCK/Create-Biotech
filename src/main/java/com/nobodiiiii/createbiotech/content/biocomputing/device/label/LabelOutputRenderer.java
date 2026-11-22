package com.nobodiiiii.createbiotech.content.biocomputing.device.label;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Depth-tested billboard anchored to the adapter. No through-wall or entity-rendering pass. */
public final class LabelOutputRenderer implements BlockEntityRenderer<LabelOutputBlockEntity> {
    private static final float MAX_WIDTH = 3;
    private final Font font;

    public LabelOutputRenderer(BlockEntityRendererProvider.Context context) { font = context.getFont(); }

    @Override public int getViewDistance() {
        return CBConfigs.SERVER.biocomputing.labelOutputViewDistance(CBConfigs.SERVER_SPEC.isLoaded());
    }

    @Override public AABB getRenderBoundingBox(LabelOutputBlockEntity output) {
        var center = worldAnchor(output);
        // Account for the billboard rotating with the camera, including its line height.
        return new AABB(center, center).inflate(MAX_WIDTH / 2 + 0.25);
    }

    private static Vec3 worldAnchor(LabelOutputBlockEntity output) {
        return output.clientOffset().anchor().add(Vec3.atLowerCornerOf(output.getBlockPos()));
    }

    @Override public boolean shouldRender(LabelOutputBlockEntity output, Vec3 camera) {
        return worldAnchor(output).closerThan(camera, getViewDistance());
    }

    @Override public void render(LabelOutputBlockEntity output, float partialTick, PoseStack pose,
            MultiBufferSource buffer, int light, int overlay) {
        if (output.clientLabel().isEmpty()) return;
        var minecraft = Minecraft.getInstance();
        var label = Component.literal(output.clientLabel());
        int width = font.width(label);
        float scale = Math.min(0.025f, MAX_WIDTH / Math.max(1, width));
        int background = (int)(minecraft.options.getBackgroundOpacity(0.25f) * 255) << 24;
        pose.pushPose();
        var anchor = output.clientOffset().anchor();
        pose.translate(anchor.x, anchor.y, anchor.z);
        pose.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
        pose.scale(scale, -scale, scale);
        font.drawInBatch(label, -width / 2f, 0, 0xffffffff, false, pose.last().pose(), buffer,
            Font.DisplayMode.NORMAL, background, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }
}
