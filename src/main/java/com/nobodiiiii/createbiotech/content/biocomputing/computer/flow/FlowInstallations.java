package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.lang.ref.WeakReference;
import java.util.*;

/** Bounded process-local compiler memo, not saved state or shared Computer RAM.
 * Exact source (including pinned bodies/versions), task root and code allowance identify an image.
 * Memory admission is repeated for every use; runtime checkpoint validation remains at the owner. */
final class FlowInstallations {
    private FlowInstallations() {}
    // Host cache bound, not a gameplay limit. One bound across ALL sources, roots and hardware variants.
    private static final int RETAINED_IMAGES = 64;
    private static final Deque<Entry> IMAGES = new ArrayDeque<>();
    private record Entry(WeakReference<FlowProgram> source, long codeAllowance, Optional<ModuleDefinition.Key> root,
            FlowProgram.Installation image, long literalUnits) {}

    static synchronized FlowProgram.Installation compile(FlowProgram source, ResourceLimits limits,
            Optional<ModuleDefinition.Key> root) {
        Entry match = null;
        for (var it = IMAGES.iterator(); it.hasNext();) {
            var entry = it.next(); var retained = entry.source().get();
            if (retained == null) { it.remove(); continue; }
            if (entry.codeAllowance() == limits.programUnits() && entry.root().equals(root) && retained.equals(source)) {
                match = entry; break;
            }
        }
        if (match != null) {
            long memory = limits.memoryUnits() - match.literalUnits();
            if (memory < 0) throw FlowProgram.error(ComputationException.Code.MEMORY_LIMIT, "flow", "Input literals exceed memory capacity");
            var old = match.image();
            // REGISTER_V1 source checking uses an unbounded graph-code allowance; encoded ISA code
            // still uses the exact allowance in the cache key. No graph execution is introduced here.
            var expressions = old.expressions().withLimits(new ResourceLimits(Long.MAX_VALUE - source.steps().size(), memory, 1));
            IMAGES.remove(match); IMAGES.addLast(match);
            return new FlowProgram.Installation(expressions, old.locals(), old.branches(), old.signals(), old.assembly());
        }
        var image = source.compileRegister(limits, root);
        if (IMAGES.size() >= RETAINED_IMAGES) IMAGES.removeFirst();
        // The value holds compiler products and immutable source parts, never the FlowProgram key itself.
        IMAGES.addLast(new Entry(new WeakReference<>(source), limits.programUnits(), root, image, FlowProgram.inputConstantUnits(source.steps())));
        return image;
    }
}
