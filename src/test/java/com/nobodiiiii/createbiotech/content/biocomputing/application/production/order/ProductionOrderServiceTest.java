package com.nobodiiiii.createbiotech.content.biocomputing.application.production.order;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.*;

class ProductionOrderServiceTest {
    private static final UUID SOURCE = new UUID(1, 2), REQUEST = new UUID(3, 4);
    private static final Limits LIMITS = new Limits(4, 8, 4096);
    private static DataValue.RecordValue record(String value) {
        return new DataValue.RecordValue(Map.of("value", new DataValue.TextValue(value)));
    }
    private static final class Store implements Journal {
        DataValue.RecordValue archive = ProductionOrderArchive.encode(Snapshot.empty());
        int writes;
        long rejectRevision = -1;
        public Snapshot load() { return ProductionOrderArchive.decode(archive); }
        public boolean commit(long expected, Snapshot next) {
            if (load().revision() != expected || expected == rejectRevision) return false;
            archive = ProductionOrderArchive.encode(next); writes++; return true;
        }
    }
    private static final class Device implements Backend {
        int sent, observed, prepared;
        boolean completed, denied, uncertain, rejected, unsupported;
        Key unavailable;
        final List<Operation> operations = new ArrayList<>();
        Store store;
        public boolean authorized(UUID source) { return !denied && SOURCE.equals(source); }
        public boolean supports(String extension, int version) { return !unsupported && extension.equals("create.production") && version == 1; }
        public Preparation prepare(UUID source, DataValue.RecordValue request) {
            prepared++;
            return Preparation.ready(new Plan("create.production", 1, List.of(request)));
        }
        public Dispatch dispatch(Operation operation, DataValue.RecordValue command) {
            assertEquals(Phase.DISPATCHING, store.load().orders().get(operation.order()).phase(),
                "native effects require a durable intent first");
            operations.add(operation);
            if (rejected) return Dispatch.rejected("insufficient_stock");
            sent++;
            if (uncertain) throw new IllegalStateException("native side effect may already have happened");
            return Dispatch.accepted(record("native-receipt"));
        }
        public Observation observe(Operation operation, DataValue.RecordValue receipt) {
            observed++;
            if (operation.order().equals(unavailable)) throw new IllegalStateException("unloaded");
            return completed ? Observation.complete(record("actual-output")) : Observation.pending();
        }
    }
    @Test void requestDispatchReceiptAndCompletionSurviveIndependentRestoreWithoutDuplicateEffects() {
        var store = new Store(); var device = new Device(); device.store = store;
        var service = new ProductionOrderService(store, device, LIMITS);
        var key = new Key(SOURCE, REQUEST);
        assertEquals("accepted", service.submit(key, record("request")).code());
        assertEquals(0, device.sent, "accepting an order must not dispatch material");
        assertEquals("progressed", service.advance().code());
        assertEquals(Phase.WAITING, service.status(key).order().orElseThrow().phase());
        assertEquals(1, device.sent);
        service = new ProductionOrderService(store, device, LIMITS);
        assertEquals("existing", service.submit(key, record("request")).code());
        assertEquals("waiting", service.advance().code());
        assertEquals(1, device.sent);
        device.completed = true;
        assertEquals("progressed", service.advance().code());
        var result = service.status(key).order().orElseThrow();
        assertEquals(Phase.COMPLETE, result.phase());
        assertEquals(record("actual-output"), result.result());
        assertEquals("idle", service.advance().code());
        assertEquals(1, device.sent);
        assertEquals(result, new ProductionOrderService(store, device, LIMITS).status(key).order().orElseThrow());
    }

    private record Fixture(Store store, Device device, ProductionOrderService service, Key key) {}
    private static Fixture fixture() {
        var store = new Store(); var device = new Device(); device.store = store;
        return new Fixture(store, device, new ProductionOrderService(store, device, LIMITS), new Key(SOURCE, REQUEST));
    }
    @Test void idempotentRequestsDoNotRefreezeAndPayloadChangesCannotReuseIdentity() {
        var f = fixture();
        assertEquals("accepted", f.service.submit(f.key, record("first")).code());
        assertEquals("existing", f.service.submit(f.key, record("first")).code());
        assertEquals("request_conflict", f.service.submit(f.key, record("replacement")).code());
        assertEquals(1, f.device.prepared);
        assertEquals(record("first"), f.service.status(f.key).order().orElseThrow().request());
    }
    @Test void rejectedDurableIntentCannotDispatchAnything() {
        var f = fixture(); f.service.submit(f.key, record("first"));
        f.store.rejectRevision = f.store.load().revision();
        assertEquals("storage_changed", f.service.advance().code());
        assertEquals(0, f.device.sent);
        assertEquals(Phase.QUEUED, f.service.status(f.key).order().orElseThrow().phase());
    }
    @Test void lostReceiptPublicationLeavesIntentAndRestoreNeverRepeatsTheNativeEffect() {
        var f = fixture(); f.service.submit(f.key, record("first"));
        f.store.rejectRevision = f.store.load().revision() + 1;
        assertEquals("storage_changed", f.service.advance().code());
        assertEquals(1, f.device.sent);
        assertEquals(Phase.DISPATCHING, f.store.load().orders().get(f.key).phase());
        f.store.rejectRevision = -1;
        var restored = new ProductionOrderService(f.store, f.device, LIMITS);
        assertEquals(Phase.REVIEW_REQUIRED, restored.advance().order().orElseThrow().phase());
        assertEquals("idle", restored.advance().code());
        assertEquals(1, f.device.sent);
    }
    @Test void uncertainDispatchIsNotReportedAsARejectionOrAutomaticallyRetried() {
        var f = fixture(); f.service.submit(f.key, record("first")); f.device.uncertain = true;
        var order = f.service.advance().order().orElseThrow();
        assertEquals(Phase.REVIEW_REQUIRED, order.phase());
        assertEquals("uncertain_dispatch", order.diagnostic());
        assertEquals("idle", new ProductionOrderService(f.store, f.device, LIMITS).advance().code());
        assertEquals(1, f.device.sent);
    }
    @Test void revokedAuthorityBeforeDispatchFailsWithoutMaterialEffects() {
        var f = fixture(); f.service.submit(f.key, record("first")); f.device.denied = true;
        assertEquals(Phase.FAILED, f.service.advance().order().orElseThrow().phase());
        assertEquals("denied", f.store.load().orders().get(f.key).diagnostic());
        assertEquals("denied", f.service.status(f.key).code());
        assertEquals(0, f.device.sent);
    }
    @Test void nativeRejectionIsTerminalNotAnImplicitRetryLoop() {
        var f = fixture(); f.service.submit(f.key, record("first")); f.device.rejected = true;
        assertEquals(Phase.FAILED, f.service.advance().order().orElseThrow().phase());
        assertEquals("insufficient_stock", f.store.load().orders().get(f.key).diagnostic());
        assertEquals("idle", f.service.advance().code());
        assertEquals(1, f.device.operations.size());
        assertEquals(0, f.device.sent);
    }
    @Test void unavailableObservationDoesNotStarveOtherAcceptedOrders() {
        var f = fixture(); f.service.submit(f.key, record("first")); f.service.advance();
        var second = new Key(SOURCE, new UUID(5, 6)); f.service.submit(second, record("second"));
        f.device.unavailable = f.key;
        assertEquals("device_unavailable", f.service.advance().code());
        assertEquals("progressed", f.service.advance().code());
        assertEquals(2, f.device.sent);
        assertEquals(Phase.WAITING, f.store.load().orders().get(f.key).phase());
    }
    @Test void unknownExtensionIsRetainedWithoutStarvingSupportedOrders() {
        var f = fixture(); f.service.submit(f.key, record("first"));
        var prior = f.store.load(); var order = prior.orders().get(f.key);
        var unsupported = new Order(order.key(), order.request(), new Plan("future", 2, order.plan().steps()),
            order.step(), order.phase(), order.receipt(), order.result(), order.diagnostic());
        f.store.commit(prior.revision(), new Snapshot(prior.revision() + 1, 0, Map.of(f.key, unsupported)));
        var second = new Key(SOURCE, new UUID(5, 6)); f.service.submit(second, record("second"));
        assertEquals("unsupported_extension", f.service.advance().code());
        assertEquals("progressed", f.service.advance().code());
        assertEquals(1, f.device.sent);
        assertEquals(unsupported, f.store.load().orders().get(f.key));
    }
    @Test void fullHistoryCannotEvictIdempotencyEvidenceOrAcceptNewMaterialWork() {
        var f = fixture();
        var service = new ProductionOrderService(f.store, f.device, new Limits(1, 8, 4096));
        service.submit(f.key, record("first")); service.advance(); f.device.completed = true; service.advance();
        assertEquals("capacity", service.submit(new Key(SOURCE, new UUID(5, 6)), record("second")).code());
        assertEquals("existing", service.submit(f.key, record("first")).code());
        assertEquals(1, f.device.prepared); assertEquals(1, f.device.sent);
    }
    @Test void unchangedSingleOrderWaitDoesNotRewriteDurableHistoryEveryTick() {
        var f = fixture(); f.service.submit(f.key, record("first")); f.service.advance();
        var before = f.store.load(); int writes = f.store.writes;
        assertEquals("waiting", f.service.advance().code());
        assertEquals(before, f.store.load());
        assertEquals(writes, f.store.writes);
    }
    // RED: trusted evidence API absent at authoring time; root controls RED/GREEN Gradle window.
    @Test void nativeEvidencePublishesPartialThenCompleteWithoutAuthorityOrDispatch() {
        var f = fixture(); f.service.submit(f.key, record("first")); f.service.advance();
        var expected = f.store.load().orders().get(f.key); f.device.denied = true;
        var partial = f.service.recordEvidence(expected, record("partial"), Phase.WAITING, "delivering");
        assertEquals("progressed", partial.code()); assertEquals(record("partial"), partial.order().orElseThrow().result());
        var complete = f.service.recordEvidence(partial.order().orElseThrow(), record("delivered"), Phase.COMPLETE, "delivered");
        assertEquals(Phase.COMPLETE, complete.order().orElseThrow().phase());
        long revision = f.store.load().revision();
        assertEquals("storage_changed", f.service.recordEvidence(partial.order().orElseThrow(), record("delivered"), Phase.COMPLETE, "delivered").code());
        assertEquals(revision, f.store.load().revision());
        assertEquals(1, f.device.sent); assertEquals(0, f.device.observed);
    }
    @Test void nativeEvidenceRejectsStaleExpectedAndCasFailureAtomically() {
        var f = fixture(); f.service.submit(f.key, record("first")); f.service.advance();
        var expected = f.store.load().orders().get(f.key);
        var stale = new Order(expected.key(), expected.request(), expected.plan(), expected.step(), expected.phase(), expected.receipt(), record("stale"), expected.diagnostic());
        var before = f.store.load();
        assertEquals("storage_changed", f.service.recordEvidence(stale, record("partial"), Phase.WAITING, "partial").code());
        assertEquals(before, f.store.load());
        f.store.rejectRevision = before.revision();
        assertEquals("storage_changed", f.service.recordEvidence(expected, record("partial"), Phase.WAITING, "partial").code());
        assertEquals(before, f.store.load());
    }
    @Test void nativeEvidenceCapacityUsesAllExistingSaveLimitsAndPreservesRecord() {
        var f = fixture(); f.service.submit(f.key, record("first")); f.service.advance();
        var before = f.store.load(); var expected = before.orders().get(f.key);
        for (var limits : List.of(new Limits(4, 8, 1), new Limits(4, 8, 4096))) {
            var service = new ProductionOrderService(f.store, f.device, limits);
            var evidence = limits.valueUnits() == 1 ? record("partial") : record("x".repeat(10000));
            assertEquals("capacity", service.recordEvidence(expected, evidence, Phase.WAITING, "partial").code());
            assertEquals(before, f.store.load());
        }
    }
    @Test void nativeEvidenceHonorsExistingOrderAndStepCapacityAndVersionExhaustion() {
        var f = fixture(); f.service.submit(f.key, record("first")); f.service.advance();
        var state = f.store.load(); var original = state.orders().get(f.key);
        var multi = new Order(original.key(), original.request(), new Plan(original.plan().extension(), original.plan().version(), List.of(record("one"), record("two"))), 0, Phase.WAITING, original.receipt(), original.result(), original.diagnostic());
        var secondKey = new Key(SOURCE, new UUID(77, 88));
        var second = new Order(secondKey, original.request(), original.plan(), 0, Phase.WAITING, original.receipt(), original.result(), original.diagnostic());
        f.store.commit(state.revision(), new Snapshot(state.revision() + 1, 0, Map.of(f.key, multi, secondKey, second)));
        var before = f.store.load();
        for (var limits : List.of(new Limits(1, 8, 4096), new Limits(4, 1, 4096))) {
            var service = new ProductionOrderService(f.store, f.device, limits);
            assertEquals("capacity", service.recordEvidence(multi, record("partial"), Phase.WAITING, "partial").code());
            assertEquals(before, f.store.load());
        }
        f.store.commit(before.revision(), new Snapshot(Long.MAX_VALUE, 0, before.orders()));
        var exhausted = f.store.load();
        assertEquals("version_exhausted", f.service.recordEvidence(multi, record("partial"), Phase.WAITING, "partial").code());
        assertEquals(exhausted, f.store.load());
    }
    @Test void nativeEvidenceReviewAndInvalidArgumentsNeverDispatch() {
        var f = fixture(); f.service.submit(f.key, record("first"));
        var queued = f.store.load().orders().get(f.key);
        assertEquals("storage_changed", f.service.recordEvidence(queued, record("bad"), Phase.COMPLETE, "bad").code());
        f.service.advance(); var expected = f.store.load().orders().get(f.key); var before = f.store.load();
        for (var phase : List.of(Phase.QUEUED, Phase.DISPATCHING, Phase.FAILED))
            assertEquals("invalid_evidence", f.service.recordEvidence(expected, record("bad"), phase, "bad").code());
        assertEquals("invalid_evidence", f.service.recordEvidence(null, record("bad"), Phase.WAITING, "bad").code());
        assertEquals("invalid_evidence", f.service.recordEvidence(expected, null, Phase.WAITING, "bad").code());
        assertEquals("invalid_evidence", f.service.recordEvidence(expected, record("bad"), null, "bad").code());
        assertEquals("invalid_evidence", f.service.recordEvidence(expected, record("bad"), Phase.WAITING, null).code());
        assertEquals(before, f.store.load());
        var reviewed = f.service.recordEvidence(expected, record("ambiguous"), Phase.REVIEW_REQUIRED, "review");
        assertEquals(Phase.REVIEW_REQUIRED, reviewed.order().orElseThrow().phase());
        assertEquals("storage_changed", f.service.recordEvidence(reviewed.order().orElseThrow(), record("later"), Phase.COMPLETE, "later").code());
        assertEquals(1, f.device.sent);
    }
    @Test void nativeEvidenceCannotCompleteIntermediateStep() {
        var f = fixture(); f.service.submit(f.key, record("first")); f.service.advance();
        var state = f.store.load(); var old = state.orders().get(f.key);
        var multi = new Order(old.key(), old.request(), new Plan(old.plan().extension(), old.plan().version(), List.of(record("one"), record("two"))), 0, Phase.WAITING, old.receipt(), old.result(), old.diagnostic());
        f.store.commit(state.revision(), new Snapshot(state.revision() + 1, 0, Map.of(f.key, multi)));
        var before = f.store.load();
        assertEquals("invalid_evidence", f.service.recordEvidence(multi, record("delivered"), Phase.COMPLETE, "complete").code());
        assertEquals(before, f.store.load());
        assertEquals("progressed", f.service.recordEvidence(multi, record("partial"), Phase.WAITING, "partial").code());
    }
}

