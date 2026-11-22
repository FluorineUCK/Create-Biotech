package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaLibraryTransfer;
import com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaLibraryFile;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec;
import java.util.*;
import org.junit.jupiter.api.*;

class SpeakerMediaTransferTest {
    private static final LibraryTrackAddress DEVICE = new LibraryTrackAddress(80, 10, -2);
    private static final LibraryTrackAddress TRACK = LibraryPositionedWriteSourceTest.TRACK;
    private static final int CHUNK = 32;
    private static final LibraryDriveLimits LIMITS = LibraryWriteHeadTest.LIMITS;
    private static final SerialRecordCodec.Limits FRAME = new SerialRecordCodec.Limits(4096, 65536, LIMITS.archive());

    @BeforeAll static void bootstrap() throws Exception { LibraryBookMediaTest.bootstrap(); }

    @Test void drivesTheActualPositionedBookAndNeedsCorrelatedWrittenReceiptBeforeSuccess() {
        var world = new Fixture("theme.mid");
        int chunks = 0;
        boolean sawCommit = false;
        for (int step = 0; !world.transfer.finished() && step < 180; step++) {
            var frame = world.next();
            var command = LibraryDriveCommand.decode(frame);
            switch (command.operation()) {
                case "write_chunk" -> chunks++;
                case "write_commit" -> {
                    sawCommit = true;
                    assertEquals(0, world.medium.writes);
                    var real = world.apply(command);
                    assertEquals(new DataValue.TextValue("accepted"), real.field("code"));
                    assertTrue(world.transfer.reply(real));
                    assertFalse(world.transfer.finished());
                    assertEquals(0, world.medium.writes);
                    continue;
                }
                default -> {}
            }
            var real = world.apply(command);
            var obsolete = new LinkedHashMap<>(real.fields());
            obsolete.put("request", new DataValue.TextValue("different request"));
            assertFalse(world.transfer.reply(new DataValue.RecordValue(obsolete)));
            assertTrue(world.transfer.reply(real));
            if (sawCommit) assertNotEquals("media_stored", world.transfer.code().orElse(""));
        }
        assertTrue(sawCommit);
        assertTrue(chunks > 1);
        assertTrue(world.transfer.finished());
        assertEquals("written", world.transfer.code().orElseThrow());
        assertEquals(2, world.medium.writes, "One data revision and one physical receipt retirement");
        var stored = world.owner.media.read(world.medium.book).get("theme.mid");
        assertEquals(LibraryEntry.Kind.DATA, stored.kind());
        assertArrayEquals(world.contents, SpeakerMediaLibraryFile.read(stored.value(), world.contents.length));
        assertEquals(TRACK, world.state.track().orElseThrow());
        assertEquals(2, world.state.slot());
        assertTrue(world.state.result().isEmpty(), "Exact write receipt acknowledged");
        assertEquals(world.transfer.writtenVersion().orElseThrow().value(), world.owner.media.stat(world.medium.book, "theme.mid").field("version"));
    }

    @Test void existingEntryIsNeverOverwrittenAndRevokedGrantDoesNotMutateBook() {
        var occupied = new Fixture("reading");
        occupied.drain(null);
        assertEquals("entry_exists", occupied.transfer.code().orElseThrow());
        assertEquals(0, occupied.medium.writes);
        var revoked = new Fixture("theme.mid");
        revoked.drain("write_begin");
        assertEquals("readonly", revoked.transfer.code().orElseThrow());
        assertEquals(0, revoked.medium.writes);
    }

    @Test void missingFinalAcknowledgementDoesNotTurnARefusedWriteIntoConfirmedStorage() {
        var world = new Fixture("theme.mid");
        boolean refusedWrite = false;
        boolean deliveredEmpty = false;
        for (int i = 0; !world.transfer.finished() && i < 250; i++) {
            var command = LibraryDriveCommand.decode(world.next());
            var reply = world.apply(command);
            if (command.operation().equals("write_commit")) {
                assertEquals(new DataValue.TextValue("accepted"), reply.field("code"));
                world.medium.grant = LibraryServiceConfiguration.Access.READ_ONLY;
            }
            if (command.operation().equals("result") && new DataValue.TextValue("readonly").equals(reply.field("code")))
                refusedWrite = true;
            if (refusedWrite && command.operation().equals("ack")) {
                // The result slot has just been cleared before the caller receives any matching acknowledgement.
                assertEquals(new DataValue.TextValue("acknowledged"), reply.field("code"));
                reply = world.apply(command);
                assertEquals(new DataValue.TextValue("empty"), reply.field("code"));
                deliveredEmpty = true;
            }
            assertTrue(world.transfer.reply(reply));
        }
        assertTrue(refusedWrite && deliveredEmpty);
        assertEquals(0, world.medium.writes);
        assertTrue(world.transfer.writtenVersion().isEmpty());
        assertEquals("readonly", world.transfer.code().orElseThrow());
    }

    @Test void lostWriteResultDoesNotClaimFailureOrRetransmitTheWrite() {
        var world = new Fixture("theme.mid");
        var written = world.takeWriteResult();
        assertTrue(world.transfer.writtenVersion().isEmpty(), "Only the device has confirmed the write");

        assertEquals("library_reply_timeout", world.transfer.replyTimeout());
        assertTrue(world.transfer.finished());
        assertTrue(world.transfer.next().isEmpty(), "The caller must inspect the book instead of retrying");
        assertFalse(world.transfer.reply(written), "A late receipt cannot revive the closed transfer");
        assertEquals(1, world.medium.writes);
        assertArrayEquals(world.contents, SpeakerMediaLibraryFile.read(
            world.owner.media.read(world.medium.book).get("theme.mid").value(), world.contents.length));
    }

    @Test void lostAcknowledgementAfterConfirmedWriteStillReportsPendingReceipt() {
        var world = new Fixture("theme.mid");
        assertTrue(world.transfer.reply(world.takeWriteResult()));
        assertTrue(world.transfer.writtenVersion().isPresent());
        var acknowledgement = LibraryDriveCommand.decode(world.next());
        assertEquals("ack", acknowledgement.operation());

        assertEquals("ack_pending", world.transfer.replyTimeout());
        assertTrue(world.transfer.finished());
        assertTrue(world.transfer.next().isEmpty(), "Do not resend the acknowledgement automatically");
        assertEquals(1, world.medium.writes);
    }

    private static final class Fixture {
        final LibraryPositionedWriteSourceTest owner = new LibraryPositionedWriteSourceTest();
        final LibraryPositionedWriteSourceTest.Fixture medium = owner.new Fixture();
        final LibraryReadHead.Source source = new LibraryPositionedReadSource(medium.access,
            address -> {
                assertEquals(TRACK, address);
                return LibraryPositionedWriteSourceTest.SHELF;
            }, volume -> true, medium.source);
        final byte[] contents = HexFormat.of().parseHex("4d546864000000060000000100604d54726b0000000400ff2f00");
        final byte[] wire = SpeakerMediaLibraryFile.encode("theme.mid", contents, FRAME.maxEncodedBytes(), FRAME.values());
        final SpeakerMediaLibraryTransfer transfer;
        final LibraryDriveCommand.Admission admission = new LibraryDriveCommand.Admission(UUID.randomUUID(), new LibraryWriteBatch(UUID.randomUUID(), 10));
        LibraryReadHead.State state = LibraryReadHead.State.empty();

        Fixture(String entry) {
            transfer = new SpeakerMediaLibraryTransfer(UUID.randomUUID(), DEVICE, TRACK, 2, "日志", entry, CHUNK, wire.length,
                (offset, count) -> Arrays.copyOfRange(wire, offset, offset + count));
        }

        DataValue.RecordValue next() {
            var candidate = transfer.next().orElseThrow();
            // Use the real finite wire codec for both directions, not a mocked address/value.
            return SerialRecordCodec.decode(SerialRecordCodec.encode(candidate, FRAME), FRAME);
        }

        DataValue.RecordValue apply(LibraryDriveCommand command) {
            if (state.transfer().filter(t -> t.commit().isPresent() && t.commit().orElseThrow().admission().verdict().isEmpty()).isPresent())
                state = LibraryReadHead.sealTransfer(state, false);
            if (state.writing().filter(w -> w.admission().verdict().isEmpty()).isPresent())
                state = LibraryReadHead.withWrite(state, state.writing().orElseThrow().seal(false));
            if (LibraryReadHead.busy(state)) {
                var tick = LibraryReadHead.prepareTick(state, source, LIMITS);
                state = tick.next();
                tick.commit().run();
            }
            DataValue.RecordValue reply;
            try {
                var checked = command.apply(state, source, LIMITS, admission, FRAME.maxEncodedBytes());
                // Maintain the media receipt exactly as the actual drive's receipt ACK path.
                if (command.operation().equals("ack") && state.result().filter(r -> r.code().equals("written")).isPresent())
                    source.releaseWrite(TRACK, 2, state.writing().orElseThrow().operation());
                state = checked.state(); reply = checked.reply();
            } catch (LibraryBookAccess.Failure denied) {
                reply = command.reply(state.sequence(), denied.code(), DataValue.NullValue.INSTANCE);
            }
            return SerialRecordCodec.decode(SerialRecordCodec.encode(reply, FRAME), FRAME);
        }

        DataValue.RecordValue takeWriteResult() {
            for (int i = 0; !transfer.finished() && i < 250; i++) {
                var command = LibraryDriveCommand.decode(next());
                var reply = apply(command);
                if (command.operation().equals("result") && new DataValue.TextValue("written").equals(reply.field("code")))
                    return reply;
                assertTrue(transfer.reply(reply));
            }
            throw new AssertionError("The drive did not return a write result");
        }

        void drain(String revokeAt) {
            for (int i = 0; !transfer.finished() && i < 250; i++) {
                var command = LibraryDriveCommand.decode(next());
                if (command.operation().equals(revokeAt)) medium.grant = LibraryServiceConfiguration.Access.READ_ONLY;
                assertTrue(transfer.reply(apply(command)));
            }
            assertTrue(transfer.finished());
        }
    }
}
