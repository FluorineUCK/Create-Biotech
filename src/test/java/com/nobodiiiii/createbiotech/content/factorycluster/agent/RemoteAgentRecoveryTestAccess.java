package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

final class RemoteAgentRecoveryTestAccess {
	private RemoteAgentRecoveryTestAccess() {}
	static SpaceAddress space(int x) {
		return new SpaceAddress(Level.OVERWORLD, null, new BlockPos(x, 0, 0));
	}
}
