package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

/** Caller-owned save envelope limits, separate from the computer's hardware resources. */
public record ArchiveLimits(int maxNodes, long maxStringChars, long maxValueUnits) {
	public ArchiveLimits {
		if (maxNodes < 1 || maxStringChars < 0 || maxValueUnits < 1)
			throw new IllegalArgumentException("Invalid archive limits");
	}
}
