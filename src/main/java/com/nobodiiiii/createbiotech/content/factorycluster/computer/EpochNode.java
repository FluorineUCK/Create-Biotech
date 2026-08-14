package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;

public record EpochNode(UUID computerId, SpaceAddress address, ComputerProfile profile) {
	public EpochNode {
		Objects.requireNonNull(computerId, "computerId");
		Objects.requireNonNull(address, "address");
		Objects.requireNonNull(profile, "profile");
	}
}
