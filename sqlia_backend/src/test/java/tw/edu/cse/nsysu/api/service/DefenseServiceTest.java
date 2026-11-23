package tw.edu.cse.nsysu.api.service;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class DefenseServiceTest {
    private DefenseService.Request request(String architecture, String algorithm) {
        var request = new DefenseService.Request();
        request.keyword = "Keyboard";
        request.architecture = architecture;
        request.algorithm = algorithm;
        return request;
    }
    private DefenseService.Predictor predictor(String action) {
        return new DefenseService.Predictor() {
            public String version() { return "fixture"; }
            public DefenseService.Prediction predict(String input, String architecture,
                    IncrementalAlgorithm algorithm) {
                return new DefenseService.Prediction("block".equals(action) ? 0.9 : 0.1, 0.5, action, List.of());
            }
        };
    }

    @Test public void allFiveArchitecturesRejectWithoutCallingProducts() throws Exception {
        for (String architecture : FrozenArchitectureComparisonService.ARCHITECTURES) {
            for (String action : List.of("block", "review")) {
                AtomicInteger calls = new AtomicInteger();
                var service = new DefenseService(predictor(action), input -> {
                    calls.incrementAndGet(); return List.of();
                }, result -> {});
                var result = service.search(request(architecture, null));
                assertEquals("review".equals(action) ? "review_pending" : "rejected", result.status);
                assertEquals(action, result.effectiveAction);
                assertFalse(result.businessQueryAttempted);
                assertFalse(result.businessQueryCompleted);
                assertEquals(0, calls.get());
            }
        }
    }

    @Test public void reviewedRequestRunsProductQueryOnlyAfterUserAllows() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var service = new DefenseService(predictor("review"), input -> {
            calls.incrementAndGet();
            return List.of(new DefenseStore.Product(2, "Keyboard", "Demo", 20000));
        }, result -> {});

        var pending = service.search(request("EXTENDED_ARM", "SGD"));
        assertEquals("review_pending", pending.status);
        assertEquals("review", pending.recommendedAction);
        assertFalse(pending.businessQueryAttempted);
        assertEquals(0, calls.get());

        var decision = new DefenseService.ReviewRequest();
        decision.requestId = pending.requestId;
        decision.decision = "allow";
        var allowed = service.resolveReview(decision);
        assertEquals("complete", allowed.status);
        assertEquals("allow", allowed.effectiveAction);
        assertTrue(allowed.businessQueryAttempted);
        assertTrue(allowed.businessQueryCompleted);
        assertEquals("Keyboard", allowed.products.getFirst().name());
        assertEquals(1, calls.get());
        assertThrows(DefenseService.ReviewResolutionFailure.class, () -> service.resolveReview(decision));
    }

    @Test public void reviewedRequestCanBeBlockedWithoutCallingProducts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var service = new DefenseService(predictor("review"), input -> {
            calls.incrementAndGet();
            return List.of();
        }, result -> {});

        var pending = service.search(request("PURE_EXTENDED_ARM", null));
        var decision = new DefenseService.ReviewRequest();
        decision.requestId = pending.requestId;
        decision.decision = "block";
        var blocked = service.resolveReview(decision);

        assertEquals("rejected", blocked.status);
        assertEquals("block", blocked.effectiveAction);
        assertFalse(blocked.businessQueryAttempted);
        assertFalse(blocked.businessQueryCompleted);
        assertEquals(0, calls.get());
    }

    @Test public void allExtendedAlgorithmsReachPredictorAndExecuteOnceWhenAllowed() throws Exception {
        for (var algorithm : IncrementalAlgorithm.available()) {
            AtomicInteger calls = new AtomicInteger();
            var selected = new DefenseService.Predictor() {
                public String version() { return "fixture"; }
                public DefenseService.Prediction predict(String input, String architecture,
                        IncrementalAlgorithm actual) {
                    assertEquals(algorithm, actual);
                    return new DefenseService.Prediction(0.1, 0.5, "allow", List.of());
                }
            };
            var service = new DefenseService(selected, input -> {
                calls.incrementAndGet(); return List.of(new DefenseStore.Product(1, "Keyboard", "Demo", 100));
            }, result -> {});
            var result = service.search(request("EXTENDED_ARM", algorithm.getId()));
            assertTrue(result.businessQueryAttempted);
            assertTrue(result.businessQueryCompleted);
            assertEquals(1, result.products.size());
            assertEquals(1, calls.get());
        }
    }

    @Test public void rejectsInvalidSelectionAndExactLengthBoundariesBeforeAccessingServices() throws Exception {
        var service = new DefenseService(null, null, null);
        assertThrows(IllegalArgumentException.class, () -> service.search(null));
        for (String value : List.of("", " \n", "a".repeat(101))) {
            var request = request("EXTENDED_ARM", null);
            request.keyword = value;
            assertThrows(IllegalArgumentException.class, () -> service.search(request));
        }
        for (String architecture : List.of("UNKNOWN", "PURE_EXTENDED_ARM", "ARM_PROFILE")) {
            assertThrows(IllegalArgumentException.class, () -> service.search(request(architecture, "SGD")));
        }
        assertThrows(IllegalArgumentException.class, () -> service.search(request(null, null)));
        var valid = request("EXTENDED_ARM", null);
        valid.keyword = "a".repeat(100);
        assertEquals("complete", new DefenseService(predictor("allow"), input -> List.of(), result -> {})
                .search(valid).status);
    }

    @Test public void detectorFailureDeniesWithoutProductCallAndPersistsError() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> statuses = new ArrayList<>();
        var broken = new DefenseService.Predictor() {
            public String version() { return "fixture"; }
            public DefenseService.Prediction predict(String input, String architecture,
                    IncrementalAlgorithm algorithm) throws Exception { throw new Exception("fixture failure"); }
        };
        var service = new DefenseService(broken, input -> { calls.incrementAndGet(); return List.of(); },
                result -> statuses.add(result.status));
        var error = assertThrows(DefenseService.Failure.class,
                () -> service.search(request("EXTENDED_ARM", "SGD")));
        assertEquals(503, error.httpStatus);
        assertEquals("DETECTOR_UNAVAILABLE", error.result.errorCode);
        assertFalse(error.result.businessQueryAttempted);
        assertEquals(List.of("started", "failed"), statuses);
        assertEquals(0, calls.get());
    }

    @Test public void auditFailureBeforeQueryPreventsProductAccess() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        var service = new DefenseService(predictor("allow"),
                input -> { calls.incrementAndGet(); return List.of(); },
                result -> { if (writes.incrementAndGet() == 2) throw new Exception("audit unavailable"); });
        var error = assertThrows(DefenseService.Failure.class,
                () -> service.search(request("EXTENDED_ARM", null)));
        assertFalse(error.result.businessQueryAttempted);
        assertEquals("deny", error.result.effectiveAction);
        assertEquals(0, calls.get());
    }

    @Test public void queryFailureIsAttemptedButNotCompleted() {
        var service = new DefenseService(predictor("allow"),
                input -> { throw new Exception("query unavailable"); }, result -> {});
        var error = assertThrows(DefenseService.Failure.class,
                () -> service.search(request("EXTENDED_ARM", null)));
        assertEquals(500, error.httpStatus);
        assertTrue(error.result.businessQueryAttempted);
        assertFalse(error.result.businessQueryCompleted);
        assertEquals("PRODUCT_QUERY_FAILED", error.result.errorCode);
    }

    @Test public void finalAuditFailureNeverClaimsTheBusinessQueryWasNotExecuted() {
        AtomicInteger writes = new AtomicInteger();
        var service = new DefenseService(predictor("allow"), input -> List.of(),
                result -> { if (writes.incrementAndGet() == 3) throw new Exception("final audit failed"); });
        var error = assertThrows(DefenseService.Failure.class,
                () -> service.search(request("EXTENDED_ARM", null)));
        assertEquals("AUDIT_UNAVAILABLE", error.result.errorCode);
        assertEquals("audit_failed", error.result.status);
        assertTrue(error.result.businessQueryAttempted);
        assertTrue(error.result.businessQueryCompleted);
    }

    @Test public void productBindingAndAuditSurviveReopeningWithoutSavingInput() throws Exception {
        var file = Files.createTempFile("defense-store-", ".db");
        try {
            try (var store = new DefenseStore(file)) {
                assertEquals("Keyboard", store.search("Keyboard").getFirst().name());
                assertEquals("Notebook", store.search("10000").getFirst().name());
                assertTrue(store.search("' OR 1=1 --").isEmpty());
                assertTrue(store.search("%").isEmpty());
                assertTrue(store.search("_").isEmpty());
                var service = new DefenseService(predictor("block"), store, store);
                service.search(request("EXTENDED_ARM", null));
            }
            try (var store = new DefenseStore(file)) {
                var rows = store.recent(200);
                assertEquals(1, rows.size());
                assertEquals("rejected", rows.getFirst().status);
                assertFalse(rows.getFirst().businessQueryAttempted);
                assertEquals(8, rows.getFirst().inputLength);
                assertEquals(1, DefenseEvaluation.auditSummary(rows).get("sampleSize"));
            }
        } finally { Files.deleteIfExists(file); }
    }
}
