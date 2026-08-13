# Factory Task Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the real-time distributed MCU task runtime, uniform processing proxies, real-stock dispatch, crash-conservative persistence, semantic signals, and bounded cleanup.

**Architecture:** Each Computer owns its frame, work slots, inbox, outbox, replies, and dispatch record; there is no central scheduler or persistent task directory. Pure selector/planner/deadlock services operate on frozen epoch snapshots. Server adapters read Create stock, discover loaded physical/virtual proxies, and transfer idempotent messages directly between loaded Computer BEs resolved through the coordinator's current structure snapshot.

**Tech Stack:** Java 21 sealed records/enums, Create LogisticsManager/Factory Panel APIs, Mixin for loaded Factory Gauge publication, Create WiFi particles, JUnit Jupiter, Computer BE NBT, `SubLevelCompat`.

## Global Constraints

- Requires plans 1–3 and keeps packet protocol at `15`.
- Frame states are only `RUN`, `WAIT`, `SLP`, `BLOCK`, and `HALT`; reasons are separate.
- Computer states are only `IDLE`, `RUN`, and `SLP`.
- A sleeping Computer remains occupied and cannot execute another frame.
- Root tasks and child tasks always probe from the weakest eligible node and tail-delegate upward only after ownership ACK.
- A busy lowest sufficient tier causes `WAIT(NODE)`; stronger nodes are not consumed for ordinary work.
- `BLOCK(WIDTH/DEPTH)` freezes the current epoch until players stop tasks; hot-added hardware does not unblock it.
- Stock comes only from accurate Create summaries and is re-read before material use, child completion, processing dispatch, and final delivery.
- Physical Factory Gauges and virtual patterns implement one immutable `ProcessingProxy` contract.
- No private item inventory, promise accounting, chunk loading, synchronous save, force-save, disk reread, or IO wait.
- Do not automatically replay an uncertain Create package dispatch after reload.
- Do not persist chronological scheduling logs.
- ACK, keepalive, cache hits, polling, retries, and cleanup never emit particles.

---

## File Map

**Create — domain/runtime**

- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/FrameState.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/FrameReason.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/ComputerState.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/OrderMode.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/TaskKey.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/DependencyKey.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/RootOrder.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/TaskFrame.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/MessageKind.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/ClusterMessage.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/OutboundMessage.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/InboundReceipt.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/NodeSelector.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/DependencyPlanner.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/DeadlockDetector.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/ComputerNodeRuntime.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/ComputerPeerTransport.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/DispatchState.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/DispatchRecord.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/ClusterSignal.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/ClusterSignalEmitter.java`

**Create — logistics/proxies**

- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/logistics/ProcessingProxy.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/logistics/ProxyKind.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/logistics/FactoryGaugeProxy.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/logistics/PatternProcessingProxy.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/logistics/FactoryGaugeProxyIndex.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/logistics/FactoryStockService.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/logistics/FactoryDispatchService.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/logistics/PatternCommunicationService.java`
- `src/main/java/com/nobodiiiii/createbiotech/mixin/FactoryPanelBehaviourMixin.java`

**Tests**

- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/NodeSelectorTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/DependencyPlannerTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/DeadlockDetectorTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/ComputerNodeRuntimeTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/RuntimePersistenceTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/logistics/FactoryGaugeProxyTest.java`

**Modify**

- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerBlockEntity.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStorageCoreBlockEntity.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelBlockEntity.java`
- `src/main/resources/create_biotech.mixins.json`
- `src/main/resources/assets/create_biotech/lang/en_us.json`
- `src/main/resources/assets/create_biotech/lang/zh_cn.json`

### Task 1: Lock Compact Task, Order, and Message Persistence Types

**Files:**
- Create: enum/value/message files through `InboundReceipt.java`.
- Test: `RuntimePersistenceTest.java`.

**Interfaces:**
- Produces: every stable runtime ID/NBT shape used by later tasks and the panel UI.

- [ ] **Step 1: Write save/load and state-invariant tests**

```java
@Test
void frameRoundTripPreservesStableIdsAndReason() {
	TaskFrame loaded = TaskFrame.load(frame.save(registries), registries).orElseThrow();
	assertEquals(frame.jobId(), loaded.jobId());
	assertEquals(frame.frameId(), loaded.frameId());
	assertEquals(frame.epochId(), loaded.epochId());
	assertEquals(FrameState.SLP, loaded.state());
	assertEquals(FrameReason.STOCK, loaded.reason());
}

@Test
void invalidStateReasonCombinationIsRejected() {
	assertThrows(IllegalArgumentException.class,
		() -> frame.withState(FrameState.BLOCK, FrameReason.STOCK));
}

@Test
void reloadTurnsArmedDispatchIntoUncertainRatherThanReplayable() {
	DispatchRecord loaded = DispatchRecord.load(armed.save(registries), registries).orElseThrow();
	assertEquals(DispatchState.UNCERTAIN, loaded.state());
	assertFalse(loaded.mayAutoDispatch());
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew.bat test --tests "*RuntimePersistenceTest" --offline`

Expected: compilation fails because runtime types do not exist.

- [ ] **Step 3: Add the exact compact enums**

```java
public enum FrameState { RUN, WAIT, SLP, BLOCK, HALT }
public enum FrameReason {
	NONE, NODE, CHILD, STOCK, MATERIAL, PATTERN, PROXY, STORAGE,
	OFFLINE, LOOP, ROUTE, WIDTH, DEPTH, OK, CANCEL, FAULT, LOOP_ABORT
}
public enum ComputerState { IDLE, RUN, SLP }
public enum OrderMode { FINITE, CONTINUOUS }
public enum MessageKind { OFFER, OWNERSHIP_ACK, CHILD_REPLY, REPLY_ACK, CANCEL }
public enum DispatchState { NONE, ARMED, CONFIRMED, UNCERTAIN }
```

`TaskFrame.withState` enforces `WAIT→NODE`, `BLOCK→WIDTH|DEPTH`, and `HALT→OK|CANCEL|FAULT|LOOP_ABORT`; `RUN` requires `NONE`; `SLP` accepts only the approved sleep reasons.

- [ ] **Step 4: Add immutable identifiers and records**

```java
public record TaskKey(UUID logisticsId, StackKey output) {}
public record DependencyKey(StackKey output, UUID proxyId, String recipeAddress,
	UUID logisticsId) {}
public record RootOrder(UUID jobId, UUID iterationId, UUID epochId, UUID logisticsId,
	StackKey output, int amount, OrderMode mode, String deliveryAddress) {}
public record ClusterMessage(UUID messageId, UUID epochId, UUID sourceComputerId,
	UUID targetComputerId, MessageKind kind, CompoundTag payload) {}
public record OutboundMessage(ClusterMessage message, boolean acknowledged) {}
public record InboundReceipt(UUID messageId, UUID ownedFrameId, MessageKind kind) {}
```

Addresses are stripped, non-empty and at most 25 characters; amounts are `1..BigItemStack.INF`. `TaskFrame` stores stable IDs, parent computer/frame IDs, key, count, local depth, immutable ancestor path, state/reason, direct child slots, aggregated complete/failed counts, selected immutable proxy snapshot, pending message IDs, and dispatch record. It does not store timestamped history.

- [ ] **Step 5: Implement NBT with local corruption isolation**

Each record has `save(HolderLookup.Provider)` and `Optional<...> load(CompoundTag, HolderLookup.Provider)`. `TaskFrame.load` rejects duplicate child slot/frame IDs, impossible state/reason pairs, mismatched epoch IDs, and invalid counts. `DispatchRecord.load` maps persisted `ARMED` to `UNCERTAIN`; persisted `CONFIRMED` stays confirmed; `mayAutoDispatch()` is true only for a newly created in-memory ARMED record whose transient `armedThisSession` flag is true.

- [ ] **Step 6: Run tests and commit**

Run: `./gradlew.bat test --tests "*RuntimePersistenceTest" --offline`

Expected: round-trip and corruption/uncertain-dispatch tests pass.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/RuntimePersistenceTest.java
git commit -m "feat: define persistent factory task frames"
```

### Task 2: Implement Weakest-Sufficient Node Selection and Tail Delegation

**Files:**
- Create: `NodeSelector.java`
- Test: `NodeSelectorTest.java`

**Interfaces:**
- Consumes: `ClusterEpoch`, `ComputerProfile`, per-node availability.
- Produces: deterministic `Selection` for root/child probe and post-expansion capability escalation.

- [ ] **Step 1: Write selector tests**

```java
@Test
void probeSkipsZombieAndChoosesNitwit() {
	assertEquals(NITWIT_ID, selector.select(epoch, statuses, 0, 0).computerId());
}

@Test
void leastSlackPreservesWideOrDeepSpecialists() {
	// 16/1 and 2/2 can both do 1/1; 2/2 has lower total slack.
	assertEquals(TWO_BY_TWO_ID, selector.select(epoch, statuses, 1, 1).computerId());
}

@Test
void busyLowestSufficientTierWaitsInsteadOfTakingStrongerNode() {
	Selection result = selector.select(epoch, busyLowestStatuses, 4, 2);
	assertEquals(SelectionKind.WAIT, result.kind());
	assertEquals(LOWEST_SUFFICIENT_ID, result.computerId());
}

@Test
void impossibleCapabilityBlocksWithExactAxis() {
	assertEquals(FrameReason.WIDTH, selector.select(epoch, statuses, 17, 1).reason());
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew.bat test --tests "*NodeSelectorTest" --offline`

Expected: compilation fails because `NodeSelector` does not exist.

- [ ] **Step 3: Implement deterministic capability tiers**

Filter zombie/zero-slot nodes and nodes below required slots/depth. Rank sufficient profiles by:

```java
int slack = (profile.slots() - requiredSlots)
	+ 16 * (profile.depth() - requiredDepth);
Comparator<EpochNode> rank = Comparator.comparingInt(node -> slack(node.profile()))
	.thenComparingInt(node -> node.profile().depth())
	.thenComparingInt(node -> node.profile().slots())
	.thenComparing(EpochNode::computerId);
```

Determine the lowest capability tuple before availability. If one member of that tuple is idle, choose its lowest UUID. If all members of that tuple are occupied/offline, return `WAIT` for that tier and do not inspect a stronger tuple. If no node supports slots, return `BLOCK/WIDTH`; if slots exist but depth does not, return `BLOCK/DEPTH`.

- [ ] **Step 4: Implement ownership semantics**

Tail delegation creates one `OFFER` containing the unchanged `frameId` and owner generation. The source remains occupied in `WAIT(NODE)` until `OWNERSHIP_ACK`. On ACK it removes the outbound offer and its local frame in one BE mutation. Duplicate offers return the existing receipt/ACK and never create a second frame.

- [ ] **Step 5: Run tests and commit**

Run: `./gradlew.bat test --tests "*NodeSelectorTest" --offline`

Expected: all selection/tier/wait/block tests pass.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/NodeSelector.java src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/NodeSelectorTest.java
git commit -m "feat: dispatch factory tasks weakest first"
```

### Task 3: Unify Virtual Patterns and Physical Factory Gauges

**Files:**
- Create: all files under `factorycluster/logistics` through `FactoryGaugeProxyIndex.java`.
- Create: `FactoryPanelBehaviourMixin.java`
- Modify: `create_biotech.mixins.json`
- Test: `FactoryGaugeProxyTest.java`

**Interfaces:**
- Produces: immutable loaded `ProcessingProxy` candidates by selected logistics UUID.

- [ ] **Step 1: Write physical-proxy adapter tests**

```java
@Test
void physicalGaugeRequiresOneNetworkForOutputAndEveryInput() {
	assertTrue(FactoryGaugeProxy.from(validGauge, NETWORK).isPresent());
	assertTrue(FactoryGaugeProxy.from(mixedInputNetworkGauge, NETWORK).isEmpty());
}

@Test
void proxySeparatesRecipeAddressFromFinalAddress() {
	ProcessingProxy proxy = FactoryGaugeProxy.from(validGauge, NETWORK).orElseThrow();
	assertEquals("iron_processing", proxy.recipeAddress());
	assertNotEquals(ROOT_DELIVERY_ADDRESS, proxy.recipeAddress());
}

@Test
void virtualAndPhysicalCandidatesShareTheSameContract() {
	assertEquals(physical.mainOutput(), virtual.mainOutput());
	assertEquals(physical.inputs().getFirst().count(), virtual.inputs().getFirst().count());
}
```

- [ ] **Step 2: Define the common proxy contract**

```java
public enum ProxyKind { FACTORY_GAUGE, PATTERN }

public interface ProcessingProxy {
	UUID proxyId();
	UUID logisticsId();
	ProxyKind kind();
	SpaceAddress address();
	List<PatternIngredient> inputs();
	List<PatternOutput> outputs();
	String recipeAddress();
	default PatternOutput mainOutput() { return outputs().getFirst(); }
}
```

Both implementations deep-copy stacks/components. `PatternProcessingProxy` wraps the immutable `PatternRecord` returned by the library.

- [ ] **Step 3: Adapt loaded Factory Gauges strictly**

Accept a `FactoryPanelBehaviour` only when active, not a restocker, output filter non-empty, `recipeOutput > 0`, and `recipeAddress` non-blank/at most 25. Its output network and every source behavior in `targetedBy` must equal the order network. Resolve each source through `FactoryPanelBehaviour.at`; if one is unloaded/missing/empty, omit the candidate. Merge identical exact input stacks; reject count overflow.

The stable proxy UUID is `UUID.nameUUIDFromBytes` over root dimension, sublevel UUID, local panel position, panel slot, output stack/components, and recipe address.

- [ ] **Step 4: Publish gauges through a weak runtime index**

`FactoryGaugeProxyIndex` mirrors the cluster index: weak per-server references keyed by logistics UUID and stable physical panel position; no NBT/SavedData. Inject publication and actual destruction only:

```java
@Inject(method = "initialize", at = @At("RETURN"), remap = false)
private void createBiotech$publish(CallbackInfo ci) {
	FactoryGaugeProxyIndex.publish((FactoryPanelBehaviour) (Object) this);
}

@Inject(method = "destroy", at = @At("HEAD"), remap = false)
private void createBiotech$remove(CallbackInfo ci) {
	FactoryGaugeProxyIndex.remove((FactoryPanelBehaviour) (Object) this);
}
```

Append `FactoryPanelBehaviourMixin` to the common mixin list. Publication ignores client levels. `FactoryPanelBehaviour` inherits `unload` rather than declaring it, so the required Mixin must not target `unload`. Lookup prunes cleared weak references and behaviors whose `panelBE()` is removed or whose owning chunk is no longer loaded; `initialize` republishes the replacement instance after movement/reload. Destruction removal checks reference identity so an old moved source cannot remove the replacement instance.

- [ ] **Step 5: Run tests/compile and commit**

```powershell
./gradlew.bat test --tests "*FactoryGaugeProxyTest" --offline
./gradlew.bat compileJava --offline
```

Expected: adapter tests and Mixin compilation pass.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/logistics src/main/java/com/nobodiiiii/createbiotech/mixin/FactoryPanelBehaviourMixin.java src/main/resources/create_biotech.mixins.json src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/logistics/FactoryGaugeProxyTest.java
git commit -m "feat: expose loaded factory processing proxies"
```

### Task 4: Plan Direct Dependencies, Width, Depth, and Loops in Real Time

**Files:**
- Create: `DependencyPlanner.java`
- Create: `DeadlockDetector.java`
- Test: `DependencyPlannerTest.java`
- Test: `DeadlockDetectorTest.java`

**Interfaces:**
- Consumes: actual stock callback and immutable proxy candidates.
- Produces: one-step expansion decisions; never a prebuilt DAG.

- [ ] **Step 1: Write direct-expansion tests**

```java
@Test void mergesIdenticalExactDependenciesBeforeCountingThreads();
@Test void componentDifferentStacksRemainDifferentThreads();
@Test void stockSatisfiesDependencyBeforeProxyLookup();
@Test void cyclicCandidateFallsBackToNonCyclicCandidate();
@Test void allCyclicWithoutNitwitSleepsLoop();
@Test void allCyclicWithNitwitHaltsLoopAbort();
```

Assert that a recipe with six slots but only two merged processing branches reports `requiredThreads=2`, not six.

- [ ] **Step 2: Implement one-step planning**

```java
public sealed interface PlanDecision {
	record Complete() implements PlanDecision {}
	record Expand(ProcessingProxy proxy, List<PlannedDependency> dependencies,
		int requiredThreads, int requiredDepth) implements PlanDecision {}
	record Sleep(FrameReason reason) implements PlanDecision {}
	record Halt(FrameReason reason) implements PlanDecision {}
}
```

Re-read stock for the requested exact output first. For each candidate, multiply input counts using `Math.multiplyExact`, subtract current stock, and merge positive deficits by `DependencyKey`. Reject candidates for the wrong network. A dependency `TaskKey` already in the frame's ancestors marks that candidate path cyclic; try all other candidates before LOOP/LOOP_ABORT. `requiredDepth` is `0` for a stock/leaf completion and `1` when direct child processing exists.

- [ ] **Step 3: Write and implement distributed deadlock tests**

Model epoch node states plus wait edges. Detect `BLOCK(DEPTH)` only when:

- all runnable epoch nodes are occupied;
- no frame is `RUN` and no frame has reached a leaf/releasable completion;
- the deepest waiting external-child offer has no idle sufficient tier;
- all relevant wait edges remain inside the frozen epoch.

Return the deepest waiting frame, breaking ties by frame UUID. Do not classify `SLP(STOCK|PATTERN|PROXY|ROUTE|OFFLINE)` as depth deadlock because an external event can wake it.

- [ ] **Step 4: Run tests and commit**

```powershell
./gradlew.bat test --tests "*DependencyPlannerTest" --tests "*DeadlockDetectorTest" --offline
```

Expected: merge/loop/width/depth tests pass.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/DependencyPlanner.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/DeadlockDetector.java src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/DependencyPlannerTest.java src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/DeadlockDetectorTest.java
git commit -m "feat: expand factory tasks in real time"
```

### Task 5: Add Stock, Pattern-Distance, and Dispatch Services

**Files:**
- Create: `FactoryStockService.java`
- Create: `FactoryDispatchService.java`
- Create: `PatternCommunicationService.java`

**Interfaces:**
- Produces: server-only adapters used by `ComputerNodeRuntime`.

- [ ] **Step 1: Implement actual-stock reads**

```java
public int count(UUID logisticsId, StackKey key) {
	return LogisticsManager.getSummaryOfNetwork(logisticsId, true)
		.getCountOf(key.stack());
}
```

Every caller also checks `Create.LOGISTICS.mayInteract(logisticsId, submittingPlayer)` at order entry; runtime frames only use logistics IDs frozen into their validated order.

- [ ] **Step 2: Implement pattern-core selection and external-coordinate range**

Resolve exactly one loaded pattern core from `ClusterMemberIndex`; conflict/missing/invalid structure returns unavailable. Require cluster/root dimension and allowed logistics bindings. Compute:

```java
int range = Math.min(configuredMax,
	configuredBase + epoch.totalPatternRangeBonus());
Vec3 computerWorld = SubLevelCompat.toWorld(level, coordinatorPos, localBoundsCenter);
Vec3 libraryWorld = library.memberAddress().worldCenter(level);
boolean inRange = computerWorld.distanceToSqr(libraryWorld) <= (double) range * range;
```

Do not compute distance for panel access, computer-internal messages, or Create stock.

- [ ] **Step 3: Implement package dispatch without promises**

Build `PackageOrderWithCrafts.simple(List<BigItemStack>)`. Processing inputs call:

```java
LogisticsManager.broadcastPackageRequest(logisticsId, RequestType.RESTOCK,
	order, null, proxy.recipeAddress())
```

Root final-output delivery calls:

```java
LogisticsManager.broadcastPackageRequest(logisticsId, RequestType.PLAYER,
	order, null, rootOrder.deliveryAddress())
```

Re-read stock immediately before either call. `false` means `SLP(ROUTE)`. A successful call marks the local dispatch `CONFIRMED`; confirmed dispatches are never called again. A reloaded `UNCERTAIN` dispatch remains `SLP(ROUTE)` until the player explicitly cancels or chooses retry in the final UI plan.

- [ ] **Step 4: Compile and commit**

Run: `./gradlew.bat compileJava --offline`

Expected: `BUILD SUCCESSFUL`.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/logistics
git commit -m "feat: connect factory tasks to Create logistics"
```

### Task 6: Integrate Local Computer Runtime, Mailboxes, and Cleanup

**Files:**
- Create: `ComputerNodeRuntime.java`
- Create: `ComputerPeerTransport.java`
- Modify: `ComputerBlockEntity.java`
- Modify: `PatternStorageCoreBlockEntity.java`
- Test: `ComputerNodeRuntimeTest.java`

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces: `submitRoot`, server tick progression, cancel/stop, live read-only snapshots.

- [ ] **Step 1: Write scenario tests with fake services**

```java
@Test void rootAndEveryChildStartAtWeakestProbeNode();
@Test void ownershipAckReleasesTailDelegatingSource();
@Test void parentSleepingForChildRemainsOccupied();
@Test void continuousBatchReleasesAndRequeuesFromWeakest();
@Test void duplicateOfferIsIdempotent();
@Test void confirmedDispatchNeverRepeatsAfterTickOrReload();
@Test void cancelRemovesFramesOffersRepliesButNotConfirmedPackages();
@Test void completedChildrenLeaveOnlyAggregateCounts();
```

- [ ] **Step 2: Implement peer resolution without another directory**

`ComputerPeerTransport` resolves the one loaded coordinator through `ClusterMemberIndex`, reads its current `ComputerStructureSnapshot`, finds the target `computerId` address, and uses `SpaceAddress.resolveBlockEntity`. It verifies target cluster/epoch/computer IDs and root dimension before delivery. It never scans worlds and never loads a chunk.

- [ ] **Step 3: Implement one bounded server tick**

Each Computer tick performs in order:

1. reject/pause when structure, coordinator, or epoch is offline;
2. accept at most 16 inbound messages, deduplicating `messageId`;
3. retry at most 16 unacknowledged ownership/reply messages;
4. advance at most one control-frame transition;
5. advance at most `profile.slots()` direct child slots;
6. run at most one stock/proxy/pattern/dispatch operation;
7. send ACKs and compact acknowledged records;
8. mark dirty/send compact status only when state changed.

No loop walks an unbounded mailbox or page collection in one tick.

- [ ] **Step 4: Implement local frame execution**

The runtime applies `DependencyPlanner`. Capability-insufficient frames tail-delegate via `NodeSelector`. Accepted frames create at most `profile.slots()` direct local children while `localDepth < profile.depth`; at the boundary, external children are offered from the weakest tier and the parent enters `SLP(CHILD)`. Child replies are ACKed before sender deletion. Parent re-reads stock before considering the child result satisfied.

Finite root completion re-reads actual output stock, dispatches to the explicit final address, then HALTs OK and deletes after notification ACK. Continuous completion creates a new iteration UUID, releases the current Computer, and submits a fresh root probe from the weakest node.

- [ ] **Step 5: Implement cancellation and bounded cleanup**

- ownership ACK removes the source offer/frame copy;
- reply ACK removes the child reply;
- completed child subtrees become only success/failure counters in the parent;
- root UI notification ACK removes a finished root;
- cancel clears local frames, non-confirmed dispatches, waits, offers, replies, and receipts for the job;
- confirmed packages are never recalled;
- an offline epoch member is never classified as an orphan;
- epoch closure requires every frozen member online, idle, empty-mailbox, and no root jobs.

- [ ] **Step 6: Run runtime tests and commit**

Run: `./gradlew.bat test --tests "*ComputerNodeRuntimeTest" --offline`

Expected: every scenario passes and fake dispatch invocation count remains exactly one.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerBlockEntity.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStorageCoreBlockEntity.java src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/ComputerNodeRuntimeTest.java
git commit -m "feat: run distributed factory task frames"
```

### Task 7: Add Coalesced Create WiFi Semantic Signals

**Files:**
- Create: `ClusterSignal.java`
- Create: `ClusterSignalEmitter.java`
- Modify: panel/core/computer blocks and BEs.

**Interfaces:**
- Produces: particle-only semantic feedback without packet registration.

- [ ] **Step 1: Define only approved semantic events**

```java
public enum ClusterSignal {
	ORDER, ROOT_ACCEPT, CHILD_OR_TAIL, PATTERN_QUERY, PATTERN_RESULT,
	PROCESS_DISPATCH, STOCK_WAKE, FINAL_SUCCESS
}
```

- [ ] **Step 2: Coalesce per endpoint/tick and use block events**

Each endpoint BE has an `EnumSet<ClusterSignal> pendingSignals`. `queueSignal` adds only; at server tick end, one `level.blockEvent(worldPosition, getBlockState().getBlock(), SIGNAL_EVENT_ID, 0)` is sent and the set clears. The block's client `triggerEvent` calls:

```java
Vec3 center = Vec3.atCenterOf(pos);
level.addParticle(new WiFiParticle.Data(), center.x, center.y, center.z, 1, 1, 1);
```

This deliberately uses the endpoint's local `Level` and local coordinates so Sable renders it once in the correct space.

- [ ] **Step 3: Wire signal points**

Emit at validated order submission, root acceptance, child/tail send+receive, pattern query send/core receive, pattern result send/computer receive, confirmed processing dispatch, stock-level wake, and final-success executing node/panel receive. Factory Gauge keeps its native effect. Do not call the emitter from ACK/retry/poll/cache/cleanup paths.

- [ ] **Step 4: Build, inspect, and commit**

```powershell
./gradlew.bat test --offline
./gradlew.bat build --offline
git diff --check
git grep -n -E "saveAllChunks|waitUntilIOWorkerComplete|DimensionDataStorage\.save" -- src/main/java/com/nobodiiiii/createbiotech/content/factorycluster
```

Expected: tests/build pass, no whitespace errors, forbidden synchronous-save search is empty.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster
git commit -m "feat: signal factory task activity"
```

### Task 8: Runtime Game-Test Gate

**Files:**
- Create: `src/main/java/com/nobodiiiii/createbiotech/gametest/FactoryRuntimeGameTests.java`

**Interfaces:**
- Produces: server-world regression coverage before UI exposure.

- [ ] **Step 1: Add required scenario tests**

Use an empty template and deterministic fake proxy hooks to cover:

```java
static void finiteOrderCompletesAndDispatchesOnce(GameTestHelper helper);
static void continuousOrderRequeuesWeakest(GameTestHelper helper);
static void widthBlocksFrozenEpoch(GameTestHelper helper);
static void depthDeadlockBlocksBranch(GameTestHelper helper);
static void loopSleepsWithoutNitwit(GameTestHelper helper);
static void loopAbortHaltsRootWithNitwit(GameTestHelper helper);
static void wrongNetworkStockNeverSatisfiesFrame(GameTestHelper helper);
static void patternRangeUsesExternalCoordinates(GameTestHelper helper);
static void unloadedNodeFreezesWithoutChunkTicket(GameTestHelper helper);
```

- [ ] **Step 2: Run GameTest**

Run: `./gradlew.bat runGameTestServer --offline`

Expected: all runtime tests pass and server exits successfully.

- [ ] **Step 3: Commit**

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/gametest/FactoryRuntimeGameTests.java
git commit -m "test: cover distributed factory runtime"
```
