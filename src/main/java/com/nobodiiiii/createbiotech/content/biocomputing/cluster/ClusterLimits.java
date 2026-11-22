package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.Objects;

/** Controller-owned storage ceilings, never the sum of member computing resources. */
public record ClusterLimits(int configurationEntries, MailboxLimits mailbox, long stateUnits, ArchiveLimits archive) {
    public ClusterLimits {
        if (configurationEntries < 1 || stateUnits < 0) throw new IllegalArgumentException("Invalid cluster limits");
        Objects.requireNonNull(mailbox); Objects.requireNonNull(archive);
    }
}
