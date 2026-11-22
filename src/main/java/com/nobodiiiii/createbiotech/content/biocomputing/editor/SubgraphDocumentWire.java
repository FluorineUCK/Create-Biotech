package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;

/** Existing bounded typed-value envelope; decoding does not install or execute a subgraph. */
public final class SubgraphDocumentWire {
    private SubgraphDocumentWire() {}
    public static byte[] encode(SubgraphDocument document, ArchiveLimits limits) {
        return ProgramEditorWire.encodeValue(SubgraphDocumentCodec.encode(document), limits);
    }
    public static SubgraphDocument decode(byte[] bytes, ArchiveLimits limits) {
        return SubgraphDocumentCodec.decode(ProgramEditorWire.decodeValue(bytes, limits));
    }
}
