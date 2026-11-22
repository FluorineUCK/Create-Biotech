package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial;
import java.util.*;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Observed production layout, not general Cluster membership or a world-access capability. */
public record ProductionLayout(Bounds bounds, List<Member> members, List<Panel> panels) {
    // Existing historical format bounds, not new configurable gameplay capacities.
    static final int MAX_MEMBERS = 256, MAX_VOLUME = 4096;

    public ProductionLayout {
        Objects.requireNonNull(bounds);
        members = List.copyOf(members); panels = List.copyOf(panels);
        if (members.isEmpty() || members.size() > MAX_MEMBERS)
            throw new IllegalArgumentException("invalid production member count");
        UUID previous = null;
        var positions = new HashSet<BlockPos>();
        Address space = members.getFirst().address();
        for (var member : members) {
            if (previous != null && previous.compareTo(member.computerId()) >= 0)
                throw new IllegalArgumentException("production members must be uniquely UUID-sorted");
            previous = member.computerId();
            if (!bounds.contains(member.address().localPos()) || !positions.add(member.address().localPos())
                || !space.sameSpace(member.address())) throw new IllegalArgumentException("invalid member position or space");
        }
        var panelIds = new HashSet<UUID>(); var panelPositions = new HashSet<BlockPos>();
        for (var panel : panels)
            if (!panelIds.add(panel.panelId()) || !panelPositions.add(panel.address().localPos())
                || !bounds.contains(panel.address().localPos()) || !space.sameSpace(panel.address()))
                throw new IllegalArgumentException("invalid or duplicate panel identity, position or space");
    }

    /** Identity value only; resolving a target remains the authorized world's responsibility. */
    public record Address(ResourceKey<Level> dimension, @Nullable UUID subLevelId, BlockPos localPos) {
        public Address { Objects.requireNonNull(dimension); localPos = Objects.requireNonNull(localPos).immutable(); }
        public boolean sameSpace(Address other) {
            return dimension.equals(other.dimension) && Objects.equals(subLevelId, other.subLevelId);
        }
    }
    public record Member(UUID computerId, Address address, @Nullable ProductionResourceProfile profile) {
        public Member { Objects.requireNonNull(computerId); Objects.requireNonNull(address); }
    }
    public record Panel(UUID panelId, Address address, Direction facing, ComputerShellMaterial baseMaterial) {
        public Panel {
            Objects.requireNonNull(panelId); Objects.requireNonNull(address);
            Objects.requireNonNull(facing); Objects.requireNonNull(baseMaterial);
            if (!facing.getAxis().isHorizontal()) throw new IllegalArgumentException("panel face must be horizontal");
        }
    }
    /** No mutable BoundingBox is retained or returned. */
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public Bounds {
            long x = (long)maxX - minX + 1, y = (long)maxY - minY + 1, z = (long)maxZ - minZ + 1;
            if (x < 3 || y < 3 || z < 3 || x > MAX_VOLUME || y > MAX_VOLUME || z > MAX_VOLUME
                || x * y * z > MAX_VOLUME) throw new IllegalArgumentException("invalid production bounds");
        }
        public boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }
    }
}
