package com.nobodiiiii.createbiotech.content.dingdongchicken;

import java.util.List;

import net.minecraft.client.model.ChickenModel;
import net.minecraft.client.model.geom.ModelPart;

public class DingDongChickenModel extends ChickenModel<DingDongChickenEntity> {

	public DingDongChickenModel(ModelPart root) {
		super(root);
	}

	@Override
	protected Iterable<ModelPart> headParts() {
		return List.of();
	}
}
