package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Optional;

/** Additional target-level authority, independent of which data components are requested. */
@FunctionalInterface
public interface ReadAccessPolicy {
    Optional<DataValue.ErrorValue> deny(BlockReadContext context);
}
