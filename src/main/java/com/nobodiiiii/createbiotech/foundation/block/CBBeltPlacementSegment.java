package com.nobodiiiii.createbiotech.foundation.block;

import com.simibubi.create.content.kinetics.belt.BeltBlockEntity.CasingType;

/** Read/write metadata carried by a placed belt segment during schematic chain reconstruction. */
public interface CBBeltPlacementSegment {
	int createBiotech$getBeltLength();
	boolean createBiotech$hasPulley();
	CasingType createBiotech$getCasingType();
	void createBiotech$setCasingType(CasingType casing);
}
