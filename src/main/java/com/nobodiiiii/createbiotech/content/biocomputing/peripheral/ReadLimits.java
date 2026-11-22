package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

/** Server-controlled ceilings for one immutable read result. */
public record ReadLimits(int maxEntries, long maxStringChars, long maxValueUnits) {
    public ReadLimits {
        if (maxEntries <= 0 || maxStringChars < 0 || maxValueUnits <= 0)
            throw new IllegalArgumentException("Invalid snapshot limits");
    }
}
