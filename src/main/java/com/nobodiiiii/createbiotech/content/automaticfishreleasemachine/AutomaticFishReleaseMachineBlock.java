package com.nobodiiiii.createbiotech.content.automaticfishreleasemachine;

import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.content.kinetics.waterwheel.LargeWaterWheelBlock;
import com.simibubi.create.content.kinetics.waterwheel.LargeWaterWheelBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

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
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level.isClientSide)
			return;
		if (level.getBlockEntity(pos) instanceof AutomaticFishReleaseMachineBlockEntity releaseMachine)
			releaseMachine.setReputationOwner(placer);
	}

	@Override
	public BlockEntityType<? extends LargeWaterWheelBlockEntity> getBlockEntityType() {
		return CBBlockEntityTypes.AUTOMATIC_FISH_RELEASE_MACHINE.get();
	}
}
