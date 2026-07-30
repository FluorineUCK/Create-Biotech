# Slime Universal Joint Local-Link Design

**Status:** Approved for implementation on 2026-07-30.

## Context

The current universal-joint implementation treats every endpoint and pair as an
independent persistent object in `UniversalJointLinkSavedData`. That directory
also acts as a move journal, dismantling tombstone, fracture journal, repair
journal, and address reservation table.

This prevents immediate reuse of a dismantled endpoint address until the world
save callback retires its tombstone. It also adds global lookups and recovery
work to operations whose two physical endpoints already contain enough
information to find each other.

The replacement follows the user-approved weak-crash-consistency model:

- normal operations finish synchronously while both endpoints are loaded;
- a server crash between two block replacements may leave a partial operation;
- loaded endpoints validate their mutual references and recover by completing or
  clearing the local operation where possible;
- no global address reservation or exactly-once journal is retained.

## Goals

1. Store a connection only on its two block entities.
2. Reuse a removed endpoint position immediately, before a world save.
3. Resolve the peer through `SubLevelCompat` using raw position and sublevel UUID.
4. When Sable changes one endpoint's address or sublevel UUID, update the peer's
   local reference directly.
5. Preserve world-to-sublevel and sublevel-to-sublevel rendering, kinetics,
   elastic impulse, slowdown, fracture, half-shaft repair, and normal drops.
6. Keep Sable optional and keep the network protocol version at `15`.

## Non-goals

- Persistent UUID-to-address lookup after the selected half shaft moves and the
  server restarts.
- Exactly-once fracture drops across a crash.
- Atomic repair material consumption across a crash.
- Force-loading an unloaded peer chunk.

## Local Data Model

Every intact joint and half shaft keeps:

```text
endpointId: UUID
moveRevision: long
```

An intact joint additionally keeps:

```text
linkId: UUID
peerEndpointId: UUID
peerMoveRevision: long
peerAddress:
  dimension: ResourceKey<Level>
  subLevelId: nullable UUID
  rawPos: BlockPos
expectedOwnAddress:
  dimension: ResourceKey<Level>
  subLevelId: nullable UUID
  rawPos: BlockPos
```

`linkId` identifies one connection epoch. Reconnecting the same endpoints
creates a new value. `moveRevision` increases before a Sable move so a stale
source copy cannot satisfy a reference to the moved endpoint.

The records live with `UniversalJointEndpointBlockEntity`; they are value types,
not a directory or registry.

## Mutual Validation

Endpoints A and B form an active link only when:

1. A's peer endpoint ID equals B's endpoint ID.
2. B's peer endpoint ID equals A's endpoint ID.
3. Both link IDs are equal.
4. Each stored peer revision is not newer than the peer's current revision.
5. Each stored peer address equals the peer's current dimension, raw position,
   and sublevel UUID.

A missing, unloaded, or differently occupied peer address keeps the link
dormant. The endpoint never updates or modifies an occupant whose identity does
not match. It also does not clear the dormant reference merely because the
cached address is empty: that state is indistinguishable from a peer that moved
while this endpoint was unloaded and still needs to advertise its new address.
No dormant reference reserves the old address or makes a newly placed block part
of the link.

## Sable Movement

`UniversalJointEndpointBlockSableMixin` remains the optional Sable assembly and
drag adapter. It calls the endpoint before/after move methods but no longer
starts a global transit transaction.

Before moving:

1. Capture the source and destination address.
2. Increment the moving endpoint's revision.
3. Store the destination as the endpoint's latest expected own address.
4. Mark the source endpoint as being moved so its removal callback cannot
   dismantle the pair.

After moving:

1. Validate that the actual address equals the expected destination.
2. Resolve the peer through the endpoint's existing peer address without
   loading a chunk.
3. Validate the peer endpoint ID and link ID.
4. Advertise the moving endpoint's actual address and new revision to the peer.
5. Refresh the moving endpoint's peer address from the peer's actual address.
6. Clear the transient move flag and synchronize only the two affected block
   entities.

If the peer is unloaded, the moved endpoint retains its local reference and
retries while ticking.

Sable processes one block as `beforeMove -> serialize old BE -> create/load new
BE -> afterMove`, and removes all source blocks only after the whole batch. For
two linked endpoints moved in one batch, the first endpoint advertises its new
address to the second source BE before that second BE is serialized. The second
destination BE therefore already knows the first destination address and can
advertise back. This covers either block iteration order without a remapper.

No static remapper, SavedData directory, time-to-live map, or world scan is
introduced.

The Sable mixins themselves remain necessary:

- `UniversalJointEndpointBlockSableMixin` supplies optional movement callbacks
  and endpoint air drag.
- `UniversalJointBlockEntitySableMixin` supplies physics impulses. It leaves
  Sable's loading-dependency chain untouched so linked sublevels retain their
  normal independent serialization and unloading behavior.
- `SableMixinPlugin` prevents hard loading optional Sable API classes.

## Connection and Dismantling

Pair placement writes a newly generated `linkId` and reciprocal peer references
to both newly placed joints. Failure rolls both blocks back immediately.

Breaking an endpoint:

1. Resolve and validate the loaded peer.
2. Clear both local references before changing either block.
3. Destroy the peer without drops under the existing controlled-replacement
   guard.
4. Allow the clicked endpoint to produce the one normal universal-joint drop.

If the peer is not loaded, the removed endpoint cannot leave a global deletion
obligation. The stale peer remains dormant when it later loads; it never
modifies a different endpoint occupying the cached address, never renders an
active shaft without reciprocal validation, and does not reserve either
position.

## Fracture

The stable primary endpoint, chosen by endpoint UUID ordering, owns the fracture
attempt. It proceeds only when both mutually linked endpoints are loaded:

1. Guard both endpoints against recursive removal.
2. Snapshot block states, endpoint identities, positions, and velocities.
3. Replace both joints with half shafts while preserving facing, waterlogging,
   endpoint UUID, and move revision.
4. Roll both positions back if either replacement fails.
5. Spawn one slime ball at the world-space midpoint with the average endpoint
   velocity.

There is no fracture entity lock or persistent pending-drop journal.

## Half-shaft Repair

The slime-filled box selection stores the selected endpoint identity, revision,
dimension, sublevel UUID, and raw position. The second click resolves that exact
address through `SubLevelCompat`; it never scans the world.

After distance and identity validation:

1. Snapshot both half shafts and the box.
2. Replace both halves with joints while preserving endpoint identities.
3. Write a new reciprocal local link.
4. Roll both blocks back on failure.
5. Clear the captured slime and repair selection only after both joints link.

Moving the selected half after the first click invalidates the cached selection
unless its old address still resolves to the same endpoint. This is the explicit
cost of removing persistent UUID-to-address lookup.

## Kinetics and Physics

Kinetic bridge inspection uses only a mutually validated loaded pair. Driver
selection keeps the current external-source comparison; ties use the existing
valid bridge signature and then stable endpoint UUID ordering.

The elastic owner rule is:

- root-to-sublevel: the sublevel endpoint owns the impulse;
- two different sublevels: the lower endpoint UUID owns it;
- same space: no Sable cross-space impulse.

The current configurable distance, damping, impulse, endpoint drag, and slowdown
values remain unchanged and continue to use `CBConfigs`.

## Cleanup

Remove:

- `UniversalJointLinkSavedData`;
- server tick and level-save handlers used only by its journals;
- fracture-drop entity locking and its entity Mixin;
- repair-consumption locking and player/world save verification;
- transit generations, tombstones, pair statuses, and save-boundary cleanup.

Retain:

- `SubLevelCompat` coordinate, velocity, normal, space, and strict block-entity
  resolution helpers;
- Sable's two optional universal-joint mixins and resource-based plugin check;
- existing visual, placement, block, half-shaft, and config registrations.

## Compatibility

Existing block-entity NBT already contains linked position, linked sublevel,
endpoint ID, and pair/link ID. The new reader consumes those values directly.
If a legacy link lacks peer identity or link ID, it remains dormant until both
legacy endpoints are loaded and mutually point to each other; one local
normalization pass then fills the missing identity fields.

The obsolete SavedData file is ignored. It does not reserve positions and can
remain harmlessly in an existing world save.
