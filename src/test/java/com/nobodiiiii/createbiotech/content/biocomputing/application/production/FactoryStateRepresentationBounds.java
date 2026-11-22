package com.nobodiiiii.createbiotech.content.biocomputing.application.production;

/** Structural bounds shared by configuration and the sole current durable schema. */
public final class FactoryStateRepresentationBounds {
	public static final int MAX_COLLECTION_ENTRIES = 16_384;
	/** Shared server config/wire cap for a single paged Panel representation. */
	public static final int MAX_PANEL_ROWS = 4_096;

	private FactoryStateRepresentationBounds() {}
}
