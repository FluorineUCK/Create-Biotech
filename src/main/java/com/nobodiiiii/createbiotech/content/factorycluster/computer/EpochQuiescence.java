package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import java.util.UUID;

public interface EpochQuiescence {
	boolean allRootsStopped();
	boolean nodeIdle(UUID computerId);
	boolean nodeRootFree(UUID computerId);
	boolean nodeMailboxEmpty(UUID computerId);
}
