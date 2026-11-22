package com.nobodiiiii.createbiotech.content.factorycluster.createbridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeAddressRules;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.NativeOrderLedger;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
/** Immutable metadata copied from a native package after its target Packager accepted it. */
public final class NativePackageObservation {
	private static final RuntimeFenceRegistry<MinecraftServer> RUNTIMES =
		new RuntimeFenceRegistry<>();
	private final CreateRouteKey route;
	private final boolean hasOrderData;
	private final int orderId;
	private final int linkIndex;
	private final int fragmentIndex;
	private final boolean finalLink;
	private final boolean finalFragment;
	private final Optional<NativeOrderLedger.OrderContext> orderContext;
	private final Map<StackKey, Long> acceptedStacks;

	public NativePackageObservation(CreateRouteKey route, boolean hasOrderData, int orderId,
		int linkIndex, int fragmentIndex, boolean finalLink, boolean finalFragment,
		@Nullable NativeOrderLedger.OrderContext orderContext,
		Map<StackKey, Long> acceptedStacks) {
		this.route = Objects.requireNonNull(route, "route");
		this.hasOrderData = hasOrderData;
		if (hasOrderData && (linkIndex < 0 || fragmentIndex < 0))
			throw new IllegalArgumentException("Native link and fragment indexes cannot be negative");
		this.orderId = orderId;
		this.linkIndex = linkIndex;
		this.fragmentIndex = fragmentIndex;
		this.finalLink = finalLink;
		this.finalFragment = finalFragment;
		this.orderContext = Optional.ofNullable(orderContext);
		this.acceptedStacks = immutableCounts(acceptedStacks);
	}

	public CreateRouteKey route() { return route; }
	public boolean hasOrderData() { return hasOrderData; }
	public int orderId() { return orderId; }
	public int linkIndex() { return linkIndex; }
	public int fragmentIndex() { return fragmentIndex; }
	public boolean isFinalLink() { return finalLink; }
	public boolean isFinal() { return finalFragment; }
	public Optional<NativeOrderLedger.OrderContext> orderContext() { return orderContext; }
	public Map<StackKey, Long> acceptedStacks() { return acceptedStacks; }

	public static NativePackageObservation fromAcceptedPackage(CreateRouteKey route, ItemStack box) {
		Objects.requireNonNull(box, "box");
		if (!PackageItem.isPackage(box))
			throw new IllegalArgumentException("Observation source is not a native package");
		Map<StackKey, Long> accepted = new LinkedHashMap<>();
		ItemStackHandler contents = PackageItem.getContents(box);
		for (int slot = 0; slot < contents.getSlots(); slot++) {
			ItemStack stack = contents.getStackInSlot(slot);
			if (!stack.isEmpty()) accepted.merge(new StackKey(stack), (long) stack.getCount(),
				Math::addExact);
		}
		PackageOrderWithCrafts nativeContext = PackageItem.getOrderContext(box);
		NativeOrderLedger.OrderContext context = nativeContext == null ? null
			: immutableOrderContext(nativeContext);
		return new NativePackageObservation(route, PackageItem.hasOrderData(box),
			PackageItem.getOrderId(box), PackageItem.getLinkIndex(box), PackageItem.getIndex(box),
			PackageItem.isFinalLink(box), PackageItem.isFinal(box), context, accepted);
	}

	/** Installs one runtime-owned publisher for one server; another server is always isolated. */
	public static AutoCloseable install(MinecraftServer server, AcceptancePublisher publisher) {
		return RUNTIMES.install(Objects.requireNonNull(server, "server"),
			Objects.requireNonNull(publisher, "publisher"));
	}

	/** Rolls back only the exact publisher whose installation transaction failed. */
	public static boolean uninstallIfOwnedBy(MinecraftServer server,
		AcceptancePublisher publisher) {
		return RUNTIMES.uninstallIfOwnedBy(Objects.requireNonNull(server, "server"),
			Objects.requireNonNull(publisher, "publisher"));
	}

	/** Exposes the non-clearable latch retained for this complete server lifetime. */
	public static Optional<RuntimeRecoveryFence> installedRecoveryFence(MinecraftServer server) {
		return RUNTIMES.fence(Objects.requireNonNull(server, "server"));
	}

	/** Read-only lifecycle inspection; a retained fence alone does not mean a publisher is active. */
	public static boolean publisherInstalled(MinecraftServer server) {
		return RUNTIMES.binding(Objects.requireNonNull(server, "server")).isPresent();
	}

	/** Called only by the Packager mixin after a non-simulated native unpack succeeds. */
	public static void publishAccepted(PackagerBlockEntity packager, ItemStack box) {
		RuntimeBinding binding = null;
		try {
			Objects.requireNonNull(packager, "packager");
			Objects.requireNonNull(box, "box");
			if (packager.getLevel() == null || packager.getLevel().isClientSide()) return;
			MinecraftServer server = packager.getLevel().getServer();
			if (server == null) return;
			binding = RUNTIMES.binding(server).orElse(null);
			if (binding == null) return;
			publishAccepted(binding, new AcceptedPackageSource() {
				@Override public boolean isNativePackage() { return PackageItem.isPackage(box); }
				@Override public BlockPos packagerPosition() {
					return packager.getBlockPos().immutable();
				}
				@Override public String address() { return PackageItem.getAddress(box); }
				@Override public Optional<CreateRouteKey> managedInputRoute(
					AcceptancePublisher publisher, String exactAddress) {
					return publisher.managedInputRoute(packager, exactAddress);
				}
				@Override public NativePackageObservation copy(CreateRouteKey route) {
					return fromAcceptedPackage(route, box);
				}
			});
		} catch (Throwable failure) {
			if (binding != null)
				handlePreparationFailure(binding.publisher(), binding.fence(),
					RecoveryContext.global(null, null), failure);
		}
	}

	/** Production observation path, separated from the native adapter for strict boundary tests. */
	static void publishAccepted(RuntimeBinding binding, AcceptedPackageSource source) {
		binding = Objects.requireNonNull(binding, "binding");
		RecoveryContext context = RecoveryContext.global(null, null);
		try {
			if (binding.fence().globallyFenced()) return;
			source = Objects.requireNonNull(source, "source");
			if (!source.isNativePackage()) return;
			BlockPos packagerPosition = source.packagerPosition().immutable();
			context = RecoveryContext.global(null, packagerPosition);
			String rawAddress = source.address();
			context = RecoveryContext.global(rawAddress, packagerPosition);
			String exactAddress = RecipeAddressRules.requireNormalized(rawAddress);
			context = RecoveryContext.global(exactAddress, packagerPosition);
			Optional<CreateRouteKey> route = source.managedInputRoute(binding.publisher(),
				exactAddress);
			if (route.isEmpty()) return;
			CreateRouteKey managedRoute = route.orElseThrow();
			if (!managedRoute.address().equals(exactAddress))
				throw new IllegalStateException("Managed route address changed during observation");
			context = RecoveryContext.routeSpecific(managedRoute, exactAddress, packagerPosition);
			if (binding.fence().blocks(managedRoute)) return;
			NativePackageObservation observation = source.copy(managedRoute);
			publishSafely(binding.publisher(), observation, binding.fence(), context);
		} catch (Throwable failure) {
			handlePreparationFailure(binding.publisher(), binding.fence(), context, failure);
		}
	}

	/** Drives an accepted source through the publisher that is actually installed for this server. */
	static void publishAcceptedFromInstalledRuntime(MinecraftServer server,
		AcceptedPackageSource source) {
		RUNTIMES.binding(Objects.requireNonNull(server, "server"))
			.ifPresent(binding -> publishAccepted(binding, Objects.requireNonNull(source, "source")));
	}

	/** A failed observation sink can fence recovery, but can never fail native unpacking. */
	public static void publishSafely(AcceptancePublisher publisher,
		NativePackageObservation observation, RuntimeRecoveryFence fence,
		RecoveryContext context) {
		publisher = Objects.requireNonNull(publisher, "publisher");
		observation = Objects.requireNonNull(observation, "observation");
		fence = Objects.requireNonNull(fence, "fence");
		context = Objects.requireNonNull(context, "context");
		if (fence.blocks(observation.route())) return;
		try {
			publisher.publish(observation);
		} catch (Throwable failure) {
			fence.latchRoute(context, failure);
			try {
				publisher.recordRecoveryRequired(context, observation, failure);
			} catch (Throwable recoveryFailure) {
				fence.latchRecoveryFailure(context, recoveryFailure);
			}
		}
	}

	/** Preserves Create's native return value even when all observation work fails. */
	public static boolean preserveNativeAcceptance(boolean accepted, boolean simulate,
		Runnable observationWork) {
		observationWork = Objects.requireNonNull(observationWork, "observationWork");
		if (accepted && !simulate)
			try {
				observationWork.run();
			} catch (Throwable ignored) {
				// Native unpack already succeeded and must remain successful.
			}
		return accepted;
	}

	static void handlePreparationFailure(AcceptancePublisher publisher,
		RuntimeRecoveryFence fence, RecoveryContext context, Throwable failure) {
		publisher = Objects.requireNonNull(publisher, "publisher");
		fence = Objects.requireNonNull(fence, "fence");
		context = Objects.requireNonNull(context, "context");
		failure = Objects.requireNonNull(failure, "failure");
		fence.latchGlobal(context, failure);
		try {
			publisher.recordRecoveryRequired(context, null, failure);
		} catch (Throwable recoveryFailure) {
			fence.latchRecoveryFailure(context, recoveryFailure);
			// The server-wide runtime latch was installed before persistence was attempted.
		}
	}

	@Override public boolean equals(Object other) {
		return other instanceof NativePackageObservation observation
			&& route.equals(observation.route) && hasOrderData == observation.hasOrderData
			&& orderId == observation.orderId && linkIndex == observation.linkIndex
			&& fragmentIndex == observation.fragmentIndex && finalLink == observation.finalLink
			&& finalFragment == observation.finalFragment
			&& orderContext.equals(observation.orderContext)
			&& acceptedStacks.equals(observation.acceptedStacks);
	}

	@Override public int hashCode() {
		return Objects.hash(route, hasOrderData, orderId, linkIndex, fragmentIndex, finalLink,
			finalFragment, orderContext, acceptedStacks);
	}

	private static Map<StackKey, Long> immutableCounts(Map<StackKey, Long> values) {
		values = Map.copyOf(Objects.requireNonNull(values, "acceptedStacks"));
		LinkedHashMap<StackKey, Long> copied = new LinkedHashMap<>();
		for (Map.Entry<StackKey, Long> entry : values.entrySet()) {
			Objects.requireNonNull(entry.getKey(), "accepted stack");
			if (entry.getValue() == null || entry.getValue() <= 0)
				throw new IllegalArgumentException("Accepted stack counts must be positive");
			copied.put(entry.getKey(), entry.getValue());
		}
		return Map.copyOf(copied);
	}

	private static NativeOrderLedger.OrderContext immutableOrderContext(
		PackageOrderWithCrafts context) {
		Map<StackKey, Long> ordered = new LinkedHashMap<>();
		for (BigItemStack stack : context.stacks())
			if (!stack.stack.isEmpty() && stack.count > 0)
				ordered.merge(new StackKey(stack.stack), (long) stack.count, Math::addExact);
		return ordered.isEmpty() ? null : new NativeOrderLedger.OrderContext(ordered);
	}

	/** Runtime-only monotonic recovery fence; no API can clear a latch during server lifetime. */
	public static final class RuntimeRecoveryFence {
		private final Map<CreateRouteKey, RecoveryEvidence> routeEvidence = new LinkedHashMap<>();
		private boolean globallyFenced;
		@Nullable private RecoveryEvidence globalEvidence;

		private synchronized void latchRoute(RecoveryContext context, Throwable failure) {
			context = Objects.requireNonNull(context, "context");
			failure = Objects.requireNonNull(failure, "failure");
			if (context.route().isEmpty()) {
				latchGlobal(context, failure);
				return;
			}
			CreateRouteKey route = context.route().orElseThrow();
			routeEvidence.merge(route, new RecoveryEvidence(context, failure, false),
				RecoveryEvidence::prefer);
		}

		private synchronized void latchGlobal(RecoveryContext context, Throwable failure) {
			latchGlobal(context, failure, false);
		}

		private synchronized void latchRecoveryFailure(RecoveryContext context, Throwable failure) {
			latchGlobal(context, failure, true);
		}

		private void latchGlobal(RecoveryContext context, Throwable failure,
			boolean recoveryFailure) {
			RecoveryContext retained = Objects.requireNonNull(context, "context");
			Throwable retainedFailure = Objects.requireNonNull(failure, "failure");
			RecoveryEvidence candidate = new RecoveryEvidence(retained, retainedFailure,
				recoveryFailure);
			retained.route().ifPresent(route -> {
				routeEvidence.merge(route, candidate, RecoveryEvidence::prefer);
			});
			globallyFenced = true;
			globalEvidence = globalEvidence == null ? candidate : globalEvidence.prefer(candidate);
		}

		/** True when publishing this route would permit progress after lost evidence. */
		public synchronized boolean blocks(CreateRouteKey route) {
			return globallyFenced || routeEvidence.containsKey(Objects.requireNonNull(route, "route"));
		}

		public synchronized boolean routeFenced(CreateRouteKey route) {
			return routeEvidence.containsKey(Objects.requireNonNull(route, "route"));
		}

		public synchronized boolean globallyFenced() { return globallyFenced; }

		public synchronized Optional<RecoveryContext> routeContext(CreateRouteKey route) {
			return Optional.ofNullable(routeEvidence.get(Objects.requireNonNull(route, "route")))
				.map(RecoveryEvidence::context);
		}

		public synchronized Optional<RecoveryContext> globalContext() {
			return Optional.ofNullable(globalEvidence).map(RecoveryEvidence::context);
		}

		public synchronized Optional<Throwable> routeFailure(CreateRouteKey route) {
			return Optional.ofNullable(routeEvidence.get(Objects.requireNonNull(route, "route")))
				.map(RecoveryEvidence::failure);
		}

		public synchronized Optional<Throwable> globalFailure() {
			return Optional.ofNullable(globalEvidence).map(RecoveryEvidence::failure);
		}

		public synchronized Optional<RecoveryEvidence> globalEvidence() {
			return Optional.ofNullable(globalEvidence);
		}
	}

	/** One real failure event; its context and cause are never merged across observations. */
	public record RecoveryEvidence(RecoveryContext context, Throwable failure,
		boolean recoveryPersistenceFailure) {
		public RecoveryEvidence {
			context = Objects.requireNonNull(context, "context");
			failure = Objects.requireNonNull(failure, "failure");
		}

		private RecoveryEvidence prefer(RecoveryEvidence newer) {
			newer = Objects.requireNonNull(newer, "newer");
			if (recoveryPersistenceFailure) return this;
			if (newer.recoveryPersistenceFailure) return newer;
			return newer.context.specificity() > context.specificity() ? newer : this;
		}
	}

	/** Best available immutable context, retained even when the fence scope must be global. */
	public record RecoveryContext(Optional<CreateRouteKey> route, Optional<String> address,
		Optional<BlockPos> packagerPosition) {
		public RecoveryContext {
			route = Objects.requireNonNull(route, "route");
			address = Objects.requireNonNull(address, "address");
			packagerPosition = Objects.requireNonNull(packagerPosition, "packagerPosition")
				.map(BlockPos::immutable);
		}

		public static RecoveryContext routeSpecific(CreateRouteKey route, String address,
			BlockPos packagerPosition) {
			return new RecoveryContext(Optional.of(Objects.requireNonNull(route, "route")),
				Optional.of(Objects.requireNonNull(address, "address")),
				Optional.of(Objects.requireNonNull(packagerPosition, "packagerPosition")));
		}

		public static RecoveryContext global(@Nullable String address,
			@Nullable BlockPos packagerPosition) {
			return new RecoveryContext(Optional.empty(), Optional.ofNullable(address),
				Optional.ofNullable(packagerPosition));
		}

		private int specificity() {
			return (route.isPresent() ? 4 : 0) + (address.isPresent() ? 2 : 0)
				+ (packagerPosition.isPresent() ? 1 : 0);
		}
	}

	static record RuntimeBinding(AcceptancePublisher publisher, RuntimeRecoveryFence fence) {
		RuntimeBinding {
			publisher = Objects.requireNonNull(publisher, "publisher");
			fence = Objects.requireNonNull(fence, "fence");
		}
	}

	interface AcceptedPackageSource {
		boolean isNativePackage();
		BlockPos packagerPosition();
		String address();
		Optional<CreateRouteKey> managedInputRoute(AcceptancePublisher publisher,
			String exactAddress);
		NativePackageObservation copy(CreateRouteKey route);
	}

	/** Keeps the fail-closed latch stable across publisher teardown and reinstall. */
	static final class RuntimeFenceRegistry<K> {
		private final Map<K, RuntimeState> states = new WeakHashMap<>();

		public synchronized AutoCloseable install(K key, AcceptancePublisher publisher) {
			key = Objects.requireNonNull(key, "key");
			publisher = Objects.requireNonNull(publisher, "publisher");
			RuntimeState state = states.computeIfAbsent(key, ignored -> new RuntimeState());
			if (state.publisher != null)
				throw new IllegalStateException("An Agent package publisher is already installed");
			long installation = ++state.installation;
			state.publisher = publisher;
			AcceptancePublisher installed = publisher;
			return () -> close(state, installed, installation);
		}

		public synchronized Optional<RuntimeBinding> binding(K key) {
			RuntimeState state = states.get(Objects.requireNonNull(key, "key"));
			return state == null || state.publisher == null ? Optional.empty()
				: Optional.of(new RuntimeBinding(state.publisher, state.fence));
		}

		public synchronized boolean uninstallIfOwnedBy(K key, AcceptancePublisher publisher) {
			RuntimeState state = states.get(Objects.requireNonNull(key, "key"));
			if (state == null || state.publisher != Objects.requireNonNull(publisher, "publisher"))
				return false;
			state.publisher = null;
			return true;
		}

		public synchronized Optional<RuntimeRecoveryFence> fence(K key) {
			RuntimeState state = states.get(Objects.requireNonNull(key, "key"));
			return state == null ? Optional.empty() : Optional.of(state.fence);
		}

		private synchronized void close(RuntimeState state, AcceptancePublisher publisher,
			long installation) {
			if (state.installation == installation && state.publisher == publisher)
				state.publisher = null;
		}

		private static final class RuntimeState {
			private final RuntimeRecoveryFence fence = new RuntimeRecoveryFence();
			@Nullable private AcceptancePublisher publisher;
			private long installation;
		}
	}

	public interface AcceptancePublisher {
		/** Returns a route only when this exact physical Packager/address is a managed Agent input. */
		Optional<CreateRouteKey> managedInputRoute(PackagerBlockEntity packager, String exactAddress);
		void publish(NativePackageObservation observation);
		/** Attempts to persist the already-latched fail-closed recovery requirement. */
		void recordRecoveryRequired(RecoveryContext context,
			@Nullable NativePackageObservation observation, Throwable failure);
	}
}
