package com.nobodiiiii.createbiotech.content.surgery;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

/**
 * One immutable graph for every rigid surgical-cube connection.
 *
 * <p>Native, uncut model seams and explicit links such as super-glue joints are inserted into the
 * same adjacency map. Consumers deliberately cannot ask which kind of edge was traversed: packing,
 * cutting previews, selection and grounding must therefore agree about connectivity.</p>
 */
public final class SurgicalConnectionGraph<K> {
	private final List<K> bodyOrder;
	private final Map<K, Body<K>> bodies;
	private final Map<Endpoint<K>, Set<Endpoint<K>>> adjacency;

	private SurgicalConnectionGraph(List<K> bodyOrder, Map<K, Body<K>> bodies,
		Map<Endpoint<K>, Set<Endpoint<K>>> adjacency) {
		this.bodyOrder = List.copyOf(bodyOrder);
		this.bodies = Map.copyOf(bodies);
		Map<Endpoint<K>, Set<Endpoint<K>>> frozen = new HashMap<>();
		adjacency.forEach((endpoint, neighbors) -> frozen.put(endpoint, Set.copyOf(neighbors)));
		this.adjacency = Map.copyOf(frozen);
	}

	/** Returns {@code null} when a body or explicit connection references invalid topology. */
	@Nullable
	public static <K> SurgicalConnectionGraph<K> create(List<Body<K>> bodies, List<Link<K>> links) {
		if (bodies == null || bodies.isEmpty() || links == null)
			return null;
		Map<K, Body<K>> indexed = new LinkedHashMap<>();
		Map<Endpoint<K>, Set<Endpoint<K>>> adjacency = new HashMap<>();
		for (Body<K> body : bodies) {
			if (body == null || body.key == null || indexed.putIfAbsent(body.key, body) != null
				|| !SurgicalAssembly.validTopology(body.cubeCount, body.seams)
				|| body.presentCubes.length() > body.cubeCount || body.cutSeams.length() > body.seams.size())
				return null;
			for (int cube = body.presentCubes.nextSetBit(0); cube >= 0;
				cube = body.presentCubes.nextSetBit(cube + 1))
				adjacency.put(new Endpoint<>(body.key, cube), new HashSet<>());
		}

		for (Body<K> body : indexed.values())
			for (int seamId = 0; seamId < body.seams.size(); seamId++) {
				if (body.cutSeams.get(seamId))
					continue;
				SurgicalAssembly.Seam seam = body.seams.get(seamId);
				Endpoint<K> first = new Endpoint<>(body.key, seam.first());
				Endpoint<K> second = new Endpoint<>(body.key, seam.second());
				if (adjacency.containsKey(first) && adjacency.containsKey(second))
					connect(adjacency, first, second);
			}
		for (Link<K> link : links) {
			if (link == null || link.first == null || link.second == null
				|| !adjacency.containsKey(link.first) || !adjacency.containsKey(link.second))
				return null;
			connect(adjacency, link.first, link.second);
		}
		return new SurgicalConnectionGraph<>(new ArrayList<>(indexed.keySet()), indexed, adjacency);
	}

	private static <K> void connect(Map<Endpoint<K>, Set<Endpoint<K>>> adjacency,
		Endpoint<K> first, Endpoint<K> second) {
		if (first.equals(second))
			return;
		adjacency.get(first).add(second);
		adjacency.get(second).add(first);
	}

	/** All cubes reachable through either native seams or explicit links. */
	public Component<K> componentContaining(K body, int cube) {
		if (body == null || cube < 0)
			return Component.empty();
		Endpoint<K> start = new Endpoint<>(body, cube);
		if (!adjacency.containsKey(start))
			return Component.empty();
		Set<Endpoint<K>> visited = new HashSet<>();
		ArrayDeque<Endpoint<K>> pending = new ArrayDeque<>();
		visited.add(start);
		pending.add(start);
		while (!pending.isEmpty()) {
			Endpoint<K> current = pending.removeFirst();
			for (Endpoint<K> neighbor : adjacency.getOrDefault(current, Set.of()))
				if (visited.add(neighbor))
					pending.addLast(neighbor);
		}
		return component(visited);
	}

	/** The selected cube and every cube joined to it by exactly one connection edge. */
	public Component<K> directConnections(K body, int cube) {
		if (body == null || cube < 0)
			return Component.empty();
		Endpoint<K> start = new Endpoint<>(body, cube);
		if (!adjacency.containsKey(start))
			return Component.empty();
		Set<Endpoint<K>> connected = new HashSet<>(adjacency.get(start));
		connected.add(start);
		return component(connected);
	}

	/** Every connected component, ordered largest-first and then by stable body/cube order. */
	public List<Component<K>> components() {
		Set<Endpoint<K>> visited = new HashSet<>();
		List<OrderedComponent<K>> found = new ArrayList<>();
		for (int bodyIndex = 0; bodyIndex < bodyOrder.size(); bodyIndex++) {
			K body = bodyOrder.get(bodyIndex);
			Body<K> descriptor = bodies.get(body);
			for (int cube = descriptor.presentCubes.nextSetBit(0); cube >= 0;
				cube = descriptor.presentCubes.nextSetBit(cube + 1)) {
				Endpoint<K> start = new Endpoint<>(body, cube);
				if (visited.contains(start))
					continue;
				Component<K> component = componentContaining(body, cube);
				visited.addAll(endpoints(component));
				found.add(new OrderedComponent<>(component, bodyIndex, cube));
			}
		}
		found.sort(Comparator.<OrderedComponent<K>>comparingInt(value -> value.component.size()).reversed()
			.thenComparingInt(OrderedComponent::bodyIndex).thenComparingInt(OrderedComponent::cube));
		return found.stream().map(OrderedComponent::component).toList();
	}

	private Set<Endpoint<K>> endpoints(Component<K> component) {
		Set<Endpoint<K>> endpoints = new HashSet<>();
		component.members.forEach((body, cubes) -> {
			for (int cube = cubes.nextSetBit(0); cube >= 0; cube = cubes.nextSetBit(cube + 1))
				endpoints.add(new Endpoint<>(body, cube));
		});
		return endpoints;
	}

	private Component<K> component(Set<Endpoint<K>> endpoints) {
		Map<K, BitSet> members = new LinkedHashMap<>();
		for (K body : bodyOrder) {
			BitSet cubes = new BitSet();
			for (Endpoint<K> endpoint : endpoints)
				if (body.equals(endpoint.body))
					cubes.set(endpoint.cube);
			if (!cubes.isEmpty())
				members.put(body, cubes);
		}
		return new Component<>(members);
	}

	public static final class Body<K> {
		private final K key;
		private final int cubeCount;
		private final BitSet presentCubes;
		private final List<SurgicalAssembly.Seam> seams;
		private final BitSet cutSeams;

		public Body(K key, int cubeCount, BitSet presentCubes,
			List<SurgicalAssembly.Seam> seams, BitSet cutSeams) {
			this.key = key;
			this.cubeCount = cubeCount;
			this.presentCubes = presentCubes == null ? new BitSet() : (BitSet) presentCubes.clone();
			this.seams = seams == null ? List.of() : List.copyOf(seams);
			this.cutSeams = cutSeams == null ? new BitSet() : (BitSet) cutSeams.clone();
		}
	}

	public record Endpoint<K>(K body, int cube) {
		public Endpoint {
			Objects.requireNonNull(body, "body");
			if (cube < 0)
				throw new IllegalArgumentException("Negative surgical cube id");
		}
	}

	public record Link<K>(Endpoint<K> first, Endpoint<K> second) {
		public Link(K firstBody, int firstCube, K secondBody, int secondCube) {
			this(new Endpoint<>(firstBody, firstCube), new Endpoint<>(secondBody, secondCube));
		}
	}

	public static final class Component<K> {
		private static final Component<?> EMPTY = new Component<>(Map.of());
		private final Map<K, BitSet> members;
		private final int size;

		private Component(Map<K, BitSet> members) {
			Map<K, BitSet> frozen = new LinkedHashMap<>();
			int total = 0;
			for (Map.Entry<K, BitSet> entry : members.entrySet()) {
				BitSet cubes = (BitSet) entry.getValue().clone();
				if (cubes.isEmpty())
					continue;
				frozen.put(entry.getKey(), cubes);
				total += cubes.cardinality();
			}
			this.members = frozen;
			this.size = total;
		}

		@SuppressWarnings("unchecked")
		private static <K> Component<K> empty() {
			return (Component<K>) EMPTY;
		}

		public BitSet cubes(K body) {
			BitSet cubes = members.get(body);
			return cubes == null ? new BitSet() : (BitSet) cubes.clone();
		}

		public Map<K, BitSet> members() {
			Map<K, BitSet> copy = new LinkedHashMap<>();
			members.forEach((body, cubes) -> copy.put(body, (BitSet) cubes.clone()));
			return Map.copyOf(copy);
		}

		public boolean contains(K body, int cube) {
			BitSet cubes = members.get(body);
			return cubes != null && cubes.get(cube);
		}

		public boolean isEmpty() {
			return size == 0;
		}

		public int size() {
			return size;
		}
	}

	private record OrderedComponent<K>(Component<K> component, int bodyIndex, int cube) {}
}
