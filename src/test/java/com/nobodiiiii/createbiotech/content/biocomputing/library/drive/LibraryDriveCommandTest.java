package com.nobodiiiii.createbiotech.content.biocomputing.library.drive;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LibraryDriveCommandTest {
    @Test void commandEchoesOpaqueCorrelationWithoutTreatingItAsAuthorization() {
        var target = new LibraryTrackAddress(2, 3, 4).value();
        var request = new DataValue.TextValue(UUID.randomUUID() + ":17");
        var frame = new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("library_command"), "target", target,
            "request", request, "operation", new DataValue.TextValue("status"), "sequence", new DataValue.IntegerValue(0), "data", DataValue.NullValue.INSTANCE));
        var command = LibraryDriveCommand.decode(frame);
        assertEquals(request, command.reply(0, "idle", DataValue.NullValue.INSTANCE).field("request"));
        for (DataValue invalid : List.of(DataValue.NullValue.INSTANCE, new DataValue.TextValue(""), new DataValue.IntegerValue(-1), new DataValue.BooleanValue(true))) {
            var fields = new LinkedHashMap<>(frame.fields()); fields.put("request", invalid);
            assertThrows(LibraryBookAccess.Failure.class, () -> LibraryDriveCommand.decode(new DataValue.RecordValue(fields)));
        }
    }
}
