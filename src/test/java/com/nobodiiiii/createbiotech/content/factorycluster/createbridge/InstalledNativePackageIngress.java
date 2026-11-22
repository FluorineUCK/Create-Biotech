package com.nobodiiiii.createbiotech.content.factorycluster.createbridge;

import java.util.Optional;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/** Drives an accepted package through an already-installed production binding. */
public final class InstalledNativePackageIngress {
	private InstalledNativePackageIngress() {}

	public static int throughPublisherLookup(MinecraftServer server, PackagerBlockEntity packager,
		String address, NativePackageObservation observation) {
		int[] copied = {0};
		NativePackageObservation.publishAcceptedFromInstalledRuntime(server, source(packager,
			address, publisher -> publisher.managedInputRoute(packager, address), route -> {
				copied[0]++;
				return observation;
			}));
		return copied[0];
	}

	public static void throughResolvedRoute(MinecraftServer server, PackagerBlockEntity packager,
		String address, CreateRouteKey route, NativePackageObservation observation) {
		NativePackageObservation.publishAcceptedFromInstalledRuntime(server, source(packager,
			address, ignored -> Optional.of(route), ignored -> observation));
	}

	public static void failAfterResolvedRoute(MinecraftServer server, PackagerBlockEntity packager,
		String address, CreateRouteKey route) {
		NativePackageObservation.publishAcceptedFromInstalledRuntime(server, source(packager,
			address, ignored -> Optional.of(route), ignored -> {
				throw new IllegalStateException("accepted package metadata failure");
			}));
	}

	private static NativePackageObservation.AcceptedPackageSource source(
		PackagerBlockEntity packager, String address,
		java.util.function.Function<NativePackageObservation.AcceptancePublisher,
			Optional<CreateRouteKey>> route,
		java.util.function.Function<CreateRouteKey, NativePackageObservation> copy) {
		return new NativePackageObservation.AcceptedPackageSource() {
			@Override public boolean isNativePackage() { return true; }
			@Override public BlockPos packagerPosition() { return packager.getBlockPos().immutable(); }
			@Override public String address() { return address; }
			@Override public Optional<CreateRouteKey> managedInputRoute(
				NativePackageObservation.AcceptancePublisher publisher, String exactAddress) {
				return route.apply(publisher);
			}
			@Override public NativePackageObservation copy(CreateRouteKey resolved) {
				return copy.apply(resolved);
			}
		};
	}
}
