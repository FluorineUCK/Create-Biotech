package com.nobodiiiii.createbiotech.content.biocomputing.block;

import net.minecraft.util.StringRepresentable;

public enum ComputerShellMaterial implements StringRepresentable {
	OPAQUE("opaque"),
	TRANSPARENT("transparent");

	/** Persisted block-state key shared by the casing-mounted devices and their placement items. */
	public static final net.minecraft.world.level.block.state.properties.EnumProperty<ComputerShellMaterial> BASE_MATERIAL =
		net.minecraft.world.level.block.state.properties.EnumProperty.create("base_material", ComputerShellMaterial.class);

	private final String name;

	ComputerShellMaterial(String name) {
		this.name = name;
	}

	@Override
	public String getSerializedName() {
		return name;
	}
}