package com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Historical production binding format; decoding never grants live authority. */
public final class ProductionBindingCodec {
    public static final int VERSION = 2;
    private ProductionBindingCodec() {}

    public static Optional<ProductionBinding> load(CompoundTag tag) {
        if (tag == null || !tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != VERSION
            || !tag.hasUUID("ClusterId") || !tag.contains("Revision", Tag.TAG_LONG) || tag.getLong("Revision") < 0)
            return Optional.empty();
        ProductionBinding.Authority authority = null;
        if (tag.contains("Authority")) {
            if (!tag.contains("Authority", Tag.TAG_COMPOUND)) return Optional.empty();
            var raw = tag.getCompound("Authority");
            if (!raw.contains("Type", Tag.TAG_STRING) || !raw.hasUUID("MemberId")) return Optional.empty();
            var role = switch (raw.getString("Type")) {
                case "PANEL" -> ProductionBinding.Role.PANEL;
                case "PATTERN_CORE" -> ProductionBinding.Role.LIBRARY;
                case "COMPUTER_COORDINATOR" -> ProductionBinding.Role.COORDINATOR;
                default -> null;
            };
            if (role == null) return Optional.empty();
            authority = new ProductionBinding.Authority(role, raw.getUUID("MemberId"));
        }
        if (!(tag.get("LogisticsBindings") instanceof ListTag list)
            || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND
            || list.size() > ProductionBinding.MAX_NETWORKS) return Optional.empty();
        var ids = new ArrayList<UUID>(); var seen = new HashSet<UUID>();
        for (Tag entry : list) {
            var network = (CompoundTag) entry;
            if (!network.getAllKeys().equals(Set.of("Id")) || !network.hasUUID("Id")) return Optional.empty();
            var id = network.getUUID("Id");
            if (!seen.add(id)) return Optional.empty();
            ids.add(id);
        }
        return Optional.of(new ProductionBinding(tag.getUUID("ClusterId"), tag.getLong("Revision"), authority, ids));
    }

    public static CompoundTag save(ProductionBinding binding) {
        var tag = new CompoundTag(); tag.putInt("Version", VERSION);
        tag.putUUID("ClusterId", binding.groupId()); tag.putLong("Revision", binding.revision());
        if (binding.authority() != null) {
            var authority = new CompoundTag();
            authority.putString("Type", switch (binding.authority().role()) {
                case PANEL -> "PANEL";
                case LIBRARY -> "PATTERN_CORE";
                case COORDINATOR -> "COMPUTER_COORDINATOR";
            });
            authority.putUUID("MemberId", binding.authority().memberId()); tag.put("Authority", authority);
        }
        var networks = new ListTag();
        for (UUID id : binding.logisticsIds()) {
            var network = new CompoundTag(); network.putUUID("Id", id); networks.add(network);
        }
        tag.put("LogisticsBindings", networks); return tag;
    }
}
