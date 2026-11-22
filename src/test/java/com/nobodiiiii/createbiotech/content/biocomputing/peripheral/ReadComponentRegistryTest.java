package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReadComponentRegistryTest {
    private static final ReadLimits LIMITS = new ReadLimits(100, 1_000, 10_000);

    @Test void rejectsInvalidDescriptorsDuplicateIdsAndRegistrationAfterBuild() {
        assertThrows(IllegalArgumentException.class, () -> descriptor("counter"));
        assertThrows(IllegalArgumentException.class, () -> descriptor("Test:counter"));
        assertThrows(IllegalArgumentException.class, () -> new ReadComponentDescriptor(
            "test:counter", " ", Set.of("block"), DataType.Scalar.INTEGER,
            ReadComponentDescriptor.UpdateHint.EXPLICIT, false));
        assertThrows(IllegalArgumentException.class, () -> new ReadComponentDescriptor(
            "test:counter", "label", Set.of(), DataType.Scalar.INTEGER,
            ReadComponentDescriptor.UpdateHint.EXPLICIT, false));
        assertThrows(IllegalArgumentException.class, () -> new ReadComponentDescriptor(
            "test:counter", "label", Set.of(" "), DataType.Scalar.INTEGER,
            ReadComponentDescriptor.UpdateHint.EXPLICIT, false));

        var builder = ReadComponentRegistry.<String>builder();
        builder.register(provider(descriptor("test:counter"), ignored -> true, ignored -> integer(1)));
        assertThrows(IllegalArgumentException.class, () -> builder.register(
            provider(descriptor("test:counter"), ignored -> true, ignored -> integer(2))));
        builder.build();
        assertThrows(IllegalStateException.class, () -> builder.register(
            provider(descriptor("test:later"), ignored -> true, ignored -> integer(3))));
    }

    @Test void describesSortedImmutableMetadataWithoutEvaluatingProvidersAndSurvivesWire() {
        var calls = new AtomicInteger();
        var targets = new java.util.HashSet<>(Set.of("zeta", "alpha"));
        var descriptor = new ReadComponentDescriptor("test:counter", "component.test.counter", targets,
            new DataType.ListType(DataType.Scalar.INTEGER), ReadComponentDescriptor.UpdateHint.BLOCK_UPDATE, true);
        var registry = ReadComponentRegistry.<String>builder().register(provider(descriptor,
            ignored -> { calls.incrementAndGet(); return true; },
            ignored -> { calls.incrementAndGet(); return new DataValue.ListValue(List.of()); })).build();
        targets.add("mutated");

        var description = registry.describe();
        assertEquals(0, calls.get());
        assertEquals(Set.of("test:counter"), registry.ids());
        assertThrows(UnsupportedOperationException.class, () -> registry.ids().add("test:nope"));
        var item = (DataValue.RecordValue) description.field("test:counter");
        assertEquals(new DataValue.TextValue("test:counter"), item.field("id"));
        assertEquals(new DataValue.TextValue("component.test.counter"), item.field("label"));
        assertEquals(new DataValue.ListValue(List.of(new DataValue.TextValue("alpha"), new DataValue.TextValue("zeta"))), item.field("target_kinds"));
        assertEquals(new DataValue.TextValue("block_update"), item.field("update_hint"));
        assertEquals(new DataValue.BooleanValue(true), item.field("additional"));
        byte[] wire = ProgramEditorWire.encodeValue(description, new ArchiveLimits(100_000, 10_000, 1_000_000));
        assertEquals(description, ProgramEditorWire.decodeValue(wire, new ArchiveLimits(100_000, 10_000, 1_000_000)));
    }

    @Test void sessionLazilyCachesRequestedValuesAndFreshSessionEvaluatesAgain() {
        var reads = new AtomicInteger();
        var untouched = new AtomicInteger();
        var registry = ReadComponentRegistry.<String>builder()
            .register(provider(descriptor("test:counter"), value -> true, value -> integer(reads.incrementAndGet())))
            .register(provider(descriptor("test:untouched"), value -> { untouched.incrementAndGet(); return true; },
                value -> integer(untouched.incrementAndGet())))
            .build();
        var session = registry.session("context", LIMITS);
        DataValue first = session.read("test:counter");
        assertSame(first, session.read("test:counter"));
        assertEquals(integer(1), first);
        assertEquals(1, reads.get());
        assertEquals(0, untouched.get());
        assertEquals(integer(2), registry.session("context", LIMITS).read("test:counter"));
    }

    @Test void convertsUnknownUnsupportedFailuresWrongTypesAndOversizeToStableErrors() {
        var registry = ReadComponentRegistry.<String>builder()
            .register(provider(descriptor("test:unsupported"), ignored -> false, ignored -> integer(1)))
            .register(provider(descriptor("test:support_failure"), ignored -> { throw new IllegalStateException("secret support"); }, ignored -> integer(1)))
            .register(provider(descriptor("test:read_failure"), ignored -> true, ignored -> { throw new StackOverflowError("secret read"); }))
            .register(provider(descriptor("test:wrong"), ignored -> true, ignored -> new DataValue.TextValue("wrong")))
            .register(provider(descriptor("test:error"), ignored -> true, ignored -> new DataValue.ErrorValue("e", "")))
            .register(provider(descriptor("test:large"), ignored -> true, ignored -> new DataValue.TextValue("x".repeat(20))))
            .build();
        var session = registry.session("context", new ReadLimits(100, 1_000, 10));
        assertError("unknown_component", session.read("test:missing"), null);
        assertError("unsupported", session.read("test:unsupported"), null);
        assertError("provider_failure", session.read("test:support_failure"), "secret");
        assertError("provider_failure", session.read("test:read_failure"), "secret");
        assertError("invalid_component_type", session.read("test:wrong"), null);
        assertError("e", session.read("test:error"), null);
        assertError("snapshot_limit", session.read("test:large"), null);
    }

    @Test void classifiesProviderLimitExceededAsSnapshotLimitRatherThanProviderFailure() {
        var registry = ReadComponentRegistry.<String>builder()
            .register(provider(descriptor("test:limited"), ignored -> true, ignored -> {
                SnapshotLimits.validate(new DataValue.TextValue("too large"), new ReadLimits(10, 100, 1));
                return integer(1);
            }))
            .build();

        assertError("snapshot_limit", registry.session("context", LIMITS).read("test:limited"), null);
    }

    @Test void aggregateAccountsForTheWholeRecordAndNeverReturnsPartialResults() {
        var registry = ReadComponentRegistry.<String>builder()
            .register(provider(descriptor("test:a"), ignored -> true, ignored -> integer(1)))
            .register(provider(descriptor("test:b"), ignored -> true, ignored -> integer(2)))
            .build();
        var session = registry.session("context", new ReadLimits(2, 100, 100));
        assertThrows(SnapshotLimits.LimitExceeded.class, () -> session.read(List.of("test:a", "test:b")));
    }

    private static ReadComponentDescriptor descriptor(String id) {
        return new ReadComponentDescriptor(id, "component.test.counter", Set.of("block"), DataType.Scalar.INTEGER,
            ReadComponentDescriptor.UpdateHint.EXPLICIT, false);
    }

    private static <C> ReadComponentProvider<C> provider(ReadComponentDescriptor descriptor,
        java.util.function.Predicate<C> supports, java.util.function.Function<C, DataValue> read) {
        return new ReadComponentProvider<>() {
            @Override public ReadComponentDescriptor descriptor() { return descriptor; }
            @Override public boolean supports(C context) { return supports.test(context); }
            @Override public DataValue read(C context) { return read.apply(context); }
        };
    }

    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }

    private static void assertError(String code, DataValue value, String forbiddenText) {
        var error = assertInstanceOf(DataValue.ErrorValue.class, value);
        assertEquals(code, error.code());
        if (forbiddenText != null) assertFalse(error.message().contains(forbiddenText));
    }
}
