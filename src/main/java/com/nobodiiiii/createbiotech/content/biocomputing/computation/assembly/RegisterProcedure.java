package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import java.util.*;

/** One revision-pinned body in the installed image; register addresses are private to its activation. */
public record RegisterProcedure(int entry, int end, String name, long revision,
        Map<String, Integer> inputs, Map<String, Integer> locals, Map<String, Integer> outputs, Optional<AssemblySource> assembly) {
    public RegisterProcedure(int entry,int end,String name,long revision,Map<String,Integer> inputs,Map<String,Integer> locals,Map<String,Integer> outputs) {
        this(entry,end,name,revision,inputs,locals,outputs,Optional.empty());
    }
    public RegisterProcedure {
        Objects.requireNonNull(name); Objects.requireNonNull(assembly);
        if (entry < 0 || end < entry || name.isBlank() || revision < 1) throw new IllegalArgumentException("Invalid procedure");
        inputs = Collections.unmodifiableMap(new TreeMap<>(inputs));
        locals = Collections.unmodifiableMap(new TreeMap<>(locals));
        outputs = Collections.unmodifiableMap(new TreeMap<>(outputs));
    }
}
