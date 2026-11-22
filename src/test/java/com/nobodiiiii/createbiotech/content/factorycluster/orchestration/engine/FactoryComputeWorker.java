package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.ProductionCatalogSearch;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.ProductionRecipeCatalog;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRequest;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionResult;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRevision;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternQuery;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternReply;

/** One daemon worker owned by one server runtime service. */
public final class FactoryComputeWorker implements AutoCloseable {
	public static final int MAX_DIAGNOSTIC_LENGTH = 1024;
	private static final AtomicLong THREAD_IDS = new AtomicLong();
	private final ClusterEngine engine;
	private final FactoryRuntimeConfig config;
	private final BlockingQueue<Work> input;
	private final BlockingQueue<ComputeResult> output;
	private final Semaphore outstanding;
	private final Consumer<UUID> transitionObserver;
	private final Object lifecycle = new Object();
	private final Thread thread;
	private boolean started;
	private boolean accepting = true;
	private boolean closed;
	private volatile boolean closing;

	public FactoryComputeWorker(ClusterEngine engine, FactoryRuntimeConfig config) {
		this(engine, config, ignored -> {});
	}

	FactoryComputeWorker(ClusterEngine engine, FactoryRuntimeConfig config, Consumer<UUID> transitionObserver) {
		this.engine = Objects.requireNonNull(engine, "engine"); this.config = Objects.requireNonNull(config, "config");
		this.transitionObserver = Objects.requireNonNull(transitionObserver, "transitionObserver");
		int queueCapacity = Math.addExact(config.pendingRunCapacity(), 1); // one reserved continuation slot
		input = new ArrayBlockingQueue<>(queueCapacity);
		output = new ArrayBlockingQueue<>(queueCapacity);
		outstanding = new Semaphore(config.pendingRunCapacity());
		thread = new Thread(this::run, "create-biotech-factory-compute-" + THREAD_IDS.incrementAndGet());
		thread.setDaemon(true);
	}

	public void start() {
		synchronized (lifecycle) {
			if (closed) throw new IllegalStateException("worker is closed");
			if (!started) { started = true; thread.start(); }
		}
	}

	public void submit(ClusterEngineState state, List<WorldFact> facts) {
		if (!trySubmit(state, facts)) throw new IllegalStateException("worker input queue is full");
	}

	boolean trySubmit(ClusterEngineState state, List<WorldFact> facts) {
		List<WorldFact> immutableFacts = List.copyOf(Objects.requireNonNull(facts));
		if (immutableFacts.size() > config.pendingRunCapacity()) throw new IllegalArgumentException("fact batch exceeds configured bound");
		Work work = new Work(Objects.requireNonNull(state), immutableFacts, List.of(), false, null,
			null, null);
		synchronized (lifecycle) {
			if (!accepting) throw new IllegalStateException("worker no longer accepts snapshots");
			if (!started) throw new IllegalStateException("worker is not started");
			if (!outstanding.tryAcquire()) return false;
			if (input.offer(work)) return true;
			outstanding.release(); return false;
		}
	}

	boolean trySubmitLibrary(ClusterEngineState state, LibraryWork libraryWork) {
		Work work = new Work(Objects.requireNonNull(state), List.of(), List.of(), false, null,
			Objects.requireNonNull(libraryWork), null);
		synchronized (lifecycle) {
			if (!accepting) throw new IllegalStateException("worker no longer accepts snapshots");
			if (!started) throw new IllegalStateException("worker is not started");
			if (!outstanding.tryAcquire()) return false;
			if (input.offer(work)) return true;
			outstanding.release(); return false;
		}
	}

	/** Accepts one receiver-owned pure job against the exact immutable process snapshot. */
	boolean trySubmitRemote(ClusterEngineState state, RemotePureWork remoteWork) {
		Work work = new Work(Objects.requireNonNull(state), List.of(), List.of(), false, null,
			null, Objects.requireNonNull(remoteWork));
		synchronized (lifecycle) {
			if (!accepting) throw new IllegalStateException("worker no longer accepts snapshots");
			if (!started) throw new IllegalStateException("worker is not started");
			if (!outstanding.tryAcquire()) return false;
			if (input.offer(work)) return true;
			outstanding.release(); return false;
		}
	}

	public List<ComputeResult> drainResults(int maximum) {
		if (maximum < 0) throw new IllegalArgumentException("negative result limit");
		ArrayList<ComputeResult> results = new ArrayList<>(maximum);
		output.drainTo(results, maximum);
		outstanding.release(results.size());
		return List.copyOf(results);
	}

	private void run() {
		long nextCycleNanos = 0;
		while (true) {
			try {
				Work work = input.poll(config.workerCadenceMillis(), TimeUnit.MILLISECONDS);
				if (work == null) {
					if (closing && input.isEmpty()) break;
					continue;
				}
				awaitCadence(nextCycleNanos);
				long cycleStart = System.nanoTime();
				long budgetNanos = Math.multiplyExact(config.workerBudgetMicros(), 1_000L);
				long deadline = Long.MAX_VALUE - cycleStart < budgetNanos ? Long.MAX_VALUE : cycleStart + budgetNanos;
				Set<UUID> advancedThisCycle = new HashSet<>();
				do {
					Work selected = preferAnotherCluster(work, advancedThisCycle, deadline);
					if (selected == null) break;
					work = selected;
					compute(work, deadline);
					advancedThisCycle.add(work.state.clusterId());
					if (System.nanoTime() >= deadline) break;
					work = input.poll();
				} while (work != null);
				long cadenceNanos = TimeUnit.MILLISECONDS.toNanos(config.workerCadenceMillis());
				nextCycleNanos = Long.MAX_VALUE - cycleStart < cadenceNanos ? Long.MAX_VALUE : cycleStart + cadenceNanos;
			} catch (InterruptedException interrupted) {
				if (closing && input.isEmpty()) break;
			}
		}
	}

	private void awaitCadence(long deadlineNanos) {
		for (;;) {
			if (closing) return;
			long remaining = deadlineNanos - System.nanoTime();
			if (remaining <= 0) return;
			try { TimeUnit.NANOSECONDS.sleep(remaining); }
			catch (InterruptedException ignored) { if (closing) return; }
		}
	}

	@Nullable private Work preferAnotherCluster(Work first, Set<UUID> advancedThisCycle, long deadline) {
		if (closing || !advancedThisCycle.contains(first.state.clusterId())) return first;
		int queued = input.size();
		for (int index = 0; index < queued; index++) {
			if (System.nanoTime() >= deadline) { input.add(first); return null; }
			Work candidate = input.poll();
			if (candidate == null) break;
			if (!advancedThisCycle.contains(candidate.state.clusterId())) {
				input.add(first);
				return candidate;
			}
			input.add(candidate);
		}
		input.add(first);
		return null;
	}

	private void compute(Work work, long deadline) {
		try {
			if (work.remoteWork != null) {
				transitionObserver.accept(work.state.clusterId());
				RemotePureResult result = computeRemote(work.state, work.remoteWork);
				output.add(new ComputeResult(work.state.clusterId(), work.state, List.of(), false,
					work.diagnostic, List.of(), Optional.empty(), Optional.of(result),
					Optional.of(work.remoteWork)));
				return;
			}
			if (work.libraryWork != null) {
				transitionObserver.accept(work.state.clusterId());
				ProductionCatalogSearch.Advance advance = ProductionCatalogSearch.advance(work.libraryWork.catalog, work.libraryWork.query);
				output.add(new ComputeResult(work.state.clusterId(), work.state, List.of(), true,
					work.diagnostic, advance.reply().stream().toList(), advance.continuation(),
					Optional.empty(), Optional.empty()));
				return;
			}
			List<WorldFact> stepFacts = work.facts.isEmpty() ? List.of() : List.of(work.facts.getFirst());
			transitionObserver.accept(work.state.clusterId());
			ClusterEngine.EngineAdvance advance = engine.advance(work.state, stepFacts, config, deadline);
			if (!stepFacts.isEmpty() && advance.state() == work.state && advance.yielded()) {
				input.add(work);
				return;
			}
			ArrayList<ProductionEffect> accumulated = new ArrayList<>(work.intents);
			accumulated.addAll(advance.intents());
			if (work.facts.size() > 1) {
				input.add(new Work(advance.state(), List.copyOf(work.facts.subList(1, work.facts.size())),
					List.copyOf(accumulated), true, work.diagnostic, null, null));
				return;
			}
			output.add(new ComputeResult(work.state.clusterId(), advance.state(), accumulated,
				advance.yielded(), work.diagnostic, List.of(), Optional.empty(), Optional.empty(),
				Optional.empty()));
		} catch (Throwable failure) {
			String detail = failure.getClass().getSimpleName() + ": " + Objects.toString(failure.getMessage(), "transition failed");
			if (detail.length() > MAX_DIAGNOSTIC_LENGTH) detail = detail.substring(0, MAX_DIAGNOSTIC_LENGTH);
			Diagnostic diagnostic = combine(work.diagnostic, detail);
			if (work.facts.size() > 1) {
				input.add(new Work(work.state, List.copyOf(work.facts.subList(1, work.facts.size())),
					work.intents, true, diagnostic, null, null));
				return;
			}
			output.add(new ComputeResult(work.state.clusterId(), work.state, work.intents, false, diagnostic,
				List.of(), work.libraryWork == null ? Optional.empty() : Optional.of(work.libraryWork.query),
				Optional.empty(), Optional.ofNullable(work.remoteWork)));
		}
	}

	private static RemotePureResult computeRemote(ClusterEngineState state,
		RemotePureWork work) {
		if (!state.clusterId().equals(work.receiverClusterId()))
			throw new IllegalArgumentException("remote work has the wrong receiver Cluster");
		ProcessRecord process = state.processes().get(work.processId());
		if (process == null || process.kind() != ProcessKind.REMOTE_LISTENER
				&& process.kind() != ProcessKind.PURE_EXPANSION
			|| process.state() == ProcessState.TERMINAL
			|| !process.owningComputerId().equals(work.receiverMemberId())
			|| process.owningSlot() != work.owningSlot()
			|| !process.owningComputer().supports(work.requiredCapability()))
			throw new IllegalArgumentException("remote work lacks its exact receiver process");
		if (work.stage() == RemotePureStage.REVISION) {
			if (work.acceptedRevision().isPresent())
				throw new IllegalArgumentException("revision work cannot contain a prior revision");
			return RemotePureResult.revision(work.leaseId(), work.revision(),
				RemoteExpansionRevision.compute(work.expansionRequest()));
		}
		RemoteExpansionRevision accepted = work.acceptedRevision().orElseThrow(() ->
				new IllegalArgumentException("result work requires the acknowledged revision"));
		return RemotePureResult.result(work.leaseId(), work.revision(),
			RemoteExpansionResult.compute(work.expansionRequest(), accepted));
	}

	private static Diagnostic combine(@Nullable Diagnostic prior, String detail) {
		String combined = prior == null ? detail : prior.message() + "; " + detail;
		if (combined.length() > MAX_DIAGNOSTIC_LENGTH) combined = combined.substring(0, MAX_DIAGNOSTIC_LENGTH);
		return new Diagnostic(combined);
	}

	@Override public void close() {
		synchronized (lifecycle) {
			if (closed) return;
			accepting = false;
			closing = true;
			if (started) thread.interrupt();
			else closed = true;
		}
		if (started) {
			boolean interrupted = false;
			for (;;) try { thread.join(); break; } catch (InterruptedException exception) { interrupted = true; thread.interrupt(); }
			synchronized (lifecycle) { closed = true; }
			if (interrupted) Thread.currentThread().interrupt();
		}
	}

	public boolean isAlive() { return thread.isAlive(); }
	public boolean isDaemon() { return thread.isDaemon(); }
	public String threadName() { return thread.getName(); }
	public boolean isClosed() { synchronized (lifecycle) { return closed; } }

	public record Diagnostic(String message) {
		public Diagnostic { Objects.requireNonNull(message); if (message.length() > MAX_DIAGNOSTIC_LENGTH) throw new IllegalArgumentException("diagnostic exceeds bound"); }
	}
	public record ComputeResult(UUID clusterId, ClusterEngineState state, List<ProductionEffect> intents,
		boolean yielded, @Nullable Diagnostic diagnostic, List<PatternReply> libraryReplies,
		Optional<PatternQuery> libraryContinuation, Optional<RemotePureResult> remotePureResult,
		Optional<RemotePureWork> remotePureWork) {
		public ComputeResult {
			Objects.requireNonNull(clusterId); Objects.requireNonNull(state);
			intents = List.copyOf(Objects.requireNonNull(intents));
			libraryReplies = List.copyOf(Objects.requireNonNull(libraryReplies));
			Objects.requireNonNull(libraryContinuation); Objects.requireNonNull(remotePureResult);
			Objects.requireNonNull(remotePureWork);
		}
	}
	private record Work(ClusterEngineState state, List<WorldFact> facts, List<ProductionEffect> intents,
		boolean continuation, @Nullable Diagnostic diagnostic, @Nullable LibraryWork libraryWork,
		@Nullable RemotePureWork remoteWork) {}
	record LibraryWork(ProductionRecipeCatalog catalog, PatternQuery query) {
		LibraryWork { Objects.requireNonNull(catalog); Objects.requireNonNull(query); }
	}

	public enum RemotePureStage { REVISION, RESULT }

	/** Bounded immutable receiver input. It contains no world object or material authority. */
	public record RemotePureWork(UUID leaseId, UUID receiverClusterId, UUID receiverMemberId,
		UUID processId, int owningSlot, long receiverGeneration, long revision,
		ProductionCapability requiredCapability, RemoteExpansionRequest expansionRequest,
		RemotePureStage stage, Optional<RemoteExpansionRevision> acceptedRevision) {
		public RemotePureWork {
			leaseId = Objects.requireNonNull(leaseId, "leaseId");
			receiverClusterId = Objects.requireNonNull(receiverClusterId, "receiverClusterId");
			receiverMemberId = Objects.requireNonNull(receiverMemberId, "receiverMemberId");
			processId = Objects.requireNonNull(processId, "processId");
			if (owningSlot < 0 || receiverGeneration < 0 || revision < 0)
				throw new IllegalArgumentException("remote work fences cannot be negative");
			requiredCapability = Objects.requireNonNull(requiredCapability, "requiredCapability");
			expansionRequest = Objects.requireNonNull(expansionRequest, "expansionRequest");
			stage = Objects.requireNonNull(stage, "stage");
			acceptedRevision = Objects.requireNonNull(acceptedRevision, "acceptedRevision");
		}
	}

	public record RemotePureResult(UUID leaseId, RemotePureStage stage, long revision,
		Optional<RemoteExpansionRevision> revisionState,
		Optional<RemoteExpansionResult> expansionResult) {
		public RemotePureResult {
			leaseId = Objects.requireNonNull(leaseId, "leaseId");
			stage = Objects.requireNonNull(stage, "stage");
			if (revision < 0) throw new IllegalArgumentException("negative remote result revision");
			revisionState = Objects.requireNonNull(revisionState, "revisionState");
			expansionResult = Objects.requireNonNull(expansionResult, "expansionResult");
			if ((stage == RemotePureStage.REVISION) != revisionState.isPresent()
				|| (stage == RemotePureStage.RESULT) != expansionResult.isPresent())
				throw new IllegalArgumentException("remote result stage/value mismatch");
		}

		private static RemotePureResult revision(UUID leaseId, long revision,
			RemoteExpansionRevision value) {
			return new RemotePureResult(leaseId, RemotePureStage.REVISION, revision,
				Optional.of(value), Optional.empty());
		}

		private static RemotePureResult result(UUID leaseId, long revision,
			RemoteExpansionResult value) {
			return new RemotePureResult(leaseId, RemotePureStage.RESULT, revision,
				Optional.empty(), Optional.of(value));
		}
	}
}
