package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.FactoryPublicTextPolicy;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldFact;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerSlotProjection.RemoteState;

/** Immutable capture facts and their JVM-lifetime Cluster-epoch publication boundary. */
public final class ComputerMonitorSnapshot {
	private static final int UUID_HEX_DIGITS = 32;
	private static final String REDACTED = "[redacted]";
	private static final Object EPOCH_PUBLICATION_LOCK = new Object();
	private static final Map<EpochFence, EpochPublicationOwner> EPOCH_PUBLICATIONS = new HashMap<>();

	private ComputerMonitorSnapshot() {}

	/**
	 * One unpublished, immutable capture made in a single server lock domain. It deliberately has
	 * no revision: only a {@link RevisionSequence} may turn these facts into a client-visible view.
	 */
	public record CaptureFacts(ClusterEngineState engine, Map<UUID, Integer> residentLevels,
		Map<UUID, GoalEvidence> goals, Map<UUID, ProcessEvidence> processes) {
		public CaptureFacts {
			engine = Objects.requireNonNull(engine, "engine");
			residentLevels = Map.copyOf(Objects.requireNonNull(residentLevels, "residentLevels"));
			goals = Map.copyOf(Objects.requireNonNull(goals, "goals"));
			processes = Map.copyOf(Objects.requireNonNull(processes, "processes"));

			Set<UUID> memberIds = engine.members().stream()
				.map(ClusterEngineState.MemberSnapshot::computerId)
				.collect(Collectors.toUnmodifiableSet());
			if (!residentLevels.keySet().equals(memberIds)
				|| !goals.keySet().equals(engine.goals().keySet())
				|| !processes.keySet().equals(engine.processes().keySet()))
				throw new IllegalArgumentException("monitor snapshot evidence is not a complete exact capture");
			residentLevels.forEach((computerId, level) -> {
				Objects.requireNonNull(computerId, "resident computerId");
				if (level == null || level < 1 || level > 5)
					throw new IllegalArgumentException("invalid exact resident level");
			});
			ClusterEngineState frozenEngine = engine;
			Set<OwnedSlot> occupiedSlots = new HashSet<>();
			frozenEngine.processes().values().forEach(process -> {
				ClusterEngineState.MemberSnapshot member = frozenEngine.member(process.owningComputerId());
				if (member == null || process.owningSlot() < 0
					|| process.owningSlot() >= member.profile().processSlots()
					|| !allowsGoalLess(process.kind())
						&& !frozenEngine.goals().containsKey(process.goalId())
					|| !occupiedSlots.add(new OwnedSlot(process.owningComputerId(), process.owningSlot())))
					throw new IllegalArgumentException("monitor snapshot contains an orphan process");
			});
		}
	}

	/** Opaque publication whose authoritative epoch revision cannot be supplied by a caller. */
	public static final class PublishedSnapshot {
		private final EpochPublicationOwner owner;
		private final long viewRevision;
		private final CaptureFacts facts;

		private PublishedSnapshot(EpochPublicationOwner owner, long viewRevision, CaptureFacts facts) {
			this.owner = owner;
			this.viewRevision = viewRevision;
			this.facts = facts;
		}

		public long viewRevision() { return viewRevision; }
		public ClusterEngineState engine() { return facts.engine(); }
		public Map<UUID, Integer> residentLevels() { return facts.residentLevels(); }
		public Map<UUID, GoalEvidence> goals() { return facts.goals(); }
		public Map<UUID, ProcessEvidence> processes() { return facts.processes(); }

		boolean isCurrentPublication() { return owner.isCurrent(this); }
	}

	/** Structured output evidence; raw diagnostic identifiers are cleaned on construction. */
	public record GoalEvidence(String output, long count) {
		public GoalEvidence {
			output = safeText(output);
			if (count < 0) throw new IllegalArgumentException("negative monitor goal count");
		}
	}

	/** Structured inventory leaf rather than a free-form diagnostic line. */
	public record InventoryEvidence(String output, long count,
		Optional<com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey> item) {
		public InventoryEvidence(String output, long count) { this(output, count, Optional.empty()); }
		public InventoryEvidence(com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey item,
			long count) {
			this(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.stack().getItem())
				.toString(), count, Optional.of(item));
		}
		public InventoryEvidence {
			output = safeText(output);
			item = Objects.requireNonNull(item, "item");
			if (count < 0) throw new IllegalArgumentException("negative monitor inventory count");
		}
	}

	/** All non-engine process facts frozen in the same capture as the engine. */
	public record ProcessEvidence(String libraryDisplay, Optional<SpaceAddress> createAddress,
		List<InventoryEvidence> inventory, boolean unresolvedSearch, boolean listener,
		boolean handoff, OptionalInt agentQueuePosition, Optional<String> logisticsAlias,
		RemoteState remoteState) {
		public ProcessEvidence {
			libraryDisplay = safeText(libraryDisplay);
			createAddress = Objects.requireNonNull(createAddress, "createAddress");
			inventory = List.copyOf(Objects.requireNonNull(inventory, "inventory"));
			agentQueuePosition = Objects.requireNonNull(agentQueuePosition, "agentQueuePosition");
			logisticsAlias = Objects.requireNonNull(logisticsAlias, "logisticsAlias")
				.map(ComputerMonitorSnapshot::safeText).filter(value -> !value.isEmpty());
			remoteState = Objects.requireNonNull(remoteState, "remoteState");
			if (inventory.size() > WorldFact.MAX_LIST_ENTRIES
				|| agentQueuePosition.isPresent() && agentQueuePosition.orElseThrow() < 0)
				throw new IllegalArgumentException("invalid frozen process evidence");
		}
	}

	/** Normalizes display text and fails closed on a 32-hex identity privacy skeleton. */
	public static String safeText(String raw) {
		raw = Normalizer.normalize(Objects.requireNonNull(raw, "monitor display text"),
			Normalizer.Form.NFKC);
		if (containsHexIdentity(raw)) return REDACTED;
		StringBuilder normalized = new StringBuilder(raw.length());
		raw.codePoints().forEach(value -> {
			int type = Character.getType(value);
			if (isPrivacyIgnorable(value)) return;
			if (type == Character.DASH_PUNCTUATION || value == 0x2212) {
				normalized.append('-');
				return;
			}
			if (type == Character.SPACE_SEPARATOR || type == Character.LINE_SEPARATOR
				|| type == Character.PARAGRAPH_SEPARATOR) {
				normalized.append(' ');
				return;
			}
			normalized.appendCodePoint(value);
		});
		return normalized.substring(0, Math.min(normalized.length(), WorldFact.MAX_TEXT_LENGTH));
	}

	private static boolean containsHexIdentity(String value) {
		// Every non-alphanumeric code point is a conservative separator. Only a real non-hex
		// letter/digit interrupts the skeleton, so no punctuation or combining-mark allowlist exists.
		int hexDigits = 0;
		var codePoints = value.codePoints().iterator();
		while (codePoints.hasNext()) {
			int codePoint = codePoints.nextInt();
			if (isAsciiHex(codePoint)) {
				if (++hexDigits >= UUID_HEX_DIGITS) return true;
			} else if (!isIdentitySeparator(codePoint)) {
				hexDigits = 0;
			}
		}
		return false;
	}

	private static boolean isAsciiHex(int value) {
		return value >= '0' && value <= '9' || value >= 'a' && value <= 'f'
			|| value >= 'A' && value <= 'F';
	}

	private static boolean isIdentitySeparator(int value) {
		return isPrivacyIgnorable(value) || !Character.isLetterOrDigit(value);
	}

	private static boolean isPrivacyIgnorable(int value) {
		return Character.getType(value) == Character.CONTROL
			|| FactoryPublicTextPolicy.isDefaultIgnorable(value);
	}

	/**
	 * Publisher facade for exactly one formed Cluster epoch. All facades resolve the same
	 * JVM-lifetime epoch owner. Rebuilding this object always gets a later publication revision,
	 * even for equal facts, while an unchanged capture on the current facade reuses its publication.
	 * The capture and this call must still share the server lock domain.
	 */
	public static final class RevisionSequence {
		private EpochFence epoch;
		private PublishedSnapshot current;

		public synchronized PublishedSnapshot publish(CaptureFacts facts) {
			facts = Objects.requireNonNull(facts, "facts");
			EpochFence candidateEpoch = new EpochFence(facts.engine().clusterId(),
				facts.engine().epochId());
			if (epoch == null) epoch = candidateEpoch;
			else if (!epoch.equals(candidateEpoch))
				throw new IllegalArgumentException("monitor revision sequence changed Cluster epoch");
				synchronized (EPOCH_PUBLICATION_LOCK) {
				EpochPublicationOwner owner = EPOCH_PUBLICATIONS.computeIfAbsent(epoch,
					EpochPublicationOwner::new);
				if (current != null && current.owner == owner && owner.isCurrentLocked(current)
					&& current.facts.equals(facts))
					return current;
				current = owner.publish(facts);
				return current;
			}
		}

		static long nextRevision(long current) {
			if (current == Long.MAX_VALUE)
				throw new IllegalStateException("monitor view revision exhausted");
			return current + 1;
		}
	}

	private static final class EpochPublicationOwner {
		private final EpochFence epoch;
		private boolean hasPublication;
		private long currentRevision;

		private EpochPublicationOwner(EpochFence epoch) {
			this.epoch = epoch;
		}

		private PublishedSnapshot publish(CaptureFacts facts) {
			if (!epoch.equals(new EpochFence(facts.engine().clusterId(), facts.engine().epochId())))
				throw new IllegalArgumentException("monitor publication owner changed Cluster epoch");
			long revision = hasPublication ? RevisionSequence.nextRevision(currentRevision) : 0;
			currentRevision = revision;
			hasPublication = true;
			return new PublishedSnapshot(this, revision, facts);
		}

		private boolean isCurrent(PublishedSnapshot candidate) {
			synchronized (EPOCH_PUBLICATION_LOCK) {
				return isCurrentLocked(candidate);
			}
		}

		private boolean isCurrentLocked(PublishedSnapshot candidate) {
			return candidate.owner == this && hasPublication
				&& candidate.viewRevision == currentRevision;
		}
	}

	private static boolean allowsGoalLess(ProcessKind kind) {
		return switch (kind) {
			case AGENT, REMOTE_LISTENER, RADIO_LISTENER, LIBRARY_QUERY,
				SPECULATIVE_EXPANSION, PATTERN_PRIORITY_LEASE, PRIORITY_LEASE -> true;
			default -> false;
		};
	}

	private record OwnedSlot(UUID computerId, int slot) {}
	private record EpochFence(UUID clusterId, UUID epochId) {}
}
