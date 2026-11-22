package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;

/** Common physical membership gate. Device-specific uniqueness and transport remain outside this class. */
public final class ClusterPhysicalPortAccess {
    private ClusterPhysicalPortAccess() {}
    public static ClusterPortBinding authorize(ClusterMemberEditorAccess access, ServerPlayer player, String alias) {
        if (access == null) throw new IllegalArgumentException("open_from_controller");
        if (!access.stillValid(player)) throw new IllegalArgumentException("access_denied");
        var controller = access.controller();
        var enclosure = controller.enclosure().enclosure().orElseThrow(() -> failure("gateway_unformed"));
        return new ClusterPortBinding(controller.controllerId(), controller.getBlockPos(), enclosure.outward(), alias);
    }
    public static ClusterEnclosure resolve(ServerLevel level, BlockPos member, ClusterPortBinding binding) {
        var pos = binding.position();
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) throw failure("gateway_unloaded");
        if (!level.getWorldBorder().isWithinBounds(pos) || !level.getWorldBorder().isWithinBounds(member)
            || !SubLevelCompat.sameSpace(level, pos, member)) throw failure("access_denied");
        if (!(chunk.getBlockEntity(pos) instanceof ClusterControllerBlockEntity controller)) throw failure("gateway_unavailable");
        if (!controller.controllerId().equals(binding.controller())) throw failure("gateway_changed");
        if (!controller.physicalIoTickAvailable()) throw failure("gateway_unavailable");
        var enclosure = controller.enclosure().enclosure().orElseThrow(() -> failure("gateway_unformed"));
        if (enclosure.outward() != binding.side()) throw failure("gateway_changed");
        if (!enclosure.containsMember(member)) throw failure("gateway_not_member");
        return enclosure;
    }
    private static CableDiscovery.DiscoveryException failure(String code) { return new CableDiscovery.DiscoveryException(code); }
}
