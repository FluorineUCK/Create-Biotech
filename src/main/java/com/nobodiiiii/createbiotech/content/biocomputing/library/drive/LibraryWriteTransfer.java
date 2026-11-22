package com.nobodiiiii.createbiotech.content.biocomputing.library.drive;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryMutation;
import java.util.*;

/** Device-owned upload reservation. Neither a book mutation nor a transferable authorization. */
public record LibraryWriteTransfer(UUID device, long sequence, String volume, String entry,
    LibraryMutation.Version expected, LibraryEntry.Kind kind, String format, long length,
    List<Segment> segments, long received, long remaining, Optional<LibraryUploadCommit> commit) {
    public static final String FORMAT = "typed_record_v1";
    /** Validate new/loaded bytes once, not again for every device activity tick. */
    public record Segment(String hex) {
        public Segment {
            if (hex.isEmpty() || (hex.length() & 1) != 0 || !hex.matches("[0-9a-f]+"))
                throw new LibraryBookAccess.Failure("invalid_checkpoint");
        }
        public int bytes() { return hex.length() / 2; }
    }
    public LibraryWriteTransfer(UUID device, long sequence, String volume, String entry,
        LibraryMutation.Version expected, LibraryEntry.Kind kind, String format, long length) {
        this(device, sequence, volume, entry, expected, kind, format, length, List.of(), 0, 0, Optional.empty());
    }
    public LibraryWriteTransfer {
        Objects.requireNonNull(device); Objects.requireNonNull(expected); Objects.requireNonNull(kind);
        LibraryBookVolumes.requireName(volume); LibraryBookVolumes.requireName(entry);
        if (sequence < 1 || !expected.entry().equals(entry) || length < 1 || length > Integer.MAX_VALUE)
            throw new LibraryBookAccess.Failure("invalid_request");
        if (!FORMAT.equals(format)) throw new LibraryBookAccess.Failure("unsupported_version");
        segments = List.copyOf(segments);
        long bytes = 0;
        for (var segment : segments) {
            bytes += segment.bytes();
        }
        if (received != bytes || received > length || remaining < 0 || segments.isEmpty() && remaining != 0)
            throw new LibraryBookAccess.Failure("invalid_checkpoint");
        Objects.requireNonNull(commit);
        if(commit.isPresent() && (received!=length || remaining!=0 || commit.orElseThrow().length()!=length))
            throw new LibraryBookAccess.Failure("invalid_checkpoint");
    }
    public static LibraryWriteTransfer begin(UUID device, long sequence, DataValue data) {
        var r = fields(data, "volume", "entry", "expected", "kind", "format", "bytes");
        try {
            return new LibraryWriteTransfer(device, sequence, text(r.field("volume")), text(r.field("entry")),
                LibraryMutation.Version.decode(r.field("expected")), LibraryEntry.Kind.valueOf(text(r.field("kind"))),
                text(r.field("format")), number(r.field("bytes")));
        } catch (LibraryBookAccess.Failure known) { throw known; }
        catch (IllegalArgumentException invalid) { throw new LibraryBookAccess.Failure("invalid_request"); }
    }
    public DataValue.RecordValue value() {
        var fields = new LinkedHashMap<String, DataValue>(Map.of("device", new DataValue.TextValue(device.toString()),
            "sequence", new DataValue.IntegerValue(sequence), "volume", new DataValue.TextValue(volume),
            "entry", new DataValue.TextValue(entry), "expected", expected.value(),
            "kind", new DataValue.TextValue(kind.name()), "format", new DataValue.TextValue(format),
            "bytes", new DataValue.IntegerValue(length)));
        if (!segments.isEmpty()) {
            fields.put("segments", new DataValue.ListValue(segments.stream().map(s -> (DataValue)new DataValue.TextValue(s.hex())).toList()));
            fields.put("received", new DataValue.IntegerValue(received));
            fields.put("remaining", new DataValue.IntegerValue(remaining));
        }
        commit.ifPresent(c -> fields.put("commit",c.value()));
        return new DataValue.RecordValue(fields);
    }
    public static LibraryWriteTransfer decode(DataValue data) {
        boolean segmented = data instanceof DataValue.RecordValue record && record.fields().containsKey("segments");
        boolean committing = data instanceof DataValue.RecordValue record && record.fields().containsKey("commit");
        var r = committing ? fields(data, "device", "sequence", "volume", "entry", "expected", "kind", "format", "bytes", "segments", "received", "remaining", "commit")
            : segmented ? fields(data, "device", "sequence", "volume", "entry", "expected", "kind", "format", "bytes", "segments", "received", "remaining")
            : fields(data, "device", "sequence", "volume", "entry", "expected", "kind", "format", "bytes");
        var id = text(r.field("device")); var device = UUID.fromString(id);
        if (!device.toString().equals(id)) throw new LibraryBookAccess.Failure("invalid_checkpoint");
        var body = new LinkedHashMap<>(r.fields()); body.remove("device"); body.remove("sequence");
        body.remove("segments"); body.remove("received"); body.remove("remaining"); body.remove("commit");
        var header = begin(device, number(r.field("sequence")), new DataValue.RecordValue(body));
        if (!segmented) return header;
        if (!(r.field("segments") instanceof DataValue.ListValue parts) || parts.values().isEmpty())
            throw new LibraryBookAccess.Failure("invalid_checkpoint");
        var restored = header.withSegments(parts.values().stream().map(LibraryWriteTransfer::text).map(Segment::new).toList(),
            number(r.field("received")), number(r.field("remaining")));
        return committing ? restored.withCommit(LibraryUploadCommit.decode(r.field("commit"))) : restored;
    }
    /** Raw NBT bytes on the public wire. Hex is only a private, bounded checkpoint representation. */
    public LibraryWriteTransfer append(DataValue data, LibraryDriveLimits limits) {
        if (remaining > 0 || commit.isPresent()) throw new LibraryBookAccess.Failure("busy");
        var r = fields(data, "offset", "bytes");
        if (number(r.field("offset")) != received) throw new LibraryBookAccess.Failure("stale_offset");
        if (!(r.field("bytes") instanceof DataValue.NbtValue nbt) || nbt.tagId() != 7)
            throw new LibraryBookAccess.Failure("invalid_request");
        var values = ((DataValue.ListValue)nbt.value()).values();
        if (values.isEmpty()) throw new LibraryBookAccess.Failure("invalid_request");
        if (values.size() > length - received) throw new LibraryBookAccess.Failure("length_mismatch");
        byte[] bytes = new byte[values.size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)((DataValue.IntegerValue)values.get(i)).value();
        var parts = new ArrayList<>(segments); parts.add(new Segment(HexFormat.of().formatHex(bytes)));
        return withSegments(parts, received + bytes.length, limits.readTicks(bytes.length));
    }
    public LibraryWriteTransfer elapsed() { return remaining == 0 ? this : withSegments(segments, received, remaining - 1); }
    private LibraryWriteTransfer withSegments(List<Segment> parts, long bytes, long ticks) {
        return new LibraryWriteTransfer(device, sequence, volume, entry, expected, kind, format, length, parts, bytes, ticks, commit);
    }
    public LibraryWriteTransfer withCommit(LibraryUploadCommit work) {
        return new LibraryWriteTransfer(device,sequence,volume,entry,expected,kind,format,length,segments,received,remaining,Optional.of(work));
    }
    /** Indexed borrowed bytes: no giant concatenation or payload clone at commit. */
    public com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDecoding.Input input() {
        int[] starts=new int[segments.size()]; int offset=0;
        for(int i=0;i<starts.length;i++) {starts[i]=offset; offset+=segments.get(i).bytes();}
        return new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDecoding.Input() {
            public int size() {return Math.toIntExact(received);}
            public int byteAt(int index) {
                Objects.checkIndex(index,size()); int segment=Arrays.binarySearch(starts,index);
                if(segment<0) segment=-segment-2;
                var text=segments.get(segment).hex(); int at=2*(index-starts[segment]);
                return (Character.digit(text.charAt(at),16)<<4)|Character.digit(text.charAt(at+1),16);
            }
        };
    }
    /** ValueNbtCodec stores text as UTF-16: reserve both hex digits, not just the empty header. */
    public long unfilledArchiveBytes() { return 4 * (length - received); }
    private static DataValue.RecordValue fields(DataValue value, String... names) {
        if (!(value instanceof DataValue.RecordValue r) || !r.fields().keySet().equals(Set.of(names)))
            throw new LibraryBookAccess.Failure("invalid_request");
        return r;
    }
    private static String text(DataValue value) {
        if (!(value instanceof DataValue.TextValue t)) throw new LibraryBookAccess.Failure("invalid_request");
        return t.value();
    }
    private static long number(DataValue value) {
        if (!(value instanceof DataValue.IntegerValue n)) throw new LibraryBookAccess.Failure("invalid_request");
        return n.value();
    }
}
