package com.nobodiiiii.createbiotech.content.biocomputing.application.production.port;

import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.nobodiiiii.createbiotech.registry.CBItems;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** The mounted device owns the material port behaviour; it is not a production cluster member. */
public final class MaterialRequestPortBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation {
    private java.util.UUID frequency;

    public MaterialRequestPortBlockEntity(BlockPos pos, BlockState state) {
        super(CBBlockEntityTypes.MATERIAL_REQUEST_PORT.get(), pos, state);
    }

    @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(new ProductionPortBehaviour(this));
    }

    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) return;
        frequency = tag.hasUUID("Freq") ? tag.getUUID("Freq") : null;
    }

    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (!clientPacket && frequency != null) tag.putUUID("Freq", frequency);
    }

    public ItemStack asConfiguredStack(HolderLookup.Provider registries) {
        var stack = new ItemStack(CBItems.MATERIAL_REQUEST_PORT.get());
        BlockItem.setBlockEntityData(stack, getType(), saveWithoutMetadata(registries));
        return stack;
    }

    @Override public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        tooltip.add(Component.translatable("create_biotech.production_port.title"));
        tooltip.add(Component.translatable("create_biotech.production_port.goggle_hint"));
        return true;
    }
}
