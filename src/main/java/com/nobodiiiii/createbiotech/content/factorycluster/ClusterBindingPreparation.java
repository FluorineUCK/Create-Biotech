package com.nobodiiiii.createbiotech.content.factorycluster;

public enum ClusterBindingPreparation {
	READY,
	IDENTITY,
	REVISION,
	CAPACITY,
	ACTIVE;

	public boolean ready() {
		return this == READY;
	}
}
