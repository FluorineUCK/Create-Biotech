package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;

public record ComputerStructureNode(UUID computerId, SpaceAddress address,
	@Nullable ComputerProfile profile) {
	public ComputerStructureNode {
		Objects.requireNonNull(computerId, "computerId");
		Objects.requireNonNull(address, "address");
	}
}
