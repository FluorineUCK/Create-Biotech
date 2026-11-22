package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import java.util.Objects;

/** Pure, revision-pinned reusable graph, not a recipe PatternRecord or a world capability. */
public record ModuleDefinition(Key key, ComputationGraph graph) {
	public ModuleDefinition { Objects.requireNonNull(key); Objects.requireNonNull(graph); }
	public record Key(String id, long revision) {
		public Key {
			ComputationGraph.requireName(id);
			if (revision < 1)
				throw new IllegalArgumentException("Module revisions start at one");
		}
	}
}
