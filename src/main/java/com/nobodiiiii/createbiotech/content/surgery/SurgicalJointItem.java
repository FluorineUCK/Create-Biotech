package com.nobodiiiii.createbiotech.content.surgery;

import net.minecraft.world.item.Item;

/** A chain-and-cartilage joint that pins one surgical cube group to another as a moving limb. */
public class SurgicalJointItem extends Item {
	private final SurgicalLimbType limbType;

	public SurgicalJointItem(SurgicalLimbType limbType, Properties properties) {
		super(properties);
		this.limbType = limbType;
	}

	public SurgicalLimbType limbType() {
		return limbType;
	}
}
