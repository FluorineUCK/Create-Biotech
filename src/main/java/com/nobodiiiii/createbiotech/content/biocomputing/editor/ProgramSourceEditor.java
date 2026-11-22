package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import net.minecraft.network.chat.Component;

/** Draft-only destination for a field selected from a frozen debugger snapshot. */
interface ProgramSourceEditor {
    void importValuePath(DataValue.RecordValue frozenInputs, ValuePath path);
    Component valuePathActionTitle();
}
