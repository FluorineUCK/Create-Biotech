package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.Objects;

import com.simibubi.create.content.logistics.BigItemStack;

public record PatternOutput(StackKey stack, int count) {
	public PatternOutput {
		stack = Objects.requireNonNull(stack, "stack");
		if (count <= 0 || count > BigItemStack.INF)
			throw new IllegalArgumentException("Pattern output count must be positive");
	}
}
