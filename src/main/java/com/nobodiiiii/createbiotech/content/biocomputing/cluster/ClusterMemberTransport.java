package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.OutboundMessage;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointTransport;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** One explicitly reserved internal output. A failure here never probes a same-named cable target. */
public final class ClusterMemberTransport {
    private ClusterMemberTransport() {}
    public static EndpointTransport.Result deliver(SmartBlockEntity source, ClusterAttachment attachment, UUID identity,
            String returnEndpoint, OutboundMessage output) {
        try {
            if (source.isRemoved() || !(source.getLevel() instanceof ServerLevel level) || !level.hasChunkAt(source.getBlockPos())
                    || level.getBlockEntity(source.getBlockPos()) != source) return failure("unavailable");
            var target = attachment.position();
            if (!level.isInWorldBounds(target) || !level.getWorldBorder().isWithinBounds(target)) return failure("controller_unavailable");
            if (!level.hasChunkAt(target)) return failure("controller_unloaded");
            if (!SubLevelCompat.sameSpace(level, source.getBlockPos(), target)) return failure("wrong_space");
            if (!(level.getBlockEntity(target) instanceof ClusterControllerBlockEntity controller)) return failure("controller_missing");
            if (!controller.controllerId().equals(attachment.controller())) return failure("controller_identity_changed");
            return controller.receiveInternal(source.getBlockPos(), output.endpoint(), EndpointTransport.envelope(identity, returnEndpoint, output));
        } catch (RuntimeException | StackOverflowError failure) { return failure("provider_failure"); }
    }
    private static EndpointTransport.Result failure(String code) { return new EndpointTransport.Result(code, Optional.empty()); }
}
