package com.nobodiiiii.createbiotech.content.biocomputing.library.service;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.Objects;

/** Service buffers and configuration, independent of physical media capacity and Computer RAM. */
public record LibraryServiceLimits(long stateUnits, int grants, MailboxLimits mailbox, ArchiveLimits archive) {
    public LibraryServiceLimits {
        if (stateUnits < 1 || grants < 1) throw new IllegalArgumentException("Positive service limits required");
        Objects.requireNonNull(mailbox); Objects.requireNonNull(archive);
    }
}

