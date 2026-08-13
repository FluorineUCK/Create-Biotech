package com.nobodiiiii.createbiotech.content.sonicdogcannon;

import java.util.function.Consumer;

import com.nobodiiiii.createbiotech.client.SonicDogCannonArmPose;
import com.nobodiiiii.createbiotech.client.SonicDogCannonItemRenderer;
import com.simibubi.create.content.equipment.armor.BacktankUtil;
import com.simibubi.create.foundation.item.render.CustomRenderedItems;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import org.jetbrains.annotations.Nullable;

public class SonicDogCannonItem extends Item {

	public static final int MAX_DURABILITY = 100;
	public static final int FULL_CHARGE_TICKS = 40;
	public static final double CONE_RANGE = 16.0d;
	private static final double MIN_BEAM_RANGE = 8.0d;
	private static final double MAX_BEAM_RANGE = 24.0d;
	private static final double BEAM_HIT_RADIUS = 0.6d;

	public SonicDogCannonItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		player.startUsingItem(hand);
		return InteractionResultHolder.consume(stack);
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity entity) {
		return 72000;
	}

	@Override
	public UseAnim getUseAnimation(ItemStack stack) {
		// The cannon supplies its own two-handed pose; vanilla's bow pose would override it.
		return UseAnim.NONE;
	}

	@Override
	public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingUseDuration) {
		if (level.isClientSide || !(entity instanceof Player player))
			return;

		int elapsed = getUseDuration(stack, entity) - remainingUseDuration;
		if (elapsed <= 0 || elapsed % 20 != 0)
			return;

		if (!BacktankUtil.canAbsorbDamage(player, MAX_DURABILITY)) {
			EquipmentSlot slot = player.getUsedItemHand() == InteractionHand.MAIN_HAND
				? EquipmentSlot.MAINHAND
				: EquipmentSlot.OFFHAND;
			stack.hurtAndBreak(1, player, slot);
		}
	}

	@Override
	public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
		if (!(entity instanceof Player player) || !(level instanceof ServerLevel serverLevel))
			return;

		int chargeTicks = Math.max(0, getUseDuration(stack, entity) - timeLeft);
		Vec3 direction = player.getLookAngle().normalize();
		Vec3 origin = player.getEyePosition().add(direction.scale(0.5d));

		if (chargeTicks >= FULL_CHARGE_TICKS) {
			SonicDogConeWave.fire(serverLevel, player, origin, direction);
		} else {
			double range = MIN_BEAM_RANGE
				+ (MAX_BEAM_RANGE - MIN_BEAM_RANGE) * Math.min(chargeTicks, FULL_CHARGE_TICKS - 1)
				/ (FULL_CHARGE_TICKS - 1.0d);
			fireSonicBoom(serverLevel, player, origin, direction, range);
		}

		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.WARDEN_SONIC_BOOM,
			SoundSource.PLAYERS, 1.0f, 1.0f);
		player.awardStat(Stats.ITEM_USED.get(this));
	}

	private static void fireSonicBoom(ServerLevel level, Player player, Vec3 origin, Vec3 direction, double range) {
		Vec3 end = origin.add(direction.scale(range));
		int particleCount = Math.max(1, (int) Math.ceil(range));
		for (int i = 1; i <= particleCount; i++) {
			double distance = Math.min(i, range);
			Vec3 particlePos = origin.add(direction.scale(distance));
			level.sendParticles(net.minecraft.core.particles.ParticleTypes.SONIC_BOOM,
				particlePos.x, particlePos.y, particlePos.z, 1, 0.0d, 0.0d, 0.0d, 0.0d);
		}

		AABB searchBox = new AABB(origin, end).inflate(BEAM_HIT_RADIUS);
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, searchBox,
			candidate -> candidate != player && candidate.isAlive() && !candidate.isSpectator())) {
			if (target.getBoundingBox().inflate(BEAM_HIT_RADIUS).clip(origin, end).isPresent())
				target.hurt(level.damageSources().sonicBoom(player), 1.0f);
		}
	}

	@Override
	public boolean isBarVisible(ItemStack stack) {
		return BacktankUtil.isBarVisible(stack, MAX_DURABILITY);
	}

	@Override
	public int getBarWidth(ItemStack stack) {
		return BacktankUtil.getBarWidth(stack, MAX_DURABILITY);
	}

	@Override
	public int getBarColor(ItemStack stack) {
		return BacktankUtil.getBarColor(stack, MAX_DURABILITY);
	}

	@Override
	@OnlyIn(Dist.CLIENT)
	public void initializeClient(Consumer<IClientItemExtensions> consumer) {
		CustomRenderedItems.register(this);
		consumer.accept(new IClientItemExtensions() {
			private final SonicDogCannonItemRenderer renderer = new SonicDogCannonItemRenderer();

			@Override
			@Nullable
			public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
				return SonicDogCannonArmPose.ARM_POSE.getValue();
			}

			@Override
			public BlockEntityWithoutLevelRenderer getCustomRenderer() {
				return renderer;
			}
		});
	}
}
