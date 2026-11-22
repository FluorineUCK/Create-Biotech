/**
 * Bounded, explicit read snapshots for physical reader targets.
 *
 * <p>Supported: public block properties and update tags; the exposed face's NeoForge
 * item, fluid-storage and energy capabilities; serializable item/fluid components
 * encoded with Mojang codecs and registry ops. Returned values contain no world,
 * stack, capability, tag or executable references. Nothing polls or writes back.
 *
 * <p>Create 6.0.10: speed, theoretical speed and overstressed use public kinetic
 * getters. Network stress/capacity are the last public update-tag aggregates, not
 * recomputed values or per-machine contributions. Missing network data stays
 * unsupported. No kinetic network is created, enumerated or refreshed.
 *
 * <p>Logistics exposes only the target's linked behaviour or a packager's loaded,
 * correctly attached stock links, with Create's network interaction permission.
 * Inventory summaries remain unsupported: Create's summary getter submits arrivals
 * and changes promise/cache state. No global network index is copied or queried for
 * stock. Fluid transport remains unsupported as a separate adapter; pipes are not
 * treated as a tank or as a full-network fluid inventory. Public pump/pipe block
 * properties are still available through the generic state field.
 *
 * <p>Provider failure, unsupported, access denial, unloaded targets and snapshot
 * ceilings remain distinct errors. A computer behaviour is rejected before update
 * tags or capabilities can reveal its RAM. The depth ceiling is a stack-safety
 * invariant; adjustable entry, text and logical-storage ceilings come from ReadLimits.
 */
package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;
