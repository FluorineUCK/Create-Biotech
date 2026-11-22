package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.Objects;

/** Owned data documents, never installed programs or references into a Computer. */
public record LibraryEntry(Kind kind, DataValue value) {
    public enum Kind { DATA, PROGRAM, SUBGRAPH }
    public LibraryEntry {
        Objects.requireNonNull(kind); Objects.requireNonNull(value);com.nobodiiiii.createbiotech.content.biocomputing.computation.LocalReferences.portable(value);
        switch (kind) {
            case DATA -> {}
            case PROGRAM -> ProgramDocumentCodec.decode(value);
            case SUBGRAPH -> SubgraphDocumentCodec.decode(value);
        }
    }
}
