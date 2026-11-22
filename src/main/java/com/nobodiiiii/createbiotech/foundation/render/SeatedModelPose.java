package com.nobodiiiii.createbiotech.foundation.render;

import net.minecraft.client.model.geom.ModelPart;

/** Fixed seated-leg geometry shared by villager- and illager-family models. */
public final class SeatedModelPose {
	private static final float RIGHT_LEG_X_ROT = -1.4137167f;
	private static final float RIGHT_LEG_Y_ROT = 0.31415927f;
	private static final float RIGHT_LEG_Z_ROT = 0.07853982f;
	private static final float LEFT_LEG_X_ROT = -1.4137167f;
	private static final float LEFT_LEG_Y_ROT = -0.31415927f;
	private static final float LEFT_LEG_Z_ROT = -0.07853982f;

	private SeatedModelPose() {
	}

	public static void apply(ModelPart root) {
		ModelPart rightLeg = root.getChild("right_leg");
		ModelPart leftLeg = root.getChild("left_leg");
		rightLeg.setRotation(RIGHT_LEG_X_ROT, RIGHT_LEG_Y_ROT, RIGHT_LEG_Z_ROT);
		leftLeg.setRotation(LEFT_LEG_X_ROT, LEFT_LEG_Y_ROT, LEFT_LEG_Z_ROT);
	}
}
