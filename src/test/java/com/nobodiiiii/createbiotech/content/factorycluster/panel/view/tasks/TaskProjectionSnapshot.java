package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentNewTaskDefaults;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentNewTaskDefaults.FormationPublication;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryGoalTransitionPolicy;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService.PanelPublicationFence;

/** Immutable Task/New Task capture facts and a runtime-owned publication authority. */
public final class TaskProjectionSnapshot {
	private TaskProjectionSnapshot() {}

	/** Production defaults can only be minted from a current runtime role fence. */
	public sealed interface DefaultsSource permits StandardDefaults, AgentDefaults, UnavailableDefaults {
		AgentNewTaskDefaults defaults();
		default Optional<AgentNewTaskDefaults> availableDefaults() { return Optional.of(defaults()); }
		default NewTaskContextProjection.Status status() { return NewTaskContextProjection.Status.CURRENT; }
		boolean isCurrentFor(ClusterEngineState engine);
	}
	public static final class UnavailableDefaults implements DefaultsSource {
		private final PanelPublicationFence fence;
		private final ClusterServiceRole role;
		private final NewTaskContextProjection.Status status;
		private UnavailableDefaults(PanelPublicationFence fence, ClusterServiceRole role,
			NewTaskContextProjection.Status status) {
			this.fence = Objects.requireNonNull(fence); this.role = Objects.requireNonNull(role);
			this.status = Objects.requireNonNull(status);
			if (status == NewTaskContextProjection.Status.CURRENT || status == NewTaskContextProjection.Status.STALE)
				throw new IllegalArgumentException("missing defaults require an unavailable reason");
		}
		@Override public AgentNewTaskDefaults defaults() { throw new IllegalStateException("New Task defaults unavailable"); }
		@Override public Optional<AgentNewTaskDefaults> availableDefaults() { return Optional.empty(); }
		@Override public NewTaskContextProjection.Status status() { return status; }
		@Override public boolean isCurrentFor(ClusterEngineState engine) { return fence.isCurrent(engine, role); }
	}
	public static DefaultsSource unavailableDefaults(PanelPublicationFence fence, ClusterServiceRole role,
		NewTaskContextProjection.Status reason) { return new UnavailableDefaults(fence, role, reason); }
	public static final class StandardDefaults implements DefaultsSource {
		private final PanelPublicationFence fence;
		private final AgentNewTaskDefaults defaults;
		private StandardDefaults(PanelPublicationFence fence, AgentNewTaskDefaults defaults) {
			this.fence = Objects.requireNonNull(fence, "fence");
			this.defaults = Objects.requireNonNull(defaults, "defaults");
		}
		@Override public AgentNewTaskDefaults defaults() { return defaults; }
		@Override public boolean isCurrentFor(ClusterEngineState engine) {
			return fence.isCurrent(engine, ClusterServiceRole.STANDARD);
		}
	}
	public static final class AgentDefaults implements DefaultsSource {
		private final PanelPublicationFence fence;
		private final AgentNewTaskDefaults defaults;
		private final FormationPublication publication;
		private AgentDefaults(PanelPublicationFence fence, AgentNewTaskDefaults defaults,
			FormationPublication publication) {
			this.fence = Objects.requireNonNull(fence, "fence");
			this.defaults = Objects.requireNonNull(defaults, "defaults");
			this.publication = Objects.requireNonNull(publication, "publication");
		}
		@Override public AgentNewTaskDefaults defaults() { return defaults; }
		public FormationPublication publication() { return publication; }
		@Override public boolean isCurrentFor(ClusterEngineState engine) {
			return fence.isCurrent(engine, ClusterServiceRole.AGENT)
				&& matchesCurrentAgentDefault(engine, defaults, publication, fence);
		}
	}
	public static DefaultsSource standardDefaults(PanelPublicationFence fence,
		AgentNewTaskDefaults defaults) {
		return new StandardDefaults(fence, defaults);
	}
	public static DefaultsSource agentDefaults(PanelPublicationFence fence,
		AgentNewTaskDefaults defaults, FormationPublication publication) {
		return new AgentDefaults(fence, defaults, publication);
	}

	/** One unpublished immutable capture. View revisions are allocated only by PublicationAuthority. */
	public record CaptureFacts(ClusterEngineState engine,
		Map<UUID, TaskProjectionService.GoalDisplay> displays, DefaultsSource defaultsSource,
		Set<CreateRouteKey> localValidEntrances) {
		public CaptureFacts {
			engine = Objects.requireNonNull(engine, "engine");
			displays = Map.copyOf(Objects.requireNonNull(displays, "displays"));
			defaultsSource = Objects.requireNonNull(defaultsSource, "defaultsSource");
			localValidEntrances = Set.copyOf(Objects.requireNonNull(localValidEntrances,
				"localValidEntrances"));
			AgentNewTaskDefaults defaults = defaultsSource.availableDefaults().orElse(null);
			if (!engine.goals().keySet().equals(displays.keySet()))
				throw new IllegalArgumentException("Goal display index differs from engine Goals");
			if (defaults != null && (!engine.clusterId().equals(defaults.ownerClusterId())
				|| !localValidEntrances.contains(defaults.selectedInput())))
				throw new IllegalArgumentException("New Task default is not a current local entrance");
			if (!defaultsSource.isCurrentFor(engine))
				throw new IllegalArgumentException("New Task defaults lack a current runtime role publication");
			for (Map.Entry<UUID, TaskProjectionService.GoalDisplay> entry : displays.entrySet()) {
				TaskProjectionService.GoalDisplay display = entry.getValue();
				if (!display.computationSelection().equals(
					FactoryGoalTransitionPolicy.computationSelection(engine, entry.getKey()))
					|| display.recommendedInput().isPresent()
						&& !localValidEntrances.contains(display.recommendedInput().orElseThrow()))
					throw new IllegalArgumentException("Goal display differs from current transition/default capture");
			}
		}
	}

	/** Opaque result whose revisions were assigned by its owning runtime authority. */
	public static final class PublishedSnapshot {
		private final PublicationAuthority authority;
		private final ClusterPublicationOwner owner;
		private final long tasksRevision;
		private final long newTaskRevision;
		private final CaptureFacts facts;
		private PublishedSnapshot(PublicationAuthority authority, ClusterPublicationOwner owner, long tasksRevision,
			long newTaskRevision, CaptureFacts facts) {
			this.authority = authority; this.owner = owner; this.tasksRevision = tasksRevision;
			this.newTaskRevision = newTaskRevision; this.facts = facts;
		}
		public ClusterEngineState engine() { return facts.engine(); }
		public long tasksRevision() { return tasksRevision; }
		public long newTaskRevision() { return newTaskRevision; }
		public Map<UUID, TaskProjectionService.GoalDisplay> displays() { return facts.displays(); }
		public AgentNewTaskDefaults defaults() { return facts.defaultsSource().defaults(); }
		public Optional<AgentNewTaskDefaults> availableDefaults() { return facts.defaultsSource().availableDefaults(); }
		public NewTaskContextProjection.Status newTaskStatus() { return facts.defaultsSource().status(); }
		public Set<CreateRouteKey> localValidEntrances() { return facts.localValidEntrances(); }
		public boolean isCurrentPublication() {
			return authority.isCurrent(this) && facts.defaultsSource().isCurrentFor(facts.engine());
		}
	}

	/** Runtime-owned owner map. A new instance is an isolated server/world authority. */
	public static final class PublicationAuthority implements AutoCloseable {
		private final Map<UUID, ClusterPublicationOwner> owners = new HashMap<>();
		private boolean closed;
		private PublicationAuthority() {}
		public static PublicationAuthority runtimeAuthority() { return new PublicationAuthority(); }
		/** Runtime-only path: the opaque fence checks current state under the runtime lock. */
		public PublishedSnapshot publish(PanelPublicationFence fence, CaptureFacts facts) {
			facts = Objects.requireNonNull(facts, "facts");
			Objects.requireNonNull(fence, "fence").requireTaskFacts(facts);
			synchronized (this) {
				if (closed) throw new IllegalStateException("Task publication authority is closed");
				return publishUnchecked(facts);
			}
		}
		private PublishedSnapshot publishUnchecked(CaptureFacts facts) {
			ClusterPublicationOwner owner = owners.computeIfAbsent(facts.engine().clusterId(),
				ClusterPublicationOwner::new);
			return owner.publish(this, facts);
		}
		public synchronized Optional<PublishedSnapshot> capture(UUID clusterId) {
			if (closed) return Optional.empty();
			ClusterPublicationOwner owner = owners.get(Objects.requireNonNull(clusterId, "clusterId"));
			return owner == null ? Optional.empty() : Optional.ofNullable(owner.current);
		}
		/** Clears one exact epoch's retained payload while keeping its lightweight revision owner. */
		public synchronized void retireEpoch(UUID clusterId, UUID epochId) {
			ClusterPublicationOwner owner = owners.get(Objects.requireNonNull(clusterId, "clusterId"));
			if (owner != null) owner.retire(Objects.requireNonNull(epochId, "epochId"));
		}
		/** Called by runtime epoch replacement/unload before it drops the Cluster state. */
		public synchronized void revokeCluster(UUID clusterId) {
			ClusterPublicationOwner owner = owners.remove(Objects.requireNonNull(clusterId, "clusterId"));
			if (owner != null) owner.revoke();
		}
		private synchronized boolean isCurrent(PublishedSnapshot candidate) {
			return !closed && candidate.owner != null && candidate.owner.isCurrent(candidate);
		}
		public synchronized int publicationCountForTest() {
			return (int) owners.values().stream().filter(ClusterPublicationOwner::retainsPayload).count();
		}
		@Override public synchronized void close() {
			if (closed) return;
			closed = true; owners.values().forEach(ClusterPublicationOwner::revoke); owners.clear();
		}
	}

	private static boolean matchesCurrentAgentDefault(ClusterEngineState engine,
		AgentNewTaskDefaults defaults, FormationPublication publication, PanelPublicationFence fence) {
		if (!publication.isCurrentPublication()
			|| !publication.belongsTo(fence)
			|| !engine.clusterId().equals(publication.formation().ownerClusterId())
			|| !engine.epochId().equals(publication.epochId())) return false;
		return AgentNewTaskDefaults.tryResolve(publication, Optional.of(defaults.selectedInput()))
			.filter(defaults::equals).isPresent();
	}

	private static final class ClusterPublicationOwner {
		private final UUID clusterId;
		private final Set<UUID> retiredEpochs = new HashSet<>();
		private UUID epochId;
		private long generation;
		private TaskFacts taskFacts;
		private NewTaskFacts newTaskFacts;
		private long tasksRevision;
		private long newTaskRevision;
		private boolean retirementRevisionExhausted;
		private PublishedSnapshot current;
		private boolean revoked;
		private ClusterPublicationOwner(UUID clusterId) { this.clusterId = clusterId; }
		private PublishedSnapshot publish(PublicationAuthority authority, CaptureFacts facts) {
			if (revoked || !clusterId.equals(facts.engine().clusterId()))
				throw new IllegalStateException("Task publication owner is revoked");
			if (epochId != null && !epochId.equals(facts.engine().epochId())) {
				if (retiredEpochs.contains(facts.engine().epochId()))
					throw new IllegalArgumentException("Task publication reopens retired Cluster epoch");
				retiredEpochs.add(epochId); epochId = null; current = null;
			}
			if (epochId == null) {
				epochId = facts.engine().epochId(); generation = facts.engine().generation();
				taskFacts = null; newTaskFacts = null; tasksRevision = 0; newTaskRevision = 0;
				retirementRevisionExhausted = false;
			} else if (facts.engine().generation() < generation)
				throw new IllegalArgumentException("Task publication generation regressed");
			else if (facts.engine().generation() == generation && current != null
				&& !current.engine().equals(facts.engine()))
				throw new IllegalArgumentException("Task publication changed engine without generation");
			if (current == null && retirementRevisionExhausted)
				throw new IllegalStateException("Task view revision exhausted during lifecycle retirement");
			TaskFacts nextTasks = new TaskFacts(facts.engine(), facts.displays());
			NewTaskFacts nextNewTask = new NewTaskFacts(facts.engine(), facts.displays(),
				new DefaultsValue(facts.defaultsSource() instanceof AgentDefaults,
					facts.defaultsSource().availableDefaults(), facts.defaultsSource().status()), facts.localValidEntrances());
			if (current != null && taskFacts.equals(nextTasks) && newTaskFacts.equals(nextNewTask))
				return current;
			if (current != null) {
				if (!taskFacts.equals(nextTasks)) tasksRevision = nextRevision(tasksRevision);
				if (!newTaskFacts.equals(nextNewTask)) newTaskRevision = nextRevision(newTaskRevision);
			}
			generation = facts.engine().generation(); taskFacts = nextTasks; newTaskFacts = nextNewTask;
			current = new PublishedSnapshot(authority, this, tasksRevision, newTaskRevision, facts);
			return current;
		}
		private boolean isCurrent(PublishedSnapshot candidate) { return !revoked && current == candidate; }
		private void retire(UUID exactEpochId) {
			if (revoked || epochId == null || !epochId.equals(exactEpochId)) return;
			if (!retainsPayload()) return;
			current = null;
			taskFacts = null;
			newTaskFacts = null;
			if (tasksRevision == Long.MAX_VALUE || newTaskRevision == Long.MAX_VALUE)
				retirementRevisionExhausted = true;
			else {
				tasksRevision++;
				newTaskRevision++;
			}
		}
		private boolean retainsPayload() {
			return current != null || taskFacts != null || newTaskFacts != null;
		}
		private void revoke() {
			revoked = true;
			current = null;
			taskFacts = null;
			newTaskFacts = null;
			retiredEpochs.clear();
			epochId = null;
		}
	}
	private static long nextRevision(long current) {
		if (current == Long.MAX_VALUE) throw new IllegalStateException("Task view revision exhausted");
		return current + 1;
	}
	private record TaskFacts(ClusterEngineState engine,
		Map<UUID, TaskProjectionService.GoalDisplay> displays) {}
	private record NewTaskFacts(ClusterEngineState engine,
		Map<UUID, TaskProjectionService.GoalDisplay> displays, DefaultsValue defaults,
		Set<CreateRouteKey> entrances) {}
	private record DefaultsValue(boolean agent, Optional<AgentNewTaskDefaults> defaults, NewTaskContextProjection.Status status) {}
}
