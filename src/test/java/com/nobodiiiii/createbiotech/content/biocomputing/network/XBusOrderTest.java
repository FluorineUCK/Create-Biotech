package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class XBusOrderTest {
    @Test void matchesIndependentOriginalBinaryPermutationsIncludingUnsignedHighBits() throws IOException {
        var resource = getClass().getResourceAsStream("/biocomputing/xbus-original-order.csv");
        assertNotNull(resource);
        try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            var rows = reader.lines().filter(line -> !line.startsWith("#")).toList();
            assertEquals(177, rows.size());
            for (var row : rows) {
                var fields = row.split(",", -1); long seed = Long.parseLong(fields[0]); int n = Integer.parseInt(fields[1]);
                var canonical = new ArrayList<>(IntStream.range(0, n).boxed().toList());
                var expected = fields[2].isEmpty() ? List.<Integer>of() : Arrays.stream(fields[2].split("\\|")).map(Integer::parseInt).toList();
                assertEquals(expected, XBusOrder.shuffled(canonical, seed), row);
                assertEquals(expected, XBusOrder.shuffled(canonical, seed), "A peek does not advance ordering state");
                assertEquals(IntStream.range(0, n).boxed().toList(), canonical, "Physical order is not mutated");
            }
        }
    }
}
