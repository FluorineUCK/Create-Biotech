package com.nobodiiiii.createbiotech.content.biocomputing.block;

import java.util.*;
import java.lang.ref.WeakReference;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterEnclosure;

public final class SerialPanelEntity extends BlockEntity {
    private static final Map<ServerPlayer,WeakReference<SerialPanelEntity>> selections=new WeakHashMap<>();
    private UUID identity=UUID.randomUUID(), controller;
    private BlockPos anchor;
    public SerialPanelEntity(BlockPos pos, BlockState state){super(CBBlockEntityTypes.SERIAL_PANEL.get(),pos,state);}
    public UUID identity(){return identity;}
    public void select(ServerPlayer player){ selections.put(player,new WeakReference<>(this)); }
    public static SerialPanelEntity selected(ServerPlayer player){
        var ref=selections.get(player);var panel=ref==null?null:ref.get();
        if(panel==null || panel.isRemoved() || panel.level!=player.serverLevel() || !player.serverLevel().hasChunkAt(panel.worldPosition)
            || player.serverLevel().getBlockEntity(panel.worldPosition)!=panel || !player.serverLevel().mayInteract(player,panel.worldPosition))
            throw new IllegalArgumentException("select_serial_panel");
        return panel;
    }
    public Direction side(){return getBlockState().getValue(SerialPanelBlock.FACING);}
    public boolean onShell(ClusterEnclosure shape){return shape.shell().contains(worldPosition) && !shape.bounds().contains(worldPosition.relative(side()));}
    public void bind(ClusterControllerBlockEntity c){anchor=c.getBlockPos();controller=c.controllerId();setChanged();}
    public ClusterControllerBlockEntity owner(){
        if(anchor==null || level==null || !level.hasChunkAt(anchor) || !(level.getBlockEntity(anchor) instanceof ClusterControllerBlockEntity c) || !c.controllerId().equals(controller))return null;
        return c;
    }
    @Override protected void saveAdditional(CompoundTag t,HolderLookup.Provider regs){super.saveAdditional(t,regs);t.putUUID("Panel",identity);if(anchor!=null){t.putLong("Anchor",anchor.asLong());t.putUUID("Owner",controller);}}
    @Override protected void loadAdditional(CompoundTag t,HolderLookup.Provider regs){super.loadAdditional(t,regs);if(t.hasUUID("Panel"))identity=t.getUUID("Panel");anchor=null;if(t.hasUUID("Owner")&&t.contains("Anchor",Tag.TAG_LONG)){anchor=BlockPos.of(t.getLong("Anchor"));controller=t.getUUID("Owner");}}
}
