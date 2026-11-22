package com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu;

import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHardwareView;
import java.util.*;
import net.minecraft.core.BlockPos;

/** Frozen public controller view. It contains no Computer program, memory or queued message body. */
public record ClusterControlView(ClusterConfiguration configuration, List<Candidate> candidates,
    String diagnostic, int queued, int pending, Map<String, String> wireDiagnostics, Map<String, String> publicPinDiagnostics) {
    public ClusterControlView(ClusterConfiguration configuration, List<Candidate> candidates, String diagnostic, int queued, int pending, Map<String, String> wireDiagnostics) {
        this(configuration, candidates, diagnostic, queued, pending, wireDiagnostics, Map.of());
    }
    public ClusterControlView(ClusterConfiguration configuration, List<Candidate> candidates, String diagnostic, int queued, int pending) {
        this(configuration, candidates, diagnostic, queued, pending, Map.of());
    }
    public ClusterControlView {
        Objects.requireNonNull(configuration); candidates = List.copyOf(candidates);
        Objects.requireNonNull(diagnostic);
        wireDiagnostics = Map.copyOf(wireDiagnostics);
        publicPinDiagnostics = Map.copyOf(publicPinDiagnostics);
        if (!configuration.wireNames().containsAll(wireDiagnostics.keySet())) throw new IllegalArgumentException("unknown wire diagnostic");
        if (!configuration.publicPins().keySet().containsAll(publicPinDiagnostics.keySet())) throw new IllegalArgumentException("unknown public pin diagnostic");
        if (queued < 0 || pending < 0) throw new IllegalArgumentException("negative cluster queue count");
    }
    public record Candidate(BlockPos position, Optional<UUID> identity, List<String> endpoints,
        boolean member, boolean selectable, boolean editable, String diagnostic, Optional<ComputerHardwareView> hardware) {
        public Candidate(BlockPos position, Optional<UUID> identity, List<String> endpoints,
            boolean member, boolean selectable, boolean editable, String diagnostic) {
            this(position, identity, endpoints, member, selectable, editable, diagnostic, Optional.empty());
        }
        public Candidate(BlockPos position, Optional<UUID> identity, List<String> endpoints,
            boolean member, boolean selectable, String diagnostic) {
            this(position, identity, endpoints, member, selectable, member && (selectable || diagnostic.equals("no_program")), diagnostic);
        }
        public Candidate {
            position = Objects.requireNonNull(position).immutable(); identity = Objects.requireNonNull(identity);
            endpoints = List.copyOf(endpoints); Objects.requireNonNull(diagnostic); Objects.requireNonNull(hardware);
            if (selectable && !member) throw new IllegalArgumentException("nonmember cannot be configured");
            if (editable && !member) throw new IllegalArgumentException("nonmember cannot be edited");
        }
    }
}
