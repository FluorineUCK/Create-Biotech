package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;

/** Editable documents retain the original BCP version and wire format. */
public final class ProgramDocumentWire {
    static final int MAX_EXPANDED_BYTES = BoundedEditorEnvelope.MAX_EXPANDED_BYTES;
    private ProgramDocumentWire() {}
    public static byte[] encode(ProgramDocument document, ArchiveLimits limits) {
        return BoundedEditorEnvelope.DOCUMENT.encode(ProgramDocumentCodec.encode(document), limits);
    }
    public static ProgramDocument decode(byte[] bytes, ArchiveLimits limits) {
        return ProgramDocumentCodec.decode(BoundedEditorEnvelope.DOCUMENT.decode(bytes, limits));
    }
}
