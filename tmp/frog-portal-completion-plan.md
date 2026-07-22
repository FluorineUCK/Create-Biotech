# 巨型青蛙传送门 + 青蛙胃袋维度 — Completion Plan

## Context / current state (discovered)

Work lives **untracked** in the `feat/frog-portal` worktree
(`.claude/worktrees/frog-portal`, branch based on the 1.21.1 NeoForge port).

Already written (logic complete, 1.21.1 NeoForge idioms — data components, `HolderLookup.Provider` save/load):

- `content/frogportal/` — 8 classes: `GiantFrogPortalBlock(+BlockEntity)`, `FrogEsophagusBlock(+BlockEntity)`,
  `FrogStomachDimensions`, `FrogStomachSavedData`, `FrogStomachSpace`, `FrogTeleportCooldowns`
- `data/create_biotech/dimension/frog_stomach.json` + `dimension_type/frog_stomach.json`

**Problem:** nothing is registered. The classes reference symbols that don't exist yet, so the branch
does not compile and the blocks have no models/lang/loot:
`CBBlocks.{GIANT_FROG_PORTAL, FROG_STOMACH_WALL, FROG_ESOPHAGUS}`,
`CBBlockEntityTypes.{GIANT_FROG_PORTAL, FROG_ESOPHAGUS}`, `CBItems.GIANT_FROG_PORTAL`,
`CBDataComponents.FROG_STOMACH_SPACE`, `CBConfigs.SERVER.frogStomach.boxSize`.

## What the feature does (from the code)

- **Giant Frog Portal** block — stand on top → teleported into a private room in the **Frog Stomach**
  dimension. Each portal binds to a unique room; the binding rides on the item via a data component, so
  break/replace rebinds to the same room. Item is non-stackable.
- **Frog Stomach** dimension — flat void world; rooms are hollow indestructible cubes on a grid, built on
  demand. Each room has a **Frog Esophagus** (indestructible return portal): stand on it → return to your
  entry point.
- Room edge length is a server config, default **48** (3×3 chunks).

## Decisions (from your answers)

- **Visuals: deferred to you.** I'll create blockstate + minimal `cube_all` models + item model that point
  at texture paths `create_biotech:block/{giant_frog_portal, frog_stomach_wall, frog_esophagus}`. Until you
  drop PNGs there they render as the missing-texture placeholder — everything else works.
- **Recipe: none.** Obtainable from the creative tab only.

## Plan

### A. Registration (Java) — edit 6 existing registry files

1. **CBDataComponents** — add `FROG_STOMACH_SPACE` : `DataComponentType<Long>` (persistent `Codec.LONG`,
   networked `ByteBufCodecs.VAR_LONG`).
2. **CBBlocks** — add 3 blocks:
   - `GIANT_FROG_PORTAL` → `new GiantFrogPortalBlock(...)`, organic props (slime sound, green map color, `noOcclusion`).
   - `FROG_STOMACH_WALL` → plain `new Block(...)`, **indestructible** (`strength(-1f, 3.6e6f)`, `noLootTable`), pink map color.
   - `FROG_ESOPHAGUS` → `new FrogEsophagusBlock(...)`, **indestructible**, `noLootTable`, `noOcclusion`.
3. **CBBlockEntityTypes** — add `GIANT_FROG_PORTAL` (of `GiantFrogPortalBlockEntity`) and `FROG_ESOPHAGUS`
   (of `FrogEsophagusBlockEntity`).
4. **CBItems** — add `GIANT_FROG_PORTAL` = `new BlockItem(...GIANT_FROG_PORTAL..., new Item.Properties().stacksTo(1))`.
   (Wall + Esophagus are not obtainable → no items.)
5. **CBConfigs.Server** — add nested `FrogStomach` with `boxSize = defineInRange("boxSize", 48, 5, 256)`
   (wire the field + constructor call, `push/pop("frogStomach")`).
6. **CBCreativeModeTabs.MAIN** — add `output.accept(CBItems.GIANT_FROG_PORTAL.get())` near the other teleport items.

All `CB*` registries are already `register()`-ed in `CreateBiotech` — no new wiring needed.

### B. Resources — add ~7 JSON files (textures deferred to you)

7. **blockstates/** (3): `giant_frog_portal.json`, `frog_stomach_wall.json`, `frog_esophagus.json` → single
   variant pointing at the block model.
8. **models/block/** (3): `cube_all` parent → `create_biotech:block/<name>` (placeholder texture path).
9. **models/item/** (1): `giant_frog_portal.json` → parent = its block model.
10. **loot_table/blocks/** (1): `giant_frog_portal.json` self-drop (the block's `getDrops` override copies the
    space-id component at runtime; wall + esophagus use `noLootTable()`, no JSON).
11. **lang/** `en_us.json` + `zh_cn.json`: names for all 3 blocks
    (巨型青蛙传送门 / 青蛙胃袋壁 / 青蛙食道) + a short tooltip on the portal.

Dimension + dimension_type JSON already exist and load automatically at server start; `server.getLevel(FROG_STOMACH)` resolves from them. No Java-side dimension registration required.

### C. Verify

12. Run `./gradlew compileJava` in the worktree until clean. This is **not** a mixin change, so per project
    rules I will **not** run `quickPlayClient`/`test.py` unless you ask.

## Scope / boundaries

- I work in the existing `feat/frog-portal` worktree and **will not commit** unless you ask.
- No new textures/PNGs (per your choice), no recipe, no gameplay-logic changes to the 8 existing classes
  (only registration + resources). If compilation reveals a genuine bug in the existing logic I'll flag it
  before changing behavior.

## Deliverable checklist

- [ ] 6 registry files edited
- [ ] 3 blockstates, 3 block models, 1 item model, 1 loot table
- [ ] en_us + zh_cn lang entries
- [ ] `./gradlew compileJava` clean
