package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Live local interaction policy, separate from physical identity admission and application grants. */
public final class LibraryPlayerAccess {
    private final SmartBlockEntity owner;
    private final Predicate<ServerPlayer> application;

    public LibraryPlayerAccess(SmartBlockEntity owner, Predicate<ServerPlayer> application) {
        this.owner = Objects.requireNonNull(owner);
        this.application = Objects.requireNonNull(application);
    }

    SmartBlockEntity owner() { return owner; }

    /** Exact loaded host, never a replacement at its coordinates or a forced chunk load. */
    public boolean canUse(Player player) {
        var level = owner.getLevel();
        var pos = owner.getBlockPos();
        return level != null && player.level() == level && !owner.isRemoved()
            && SubLevelCompat.getLoadedBlockEntity(level, pos) == owner
            && SubLevelCompat.canEntityInteractWith(level, pos, player)
            && SubLevelCompat.distanceSquared(level, Vec3.atCenterOf(pos), player.position()) <= 64.0;
    }

    /** Application restrictions remain live; a prior menu opening is not a cached edit grant. */
    public boolean canEdit(ServerPlayer player) {
        return editPermission(canUse(player), player.mayBuild(), player.isSpectator(), () -> application.test(player));
    }

    public static boolean editPermission(boolean canUse, boolean mayBuild, boolean spectator,
        BooleanSupplier application) {
        return canUse && mayBuild && !spectator && application.getAsBoolean();
    }
}
