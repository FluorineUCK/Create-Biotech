package com.nobodiiiii.createbiotech.content.biocomputing.library;

/** Appearance-only boundary; renderers cannot inspect recipe indexes, storage or resident entity NBT. */
public interface LibraryResidentRenderHost {
    LibraryResidentCapture.Projection libraryResidentView();
}
