# Task 6 report — stable Computer coordinators

## Scope and decisions

- Base: `d5c39e254116a4d7d898d23efb2595daad4640f1`.
- Each `ComputerBlockEntity` constructs one lifetime-stable `public final`
  `ComputerCoordinatorMember`. Non-elected instances remain dormant; the BE itself never
  implements `ClusterMember`.
- Only the exact elected, accepted, profile-ready Computer publishes its owned adapter.
  Publication caches the accepted exact BE object list. Invalidate, server read, profile loss,
  identity/not-ready transition, coordinator change, and binding rekey unregister the exact old
  object before its state changes.
- Adapter prepare validates the cached replica identities, persistence, record/coordinator,
  root dimension, epoch/fault state, revisions, authority access, and binding capacity without
  persistent writes. Commit uses only the cached immutable proposal/object list and performs the
  replica write inside one `ClusterMemberIndex.rebind`; it does not scan or resolve the world.
- Existing lower-revision replicas are preserved until Foundation reports `READY`; ahead and
  equal-divergent replicas force adapter access back to `CONFLICT`. A Task-5 fresh joiner is
  deliberately narrower: only `record == null && epoch == null && binding == null` may inherit the
  authoritative binding inside the topology staged transaction.
- `startEpoch` retains the Task-5 local gates and, in the real world path, additionally requires
  the owned published adapter and same-call `ClusterBindingService.bindingAccess == READY`.
- `ComputerStructureLocator.findCoordinator` is a public thin mapping over the existing final
  BE revalidation and never creates an adapter.
- `ComputerCasingBlock` follows the required server/selection/locator/public-bind order. The
  no-selection fallback is `super.onSneakWrenched(...)`: `CasingBlock` already supplies the
  inherited `IWrenchable` implementation, so `IWrenchable.super` is not a legal Java qualifier in
  this subclass.

## Test fixture rulings

- No Unsafe, BE/level/registry private-state reflection, fake null-level entity, network, mixin,
  SavedData, or GameTest was added.
- Per scope ruling, only the type-injecting constructors of `FactoryPanelBlockEntity` and
  `PatternStorageCoreBlockEntity` changed from package-private to `protected`; thin test subclasses
  override only `memberAddress()`.
- The production-only publication test wrapper was removed. The computer-package owned test now
  exposes a test-only `PublishedStructure` fixture to the Foundation-package owned test, while all
  member/binding work still runs through real BEs and the existing package-private production
  transaction entry.
- The two owned tests reuse the repository's approved `LoadingModList.of/get` bootstrap and the
  approved `Bootstrap.isBootstrapped=true` reflection solely to bypass unavailable pure-JUnit
  resource bootstrap. This makes GameTest unnecessary and does not mutate BE, level, registry,
  FluidType, Attribute, or production private state.

## RED / GREEN record

1. Tests-only expanded RED:
   `gradlew.bat test --tests "*ComputerCoordinatorMemberTest" --tests
   "*ComputerCoordinatorBindingTest" --offline --no-daemon --max-workers=1`
   failed compilation first on missing `ComputerCoordinatorMember` (before production existed).
2. Initial GREEN iterations compiled, then exposed the normal Minecraft bootstrap dependency;
   after the approved existing bootstrap fixture, 11 tests reached behavior assertions (6 failed),
   then 3, 2, and 1 failed as the minimal lifecycle/propagation fixes landed.
3. Self-review RED: the focused lower-replica test failed 1/1 because `READY` plus an
   equal-divergent internal replica left `canRebind()` true. The adapter now records `CONFLICT`.
4. Self-review RED: the focused profile-loss test failed 1/1 because a non-owner replica mutation
   did not withdraw the inactive publication. Exact publication invalidation now precedes profile
   mutation.
5. Full-build regression RED: 329 tests / 5 failures, all existing Task-5 fresh hot-add/pending
   cases. The named `inactiveSameShellLowerUuidHotAddUpdatesCoordinatorAndPreservesIdentityBinding`
   locked the fresh-joiner rule above. The focused Task-5 controller suite then passed 40/40.

Every Gradle round used D-drive `TEMP`/`TMP`, `--offline --no-daemon --max-workers=1`, ran
serially, and ended with `gradlew.bat --stop`. No PID-based process termination was used; the only
explicitly observed debug daemon was this task's single-use PID `64504`, which exited normally.

## Final verification

- Task 6 focused: 12/12 passed (`ComputerCoordinatorMemberTest` 9,
  `ComputerCoordinatorBindingTest` 3), `BUILD SUCCESSFUL`.
- Task-5 controller regression: 40/40 passed, `BUILD SUCCESSFUL`.
- Foundation regression: 29/29 passed (`ClusterBindingServiceTest` 23,
  `ClusterMemberIndexTest` 6), `BUILD SUCCESSFUL`.
- Full `gradlew.bat build --offline --no-daemon --max-workers=1`: 329/329 passed,
  0 failures/errors/skips; assemble/check/build completed.
- `git diff --check`: clean.
- Final free space remained above the requested floor (C about 9.08 GB, D about 50.76 GB).
- Compiler output contains only pre-existing deprecation and missing-annotation-enum warnings.

## Self-review

- Confirmed no per-tick/new temporary adapters and no `ComputerBlockEntity implements
  ClusterMember`.
- Confirmed exact-object unregister occurs before read/discard/rekey and replica cache invalidation
  is identity-based.
- Confirmed prepare serialization remains byte-identical on refusal and commit performs zero world
  probes.
- Confirmed one immutable binding object reaches every prepared replica; non-coordinators never
  publish.
- `ClusterBinding` itself rejects more than `MAX_BINDINGS` at construction, so the test covers that
  public constructor gate; the adapter retains the defensive `CAPACITY` branch for API completeness.
- Diff contains only the seven brief-owned production/test paths, the two constructor files allowed
  by explicit scope ruling, and this report.
