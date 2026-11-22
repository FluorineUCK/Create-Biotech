package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ResidentHost;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

/** Physical player/lifecycle boundary, never a program-callable world-write or production runtime API. */
public interface ComputerPhysicalHost extends ResidentHost {
    @Override SmartBlockEntity residentOwner();
    void tick();
    ComputerInstallResult installResident(ServerPlayer player, InteractionHand hand);
    /** Non-sneaking physical use; an installed application may handle it, otherwise return false. */
    boolean openComputerApplication(ServerPlayer player);
}
