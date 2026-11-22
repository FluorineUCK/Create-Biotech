package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;

import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

public record ComputerNodeView(UUID computerId, @Nullable ProductionResourceProfile profile,
	boolean online, boolean pending, boolean coordinator) {
	public ComputerNodeView {
		Objects.requireNonNull(computerId, "computerId");
	}
}
