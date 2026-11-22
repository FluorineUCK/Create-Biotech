package com.nobodiiiii.createbiotech.content.biocomputing.application.production.port;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerCasingBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial;
import com.nobodiiiii.createbiotech.content.biocomputing.block.TransparentComputerCasingBlock;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Portable, casing-mounted material request port. Native selection is not a device grant. */
public final class MaterialRequestPortItem extends LogisticallyLinkedBlockItem {
    public MaterialRequestPortItem(Block block, Properties properties) { super(block, properties); }

    /** Native assignment can originate at a stock link, gauge, or other Create interaction. */
    public static boolean prepareNativeSelection(ItemStack stack, Player player, UUID next) {
        if (player == null || player.isSpectator() || !player.mayBuild()) return false;
        var previous = networkFromStack(stack);
        if (!Create.LOGISTICS.mayInteract(next, player)
                || previous != null && !Create.LOGISTICS.mayAdministrate(previous, player)) {
            player.displayClientMessage(Component.translatable("create_biotech.factory_cluster.binding.permission"), true);
            return false;
        }
        // Create writes only Freq in its ordinary assignFrequency path. Give it a legal
        // block-entity component without serializing a new port or touching any old binding.
        CustomData.update(DataComponents.BLOCK_ENTITY_DATA, stack,
            tag -> BlockEntity.addEntityType(tag, CBBlockEntityTypes.MATERIAL_REQUEST_PORT.get()));
        return true;
    }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        var selected = networkFromStack(stack);
        if (selected == null || level.isClientSide) return super.use(level, player, hand);
        if (!player.mayBuild() || player.isSpectator() || !Create.LOGISTICS.mayAdministrate(selected, player)) {
            player.displayClientMessage(Component.translatable("create_biotech.factory_cluster.binding.permission"), true);
            return InteractionResultHolder.fail(stack);
        }
        // Create clears the entire component, including other mods' and the port's saved data.
        var kept = stack.getOrDefault(DataComponents.BLOCK_ENTITY_DATA, CustomData.EMPTY).copyTag();
        kept.remove("Freq");
        var result = super.use(level, player, hand);
        BlockItem.setBlockEntityData(stack, CBBlockEntityTypes.MATERIAL_REQUEST_PORT.get(), kept);
        return result;
    }

    public static Optional<ComputerShellMaterial> embeddingMaterial(BlockState state, Direction face) {
        if (face == null || !face.getAxis().isHorizontal()) return Optional.empty();
        if (state.getBlock() instanceof ComputerCasingBlock) return Optional.of(ComputerShellMaterial.OPAQUE);
        if (state.getBlock() instanceof TransparentComputerCasingBlock) return Optional.of(ComputerShellMaterial.TRANSPARENT);
        return Optional.empty();
    }

    @Override public InteractionResult useOn(UseOnContext context) {
        var player = context.getPlayer();
        var level = context.getLevel();
        var pos = context.getClickedPos();
        if (player == null || player.isSpectator() || !player.mayBuild() || !level.mayInteract(player, pos))
            return InteractionResult.FAIL;
        if (!player.isShiftKeyDown() && BlockEntityBehaviour.get(level, pos, LogisticallyLinkedBehaviour.TYPE) != null)
            return super.useOn(context);
        var face = context.getClickedFace();
        var material = embeddingMaterial(level.getBlockState(pos), face);
        if (material.isEmpty() || !player.mayUseItemAt(pos, face, context.getItemInHand())) return InteractionResult.FAIL;
        var data = context.getItemInHand().get(DataComponents.BLOCK_ENTITY_DATA);
        if (data != null && !CBBlockEntityTypes.MATERIAL_REQUEST_PORT.getId().toString().equals(data.copyTag().getString("id")))
            return InteractionResult.FAIL;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        var state = getBlock().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, face)
            .setValue(ComputerShellMaterial.BASE_MATERIAL, material.orElseThrow());
        if (!level.setBlock(pos, state, Block.UPDATE_ALL)) return InteractionResult.FAIL;
        var host = level.getBlockEntity(pos);
        if (host == null || host.getType() != CBBlockEntityTypes.MATERIAL_REQUEST_PORT.get()) return InteractionResult.FAIL;
        if (data != null) host.loadCustomOnly(data.copyTag(), level.registryAccess());
        if (!player.getAbilities().instabuild) context.getItemInHand().shrink(1);
        return InteractionResult.SUCCESS;
    }

    @Override public boolean isFoil(ItemStack stack) { return isTuned(stack); }
}
