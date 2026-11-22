package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

/** Independently saved connection grant; corrupt data must not silently become a standalone Computer. */
public final class ClusterAttachmentStore {
    private Tag durable;
    private ClusterAttachment attachment;
    private boolean invalid;
    public void read(Tag tag) {
        durable = tag == null ? null : tag.copy(); attachment = null; invalid = false;
        if (tag == null) return;
        try {
            if (!(tag instanceof CompoundTag root) || root.getInt("Version") != 1
                    || !root.getAllKeys().equals(Set.of("Version", "Controller", "Position", "Outputs"))
                    || !root.hasUUID("Controller") || !root.contains("Position", Tag.TAG_LONG)
                    || !(root.get("Outputs") instanceof ListTag list) || list.size() > ClusterAttachment.MAX_OUTPUTS
                    || !list.isEmpty() && list.getElementType() != Tag.TAG_STRING) throw new IllegalArgumentException();
            var names = new HashSet<String>();
            for (int i = 0; i < list.size(); i++) if (!names.add(list.getString(i))) throw new IllegalArgumentException();
            attachment = new ClusterAttachment(root.getUUID("Controller"), BlockPos.of(root.getLong("Position")), names);
        } catch (IllegalArgumentException failure) { invalid = true; }
    }
    public Tag write() { return durable == null ? null : durable.copy(); }
    public Optional<ClusterAttachment> attachment() { return Optional.ofNullable(attachment); }
    public Optional<String> diagnostic() { return invalid ? Optional.of("invalid_attachment") : Optional.empty(); }
    public void configure(ClusterAttachment attachment) {
        var encoded = new CompoundTag(); encoded.putInt("Version", 1); encoded.putUUID("Controller", attachment.controller());
        encoded.putLong("Position", attachment.position().asLong()); var outputs = new ListTag();
        attachment.outputs().stream().sorted().forEach(name -> outputs.add(StringTag.valueOf(name))); encoded.put("Outputs", outputs);
        durable = encoded; this.attachment = attachment; invalid = false;
    }
    public void clear() { durable = null; attachment = null; invalid = false; }
}
