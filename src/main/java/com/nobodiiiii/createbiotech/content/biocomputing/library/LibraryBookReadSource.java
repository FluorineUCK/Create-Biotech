package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryWriteService;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryMutation;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageEnvelope;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;

/** Event-refreshed name directory; every read or prepared storage operation checks the complete physical book. */
public final class LibraryBookReadSource implements LibraryWriteService.Source {
    private final LibraryBookAccess access; private final Supplier<Set<BlockPos>> changes;
    private boolean initialized;
    public LibraryBookReadSource(LibraryBookAccess access, Supplier<Set<BlockPos>> changes) {
        this.access = Objects.requireNonNull(access); this.changes = Objects.requireNonNull(changes);
    }
    @Override public LibraryWriteService.Directory snapshot() {
        var dirty = changes.get();
        boolean incremental = initialized; initialized = false;
        var books = incremental ? access.listChanged(dirty) : access.list();
        var named = new HashMap<String, LibraryBookAccess.Handle>(); var duplicates = new HashSet<String>();
        for (var book : books) access.snapshotVolumeName(book).ifPresent(name -> {
            if (named.putIfAbsent(name, book) != null) duplicates.add(name);
        });
        initialized = true;
        return new LibraryWriteService.Directory() {
            public String status(String name) { return duplicates.contains(name) ? "duplicate_volume" : named.containsKey(name) ? "ok" : "missing_volume"; }
            public Map<String, LibraryEntry> read(String name) {
                String status = status(name); if (!status.equals("ok")) throw new LibraryBookAccess.Failure(status);
                return access.read(named.get(name));
            }
            public DataValue.RecordValue stat(String name, String entry) {
                String status = status(name); if (!status.equals("ok")) throw new LibraryBookAccess.Failure(status);
                return access.stat(named.get(name), entry);
            }
            private LibraryBookAccess.Handle handle(MessageEnvelope request) {
                String volume = LibraryMutation.decode(request.payload()).volume();
                String status = status(volume); if (!status.equals("ok")) throw new LibraryBookAccess.Failure(status);
                return named.get(volume);
            }
            public LibraryBookAccess.PreparedWrite prepare(MessageEnvelope request) { return access.prepareWrite(handle(request), request); }
            public void release(MessageEnvelope request) { access.releaseReceipt(handle(request), request); }
        };
    }
}
