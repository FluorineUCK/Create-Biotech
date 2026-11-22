package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException;
import java.util.*;
import java.util.function.*;

/** Pattern-style latest-intent fence, without library generations or book editing authority. */
public final class ProgramEditSession {
    public record Result(String code, long revision, String detail) {}
    private final UUID nonce;
    private long lastIntent;
    public ProgramEditSession(UUID nonce) { this.nonce = Objects.requireNonNull(nonce); }

    public Optional<Result> submit(UUID nonce, long intent, long expectedRevision, LongSupplier revision,
        Supplier<ComputerRuntime.InstallResult> install) {
        if (!this.nonce.equals(nonce) || intent <= lastIntent) return Optional.empty();
        lastIntent = intent;
        if (expectedRevision != revision.getAsLong()) return Optional.of(new Result("stale", revision.getAsLong(), ""));
        try {
            var result = install.get();
            return Optional.of(new Result(result == ComputerRuntime.InstallResult.APPLIED ? "applied" : "queued", revision.getAsLong(), ""));
        } catch (ComputationException error) {
            return Optional.of(new Result("invalid", revision.getAsLong(), error.code() + ": " + error.getMessage()));
        } catch (ComputerBlockHost.HostException error) {
            return Optional.of(new Result("unavailable", revision.getAsLong(), error.code() + ": " + error.getMessage()));
        } catch (IllegalArgumentException error) {
            return Optional.of(new Result("invalid", revision.getAsLong(), String.valueOf(error.getMessage())));
        }
    }
}
