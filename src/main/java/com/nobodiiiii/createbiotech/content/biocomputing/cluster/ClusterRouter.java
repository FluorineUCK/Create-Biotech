package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.nbt.*;

/** Thread-confined durable routing owner. No remote calls occur inside a state transition. */
public final class ClusterRouter {
    private final Runnable markChanged;
    private Tag durable;
    private ClusterState published;
    private ClusterLimits validated;
    private String diagnostic;

    public ClusterRouter(Runnable markChanged) { this.markChanged = Objects.requireNonNull(markChanged); }
    public void read(Tag tag) { durable = tag == null ? null : tag.copy(); published = null; validated = null; diagnostic = null; }
    public Tag write() { return durable == null ? null : durable.copy(); }
    public void unload() { published = null; validated = null; }
    public Optional<String> diagnostic() { return Optional.ofNullable(diagnostic); }
    public Optional<ClusterState> snapshot(ClusterLimits limits) { return prepare(limits) ? Optional.of(published) : Optional.empty(); }
    public String configure(ClusterConfiguration configuration, ClusterLimits limits) {
        Objects.requireNonNull(configuration);
        if (!prepare(limits)) return "unavailable";
        if (published.configuration().equals(configuration)) return "configured";
        if (published.busy()) return "busy";
        String failure = publish(published.configure(configuration), limits);
        return failure == null ? "configured" : failure;
    }
    public AdmissionStatus receive(ClusterState.Side side, String port, MessageEnvelope message, ClusterLimits limits) {
        Objects.requireNonNull(message); var ingress = new ClusterState.Ingress(side, port);
        if (!prepare(limits)) return AdmissionStatus.UNAVAILABLE;
        var admitted = published.admit(ingress, message, limits);
        if (admitted.status() != AdmissionStatus.ACCEPTED) return admitted.status();
        String failure = publish(admitted.state(), limits);
        if (failure == null) return AdmissionStatus.ACCEPTED;
        return switch (failure) {
            case "memory_limit" -> AdmissionStatus.MEMORY_LIMIT;
            case "archive_limit" -> AdmissionStatus.ARCHIVE_LIMIT;
            case "queue_full" -> AdmissionStatus.QUEUE_FULL;
            case "source_limit" -> AdmissionStatus.SOURCE_LIMIT;
            default -> AdmissionStatus.UNAVAILABLE;
        };
    }
    public Optional<ClusterState.Outbound> outbound(ClusterLimits limits) { return prepare(limits) ? published.outbound() : Optional.empty(); }
    public boolean acknowledge(ClusterState.Outbound expected, ClusterLimits limits) {
        Objects.requireNonNull(expected);
        if (!prepare(limits)) return false;
        var candidate = published.acknowledge(expected);
        return candidate != published && publish(candidate, limits) == null;
    }
    private boolean prepare(ClusterLimits limits) {
        Objects.requireNonNull(limits);
        if (limits.equals(validated)) return published != null;
        validated = limits; published = null; diagnostic = null;
        try {
            if (durable != null && !(durable instanceof CompoundTag)) throw new IllegalArgumentException("Cluster archive must be a compound");
            var candidate = durable == null ? ClusterState.empty() : ClusterNbtCodec.decode((CompoundTag)durable, limits.archive());
            String failure = limitFailure(candidate, limits);
            if (failure != null) { diagnostic = failure; return false; }
            published = candidate; return true;
        } catch (IllegalArgumentException failure) { diagnostic = "invalid_save: " + failure.getMessage(); return false; }
    }
    private String publish(ClusterState candidate, ClusterLimits limits) {
        String failure = limitFailure(candidate, limits);
        if (failure != null) return failure;
        CompoundTag encoded;
        try { encoded = ClusterNbtCodec.encode(candidate, limits.archive()); }
        catch (ArchiveException invalid) { return invalid.code() == ArchiveException.Code.LIMIT_EXCEEDED ? "archive_limit" : "unavailable"; }
        published = candidate; durable = encoded; validated = limits; diagnostic = null; markChanged.run(); return null;
    }
    private static String limitFailure(ClusterState state, ClusterLimits limits) {
        if (state.configuration().members().size() > limits.configurationEntries()
            || state.configuration().ports().size() + (long)state.configuration().publicPins().size() > limits.configurationEntries()) return "configuration_limit";
        if (state.configuration().wires().size() + (long)state.configuration().signals().size() > limits.configurationEntries()
            || state.configuration().connections().size() > limits.configurationEntries()
            || state.configuration().members().keySet().stream().anyMatch(name -> state.configuration().outputNames(name).size()
                > Math.min(limits.configurationEntries(), ClusterAttachment.MAX_OUTPUTS))) return "configuration_limit";
        if (state.mailbox().queued().size() > limits.mailbox().messages() || state.pending().size() > limits.mailbox().messages()) return "queue_full";
        if (state.mailbox().receipts().size() > limits.mailbox().sources()) return "source_limit";
        return ClusterNbtCodec.value(state).storageUnits() > limits.stateUnits() ? "memory_limit" : null;
    }
}
