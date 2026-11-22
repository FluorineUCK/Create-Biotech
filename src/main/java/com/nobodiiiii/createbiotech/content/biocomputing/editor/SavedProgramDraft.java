package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.CompoundTag;

/** One bounded editable document, independent of installed code and runtime RAM. */
public final class SavedProgramDraft {
    private Tag durable;
    public record Snapshot(long revision, byte[] document) {
        public Snapshot { document = document.clone(); }
        @Override public byte[] document() { return document.clone(); }
    }
    public Snapshot snapshot(ArchiveLimits limits) {
        if (durable == null) return new Snapshot(0, new byte[0]);
        if (!(durable instanceof CompoundTag tag) || !tag.getAllKeys().equals(java.util.Set.of("Version", "Revision", "Document"))
            || !tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1
            || !tag.contains("Revision", Tag.TAG_LONG) || tag.getLong("Revision") < 1
            || !tag.contains("Document", Tag.TAG_BYTE_ARRAY)) throw new IllegalArgumentException("Invalid saved draft metadata");
        byte[] bytes = tag.getByteArray("Document");
        ProgramDocumentWire.decode(bytes, limits); // Schema/safety validation only, never compile/install.
        return new Snapshot(tag.getLong("Revision"), bytes);
    }
    public boolean save(long expected, byte[] document, ArchiveLimits limits) {
        var current = snapshot(limits);
        if (expected != current.revision()) return false;
        if (expected == Long.MAX_VALUE) throw new IllegalArgumentException("Draft revision exhausted");
        byte[] canonical = ProgramDocumentWire.encode(ProgramDocumentWire.decode(document, limits), limits);
        var next = new CompoundTag(); next.putInt("Version", 1); next.putLong("Revision", expected + 1);
        next.putByteArray("Document", canonical); durable = next;
        return true;
    }
    public void read(Tag tag) { durable = tag == null ? null : tag.copy(); }
    public Tag write() { return durable == null ? null : durable.copy(); }
}
