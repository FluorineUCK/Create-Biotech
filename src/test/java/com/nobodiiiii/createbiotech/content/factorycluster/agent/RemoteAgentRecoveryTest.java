package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputLedger;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentPendingRunQueue.EnqueueResult;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateDispatchCommand;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.NativePackageObservation;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.OutputLogisticsGateway;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.AgentProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.ProcessProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RecoveryProtocolMessage;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class RemoteAgentRecoveryTest {
	private static final UUID SOURCE = id(1);
	private static final UUID TARGET = id(2);
	private static final UUID AGENT = id(3);
	private static final UUID REQUEST = id(4);
	private static final UUID RUN = id(5);
	private static final UUID PATTERN = id(6);
	private static final UUID GOAL = id(7);
	private static final UUID NETWORK = id(8);
	private static final UUID OUTPUT_EPOCH = id(9);
	private static final CreateRouteKey INPUT = new CreateRouteKey(NETWORK, "agent-input");
	private static final CreateRouteKey OUTPUT = new CreateRouteKey(NETWORK, "agent-output");
	private static final CreateRouteKey WAREHOUSE = new CreateRouteKey(NETWORK, "warehouse");
	private static StackKey STONE;

	@BeforeAll static void bootstrapMinecraftRegistries() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			var bootstrapped = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
		STONE = new StackKey(new ItemStack(Items.STONE));
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (loadingModList.getMethod("get").invoke(null) != null) return;
			loadingModList.getMethod("of", List.class, List.class, List.class, List.class,
				Map.class).invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test
	void remoteKeyFreezesEveryIdentityFenceAndProtocolMessagesAreRadioPayloads() {
		RemoteRequestKey key = key(10, 20, 30);
		assertEquals(REQUEST, key.requestId());
		assertEquals(RUN, key.runId());
		assertEquals(10, key.fence());
		assertEquals(20, key.sourceGeneration());
		assertEquals(30, key.agentGeneration());
		assertThrows(IllegalArgumentException.class,
			() -> new RemoteRequestKey(REQUEST, RUN, -1, 0, 0));

		AgentProtocolMessage.Offer offer = new AgentProtocolMessage.Offer(key, ticket(0, 1));
		assertTrue(offer instanceof ProcessProtocolMessage);
		assertFalse(offer.fingerprint().isBlank());
		assertThrows(IllegalArgumentException.class,
			() -> new AgentProtocolMessage.Offer(key(11, 20, 30), ticket(0, 1)));
	}

	@Test
	void evidenceIdentityIsBoundedCanonicalAndDeterministicForCurrentSchema() {
		RemoteRequestKey key = key(10, 20, 30);
		RemoteAgentRecord first = RemoteAgentRecord.queued(key, TARGET, ticket(0, 1));
		RemoteAgentRecord same = RemoteAgentRecord.queued(key, TARGET, ticket(0, 1));
		RemoteAgentRecord changed = RemoteAgentRecord.queued(key, TARGET, ticket(0, 2));

		assertEquals(first.evidenceIdentity(), same.evidenceIdentity());
		assertEquals(64, first.evidenceIdentity().length());
		assertTrue(first.evidenceIdentity().matches("[0-9a-f]{64}"));
		assertFalse(first.evidenceIdentity().contains("RemoteAgentRecord"));
		assertNotEquals(first.evidenceIdentity(), changed.evidenceIdentity());
	}

	@Test
	void terminalRadioEnvelopeRoundTripsExactComponentsWithoutFingerprintMutation() {
		ItemStack exact = new ItemStack(Items.STONE);
		exact.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 1);
		RemoteRequestKey key = key(10, 20, 30);
		Harness harness = harness(8);
		AgentProcess process = process(30);
		harness.ledger.offer(new AgentProtocolMessage.Offer(key, ticket(0, 1)), harness.queue,
			registration(30));
		harness.ledger.reserve(key, harness.queue, process);
		AgentInputContract input = inputAtIntent(30, InputMode.EXACT, ignored -> {},
			new StackKey(exact));
		CreateDispatchCommand command = input.snapshot().currentCommand().orElseThrow();
		input.confirmDispatch(AgentInputTestFacts.committed(command, 701));
		harness.ledger.recordInput(key, input.snapshot());
		ProductionOutputLedger dispatchLedger = emptyDispatchedLedger(key);
		ProductionOutputContract.Snapshot output = dispatchedOutputSnapshot(input.snapshot(),
			dispatchLedger);
		harness.ledger.recordOutput(key, output);
		harness.ledger.releaseCompleted(key, output, dispatchLedger, process);
		RemoteAgentRecord terminal = harness.ledger.status(key).orElseThrow();
		RadioEndpoint.Hop source = new RadioEndpoint.Hop(
			new RadioEndpoint.Key(SOURCE, id(901)), 20);
		RadioEndpoint.Hop target = new RadioEndpoint.Hop(
			new RadioEndpoint.Key(TARGET, id(902)), 30);
		RecoveryProtocolMessage.PeerFence peers = new RecoveryProtocolMessage.PeerFence(
			source, target);
		RecoveryProtocolMessage.Terminal message = new RecoveryProtocolMessage.Terminal(key,
			peers, terminal);
		RadioEnvelope envelope = new RadioEnvelope(id(903), target.key().clusterId(),
			target.key().memberId(), source.key().clusterId(), source.key().memberId(), CommunicationFrequency.of("7"), 0,
			target.generation(), source.generation(), message.fingerprint(), message);

		var encoded = CurrentSchemaStateNbt.save(envelope, RegistryAccess.EMPTY);
		assertEquals(envelope, CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY,
			RadioEnvelope.class).orElseThrow());
	}

	@Test
	void exactComponentEvidenceIdentityIgnoresJvmIdentityHashAllocationOrder() throws Exception {
		assertEquals(componentEvidenceIdentityFromFreshJvm(false),
			componentEvidenceIdentityFromFreshJvm(true),
			"persisted evidence cannot depend on JVM object-identity hash allocation order");
	}

	@Test
	void freshJvmEvidenceProbeSupportsClasspathBeyondWindowsCommandLineLimit() throws Exception {
		String classpath = System.getProperty("java.class.path");
		while (classpath.length() < 40_000)
			classpath += java.io.File.pathSeparator + classpath;
		assertEquals(componentEvidenceIdentityFromFreshJvm(false),
			componentEvidenceIdentityFromFreshJvm(false, classpath));
	}

	public static void main(String[] arguments) {
		if (arguments.length != 1 || !arguments[0].startsWith("component-evidence-digest:"))
			throw new IllegalArgumentException("Unknown recovery test probe");
		if (arguments[0].endsWith(":shifted")) {
			for (int index = 0; index < 65_536; index++)
				System.identityHashCode(new Object());
		}
		bootstrapMinecraftRegistries();
		ItemStack exact = new ItemStack(Items.STONE);
		exact.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 1);
		RemoteRequestKey key = key(10, 20, 30);
		RemoteAgentRecord record = RemoteAgentRecord.queued(key, TARGET, ticket(0, 1))
			.reserve().withInput(inputAtIntent(30, InputMode.EXACT, ignored -> {},
				new StackKey(exact)).snapshot());
		System.out.println("CURRENT_SCHEMA_EVIDENCE=" + record.evidenceIdentity());
	}

	private static String componentEvidenceIdentityFromFreshJvm(boolean shifted) throws Exception {
		return componentEvidenceIdentityFromFreshJvm(shifted, System.getProperty("java.class.path"));
	}

	private static String componentEvidenceIdentityFromFreshJvm(boolean shifted, String classpath)
			throws Exception {
		String javaCommand = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
			.toString();
		String probe = "component-evidence-digest:" + (shifted ? "shifted" : "baseline");
		// Dependency paths can exceed Windows' process command-line limit in a nested worktree.
		var arguments = java.nio.file.Files.createTempFile("recovery-probe-", ".args");
		try {
			String quotedClasspath = classpath.replace("\\", "\\\\").replace("\"", "\\\"")
				.replace("\n", "\\n").replace("\r", "\\r");
			java.nio.file.Files.writeString(arguments, "-cp\n\"" + quotedClasspath + "\"\n"
				+ RemoteAgentRecoveryTest.class.getName() + "\n" + probe + "\n",
				java.nio.charset.StandardCharsets.UTF_8);
			Process process = new ProcessBuilder(javaCommand, "@" + arguments)
				.redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(),
				java.nio.charset.StandardCharsets.UTF_8);
			assertEquals(0, process.waitFor(), output);
			return output.lines().filter(line -> line.startsWith("CURRENT_SCHEMA_EVIDENCE="))
				.map(line -> line.substring("CURRENT_SCHEMA_EVIDENCE=".length()))
				.findFirst().orElseThrow(() -> new AssertionError(output));
		} finally {
			java.nio.file.Files.deleteIfExists(arguments);
		}
	}

	@Test
	void lostOfferAcknowledgementAndDuplicateOfferReturnTheSameImmutableRecord() {
		Harness harness = harness(8);
		RemoteRequestKey key = key(10, 20, 30);
		AgentProtocolMessage.Offer offer = new AgentProtocolMessage.Offer(key, ticket(0, 1));

		RecoveryLedger.OfferResult first = harness.ledger.offer(offer, harness.queue,
			registration(30));
		RecoveryLedger.OfferResult duplicate = harness.ledger.offer(offer, harness.queue,
			registration(30));

		assertEquals(RecoveryLedger.OfferDisposition.ACCEPTED, first.disposition());
		assertEquals(RecoveryLedger.OfferDisposition.DUPLICATE, duplicate.disposition());
		assertEquals(first.record(), duplicate.record());
		assertEquals(1, harness.queue.size());
		assertEquals(1, harness.ledger.size());
	}

	@Test
	void conflictingDuplicateCannotRewriteTheFrozenTicket() {
		Harness harness = harness(8);
		RemoteRequestKey key = key(10, 20, 30);
		harness.ledger.offer(new AgentProtocolMessage.Offer(key, ticket(0, 1)), harness.queue,
			registration(30));

		assertThrows(IllegalStateException.class, () -> harness.ledger.offer(
			new AgentProtocolMessage.Offer(key, ticket(0, 2)), harness.queue, registration(30)));
		assertEquals(1, harness.queue.size());
	}

	@Test
	void exactStatusSurvivesTargetRouteLossAndMissingKnownRecordNeverFallsBack() {
		Harness harness = harness(8);
		RemoteRequestKey key = key(10, 20, 30);
		RemoteAgentRecord admitted = harness.ledger.offer(
			new AgentProtocolMessage.Offer(key, ticket(0, 1)), harness.queue, registration(30)).record();

		assertEquals(admitted, harness.ledger.query(new AgentProtocolMessage.StatusQuery(key))
			.record().orElseThrow());
		assertEquals(RecoveryLedger.QueryDisposition.FOUND,
			harness.ledger.query(new AgentProtocolMessage.StatusQuery(key)).disposition());
		RemoteRequestKey unknown = new RemoteRequestKey(id(90), id(91), 11, 20, 30);
		RecoveryLedger.QueryResult missing = harness.ledger.query(
			new AgentProtocolMessage.StatusQuery(unknown));
		assertEquals(RecoveryLedger.QueryDisposition.REMOTE_RECOVERY, missing.disposition());
		assertEquals(Optional.of(WaitReason.REMOTE_RECOVERY), missing.waitReason());
		assertTrue(missing.record().isEmpty());
	}

	@Test
	void queuedAndReservedRecordsRestoreWithoutManufacturingWorldActions() {
		RecordingStore store = new RecordingStore();
		Harness first = harness(8, store);
		RemoteRequestKey queuedKey = key(10, 20, 30);
		first.ledger.offer(new AgentProtocolMessage.Offer(queuedKey, ticket(0, 1)), first.queue,
			registration(30));

		RecoveryLedger restoredQueued = new RecoveryLedger(config(8), store);
		assertEquals(RemoteAgentPhase.QUEUED, restoredQueued.status(queuedKey).orElseThrow().phase());

		AgentProcess process = process(30);
		assertTrue(first.ledger.reserve(queuedKey, first.queue, process));
		RecoveryLedger restoredReserved = new RecoveryLedger(config(8), store);
		assertEquals(RemoteAgentPhase.RESERVED,
			restoredReserved.status(queuedKey).orElseThrow().phase());
		assertEquals(Optional.of(REQUEST), process.activeContract());
	}

	@Test
	void failedPreDispatchAdmissionRollsBackReservedLedgerQueueProcessAndLineage() {
		Harness harness = harness(8);
		RemoteRequestKey key = key(10, 20, 30);
		AgentProcess process = process(30);
		AgentProtocolMessage.Offer offer = new AgentProtocolMessage.Offer(key, ticket(0, 1));
		assertEquals(RecoveryLedger.OfferDisposition.ACCEPTED,
			harness.ledger.offer(offer, harness.queue, registration(30)).disposition());
		assertTrue(harness.ledger.reserve(key, harness.queue, process));

		assertTrue(harness.ledger.rollbackAdmission(key, harness.queue, process));
		assertEquals(0, harness.ledger.size());
		assertEquals(0, harness.queue.size());
		assertTrue(process.activeContract().isEmpty());
		assertEquals(RecoveryLedger.OfferDisposition.ACCEPTED,
			harness.ledger.offer(new AgentProtocolMessage.Offer(key, ticket(1, 1)),
				harness.queue, registration(30)).disposition(),
			"rollback must remove the lineage high-watermark so the exact request is retryable");
	}

	@Test
	void crashInDispatchIntentBecomesUncertainKeepsTheAgentLockAndNeverRedispatches() {
		RecordingStore store = new RecordingStore();
		Harness harness = harness(8, store);
		RemoteRequestKey key = key(10, 20, 30);
		AgentProcess process = process(30);
		harness.ledger.offer(new AgentProtocolMessage.Offer(key, ticket(0, 1)), harness.queue,
			registration(30));
		harness.ledger.reserve(key, harness.queue, process);
		AgentInputContract input = inputAtIntent(30, InputMode.EXACT);
		assertTrue(harness.ledger.recordInput(key, input.snapshot()));

		RecoveryLedger restored = new RecoveryLedger(config(8), store);
		RemoteAgentRecord uncertain = restored.status(key).orElseThrow();
		assertEquals(0, restored.recoverAfterReload(), "constructor already reconciles reload");
		assertEquals(RemoteAgentPhase.RESET_REQUIRED, uncertain.phase());
		assertEquals(RemoteAgentRecord.RecoveryCondition.UNCERTAIN_DISPATCH,
			uncertain.condition());
		assertTrue(uncertain.futureExecutionStopped());
		assertEquals(Optional.of(REQUEST), process.activeContract(), "uncertainty keeps the lock");
		AgentInputContract rehydrated = AgentInputContract.restore(
			uncertain.inputState().orElseThrow(), ignored -> {});
		assertTrue(rehydrated.beginDispatch(id(100), 10).isEmpty());
	}

	@Test
	void confirmedDispatchRecoveryResendsEvidenceButNeverRedispatches() {
		Harness harness = harness(8);
		RemoteRequestKey key = key(10, 20, 30);
		AgentProcess process = process(30);
		harness.ledger.offer(new AgentProtocolMessage.Offer(key, ticket(0, 1)), harness.queue,
			registration(30));
		harness.ledger.reserve(key, harness.queue, process);
		AgentInputContract input = inputAtIntent(30, InputMode.EXACT);
		CreateDispatchCommand command = input.snapshot().currentCommand().orElseThrow();
		input.confirmDispatch(AgentInputTestFacts.committed(command, 501));
		harness.ledger.recordInput(key, input.snapshot());

		RecoveryLedger.ReconcileResult result = harness.ledger.reconcile(key);
		assertEquals(RecoveryLedger.ReconcileAction.RESEND_EVIDENCE, result.action());
		assertFalse(result.redispatchAllowed());
		assertEquals(RemoteAgentPhase.DISPATCH_CONFIRMED, result.record().phase());
		assertTrue(result.record().inputState().orElseThrow().nativeOrders().isPresent());
	}

	@Test
	void staleFenceSourceGenerationAndAgentGenerationAreRejectedNotMerged() {
		Harness harness = harness(8);
		assertEquals(RecoveryLedger.OfferDisposition.STALE_AGENT_GENERATION,
			harness.ledger.offer(new AgentProtocolMessage.Offer(key(10, 20, 29), ticket(0, 1)),
				harness.queue, registration(30)).disposition());
		RemoteRequestKey accepted = key(10, 20, 30);
		harness.ledger.offer(new AgentProtocolMessage.Offer(accepted, ticket(0, 1)), harness.queue,
			registration(30));
		assertEquals(RecoveryLedger.OfferDisposition.STALE_FENCE,
			harness.ledger.offer(new AgentProtocolMessage.Offer(key(9, 20, 30), ticketWithFence(9)),
				harness.queue, registration(30)).disposition());
		assertEquals(RecoveryLedger.QueryDisposition.STALE_SOURCE_GENERATION,
			harness.ledger.query(new AgentProtocolMessage.StatusQuery(key(10, 19, 30)))
				.disposition());
		assertEquals(1, harness.ledger.size());
	}

	@Test
	void preIntentRetryMayMoveToANewAgentGenerationButPostIntentRetryIsRejected() {
		Harness safe = harness(8);
		RemoteRequestKey old = key(10, 20, 30);
		safe.ledger.offer(new AgentProtocolMessage.Offer(old, ticket(0, 1)), safe.queue,
			registration(30));
		AgentProtocolMessage.Offer retry = new AgentProtocolMessage.Offer(key(10, 20, 31),
			ticketWithIds(1, 1, REQUEST, RUN));
		RecoveryLedger.OfferResult moved = safe.ledger.retryToNewAgentGeneration(old, retry,
			safe.queue, registration(31), null);
		assertEquals(RecoveryLedger.OfferDisposition.ACCEPTED, moved.disposition());
		assertEquals(RemoteAgentPhase.RELEASED, safe.ledger.status(old).orElseThrow().phase());
		assertEquals(RemoteAgentRecord.TerminalOutcome.RETRIED_PRE_INTENT,
			safe.ledger.status(old).orElseThrow().terminalOutcome().orElseThrow());

		Harness unsafe = harness(8);
		unsafe.ledger.offer(new AgentProtocolMessage.Offer(old, ticket(0, 1)), unsafe.queue,
			registration(30));
		AgentProcess process = process(30);
		unsafe.ledger.reserve(old, unsafe.queue, process);
		unsafe.ledger.recordInput(old, inputAtIntent(30, InputMode.EXACT).snapshot());
		assertEquals(RecoveryLedger.OfferDisposition.POST_INTENT_RETRY_REJECTED,
			unsafe.ledger.retryToNewAgentGeneration(old, retry, unsafe.queue, registration(31), process)
				.disposition());
		assertTrue(unsafe.ledger.status(retry.key()).isEmpty());
	}

	@Test
	void unacknowledgedTerminalEvidenceSurvivesReloadAndAckCompactsIt() {
		RecordingStore store = new RecordingStore();
		Harness harness = harness(8, store);
		RemoteRequestKey key = key(10, 20, 30);
		harness.ledger.offer(new AgentProtocolMessage.Offer(key, ticket(0, 1)), harness.queue,
			registration(30));
		assertEquals(RecoveryLedger.CancelResult.CANCELLED_PRE_INTENT,
			harness.ledger.cancel(new AgentProtocolMessage.Cancel(key), harness.queue, null, null));
		long revision = harness.ledger.status(key).orElseThrow().revision();

		RecoveryLedger restored = new RecoveryLedger(config(8), store);
		assertEquals(1, restored.unacknowledgedEvidenceCount());
		assertEquals(RemoteAgentRecord.TerminalOutcome.CANCELLED_PRE_INTENT,
			restored.status(key).orElseThrow().terminalOutcome().orElseThrow());
		assertTrue(restored.acknowledge(new AgentProtocolMessage.EvidenceAck(key, revision)));
		assertTrue(restored.acknowledge(new AgentProtocolMessage.EvidenceAck(key, revision)),
			"replayed delivery after a crash must recognize its exact durable tombstone");
		assertEquals(0, restored.unacknowledgedEvidenceCount());
		assertEquals(RecoveryLedger.QueryDisposition.ACKNOWLEDGED_TOMBSTONE,
			restored.query(new AgentProtocolMessage.StatusQuery(key)).disposition());
	}

	@Test
	void ledgerSaturationBackpressuresNewAdmissionButStillAllowsQueryCancelAndAck() {
		Harness harness = harness(1);
		RemoteRequestKey first = key(10, 20, 30);
		harness.ledger.offer(new AgentProtocolMessage.Offer(first, ticket(0, 1)), harness.queue,
			registration(30));
		RemoteRequestKey second = new RemoteRequestKey(id(40), id(41), 11, 21, 30);
		RecoveryLedger.OfferResult full = harness.ledger.offer(new AgentProtocolMessage.Offer(second,
			ticketWithIds(1, 1, id(40), id(41))), harness.queue, registration(30));
		assertEquals(RecoveryLedger.OfferDisposition.RECOVERY_LEDGER_FULL, full.disposition());
		assertEquals(Optional.of(WaitReason.RECOVERY_LEDGER_FULL), full.waitReason());
		assertEquals(RecoveryLedger.QueryDisposition.FOUND,
			harness.ledger.query(new AgentProtocolMessage.StatusQuery(first)).disposition());
		assertEquals(RecoveryLedger.CancelResult.CANCELLED_PRE_INTENT,
			harness.ledger.cancel(new AgentProtocolMessage.Cancel(first), harness.queue, null, null));
		long revision = harness.ledger.status(first).orElseThrow().revision();
		assertTrue(harness.ledger.acknowledge(new AgentProtocolMessage.EvidenceAck(first, revision)));
		assertEquals(RecoveryLedger.OfferDisposition.RECOVERY_LEDGER_FULL,
			harness.ledger.offer(new AgentProtocolMessage.Offer(second,
				ticketWithIds(1, 1, id(40), id(41))), harness.queue, registration(30))
				.disposition());
		RecoveryLedger.LineageCompactionEvidenceIssuer issuer =
			new RecoveryLedger.LineageCompactionEvidenceIssuer(() -> true, request -> Optional.of(
				new RecoveryLedger.ResolvedLineageCompactionFacts(request.key(), id(990), true,
					21, 31)));
		assertTrue(harness.ledger.compactAcknowledgedLineage(
			issuer.capture(first).orElseThrow()));
		assertEquals(RecoveryLedger.OfferDisposition.ACCEPTED,
			harness.ledger.offer(new AgentProtocolMessage.Offer(second,
				ticketWithIds(1, 1, id(40), id(41))), harness.queue, registration(30))
				.disposition());
	}

	@Test
	void scopedAdmissionCeilingCanFillOneRealLedgerSlotWithoutMutatingGlobalCapacity() {
		Harness harness = harness(8);
		RemoteRequestKey first = key(10, 20, 30);
		assertEquals(RecoveryLedger.OfferDisposition.ACCEPTED,
			harness.ledger.offerWithinCapacity(new AgentProtocolMessage.Offer(first, ticket(0, 1)),
				harness.queue, registration(30), 1).disposition());
		RemoteRequestKey second = new RemoteRequestKey(id(40), id(41), 11, 21, 30);
		assertEquals(RecoveryLedger.OfferDisposition.RECOVERY_LEDGER_FULL,
			harness.ledger.offerWithinCapacity(new AgentProtocolMessage.Offer(second,
				ticketWithIds(1, 1, id(40), id(41))), harness.queue,
				registration(30), 1).disposition());
		assertEquals(RecoveryLedger.OfferDisposition.ACCEPTED,
			harness.ledger.offer(new AgentProtocolMessage.Offer(second,
				ticketWithIds(1, 1, id(40), id(41))), harness.queue,
				registration(30)).disposition());
	}

	@Test
	void queuedCancelRemovesTheTicketAndItsPriorityLease() {
		Harness harness = harness(8);
		RemoteRequestKey key = key(10, 20, 30);
		harness.ledger.offer(new AgentProtocolMessage.Offer(key, ticket(0, 5)), harness.queue,
			registration(30));
		assertEquals(1, harness.queue.livePriorityLeaseCount());

		assertEquals(RecoveryLedger.CancelResult.CANCELLED_PRE_INTENT,
			harness.ledger.cancel(new AgentProtocolMessage.Cancel(key), harness.queue, null, null));
		assertEquals(0, harness.queue.size());
		assertEquals(0, harness.queue.livePriorityLeaseCount());
		assertEquals(1, harness.leases.released);
	}

	@Test
	void reservedCancelReleasesTheAgentSafelyBeforeDispatchIntent() {
		Harness harness = harness(8);
		RemoteRequestKey key = key(10, 20, 30);
		AgentProcess process = process(30);
		harness.ledger.offer(new AgentProtocolMessage.Offer(key, ticket(0, 1)), harness.queue,
			registration(30));
		harness.ledger.reserve(key, harness.queue, process);

		assertEquals(RecoveryLedger.CancelResult.CANCELLED_PRE_INTENT,
			harness.ledger.cancel(new AgentProtocolMessage.Cancel(key), harness.queue, process, null));
		assertTrue(process.activeContract().isEmpty());
		assertEquals(RemoteAgentPhase.RELEASED, harness.ledger.status(key).orElseThrow().phase());
	}

	@Test
	void postIntentCancelStopsLaterExactRunsAndSequenceStagesThenRequiresResetAfterDrain() {
		Harness harness = harness(8);
		RemoteRequestKey key = key(10, 20, 30);
		AgentProcess process = process(30);
		harness.ledger.offer(new AgentProtocolMessage.Offer(key, ticket(0, 1)), harness.queue,
			registration(30));
		harness.ledger.reserve(key, harness.queue, process);
		AgentInputContract input = sequencedAtIntent(30);
		harness.ledger.recordInput(key, input.snapshot());

		assertEquals(RecoveryLedger.CancelResult.CANCEL_DRAIN,
			harness.ledger.cancel(new AgentProtocolMessage.Cancel(key), harness.queue, process, input));
		RemoteAgentRecord draining = harness.ledger.status(key).orElseThrow();
		assertEquals(RemoteAgentRecord.RecoveryCondition.CANCEL_DRAIN, draining.condition());
		assertTrue(draining.futureExecutionStopped());
		assertTrue(input.snapshot().cancellationDrain());
		assertEquals(AgentInputPhase.DISPATCH_INTENT, input.phase(),
			"cancellation must not manufacture RESET_REQUIRED before native input drains");
		assertTrue(input.advanceStage().isEmpty());
		assertEquals(Optional.of(REQUEST), process.activeContract());

		assertTrue(harness.ledger.completeCancelDrain(cancelDrainEvidence(key, input.snapshot(),
			dispatchedOutputSnapshot(input.snapshot(), emptyDispatchedLedger(key)),
			emptyDispatchedLedger(key), true, true, true), process) == false,
			"an unconfirmed native order cannot be declared drained");

		completeNativeInput(input, 501);
		harness.ledger.recordInput(key, input.snapshot());
		ProductionOutputLedger ledger = emptyDispatchedLedger(key);
		ProductionOutputContract.Snapshot output = dispatchedOutputSnapshot(input.snapshot(), ledger);
		assertTrue(harness.ledger.completeCancelDrain(cancelDrainEvidence(key, input.snapshot(),
			output, ledger, true, true, true), process));
		RemoteAgentRecord terminal = harness.ledger.status(key).orElseThrow();
		assertEquals(RemoteAgentPhase.RESET_REQUIRED, terminal.phase());
		assertEquals(RemoteAgentRecord.TerminalOutcome.CANCEL_DRAINED,
			terminal.terminalOutcome().orElseThrow());
		assertTrue(process.activeContract().isEmpty());
	}

	@Test
	void drainAndTerminalProgressDoNotDependOnTheSourceBeingOnline() {
		Harness harness = harness(8);
		RemoteRequestKey key = key(10, 20, 30);
		AgentProcess process = process(30);
		harness.ledger.offer(new AgentProtocolMessage.Offer(key, ticket(0, 1)), harness.queue,
			registration(30));
		harness.ledger.reserve(key, harness.queue, process);
		AgentInputContract input = inputAtIntent(30, InputMode.EXACT);
		harness.ledger.recordInput(key, input.snapshot());
		harness.ledger.cancel(new AgentProtocolMessage.Cancel(key), harness.queue, process, input);

		completeNativeInput(input, 501);
		harness.ledger.recordInput(key, input.snapshot());
		ProductionOutputLedger ledger = emptyDispatchedLedger(key);
		assertTrue(harness.ledger.completeCancelDrain(cancelDrainEvidence(key, input.snapshot(),
			dispatchedOutputSnapshot(input.snapshot(), ledger), ledger, true, true, true), process));
		assertTrue(process.activeContract().isEmpty());
		assertEquals(1, harness.ledger.unacknowledgedEvidenceCount(),
			"source ACK is not needed to release production resources");
	}

	@Test
	void normalOutputDrainReleasesTheAgentAndRetainsTerminalEvidenceWithoutSourceAck() {
		Harness harness = harness(8);
		RemoteRequestKey key = key(10, 20, 30);
		AgentProcess process = process(30);
		harness.ledger.offer(new AgentProtocolMessage.Offer(key, ticket(0, 1)), harness.queue,
			registration(30));
		harness.ledger.reserve(key, harness.queue, process);
		AgentInputContract input = inputAtIntent(30, InputMode.EXACT);
		CreateDispatchCommand command = input.snapshot().currentCommand().orElseThrow();
		input.confirmDispatch(AgentInputTestFacts.committed(command, 701));
		harness.ledger.recordInput(key, input.snapshot());
		ProductionOutputLedger dispatchLedger = emptyDispatchedLedger(key);
		ProductionOutputContract.Snapshot output = dispatchedOutputSnapshot(input.snapshot(),
			dispatchLedger);

		assertTrue(harness.ledger.recordOutput(key, output));
		assertEquals(RemoteAgentPhase.OUTPUT_DRAIN,
			harness.ledger.status(key).orElseThrow().phase());
		assertTrue(harness.ledger.releaseCompleted(key, output, dispatchLedger, process));
		RemoteAgentRecord terminal = harness.ledger.status(key).orElseThrow();
		assertEquals(RemoteAgentPhase.RELEASED, terminal.phase());
		assertEquals(RemoteAgentRecord.TerminalOutcome.COMPLETED,
			terminal.terminalOutcome().orElseThrow());
		assertTrue(process.activeContract().isEmpty());
		assertEquals(1, harness.ledger.unacknowledgedEvidenceCount());
	}

	@Test
	void completedReleaseIsIdempotentAfterReloadBeforeTerminalExchangePublication() {
		RecordingStore store = new RecordingStore();
		Harness harness = harness(8, store);
		RemoteRequestKey key = key(10, 20, 30);
		AgentProcess process = process(30);
		harness.ledger.offer(new AgentProtocolMessage.Offer(key, ticket(0, 1)), harness.queue,
			registration(30));
		harness.ledger.reserve(key, harness.queue, process);
		AgentInputContract input = inputAtIntent(30, InputMode.EXACT);
		CreateDispatchCommand command = input.snapshot().currentCommand().orElseThrow();
		input.confirmDispatch(AgentInputTestFacts.committed(command, 701));
		harness.ledger.recordInput(key, input.snapshot());
		ProductionOutputLedger ledger = emptyDispatchedLedger(key);
		ProductionOutputContract.Snapshot output = dispatchedOutputSnapshot(input.snapshot(), ledger);
		harness.ledger.recordOutput(key, output);
		assertTrue(harness.ledger.releaseCompleted(key, output, ledger, process));

		RecoveryLedger restored = new RecoveryLedger(config(8), store);
		assertTrue(restored.releaseCompleted(key, output, ledger, process),
			"reload must resume composition-root publication after terminal release persisted");
	}

	@Test
	void cancelAtInputEndedRemainsDrainableAndRequiresAuthoritativeEmptyEndpoints() {
		Harness harness = harness(8);
		RemoteRequestKey key = key(10, 20, 30);
		AgentProcess process = process(30);
		harness.ledger.offer(new AgentProtocolMessage.Offer(key, ticket(0, 1)), harness.queue,
			registration(30));
		harness.ledger.reserve(key, harness.queue, process);
		AgentInputContract input = inputAtIntent(30, InputMode.EXACT);
		completeNativeInput(input, 501);
		harness.ledger.recordInput(key, input.snapshot());

		assertEquals(RecoveryLedger.CancelResult.CANCEL_DRAIN,
			harness.ledger.cancel(new AgentProtocolMessage.Cancel(key), harness.queue, process, input));
		assertEquals(AgentInputPhase.INPUT_ENDED, input.phase());
		assertTrue(input.snapshot().cancellationDrain());
		ProductionOutputLedger ledger = emptyDispatchedLedger(key);
		ProductionOutputContract.Snapshot output = dispatchedOutputSnapshot(input.snapshot(), ledger);
		assertFalse(harness.ledger.completeCancelDrain(cancelDrainEvidence(key, input.snapshot(),
			output, ledger, true, false, true), process));
		assertEquals(Optional.of(REQUEST), process.activeContract());
		assertTrue(harness.ledger.completeCancelDrain(cancelDrainEvidence(key, input.snapshot(),
			output, ledger, true, true, true), process));
	}

	@Test
	void persistBeforeEffectKeepsOfferReserveReleaseAndAckCrashAtomic() {
		FaultStore store = new FaultStore();
		Harness harness = harness(8, store);
		RemoteRequestKey key = key(10, 20, 30);
		AgentProtocolMessage.Offer offer = new AgentProtocolMessage.Offer(key, ticket(0, 1));

		store.failNext();
		assertThrows(IllegalStateException.class,
			() -> harness.ledger.offer(offer, harness.queue, registration(30)));
		assertEquals(0, harness.queue.size());
		assertTrue(harness.ledger.status(key).isEmpty());

		harness.ledger.offer(offer, harness.queue, registration(30));
		AgentProcess process = process(30);
		store.failNext();
		assertThrows(IllegalStateException.class,
			() -> harness.ledger.reserve(key, harness.queue, process));
		assertEquals(1, harness.queue.size());
		assertTrue(process.activeContract().isEmpty());

		assertTrue(harness.ledger.reserve(key, harness.queue, process));
		AgentInputContract input = inputAtIntent(30, InputMode.EXACT);
		CreateDispatchCommand command = input.snapshot().currentCommand().orElseThrow();
		input.confirmDispatch(AgentInputTestFacts.committed(command, 701));
		harness.ledger.recordInput(key, input.snapshot());
		ProductionOutputLedger ledger = emptyDispatchedLedger(key);
		ProductionOutputContract.Snapshot output = dispatchedOutputSnapshot(input.snapshot(), ledger);
		harness.ledger.recordOutput(key, output);
		store.failNext();
		assertThrows(IllegalStateException.class,
			() -> harness.ledger.releaseCompleted(key, output, ledger, process));
		assertEquals(Optional.of(REQUEST), process.activeContract());
		assertEquals(RemoteAgentPhase.OUTPUT_DRAIN,
			harness.ledger.status(key).orElseThrow().phase());

		assertTrue(harness.ledger.releaseCompleted(key, output, ledger, process));
		long revision = harness.ledger.status(key).orElseThrow().revision();
		store.failNext();
		assertThrows(IllegalStateException.class, () -> harness.ledger.acknowledge(
			new AgentProtocolMessage.EvidenceAck(key, revision)));
		assertTrue(harness.ledger.status(key).isPresent(),
			"failed ACK persistence must retain correctness evidence");
	}

	@Test
	void durablePendingOutboxReconcilesEveryCrashAfterExternalEffect() {
		RemoteRequestKey key = key(10, 20, 30);
		AgentProtocolMessage.Offer offer = new AgentProtocolMessage.Offer(key, ticket(0, 1));

		FaultStore offerStore = new FaultStore();
		Harness offerHarness = harness(8, offerStore);
		offerStore.failAfter(1);
		assertThrows(IllegalStateException.class,
			() -> offerHarness.ledger.offer(offer, offerHarness.queue, registration(30)));
		assertEquals(1, offerHarness.queue.size());
		RecoveryLedger offerRestored = new RecoveryLedger(config(8), offerStore);
		assertEquals(RecoveryLedger.ResourceReconcileResult.COMPLETED_PENDING,
			offerRestored.reconcileResources(key, offerHarness.queue, registration(30), process(30)));

		FaultStore reserveStore = new FaultStore();
		Harness reserveHarness = harness(8, reserveStore);
		reserveHarness.ledger.offer(offer, reserveHarness.queue, registration(30));
		AgentProcess reservedProcess = process(30);
		reserveStore.failAfter(1);
		assertThrows(IllegalStateException.class, () -> reserveHarness.ledger.reserve(key,
			reserveHarness.queue, reservedProcess));
		assertTrue(reserveHarness.queue.find(REQUEST).isEmpty());
		assertEquals(Optional.of(REQUEST), reservedProcess.activeContract());
		RecoveryLedger reserveRestored = new RecoveryLedger(config(8), reserveStore);
		assertEquals(RecoveryLedger.ResourceReconcileResult.COMPLETED_PENDING,
			reserveRestored.reconcileResources(key, reserveHarness.queue, registration(30),
				reservedProcess));
		assertEquals(RemoteAgentPhase.RESERVED,
			reserveRestored.status(key).orElseThrow().phase());

		FaultStore releaseStore = new FaultStore();
		Harness releaseHarness = harness(8, releaseStore);
		AgentProcess releaseProcess = process(30);
		releaseHarness.ledger.offer(offer, releaseHarness.queue, registration(30));
		releaseHarness.ledger.reserve(key, releaseHarness.queue, releaseProcess);
		AgentInputContract input = inputAtIntent(30, InputMode.EXACT);
		CreateDispatchCommand command = input.snapshot().currentCommand().orElseThrow();
		input.confirmDispatch(AgentInputTestFacts.committed(command, 701));
		releaseHarness.ledger.recordInput(key, input.snapshot());
		ProductionOutputLedger ledger = emptyDispatchedLedger(key);
		ProductionOutputContract.Snapshot output = dispatchedOutputSnapshot(input.snapshot(), ledger);
		releaseHarness.ledger.recordOutput(key, output);
		releaseStore.failAfter(1);
		assertThrows(IllegalStateException.class, () -> releaseHarness.ledger.releaseCompleted(key,
			output, ledger, releaseProcess));
		assertTrue(releaseProcess.activeContract().isEmpty());
		RecoveryLedger releaseRestored = new RecoveryLedger(config(8), releaseStore);
		assertEquals(RecoveryLedger.ResourceReconcileResult.COMPLETED_PENDING,
			releaseRestored.reconcileResources(key, releaseHarness.queue, registration(30),
				releaseProcess));
		assertEquals(RemoteAgentRecord.TerminalOutcome.COMPLETED,
			releaseRestored.status(key).orElseThrow().terminalOutcome().orElseThrow());
	}

	@Test
	void freshRuntimeDeterministicallyRestoresQueuedAndReservedResources() {
		RecordingStore queuedStore = new RecordingStore();
		Harness beforeQueued = harness(8, queuedStore);
		RemoteRequestKey key = key(10, 20, 30);
		beforeQueued.ledger.offer(new AgentProtocolMessage.Offer(key, ticket(0, 1)),
			beforeQueued.queue, registration(30));
		Harness afterQueued = harness(8, queuedStore);
		AgentProcess freshQueuedProcess = process(30);
		assertEquals(RecoveryLedger.ResourceReconcileResult.RESTORED_QUEUED,
			afterQueued.ledger.reconcileResources(key, afterQueued.queue, registration(30),
				freshQueuedProcess));
		assertEquals(1, afterQueued.queue.size());

		RecordingStore reservedStore = new RecordingStore();
		Harness beforeReserved = harness(8, reservedStore);
		beforeReserved.ledger.offer(new AgentProtocolMessage.Offer(key, ticket(0, 1)),
			beforeReserved.queue, registration(30));
		assertTrue(beforeReserved.ledger.reserve(key, beforeReserved.queue, process(30)));
		Harness afterReserved = harness(8, reservedStore);
		AgentProcess freshReservedProcess = process(30);
		assertEquals(RecoveryLedger.ResourceReconcileResult.RESTORED_RESERVED,
			afterReserved.ledger.reconcileResources(key, afterReserved.queue, registration(30),
				freshReservedProcess));
		assertEquals(Optional.of(REQUEST), freshReservedProcess.activeContract());
	}

	@Test
	void freshQueueRestoresDurableTicketsWithoutResettingTheirSequenceHighWatermark() {
		RecordingStore store = new RecordingStore();
		Harness before = harness(8, store);
		RemoteRequestKey first = key(10, 20, 30);
		before.ledger.offer(new AgentProtocolMessage.Offer(first, ticket(0, 1)), before.queue,
			registration(30));
		before.ledger.cancel(new AgentProtocolMessage.Cancel(first), before.queue, null, null);
		before.ledger.acknowledge(new AgentProtocolMessage.EvidenceAck(first,
			before.ledger.status(first).orElseThrow().revision()));
		RemoteRequestKey second = new RemoteRequestKey(id(920), id(921), 11, 21, 30);
		ProductionRequestTicket secondTicket = ticketWithIds(1, 1, id(920), id(921));
		before.ledger.offer(new AgentProtocolMessage.Offer(second, secondTicket), before.queue,
			registration(30));

		Harness restored = harness(8, store);
		assertEquals(RecoveryLedger.ResourceReconcileResult.RESTORED_QUEUED,
			restored.ledger.reconcileResources(second, restored.queue, registration(30), process(30)));
		assertEquals(Optional.of(secondTicket), restored.queue.find(second.requestId()));
	}

	@Test
	void failedGenerationRetryLeavesQueuedOrReservedOldContractResumable() {
		RemoteRequestKey old = key(10, 20, 30);
		AgentProtocolMessage.Offer retry = new AgentProtocolMessage.Offer(key(10, 20, 31),
			ticketWithIds(1, 1, REQUEST, RUN));
		Harness queued = harness(8);
		queued.ledger.offer(new AgentProtocolMessage.Offer(old, ticket(0, 1)), queued.queue,
			registration(30));
		assertEquals(RecoveryLedger.OfferDisposition.STALE_AGENT_GENERATION,
			queued.ledger.retryToNewAgentGeneration(old, retry, queued.queue, registration(32), null)
				.disposition());
		assertEquals(RemoteAgentPhase.QUEUED, queued.ledger.status(old).orElseThrow().phase());
		assertEquals(1, queued.queue.size());
		ProductionRequestTicket staleSequence = new ProductionRequestTicket(REQUEST, RUN, 10,
			SOURCE, AGENT, INPUT, PATTERN, InputMode.EXACT, 1, 99, 1, 1);
		assertEquals(RecoveryLedger.OfferDisposition.QUEUE_REJECTED,
			queued.ledger.retryToNewAgentGeneration(old,
				new AgentProtocolMessage.Offer(key(10, 20, 31), staleSequence), queued.queue,
				registration(31), null).disposition());
		assertEquals(RemoteAgentPhase.QUEUED, queued.ledger.status(old).orElseThrow().phase());
		assertEquals(Optional.of(ticket(0, 1)), queued.queue.find(REQUEST));

		Harness reserved = harness(8);
		AgentProcess process = process(30);
		reserved.ledger.offer(new AgentProtocolMessage.Offer(old, ticket(0, 1)), reserved.queue,
			registration(30));
		reserved.ledger.reserve(old, reserved.queue, process);
		assertEquals(RecoveryLedger.OfferDisposition.STALE_AGENT_GENERATION,
			reserved.ledger.retryToNewAgentGeneration(old, retry, reserved.queue,
				registration(32), process).disposition());
		assertEquals(RemoteAgentPhase.RESERVED,
			reserved.ledger.status(old).orElseThrow().phase());
		assertEquals(Optional.of(REQUEST), process.activeContract());
	}

	@Test
	void generationRetryCannotMutateTheFrozenSourceOrTicketIdentity() {
		RemoteRequestKey old = key(10, 20, 30);
		Harness harness = harness(8);
		harness.ledger.offer(new AgentProtocolMessage.Offer(old, ticket(0, 1)), harness.queue,
			registration(30));
		ProductionRequestTicket changedSource = new ProductionRequestTicket(REQUEST, RUN, 10,
			id(999), AGENT, INPUT, PATTERN, InputMode.EXACT, 1, 1, 1, 1);
		AgentProtocolMessage.Offer retry = new AgentProtocolMessage.Offer(key(10, 20, 31),
			changedSource);

		assertEquals(RecoveryLedger.OfferDisposition.LINEAGE_MUTATION_REJECTED,
			harness.ledger.retryToNewAgentGeneration(old, retry, harness.queue,
				registration(31), null).disposition());
		assertEquals(RemoteAgentPhase.QUEUED,
			harness.ledger.status(old).orElseThrow().phase());
		assertEquals(Optional.of(ticket(0, 1)), harness.queue.find(REQUEST));
	}

	@Test
	void ackWaitsForPendingExternalEffectReconciliation() {
		FaultStore store = new FaultStore();
		Harness harness = harness(8, store);
		RemoteRequestKey key = key(10, 20, 30);
		harness.ledger.offer(new AgentProtocolMessage.Offer(key, ticket(0, 1)), harness.queue,
			registration(30));
		store.failAfter(1);
		assertThrows(IllegalStateException.class, () -> harness.ledger.cancel(
			new AgentProtocolMessage.Cancel(key), harness.queue, null, null));
		long revision = harness.ledger.status(key).orElseThrow().revision();

		assertFalse(harness.ledger.acknowledge(
			new AgentProtocolMessage.EvidenceAck(key, revision)));
		assertTrue(harness.ledger.status(key).isPresent());
		Harness restored = harness(8, store);
		assertEquals(RecoveryLedger.ResourceReconcileResult.COMPLETED_PENDING,
			restored.ledger.reconcileResources(key, restored.queue, registration(30), process(30)));
		assertTrue(restored.ledger.acknowledge(
			new AgentProtocolMessage.EvidenceAck(key, revision)));
	}

	@Test
	void cancellationDrainIntentReconcilesAfterInputFenceWasPersisted() {
		FaultStore store = new FaultStore();
		Harness harness = harness(8, store);
		RemoteRequestKey key = key(10, 20, 30);
		AgentProcess process = process(30);
		harness.ledger.offer(new AgentProtocolMessage.Offer(key, ticket(0, 1)), harness.queue,
			registration(30));
		harness.ledger.reserve(key, harness.queue, process);
		AgentInputContract input = inputAtIntent(30, InputMode.EXACT);
		harness.ledger.recordInput(key, input.snapshot());

		store.failAfter(1);
		assertThrows(IllegalStateException.class, () -> harness.ledger.cancel(
			new AgentProtocolMessage.Cancel(key), harness.queue, process, input));
		assertTrue(input.cancellationDrain());
		RecoveryLedger restored = new RecoveryLedger(config(8), store);
		AgentInputContract restoredInput = AgentInputContract.restore(input.snapshot(), ignored -> {});

		assertEquals(RecoveryLedger.CancelResult.CANCEL_DRAIN, restored.cancel(
			new AgentProtocolMessage.Cancel(key), harness.queue, process, restoredInput));
		assertTrue(restored.snapshot().pendingEffects().isEmpty());
		assertEquals(restoredInput.snapshot(),
			restored.status(key).orElseThrow().inputState().orElseThrow());
	}

	@Test
	void failedInputFencePersistCannotLetRetryClearTheDurableCancelOutbox() {
		RecordingStore recoveryStore = new RecordingStore();
		Harness harness = harness(8, recoveryStore);
		RemoteRequestKey key = key(10, 20, 30);
		AgentProcess process = process(30);
		harness.ledger.offer(new AgentProtocolMessage.Offer(key, ticket(0, 1)), harness.queue,
			registration(30));
		harness.ledger.reserve(key, harness.queue, process);
		InputFaultStore inputStore = new InputFaultStore();
		AgentInputContract input = inputAtIntent(30, InputMode.EXACT, inputStore);
		harness.ledger.recordInput(key, input.snapshot());

		inputStore.failNext();
		assertThrows(IllegalStateException.class, () -> harness.ledger.cancel(
			new AgentProtocolMessage.Cancel(key), harness.queue, process, input));
		assertFalse(input.cancellationDrain(), "failed persist must not publish an in-memory fence");
		assertEquals(RecoveryLedger.EffectKind.BEGIN_CANCEL_DRAIN,
			harness.ledger.snapshot().pendingEffects().get(key).kind());

		assertEquals(RecoveryLedger.CancelResult.CANCEL_DRAIN, harness.ledger.cancel(
			new AgentProtocolMessage.Cancel(key), harness.queue, process, input));
		assertTrue(input.cancellationDrain());
		assertTrue(harness.ledger.snapshot().pendingEffects().isEmpty());
		AgentInputContract restartedInput = AgentInputContract.restore(inputStore.state,
			inputStore);
		RecoveryLedger restartedLedger = new RecoveryLedger(config(8), recoveryStore);
		assertTrue(restartedInput.cancellationDrain());
		assertTrue(restartedLedger.snapshot().pendingEffects().isEmpty());
		assertEquals(restartedInput.snapshot(),
			restartedLedger.status(key).orElseThrow().inputState().orElseThrow());
	}

	@Test
	void duplicateCancelDrainCompletionIsImmutableAndConflictsFailClosed() {
		Harness harness = harness(8);
		RemoteRequestKey key = key(10, 20, 30);
		AgentProcess process = process(30);
		harness.ledger.offer(new AgentProtocolMessage.Offer(key, ticket(0, 1)), harness.queue,
			registration(30));
		harness.ledger.reserve(key, harness.queue, process);
		AgentInputContract input = inputAtIntent(30, InputMode.EXACT);
		harness.ledger.recordInput(key, input.snapshot());
		harness.ledger.cancel(new AgentProtocolMessage.Cancel(key), harness.queue, process, input);
		completeNativeInput(input, 701);
		harness.ledger.recordInput(key, input.snapshot());
		ProductionOutputLedger ledger = emptyDispatchedLedger(key);
		ProductionOutputContract.Snapshot output = dispatchedOutputSnapshot(input.snapshot(), ledger);
		RecoveryLedger.CancelDrainEvidence evidence = cancelDrainEvidence(key, input.snapshot(),
			output, ledger, true, true, true);
		assertTrue(harness.ledger.completeCancelDrain(evidence, process));
		RemoteAgentRecord terminal = harness.ledger.status(key).orElseThrow();
		Map<RemoteRequestKey, RecoveryLedger.PendingEffect> outbox =
			harness.ledger.snapshot().pendingEffects();

		assertTrue(harness.ledger.completeCancelDrain(evidence, process));
		assertEquals(terminal, harness.ledger.status(key).orElseThrow());
		assertEquals(outbox, harness.ledger.snapshot().pendingEffects());
		ProductionOutputContract.Snapshot conflictingOutput = withWaitReason(output, Optional.empty());
		assertFalse(harness.ledger.completeCancelDrain(cancelDrainEvidence(key, input.snapshot(),
			conflictingOutput, ledger, true, true, true), process));
		assertEquals(terminal, harness.ledger.status(key).orElseThrow());
		assertEquals(outbox, harness.ledger.snapshot().pendingEffects());
	}

	@Test
	void acknowledgedLineageHighWatermarkRejectsEveryIdentityMutation() {
		Harness harness = harness(8);
		RemoteRequestKey key = key(10, 20, 30);
		harness.ledger.offer(new AgentProtocolMessage.Offer(key, ticket(0, 1)), harness.queue,
			registration(30));
		harness.ledger.cancel(new AgentProtocolMessage.Cancel(key), harness.queue, null, null);
		long revision = harness.ledger.status(key).orElseThrow().revision();
		assertTrue(harness.ledger.acknowledge(new AgentProtocolMessage.EvidenceAck(key, revision)));
		ProductionRequestTicket changedSource = new ProductionRequestTicket(REQUEST, RUN, 10,
			id(902), AGENT, INPUT, PATTERN, InputMode.EXACT, 1, 1, 1, 1);
		assertEquals(RecoveryLedger.OfferDisposition.LINEAGE_MUTATION_REJECTED,
			harness.ledger.offer(new AgentProtocolMessage.Offer(key, changedSource), harness.queue,
				registration(30)).disposition());

		RemoteRequestKey changedRun = new RemoteRequestKey(REQUEST, id(900), 10, 20, 30);
		ProductionRequestTicket changedRunTicket = ticketWithIds(1, 1, REQUEST, id(900));
		assertEquals(RecoveryLedger.OfferDisposition.LINEAGE_MUTATION_REJECTED,
			harness.ledger.offer(new AgentProtocolMessage.Offer(changedRun, changedRunTicket),
				harness.queue, registration(30)).disposition());
		RemoteRequestKey changedRequest = new RemoteRequestKey(id(901), RUN, 10, 20, 30);
		ProductionRequestTicket changedRequestTicket = new ProductionRequestTicket(id(901), RUN,
			10, SOURCE, AGENT, INPUT, PATTERN, InputMode.EXACT, 1, 1, 1, 1);
		assertEquals(RecoveryLedger.OfferDisposition.LINEAGE_MUTATION_REJECTED,
			harness.ledger.offer(new AgentProtocolMessage.Offer(changedRequest,
				changedRequestTicket), harness.queue, registration(30)).disposition());
		assertEquals(RecoveryLedger.QueryDisposition.ACKNOWLEDGED_TOMBSTONE,
			harness.ledger.query(new AgentProtocolMessage.StatusQuery(key)).disposition());
	}

	@Test
	void compactHighWatermarkStillRejectsExactReplayAfterAck() {
		Harness harness = harness(1);
		RemoteRequestKey first = key(10, 20, 30);
		AgentProtocolMessage.Offer firstOffer = new AgentProtocolMessage.Offer(first, ticket(0, 1));
		harness.ledger.offer(firstOffer, harness.queue, registration(30));
		harness.ledger.cancel(new AgentProtocolMessage.Cancel(first), harness.queue, null, null);
		assertTrue(harness.ledger.acknowledge(new AgentProtocolMessage.EvidenceAck(first,
			harness.ledger.status(first).orElseThrow().revision())));

		assertEquals(RecoveryLedger.OfferDisposition.ACKNOWLEDGED_DUPLICATE,
			harness.ledger.offer(firstOffer, harness.queue, registration(30)).disposition());
		assertEquals(RecoveryLedger.QueryDisposition.ACKNOWLEDGED_TOMBSTONE,
			harness.ledger.query(new AgentProtocolMessage.StatusQuery(first)).disposition());
		assertEquals(0, harness.queue.size());
	}

	@Test
	void largeRecoveryStateRebuildsLiveLineageMembershipAndCountsExactOccupancy() {
		int lineageCount = 4096;
		Map<RemoteRequestKey, RemoteAgentRecord> records = new LinkedHashMap<>();
		Map<RemoteRequestKey, RecoveryLedger.AcknowledgedTombstone> acknowledged =
			new LinkedHashMap<>();
		Map<RecoveryLedger.LineageId, RecoveryLedger.LineageHighWatermark> lineages =
			new LinkedHashMap<>();
		int acknowledgedOnly = 0;
		for (int index = 0; index < lineageCount; index++) {
			UUID requestId = id(10_000L + index * 2L);
			UUID runId = id(10_001L + index * 2L);
			ProductionRequestTicket ticket = ticketWithIds(index, 1, requestId, runId);
			RemoteRequestKey latest = new RemoteRequestKey(requestId, runId, 11, 20, 31);
			RecoveryLedger.LineageHighWatermark lineage =
				RecoveryLedger.LineageHighWatermark.from(latest, ticket);
			if (index % 3 == 0) {
				lineage = lineage.acknowledgeTerminal();
				acknowledged.put(latest, new RecoveryLedger.AcknowledgedTombstone(latest,
					RemoteAgentRecord.TerminalOutcome.CANCELLED_PRE_INTENT, 1));
				acknowledgedOnly++;
			} else {
				records.put(latest, RemoteAgentRecord.queued(latest, TARGET, ticket));
				if (index % 3 == 2) {
					RemoteRequestKey prior = new RemoteRequestKey(requestId, runId, 11, 20, 30);
					records.put(prior, RemoteAgentRecord.queued(prior, TARGET, ticket));
				}
			}
			lineages.put(lineage.id(), lineage);
		}
		RecordingStore store = new RecordingStore();
		store.state = new RecoveryLedger.State(records, acknowledged, Map.of(), lineages);
		RecoveryLedger ledger = new RecoveryLedger(config(records.size() + acknowledgedOnly + 1),
			store);

		RecoveryLedger.CorrectnessOccupancy snapshot = ledger.correctnessOccupancySnapshot();
		assertEquals(records.size(), snapshot.liveRecordCount());
		assertEquals(lineageCount - acknowledgedOnly, snapshot.liveLineageCount());
		assertEquals(acknowledgedOnly, snapshot.acknowledgedOnlyLineageCount());
		assertEquals(records.size() + acknowledgedOnly, snapshot.total());
		assertEquals(snapshot.total(), ledger.correctnessOccupancy());
	}

	private static Harness harness(int recoveryCapacity) {
		return harness(recoveryCapacity, new RecordingStore());
	}

	private static Harness harness(int recoveryCapacity, RecordingStore store) {
		LeaseAllocator leases = new LeaseAllocator();
		AgentPendingRunQueue queue = AgentPendingRunQueue.createServerGlobal(config(16), leases);
		return new Harness(new RecoveryLedger(config(recoveryCapacity), store), queue, leases);
	}

	private static FactoryRuntimeConfig config(int recoveryCapacity) {
		return new FactoryRuntimeConfig(10, 1000, 16, 16, 4, 4, 32, 64, 20, 5, 8, 3,
			recoveryCapacity, 64);
	}

	private static RemoteRequestKey key(long fence, long sourceGeneration,
		long agentGeneration) {
		return new RemoteRequestKey(REQUEST, RUN, fence, sourceGeneration, agentGeneration);
	}

	private static ProductionRequestTicket ticket(long sequence, int priority) {
		return ticketWithIds(sequence, priority, REQUEST, RUN);
	}

	private static ProductionRequestTicket ticketWithIds(long sequence, int priority,
		UUID requestId, UUID runId) {
		return new ProductionRequestTicket(requestId, runId,
			requestId.equals(REQUEST) ? 10 : 11, SOURCE, AGENT, INPUT, PATTERN, InputMode.EXACT,
			1, sequence, priority, 1);
	}

	private static ProductionRequestTicket ticketWithFence(long fence) {
		return new ProductionRequestTicket(REQUEST, RUN, fence, SOURCE, AGENT, INPUT, PATTERN,
			InputMode.EXACT, 1, 0, 1, 1);
	}

	private static AgentAddressRegistration registration(long generation) {
		return new AgentAddressRegistration(INPUT, AGENT, TARGET, generation, CommunicationFrequency.of("77"),
			AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE);
	}

	private static AgentProcess process(long generation) {
		AgentFormation formation = AgentFormation.form(List.of(
			endpoint(0, generation, AgentEndpointRole.INPUT, INPUT),
			endpoint(1, generation, AgentEndpointRole.OUTPUT, OUTPUT)));
		return AgentProcess.form(formation, new AgentProcess.SlotAllocator() {
			private final AgentProcess.ResidentSlot slot = new AgentProcess.ResidentSlot(id(200),
				id(201), 0, id(202), ProcessKind.AGENT, true);
			@Override public Optional<AgentProcess.ResidentSlot> acquire(UUID agentId,
				UUID ownerClusterId, ProcessKind kind) { return Optional.of(slot); }
			@Override public boolean stillValid(AgentProcess.ResidentSlot candidate) {
				return slot.equals(candidate);
			}
			@Override public void release(AgentProcess.ResidentSlot candidate) {}
		});
	}

	private static AgentEndpointState endpoint(int x, long generation, AgentEndpointRole role,
		CreateRouteKey route) {
		return new AgentEndpointState(space(x), AGENT, TARGET, generation, role, route, 0, CommunicationFrequency.of("77"),
			AgentCompletionMode.QUIET, Optional.empty(),
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, 4, WAREHOUSE,
			true, true);
	}

	private static SpaceAddress space(int x) {
		return new SpaceAddress(net.minecraft.world.level.Level.OVERWORLD, null,
			new BlockPos(x, 0, 0));
	}

	private static AgentInputContract inputAtIntent(long generation, InputMode mode) {
		return inputAtIntent(generation, mode, ignored -> {});
	}

	private static AgentInputContract inputAtIntent(long generation, InputMode mode,
		AgentInputContract.StateStore stateStore) {
		return inputAtIntent(generation, mode, stateStore, STONE);
	}

	private static AgentInputContract inputAtIntent(long generation, InputMode mode,
		AgentInputContract.StateStore stateStore, StackKey exactInput) {
		ProductionRun run = new ProductionRun(RUN, GOAL, PATTERN, 10, 1, mode, List.of(INPUT),
			WAREHOUSE, 4, Map.of(exactInput, 1L));
		AgentInputContract input = new AgentInputContract(AGENT, generation, OUTPUT_EPOCH, run,
			AgentInputContract.ExecutionShape.STANDARD,
			List.of(new AgentInputContract.StageInput(0, INPUT, Map.of(exactInput, 1L))), 20,
			stateStore);
		input.markCurrentStageReady(10, Map.of(exactInput, 1L), Set.of(INPUT), 1);
		input.beginDispatch(id(300), 10).orElseThrow();
		return input;
	}

	private static AgentInputContract sequencedAtIntent(long generation) {
		CreateRouteKey second = new CreateRouteKey(NETWORK, "agent-stage-two");
		ProductionRun run = new ProductionRun(RUN, GOAL, PATTERN, 10, 1, InputMode.EXACT,
			List.of(INPUT, second), WAREHOUSE, 4, Map.of(STONE, 1L));
		AgentInputContract input = new AgentInputContract(AGENT, generation, OUTPUT_EPOCH, run,
			AgentInputContract.ExecutionShape.SEQUENCED, List.of(
				new AgentInputContract.StageInput(0, INPUT, Map.of(STONE, 1L)),
				new AgentInputContract.StageInput(1, second, Map.of(STONE, 1L))), 20,
			ignored -> {});
		input.markCurrentStageReady(10, Map.of(STONE, 1L), Set.of(INPUT, second), 1);
		input.beginDispatch(id(301), 10).orElseThrow();
		return input;
	}

	private static ProductionOutputLedger emptyDispatchedLedger(RemoteRequestKey key) {
		ProductionOutputLedger.CompletionBoundary boundary =
			new ProductionOutputLedger.CompletionBoundary(AgentCompletionMode.TARGET,
				ProductionOutputLedger.BoundaryReason.NO_OUTPUT_TIMEOUT, true, false, false,
				8, 0, 0, 1, OUTPUT_EPOCH, Map.of());
		return new ProductionOutputLedger(key.runId(), GOAL, key.fence(), OUTPUT_EPOCH, 4,
			WAREHOUSE, Map.of(), Map.of(), Map.of(),
			new ProductionOutputLedger.SealFact(0, false, false, 0, false, boundary));
	}

	private static ProductionOutputContract.Snapshot dispatchedOutputSnapshot(
		AgentInputContract.State input, ProductionOutputLedger ledger) {
		OutputLogisticsGateway.EndpointReadiness endpoint =
			new OutputLogisticsGateway.EndpointReadiness(true, OUTPUT_EPOCH, true);
		ProductionOutputContract.ReadinessEvidence readiness =
			new ProductionOutputContract.ReadinessEvidence(Map.of(OUTPUT, endpoint));
		ProductionOutputContract.SealOutcome outcome = new ProductionOutputContract.SealOutcome(0, false,
			false, 0, false, ledger.sealFact().completionBoundary());
		return new ProductionOutputContract.Snapshot(AGENT, input.agentResetGeneration(), OUTPUT_EPOCH,
			input.run(), 0, ProductionOutputContract.Milestone.OUTPUT_DISPATCHED,
			Optional.of(WaitReason.OUTPUT_DELIVERY), true, false, false, 8, 0, 0,
			Map.of(), Map.of(), List.of(), Map.of(), Map.of(), Set.of(), Set.of(), Set.of(),
			List.of(), Optional.empty(), 1, 0, Optional.of(readiness), Optional.of(outcome),
			Optional.empty(), true);
	}

	private static ProductionOutputContract.Snapshot withWaitReason(
		ProductionOutputContract.Snapshot state, Optional<WaitReason> waitReason) {
		return new ProductionOutputContract.Snapshot(state.agentId(), state.agentResetGeneration(),
			state.outputObservationEpoch(), state.run(), state.priorNoProgressRuns(),
			state.milestone(), waitReason, state.inputEnded(), state.signalObserved(),
			state.firstOutputObserved(), state.firstOutputElapsedTicks(), state.quietElapsedTicks(),
			state.probabilityElapsedTicks(), state.creditedOutputs(), state.diagnosticOutputs(),
			state.pendingOutputs(), state.dispatchedOutputs(), state.dispatchedEvents(),
			state.seenOutputEventIds(), state.satisfiedStockScopes(), state.acceptedAgentReceipts(),
			state.lateWarehouseMaterial(), state.dispatchIntent(),
			state.lastReadinessObservationSequence(), state.confirmedDispatchSequence(),
			state.readinessEvidence(), state.sealOutcome(), state.failure(), state.agentReleased());
	}

	private static void completeNativeInput(AgentInputContract input, int orderId) {
		if (input.phase() == AgentInputPhase.DISPATCH_INTENT) {
			CreateDispatchCommand command = input.snapshot().currentCommand().orElseThrow();
			input.confirmDispatch(AgentInputTestFacts.committed(command, orderId));
		}
		if (input.phase() == AgentInputPhase.DISPATCH_CONFIRMED)
			assertTrue(input.closeInput(10));
		if (input.phase() == AgentInputPhase.INPUT_CLOSED) {
			NativePackageObservation observation = new NativePackageObservation(INPUT, true,
				orderId, 0, 0, true, true,
				new NativeOrderLedger.OrderContext(Map.of(STONE, 1L)), Map.of(STONE, 1L));
			assertEquals(AgentInputContract.ObservationResult.ACCEPTED,
				input.observe(observation));
			assertTrue(input.endInput(10));
		}
	}

	private static RecoveryLedger.CancelDrainEvidence cancelDrainEvidence(RemoteRequestKey key,
		AgentInputContract.State input, ProductionOutputContract.Snapshot output,
		ProductionOutputLedger ledger, boolean loaded, boolean buffersEmpty,
		boolean backlogsEmpty) {
		RecoveryLedger.CancelDrainEvidenceIssuer issuer =
			new RecoveryLedger.CancelDrainEvidenceIssuer(() -> true, request ->
				Optional.of(new RecoveryLedger.ResolvedCancelDrainFacts(request.key(), id(950),
					loaded, buffersEmpty, backlogsEmpty)));
		return issuer.capture(key, input, output, ledger).orElseThrow();
	}

	private static UUID id(long value) { return new UUID(0, value); }

	private record Harness(RecoveryLedger ledger, AgentPendingRunQueue queue,
		LeaseAllocator leases) {}

	private static class RecordingStore implements RecoveryLedger.StateStore {
		protected RecoveryLedger.State state;
		private final List<RecoveryLedger.State> writes = new ArrayList<>();
		@Override public Optional<RecoveryLedger.State> load() { return Optional.ofNullable(state); }
		@Override public void persist(RecoveryLedger.State next) {
			state = next;
			writes.add(next);
		}
	}

	private static final class FaultStore extends RecordingStore {
		private int failCountdown;
		void failNext() { failAfter(0); }
		void failAfter(int successfulWrites) { failCountdown = successfulWrites + 1; }
		@Override public void persist(RecoveryLedger.State next) {
			if (failCountdown > 0 && --failCountdown == 0) {
				throw new IllegalStateException("simulated recovery persistence failure");
			}
			super.persist(next);
		}
	}

	private static final class InputFaultStore implements AgentInputContract.StateStore {
		private AgentInputContract.State state;
		private boolean failNext;
		void failNext() { failNext = true; }
		@Override public void persist(AgentInputContract.State next) {
			if (failNext) {
				failNext = false;
				throw new IllegalStateException("simulated input fence persistence failure");
			}
			state = next;
		}
	}

	private static final class LeaseAllocator
		implements AgentPendingRunQueue.PriorityLeaseAllocator {
		private int released;
		@Override public Optional<AgentPendingRunQueue.PriorityLease> acquire(
			ProductionRequestTicket ticket, UUID ownerClusterId, ProcessKind kind,
			boolean requiresAlchemist) {
			return Optional.of(new AgentPendingRunQueue.PriorityLease(id(400 + ticket.enqueueSequence()),
				ownerClusterId, ticket.agentId(), ticket.requestId(), kind, requiresAlchemist));
		}
		@Override public boolean replace(AgentPendingRunQueue.PriorityLease lease,
			ProductionRequestTicket expectedTicket, ProductionRequestTicket updatedTicket,
			UUID ownerClusterId) { return true; }
		@Override public void release(AgentPendingRunQueue.PriorityLease lease) { released++; }
	}
}
