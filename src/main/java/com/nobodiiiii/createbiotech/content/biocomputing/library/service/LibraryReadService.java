package com.nobodiiiii.createbiotech.content.biocomputing.library.service;

import static com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceData.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException;
import java.util.*;

/** Public record protocol. The source exposes only copied values and has deliberately no mutation methods. */
public final class LibraryReadService {
    private LibraryReadService() {}
    public interface Source { Directory snapshot(); }
    public interface Directory {
        String status(String volume);
        Map<String, LibraryEntry> read(String volume);
        DataValue.RecordValue stat(String volume, String entry);
    }
    public static DataValue.RecordValue failure(String code) { return obj("code", code, "data", DataValue.NullValue.INSTANCE); }
    private static DataValue.RecordValue success(DataValue data) { return obj("code", "ok", "data", data); }
    public static DataValue.RecordValue execute(LibraryServiceConfiguration configuration, DataValue.RecordValue request, Source source) {
        if (!configuration.enabled()) return failure("closed");
        String operation, volume = null, entry = null;
        try {
            operation = text(request.fields().get("op"));
            switch (operation) {
                case "volumes" -> fields(request, "op");
                case "list" -> { fields(request, "op", "volume"); volume = text(request.field("volume")); }
                case "read", "stat" -> {
                    fields(request, "op", "volume", "entry"); volume = text(request.field("volume")); entry = text(request.field("entry"));
                    if (entry.isBlank() || entry.length() > LibraryBookVolumes.MAX_NAME) return failure("invalid_request");
                }
                default -> { return failure("unsupported"); }
            }
            if (volume != null) LibraryBookVolumes.requireName(volume);
        } catch (IllegalArgumentException invalid) { return failure("invalid_request"); }
        // Authorization precedes all physical inspection, including volume existence tests.
        if (volume != null && configuration.access(volume) == LibraryServiceConfiguration.Access.CLOSED) return failure("denied");
        try {
            var directory = source.snapshot();
            if (operation.equals("volumes")) {
                var rows = new ArrayList<DataValue>();
                configuration.grants().forEach((name, access) -> rows.add(obj("name", name, "access", access.name(), "status", directory.status(name))));
                return success(new DataValue.ListValue(rows));
            }
            String status = directory.status(volume);
            if (!status.equals("ok")) return failure(status);
            if (operation.equals("stat")) return success(directory.stat(volume, entry));
            var entries = directory.read(volume);
            if (operation.equals("list")) {
                var rows = new ArrayList<DataValue>();
                entries.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> rows.add(obj("name", e.getKey(), "kind", e.getValue().kind().name())));
                return success(new DataValue.ListValue(rows));
            }
            var value = entries.get(entry);
            return value == null ? failure("missing_entry") : success(obj("kind", value.kind().name(), "value", value.value()));
        } catch (LibraryBookAccess.Failure failure) { return failure(failure.code()); }
        catch (ArchiveException invalid) { return failure(invalid.code() == ArchiveException.Code.LIMIT_EXCEEDED ? "archive_limit" : "invalid_contents"); }
        catch (RuntimeException provider) { return failure("provider_failure"); }
    }
}
