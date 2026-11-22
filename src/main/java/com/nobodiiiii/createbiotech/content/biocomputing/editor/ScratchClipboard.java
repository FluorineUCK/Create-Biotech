package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import java.util.List;
import java.util.Map;

/** One local draft fragment. It never transfers installed state or public procedure definitions. */
sealed interface ScratchClipboard {
    record Statements(List<FlowProgram.Step> steps, Map<String, ComputationGraph> graphs,
                      Map<String, Map<String, DataValue>> shadows) implements ScratchClipboard {
        public Statements {
            steps = List.copyOf(steps);
            graphs = Map.copyOf(graphs);
            shadows = DataValue.immutableMap(shadows);
        }
    }

    record Value(ScratchValueLayout.Reporter reporter) implements ScratchClipboard {}

    /** A cut may be restored as the same script; all other pastes produce an independent handler. */
    record EventScript(Statements script, boolean cut) implements ScratchClipboard {
        public EventScript {
            if (script.steps().isEmpty() || !FlowDraft.clipboardEventHat(script.steps().getFirst()))
                throw new IllegalArgumentException("Expected a supported visual event hat");
        }
    }
}
