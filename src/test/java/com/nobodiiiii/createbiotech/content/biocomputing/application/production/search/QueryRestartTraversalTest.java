package com.nobodiiiii.createbiotech.content.biocomputing.application.production.search;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.ProductionLibraryQueries.RestartTraversalStats;

class QueryRestartTraversalTest {
	@Test
	void restartTraversalProbeCountsIntentionalOldStreamRemoveIfCopyAndClearPaths() {
		RestartTraversalProbe probe = new RestartTraversalProbe();
		RestartTrackedDeque<Integer> queries = new RestartTrackedDeque<>(probe,
			RestartQueue.QUERY);
		RestartTrackedDeque<Integer> replies = new RestartTrackedDeque<>(probe,
			RestartQueue.REPLY);
		queries.addAll(List.of(1, 2, 3));
		replies.addAll(List.of(4, 5));

		probe.begin();
		queries.stream().toList();
		queries.removeIf(ignored -> false);
		new ArrayList<>(queries);
		replies.clear();
		RestartTraversalStats measured = probe.finish();

		assertEquals(9, measured.queryVisits(),
			"stream/removeIf/copy must each expose all three old restart visits");
		assertEquals(2, measured.replyVisits(),
			"clear must expose the two eagerly discarded old replies");
	}

	@Test
	void restartTraversalProbeCountsMutationDrivenDequeDrains() {
		RestartTraversalProbe probe = new RestartTraversalProbe();
		RestartTrackedDeque<Integer> queries = new RestartTrackedDeque<>(probe,
			RestartQueue.QUERY);
		RestartTrackedDeque<Integer> replies = new RestartTrackedDeque<>(probe,
			RestartQueue.REPLY);
		queries.addAll(List.of(1, 2, 3));
		replies.addAll(List.of(4, 5));

		probe.begin();
		while (!queries.isEmpty())
			queries.removeFirst();
		while (replies.pollLast() != null) {
			// Deliberately model an eager mutation-based restart drain.
		}
		RestartTraversalStats measured = probe.finish();

		assertEquals(3, measured.queryVisits());
		assertEquals(2, measured.replyVisits());
	}

	@Test
	void restartTraversalProbeCountsArrayBackedSearchBulkMutationAndClonePaths() {
		RestartTraversalProbe probe = new RestartTraversalProbe();
		RestartTrackedDeque<Integer> queries = new RestartTrackedDeque<>(probe,
			RestartQueue.QUERY);
		RestartTrackedDeque<Integer> replies = new RestartTrackedDeque<>(probe,
			RestartQueue.REPLY);
		queries.addAll(List.of(1, 2, 3, 4));
		replies.addAll(List.of(5, 6, 7));

		probe.begin();
		assertTrue(queries.contains(4));
		queries.clone();
		queries.removeAll(Set.of(2));
		queries.retainAll(Set.of(1, 3, 4));
		replies.clone();
		RestartTraversalStats measured = probe.finish();

		assertEquals(15, measured.queryVisits(),
			"contains(4) + clone(4) + removeAll(4) + retainAll(3)");
		assertEquals(3, measured.replyVisits(), "clone must expose all copied replies");
	}
}
