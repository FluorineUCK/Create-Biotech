package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBranches;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import java.util.*;

/** Desugars Scratch scripts attached to an edge hat; the retained monitor stays on the existing ISA path. */
final class ScratchEdgeHats {
    private ScratchEdgeHats() {}

    static boolean attached(FlowProgram.Step step) {
        return step instanceof FlowProgram.Watch watch && watch.handler().isEmpty();
    }

    static List<FlowProgram.Step> link(List<FlowProgram.Step> scripts) {
        if (scripts.stream().noneMatch(ScratchEdgeHats::attached)) return scripts;
        var structure = FlowBranches.structure(scripts);
        var ids = new HashSet<String>();
        var keys = new HashSet<ModuleDefinition.Key>();
        for (var step : scripts) {
            ids.add(step.sourceId());
            if (step instanceof FlowProgram.Procedure procedure) keys.add(procedure.key());
            if (step instanceof FlowProgram.Watch watch) watch.handler().ifPresent(keys::add);
        }
        var lowered = new ArrayList<FlowProgram.Step>();
        for (int i = 0; i < scripts.size(); i++) {
            var step = scripts.get(i);
            lowered.add(step);
            if (!(step instanceof FlowProgram.Procedure monitor) || i + 1 >= scripts.size()
                    || !(scripts.get(i + 1) instanceof FlowProgram.Watch watch) || !attached(watch)) continue;
            int end = structure.end(i);
            String base = "edge_body_" + monitor.key().id();
            ModuleDefinition.Key handler = new ModuleDefinition.Key(base, monitor.key().revision());
            for (int suffix = 2; !keys.add(handler); suffix++) handler = new ModuleDefinition.Key(base + "_" + suffix, monitor.key().revision());
            lowered.add(new FlowProgram.Watch(watch.sourceId(), handler, watch.condition()));
            lowered.add(new FlowProgram.EndProcedure(unique(ids, watch.sourceId() + "_monitor_end")));
            lowered.add(new FlowProgram.Procedure(unique(ids, watch.sourceId() + "_handler"), handler, Map.of(), Map.of(), Map.of()));
            lowered.addAll(scripts.subList(i + 2, end + 1));
            i = end;
        }
        return lowered;
    }

    private static String unique(Set<String> used, String base) {
        String id = base;
        for (int suffix = 2; !used.add(id); suffix++) id = base + "_" + suffix;
        return id;
    }
}
