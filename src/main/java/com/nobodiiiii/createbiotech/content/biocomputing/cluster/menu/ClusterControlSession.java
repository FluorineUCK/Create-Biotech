package com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Menu-local nonce, intent and configuration revision fence. */
public final class ClusterControlSession {
    public enum Action {
        REFRESH, CONFIGURE, OPEN_COMPUTER, READ_WIRING, ADD_WIRING, REMOVE_WIRING, LIST_WIRING, READ_LOG, SET_WIRING_ENABLED;
        public boolean wiring() { return this == READ_WIRING || this == ADD_WIRING || this == REMOVE_WIRING || this == LIST_WIRING || this == SET_WIRING_ENABLED; }
        public boolean requiresValue() { return this != REFRESH && this != LIST_WIRING && this != READ_LOG; }
    }
    public record Result(String code, long revision) {}
    private final UUID nonce;
    private long lastIntent;

    public ClusterControlSession(UUID nonce) { this.nonce = Objects.requireNonNull(nonce); }

    public Optional<Result> apply(UUID nonce, long intent, long expectedRevision, Action action,
        LongSupplier revision, Supplier<String> operation) {
        Objects.requireNonNull(nonce); Objects.requireNonNull(action);
        Objects.requireNonNull(revision); Objects.requireNonNull(operation);
        if (intent < 0 || expectedRevision < 0) throw new IllegalArgumentException("negative cluster control sequence");
        if (!this.nonce.equals(nonce) || intent <= lastIntent) return Optional.empty();
        lastIntent = intent;
        long current = revision.getAsLong();
        if (action != Action.REFRESH && action != Action.READ_LOG && expectedRevision != current)
            return Optional.of(new Result("stale", current));
        String code = Objects.requireNonNull(operation.get());
        return Optional.of(new Result(code, revision.getAsLong()));
    }
}
