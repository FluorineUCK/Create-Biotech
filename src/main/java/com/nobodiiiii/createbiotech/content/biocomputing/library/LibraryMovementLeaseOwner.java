package com.nobodiiiii.createbiotech.content.biocomputing.library;

import java.util.Map;
import java.util.UUID;

/** Duck interface implemented by Create's Contraption mixin. */
public interface LibraryMovementLeaseOwner {
	boolean createBiotech$addPatternCoreLease(UUID libraryId, UUID movementToken);
	boolean createBiotech$removePatternCoreLease(UUID libraryId, UUID movementToken);
	Map<UUID, UUID> createBiotech$patternCoreLeases();
	void createBiotech$clearPatternCoreLeases();
}
