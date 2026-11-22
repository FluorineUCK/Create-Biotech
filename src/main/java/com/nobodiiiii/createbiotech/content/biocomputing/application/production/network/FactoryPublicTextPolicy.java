package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network;

/** Shared privacy boundary for text persisted into, or projected from, public Factory state. */
public final class FactoryPublicTextPolicy {
	private FactoryPublicTextPolicy() {}

	/**
	 * Conservative Default_Ignorable_Code_Point predicate.
	 *
	 * <p>The reserved code points inside these ranges are intentionally included as well. Their
	 * future Unicode assignment must not make an already-persisted invisible public identifier
	 * acquire a different meaning.</p>
	 */
	public static boolean isDefaultIgnorable(int value) {
		return Character.getType(value) == Character.FORMAT
			|| value == 0x00AD || value == 0x034F || value == 0x061C
			|| value >= 0x115F && value <= 0x1160
			|| value >= 0x17B4 && value <= 0x17B5
			|| value >= 0x180B && value <= 0x180F
			|| value >= 0x200B && value <= 0x200F
			|| value >= 0x202A && value <= 0x202E
			|| value >= 0x2060 && value <= 0x206F
			|| value == 0x3164 || value >= 0xFE00 && value <= 0xFE0F
			|| value == 0xFEFF || value == 0xFFA0
			|| value >= 0xFFF0 && value <= 0xFFF8
			|| value >= 0x1BCA0 && value <= 0x1BCAF
			|| value >= 0x1D173 && value <= 0x1D17A
			|| value >= 0xE0000 && value <= 0xE0FFF;
	}
}
