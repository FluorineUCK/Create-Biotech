package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;

/** Explicit graph-port to caller-local references; omitted returns are deliberately discarded. */
public record FlowCallBindings(Map<String, String> arguments, Map<String, String> returns) {
    public FlowCallBindings {
        arguments = names(arguments); returns = names(returns);
        if (new HashSet<>(returns.values()).size() != returns.size())
            throw FlowProgram.error(ComputationException.Code.INVALID_GRAPH, "flow", "Two returns target the same local");
    }
    private static Map<String, String> names(Map<String, String> values) {
        var result = DataValue.immutableMap(values);
        result.forEach((port, local) -> { FlowProgram.name(port); FlowProgram.name(local); });
        return result;
    }
    void validate(Map<String, DataType> inputs, Map<String, DataType> outputs, Map<String, DataType> locals, String source) {
        if (!arguments.keySet().equals(inputs.keySet()))
            throw FlowProgram.error(ComputationException.Code.MISSING_REFERENCE, source, "Call bindings differ from graph ports");
        validateReturns(outputs, locals, source);
    }
    void validateReturns(Map<String, DataType> outputs, Map<String, DataType> locals, String source) {
        if (!outputs.keySet().containsAll(returns.keySet()))
            throw FlowProgram.error(ComputationException.Code.MISSING_REFERENCE, source, "Call bindings differ from graph ports");
        returns.forEach((port, local) -> {
            if (locals.containsKey(local) && !locals.get(local).compatibleWith(outputs.get(port)))
                throw FlowProgram.error(ComputationException.Code.TYPE_MISMATCH, source, "Return type differs from local: " + local);
        });
    }
    static Map<String, String> identity(Set<String> names) {
        var result = new LinkedHashMap<String, String>(); names.forEach(name -> result.put(name, name)); return result;
    }
}
