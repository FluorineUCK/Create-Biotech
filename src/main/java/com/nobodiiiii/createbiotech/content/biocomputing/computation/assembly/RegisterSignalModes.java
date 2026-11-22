package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException;
import java.util.*;

/** Minimum instruction distance to each drive/input mode at each real PC, including loop re-entry.
 * Conservative path facts, not re-execution of hardware or a proof of historical signal values. */
final class RegisterSignalModes {
    private final Map<String, long[][]> distance = new TreeMap<>();
    RegisterSignalModes(List<RegisterProgram.Code> code, List<Set<Integer>> edges) {
        for (var entry : code) if (entry.instruction() instanceof RegisterDevices.Instruction d
                && d.kind() != RegisterDevices.Kind.XBUS_STATUS) distance.putIfAbsent(d.pin(), new long[code.size() + 1][2]);
        distance.forEach((pin, states) -> {
            for (var modes : states) Arrays.fill(modes, Long.MAX_VALUE);
            states[0][0] = 0;
            var queue = new ArrayDeque<Integer>(); queue.add(0);
            while (!queue.isEmpty()) {
                int encoded = queue.removeFirst(), pc = encoded / 2, mode = encoded % 2;
                if (pc == code.size()) continue;
                int after = mode;
                if (code.get(pc).instruction() instanceof RegisterDevices.Instruction d && d.pin().equals(pin)) {
                    if (d.kind() == RegisterDevices.Kind.SIO_READ) after = 0;
                    if (d.kind() == RegisterDevices.Kind.SIO_WRITE) after = 1;
                }
                for (int next : edges.get(pc)) if (states[pc][mode] + RegisterProgram.work(code.get(pc).instruction()) < states[next][after]) {
                    states[next][after] = states[pc][mode] + RegisterProgram.work(code.get(pc).instruction());
                    queue.addLast(next * 2 + after);
                }
            }
        });
    }
    void validate(int pc, long completed, Map<String, Integer> outputs) {
        if (!distance.keySet().containsAll(outputs.keySet()) || outputs.values().stream().anyMatch(v -> v < 0 || v > 15)) invalid();
        distance.forEach((pin, states) -> { if (states[pc][outputs.containsKey(pin) ? 1 : 0] > completed) invalid(); });
    }
    private static void invalid() {
        throw new ComputationException(ComputationException.Code.INVALID_CHECKPOINT, "assembly", "Signal modes precede or contradict their instruction path");
    }
}
