package com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateLogisticsGateway;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData;
import net.minecraft.core.HolderLookup;

/** One production retry projection; never a Computer's RAM or a shared execution resource. */
public final class ProductionSafeRetryStore {
    public static final String JOURNAL_PREFIX = "safe_create_retry/";
    private final FactoryOrchestrationSavedData journal;
    private final HolderLookup.Provider registries;
    private final Map<UUID, SafeCreateRetryProof> retries = new LinkedHashMap<>();

    public ProductionSafeRetryStore(FactoryOrchestrationSavedData journal, HolderLookup.Provider registries) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.registries = Objects.requireNonNull(registries, "registries");
    }
    /** Called at the original host bootstrap point; keeps its per-entry recovery semantics. */
    public void recover() {
        journal.readPrefix(JOURNAL_PREFIX, SafeCreateRetryProof.class, registries).forEach((key, retry) -> {
            if (!key.equals(JOURNAL_PREFIX + retry.intent().runId()))
                throw new IllegalStateException("safe Create retry journal key differs from Run");
            CreateLogisticsGateway.PersistedCommit persisted = journal.inputCommitStore(registries)
                .load(retry.intent().intentId()).orElse(null);
            if (persisted == null)
                throw new IllegalStateException("safe Create retry has no gateway artifact");
            if (persisted.provesMaterialCommit() || !persisted.allowsRetryWithoutMaterial()) {
                journal.remove(key);
                return;
            }
            SafeCreateRetryProof exact = SafeCreateRetryProof.from(retry.intent(), persisted).orElseThrow(
                () -> new IllegalStateException("safe Create retry differs from gateway artifact"));
            if (!exact.sameAuthority(retry))
                throw new IllegalStateException("conflicting safe Create retry authority");
            if (exact.rejection() != retry.rejection()) journal.write(key, exact, registries);
            installLatest(exact);
        });
    }

    /** Persist the re-derived proof before publishing it or allowing compensation to resume. */
    public void persistAndInstall(ProductionEffect.CreateDispatch intent,
        CreateLogisticsGateway.PersistedCommit persisted, SafeCreateRetryProof exact) {
        requireCompatible(exact);
        SafeCreateRetryProof durable = persist(journal, intent, persisted, registries).orElseThrow(
            () -> new IllegalStateException("safe Create retry proof changed"));
        if (!durable.sameAuthority(exact) || durable.rejection() != exact.rejection())
            throw new IllegalStateException("safe Create retry proof changed");
        installLatest(durable);
    }

    /** Journal-only trusted application operation; this does not install or grant authorization. */
    public static Optional<SafeCreateRetryProof> persist(FactoryOrchestrationSavedData journal,
        ProductionEffect.CreateDispatch intent, CreateLogisticsGateway.PersistedCommit persisted,
        HolderLookup.Provider registries) {
        FactoryOrchestrationSavedData exactJournal = Objects.requireNonNull(journal, "journal");
        HolderLookup.Provider exactRegistries = Objects.requireNonNull(registries, "registries");
        Optional<SafeCreateRetryProof> retry = SafeCreateRetryProof.from(intent, persisted);
        retry.ifPresent(exact -> exactJournal.write(JOURNAL_PREFIX + intent.runId(), exact, exactRegistries));
        return retry;
    }

    /** One exact authority per Run; a newer safe rejection replaces only the diagnosis. */
    public SafeCreateRetryProof installLatest(SafeCreateRetryProof latest) {
        Objects.requireNonNull(latest, "latest");
        requireCompatible(latest);
        retries.put(latest.intent().runId(), latest);
        return latest;
    }

    private void requireCompatible(SafeCreateRetryProof latest) {
        Objects.requireNonNull(latest, "latest");
        SafeCreateRetryProof prior = retries.get(latest.intent().runId());
        if (prior != null && !prior.sameAuthority(latest))
            throw new IllegalStateException("conflicting safe Create retry authority");
    }
    public SafeCreateRetryProof get(UUID runId) { return retries.get(runId); }
    public boolean containsKey(UUID runId) { return retries.containsKey(runId); }
    public void remove(UUID runId) { retries.remove(runId); }
    public boolean remove(UUID runId, SafeCreateRetryProof expected) { return retries.remove(runId, expected); }
    public int size() { return retries.size(); }
}
