package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import java.util.*;

/** Additive declaration constraints, not privileges or an alternative execution model. */
public record FlowBlockRules(Set<String> forbidInside, Set<String> forbidContexts) {
    public static final FlowBlockRules NONE = new FlowBlockRules(Set.of(), Set.of());
    public FlowBlockRules {
        forbidInside = Set.copyOf(forbidInside); forbidContexts = Set.copyOf(forbidContexts);
        forbidInside.forEach(FlowProgram::name); forbidContexts.forEach(FlowProgram::name);
    }
}
