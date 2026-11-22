package com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Physical player access to one Computer, not a program capability or a shared-memory grant. */
public record ClusterMemberEditorAccess(ClusterControllerBlockEntity controller, SmartBlockEntity computer) {
    public ClusterMemberEditorAccess { Objects.requireNonNull(controller); Objects.requireNonNull(computer); }

    public static SmartBlockEntity resolve(ClusterControllerBlockEntity controller, BlockPos target, ServerPlayer player) {
        if (!(controller.getLevel() instanceof ServerLevel level) || player.level() != level || !player.isAlive()
            || controller.isRemoved() || !level.hasChunkAt(controller.getBlockPos())
            || level.getBlockEntity(controller.getBlockPos()) != controller) throw denied("unavailable");
        var anchor = controller.getBlockPos();
        if (!level.getWorldBorder().isWithinBounds(anchor) || !level.mayInteract(player, anchor)
            || player.distanceToSqr(anchor.getX() + .5, anchor.getY() + .5, anchor.getZ() + .5) > 64) throw denied("access_denied");
        if (!level.hasChunkAt(target)) throw denied("member_unloaded");
        if (!SubLevelCompat.sameSpace(level, anchor, target)) throw denied("wrong_space");
        if (!level.getWorldBorder().isWithinBounds(target) || !level.mayInteract(player, target)) throw denied("access_denied");
        var scan = controller.enclosure();
        if (scan.enclosure().isEmpty()) throw denied(scan.status().name().toLowerCase(java.util.Locale.ROOT));
        if (!scan.enclosure().orElseThrow().containsMember(target)) throw denied("not_contacting");
        if (!(level.getBlockEntity(target) instanceof SmartBlockEntity owner) || owner.isRemoved()
            || owner.getBehaviour(ComputerBehaviour.TYPE) == null) throw denied("member_missing");
        return owner;
    }
    public boolean stillValid(ServerPlayer player) {
        try { return !computer.isRemoved() && resolve(controller, computer.getBlockPos(), player) == computer; }
        catch (IllegalArgumentException unavailable) { return false; }
    }
    private static IllegalArgumentException denied(String code) { return new IllegalArgumentException(code); }
}
