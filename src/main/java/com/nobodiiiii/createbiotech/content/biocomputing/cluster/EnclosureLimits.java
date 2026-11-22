package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

/** Bounded shell dimensions and named communication-binding capacity; neither pools Computer resources.
 * maxComputers limits configured bindings, not the geometric member roster. */
public record EnclosureLimits(int minSize, int maxSize, int maxComputers) {
    public EnclosureLimits {
        if (minSize < 3 || maxSize < minSize || maxSize > 16 || maxComputers < 1 || maxComputers > 256)
            throw new IllegalArgumentException("enclosure_limits");
    }
}
