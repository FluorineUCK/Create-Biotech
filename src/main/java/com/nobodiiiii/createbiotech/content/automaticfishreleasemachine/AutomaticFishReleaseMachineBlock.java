package com.nobodiiiii.createbiotech.content.automaticfishreleasemachine;

import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.content.kinetics.waterwheel.LargeWaterWheelBlock;
import com.simibubi.create.content.kinetics.waterwheel.LargeWaterWheelBlockEntity;

import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * A large water wheel with a ring of fish attached to it.
 *
 * <p>The mechanics intentionally remain in {@link LargeWaterWheelBlock}; this
 * subclass only redirects block entity creation to the Biotech type used by the
 * additional renderer.</p>
 */
public class AutomaticFishReleaseMachineBlock extends LargeWaterWheelBlock {

	public AutomaticFishReleaseMachineBlock(Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntityType<? extends LargeWaterWheelBlockEntity> getBlockEntityType() {
		return CBBlockEntityTypes.AUTOMATIC_FISH_RELEASE_MACHINE.get();
	}
}
