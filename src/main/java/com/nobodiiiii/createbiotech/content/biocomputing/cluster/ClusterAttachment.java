package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import net.minecraft.core.BlockPos;

/** Explicit physical connection metadata, separate from the Computer's program and RAM. */
public record ClusterAttachment(UUID controller, BlockPos position, Set<String> outputs) {
    /** Fixed representation ceiling, matching the existing communication endpoint config ceiling. */
    public static final int MAX_OUTPUTS = 128;
    public ClusterAttachment {
        Objects.requireNonNull(controller); position = position.immutable(); outputs = Set.copyOf(outputs);
        if (!BlockPos.of(position.asLong()).equals(position) || outputs.size() > MAX_OUTPUTS)
            throw new IllegalArgumentException("invalid_attachment");
        outputs.forEach(name -> new EndpointConfiguration(name, true));
    }
}
