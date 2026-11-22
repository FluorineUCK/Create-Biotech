package com.nobodiiiii.createbiotech.content.biocomputing.library.service;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageEnvelope;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException;

/** Dedicated storage capability; never arbitrary world-object access. Preparation has no side effects. */
public final class LibraryWriteService {
    private LibraryWriteService() {}
    public interface Source extends LibraryReadService.Source { @Override Directory snapshot(); }
    public interface Directory extends LibraryReadService.Directory {
        LibraryBookAccess.PreparedWrite prepare(MessageEnvelope request);
        void release(MessageEnvelope request);
    }
    public static boolean isMutation(DataValue.RecordValue payload) {
        return payload.fields().get("op") instanceof DataValue.TextValue op && (op.value().equals("put") || op.value().equals("delete"));
    }
    public static LibraryBookAccess.PreparedWrite prepare(LibraryServiceConfiguration config, MessageEnvelope request, Source source) {
        if (!config.enabled()) throw new LibraryBookAccess.Failure("closed");
        return prepareAdmitted(config, request, source);
    }
    /** Only for sealing previously admitted work. Closing a port cannot erase a competing write. */
    static LibraryBookAccess.PreparedWrite prepareAdmitted(LibraryServiceConfiguration config, MessageEnvelope request, Source source) {
        LibraryMutation mutation;
        try { mutation = LibraryMutation.decode(request.payload()); }
        catch (IllegalArgumentException invalid) { throw new LibraryBookAccess.Failure("invalid_request"); }
        switch (config.access(mutation.volume())) {
            case CLOSED -> throw new LibraryBookAccess.Failure("denied");
            case READ_ONLY -> throw new LibraryBookAccess.Failure("readonly");
            case READ_WRITE -> {}
        }
        var directory = source.snapshot();
        String status = directory.status(mutation.volume());
        if (!status.equals("ok")) throw new LibraryBookAccess.Failure(status);
        return directory.prepare(request);
    }
    public static String failure(RuntimeException error) {
        if (error instanceof LibraryBookAccess.Failure failure) return failure.code();
        if (error instanceof ArchiveException invalid)
            return invalid.code() == ArchiveException.Code.LIMIT_EXCEEDED ? "archive_limit" : "invalid_contents";
        return "provider_failure";
    }
}
