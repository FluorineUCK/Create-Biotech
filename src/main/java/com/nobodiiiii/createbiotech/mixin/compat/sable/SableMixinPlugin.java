package com.nobodiiiii.createbiotech.mixin.compat.sable;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Keeps optional compatibility mixins inert unless their complete target API is present. This
 * class intentionally has no static references to Sable or Simulated.
 */
public final class SableMixinPlugin implements IMixinConfigPlugin {
	private static final String SIMULATED_MOVEMENT_CHECKS =
		"dev.simulated_team.simulated.index.SimBlockMovementChecks";
	private static final String SIMULATED_MOVEMENT_CHECKS_MIXIN =
		"com.nobodiiiii.createbiotech.mixin.compat.sable.SimBlockMovementChecksMixin";

	private static final String[] REQUIRED_CLASSES = {
		"dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor",
		"dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener",
		"dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider",
		"dev.ryanhcode.sable.api.physics.force.ForceTotal",
		"dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle",
		"dev.ryanhcode.sable.api.physics.mass.MassData",
		"dev.ryanhcode.sable.api.sublevel.SubLevelContainer",
		"dev.ryanhcode.sable.sublevel.ServerSubLevel"
	};

	private boolean sableApiAvailable;
	private boolean simulatedMovementChecksAvailable;

	@Override
	public void onLoad(String mixinPackage) {
		ClassLoader loader = SableMixinPlugin.class.getClassLoader();
		sableApiAvailable = true;
		for (String className : REQUIRED_CLASSES) {
			if (!isPresent(className, loader)) {
				sableApiAvailable = false;
				break;
			}
		}
		simulatedMovementChecksAvailable = isPresent(SIMULATED_MOVEMENT_CHECKS, loader);
	}

	private static boolean isPresent(String className, ClassLoader loader) {
		try {
			// Class.forName(..., false, ...) still defines the target class. During Mixin
			// configuration this is early enough to prevent later configs (notably
			// Simulated's ServerSubLevel mixin) from transforming it. A class resource
			// lookup checks the optional API surface without entering JVM class loading.
			return loader.getResource(className.replace('.', '/') + ".class") != null;
		} catch (RuntimeException ignored) {
			return false;
		}
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (!sableApiAvailable)
			return false;
		if (mixinClassName.equals(SIMULATED_MOVEMENT_CHECKS_MIXIN))
			return simulatedMovementChecksAvailable;
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
		IMixinInfo mixinInfo) {}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
		IMixinInfo mixinInfo) {}
}
