package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;

public interface ReadComponentProvider<C> {
    ReadComponentDescriptor descriptor();
    boolean supports(C context);
    DataValue read(C context);
    /** Optional indexed subset. The default never calls the whole-component reader. */
    default DataValue readRange(C context, ReadRange range) {
        return new DataValue.ErrorValue("unsupported_range", "");
    }
}
