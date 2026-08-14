package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

public record ComputerNodeView(UUID computerId, @Nullable ComputerProfile profile,
	boolean online, boolean pending, boolean coordinator) {
	public ComputerNodeView {
		Objects.requireNonNull(computerId, "computerId");
	}
}
