package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.NativePackageObservation;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

/** Exact, idempotent evidence for the native orders belonging to one input stage. */
public final class NativeOrderLedger {
	private final Map<StackKey, Long> expectedManifest;
	private final Map<Integer, NativeOrderIdentity> expectedOrders;
	private final Map<FragmentKey, NativePackageObservation> observations = new LinkedHashMap<>();
	private final Map<StackKey, Long> acceptedManifest = new LinkedHashMap<>();
	private final Map<LinkKey, Integer> finalFragments = new HashMap<>();
	private final Set<Integer> contextOrders = new HashSet<>();
	private final Set<Integer> finalLinkOrders = new HashSet<>();

	public NativeOrderLedger(Map<StackKey, Long> expectedManifest,
		List<NativeOrderIdentity> expectedOrders) {
		this.expectedManifest = positiveManifest(expectedManifest, "expectedManifest");
		List<NativeOrderIdentity> immutableOrders = List.copyOf(Objects.requireNonNull(expectedOrders,
			"expectedOrders"));
		if (immutableOrders.isEmpty())
			throw new IllegalArgumentException("A confirmed dispatch needs native order identities");
		LinkedHashMap<Integer, NativeOrderIdentity> byId = new LinkedHashMap<>();
		for (NativeOrderIdentity identity : immutableOrders)
			if (byId.putIfAbsent(identity.orderId(), identity) != null)
				throw new IllegalArgumentException("Duplicate native order identity");
		this.expectedOrders = Map.copyOf(byId);
	}

	/** Rebuilds every derived index from immutable native evidence. */
	public static NativeOrderLedger restore(State state) {
		state = Objects.requireNonNull(state, "state");
		NativeOrderLedger ledger = new NativeOrderLedger(state.expectedManifest(),
			state.expectedOrders());
		for (NativePackageObservation observation : state.observations())
			if (ledger.observe(observation) != ObservationResult.ACCEPTED)
				throw new IllegalArgumentException("Invalid persisted native order evidence");
		if (!ledger.acceptedManifest.equals(state.acceptedManifest()))
			throw new IllegalArgumentException("Persisted native order totals are inconsistent");
		return ledger;
	}

	public ObservationResult observe(NativePackageObservation observation) {
		observation = Objects.requireNonNull(observation, "observation");
		if (!observation.hasOrderData()) return ObservationResult.INTERFERENCE;
		NativeOrderIdentity identity = expectedOrders.get(observation.orderId());
		if (identity == null || !identity.linkIndexes().contains(observation.linkIndex())
			|| observation.fragmentIndex() < 0)
			return ObservationResult.INTERFERENCE;
		int finalLink = identity.linkIndexes().stream().mapToInt(Integer::intValue).max().orElseThrow();
		if (observation.isFinalLink() != (observation.linkIndex() == finalLink))
			return ObservationResult.INTERFERENCE;
		if (observation.orderContext().isPresent()
			&& !observation.orderContext().orElseThrow().orderedStacks().equals(expectedManifest))
			return ObservationResult.INTERFERENCE;
		FragmentKey key = new FragmentKey(observation.orderId(), observation.linkIndex(),
			observation.fragmentIndex());
		NativePackageObservation prior = observations.get(key);
		if (prior != null)
			return prior.equals(observation) ? ObservationResult.DUPLICATE
				: ObservationResult.INTERFERENCE;
		for (Map.Entry<StackKey, Long> accepted : observation.acceptedStacks().entrySet()) {
			long next = Math.addExact(acceptedManifest.getOrDefault(accepted.getKey(), 0L),
				accepted.getValue());
			if (next > expectedManifest.getOrDefault(accepted.getKey(), 0L))
				return ObservationResult.INTERFERENCE;
		}

		LinkKey link = new LinkKey(observation.orderId(), observation.linkIndex());
		Integer finalFragment = finalFragments.get(link);
		if (finalFragment != null && observation.fragmentIndex() > finalFragment)
			return ObservationResult.INTERFERENCE;
		if (observation.isFinal()) {
			if (finalFragment != null && finalFragment != observation.fragmentIndex())
				return ObservationResult.INTERFERENCE;
			finalFragments.put(link, observation.fragmentIndex());
		}

		observations.put(key, observation);
		observation.acceptedStacks().forEach((stack, count) ->
			acceptedManifest.merge(stack, count, Math::addExact));
		if (observation.orderContext().isPresent()) contextOrders.add(observation.orderId());
		if (observation.isFinalLink()) finalLinkOrders.add(observation.orderId());
		return ObservationResult.ACCEPTED;
	}

	public boolean complete() {
		if (!acceptedManifest.equals(expectedManifest)) return false;
		for (NativeOrderIdentity order : expectedOrders.values()) {
			if (!contextOrders.contains(order.orderId()) || !finalLinkOrders.contains(order.orderId()))
				return false;
			for (int linkIndex : order.linkIndexes()) {
				Integer last = finalFragments.get(new LinkKey(order.orderId(), linkIndex));
				if (last == null) return false;
				for (int fragment = 0; fragment <= last; fragment++)
					if (!observations.containsKey(new FragmentKey(order.orderId(), linkIndex, fragment)))
						return false;
			}
		}
		return true;
	}

	public State snapshot() {
		return new State(expectedManifest, List.copyOf(expectedOrders.values()),
			List.copyOf(observations.values()), acceptedManifest);
	}

	public enum ObservationResult { ACCEPTED, DUPLICATE, INTERFERENCE }

	public record NativeOrderIdentity(int orderId, Set<Integer> linkIndexes) {
		public NativeOrderIdentity {
			linkIndexes = Set.copyOf(Objects.requireNonNull(linkIndexes, "linkIndexes"));
			if (linkIndexes.isEmpty())
				throw new IllegalArgumentException("Native order needs at least one link");
			int max = linkIndexes.stream().mapToInt(Integer::intValue).max().orElseThrow();
			for (int index = 0; index <= max; index++)
				if (!linkIndexes.contains(index))
					throw new IllegalArgumentException("Native link indexes must be contiguous from zero");
		}
	}

	public record OrderContext(Map<StackKey, Long> orderedStacks) {
		public OrderContext { orderedStacks = positiveManifest(orderedStacks, "orderedStacks"); }
	}

	public record State(Map<StackKey, Long> expectedManifest,
		List<NativeOrderIdentity> expectedOrders, List<NativePackageObservation> observations,
		Map<StackKey, Long> acceptedManifest) {
		public State {
			expectedManifest = positiveManifest(expectedManifest, "expectedManifest");
			expectedOrders = List.copyOf(Objects.requireNonNull(expectedOrders,
				"expectedOrders"));
			observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
			acceptedManifest = Map.copyOf(Objects.requireNonNull(acceptedManifest,
				"acceptedManifest"));
		}
	}

	private record FragmentKey(int orderId, int linkIndex, int fragmentIndex) {}
	private record LinkKey(int orderId, int linkIndex) {}

	private static Map<StackKey, Long> positiveManifest(Map<StackKey, Long> values, String name) {
		values = Map.copyOf(Objects.requireNonNull(values, name));
		if (values.isEmpty()) throw new IllegalArgumentException(name + " cannot be empty");
		for (Map.Entry<StackKey, Long> entry : values.entrySet()) {
			Objects.requireNonNull(entry.getKey(), name + " key");
			if (entry.getValue() == null || entry.getValue() <= 0)
				throw new IllegalArgumentException(name + " quantities must be positive");
		}
		return values;
	}
}
