package com.nobodiiiii.createbiotech.content.biocomputing.network;

import java.util.*;

/** Deterministic ordering of a caller-canonicalized list; no world clock or spatial policy. */
public final class XBusOrder {
    private XBusOrder() {}
    public static <T> List<T> shuffled(List<T> canonical, long seed) {
        var result = new ArrayList<>(List.copyOf(canonical));
        long a = mix(seed), b = mix(a);
        for (int i = result.size() - 1; i >= 1; i--) {
            long x = a, y = b; a = y;
            x ^= x << 23; x ^= x >>> 17; x ^= y; x ^= y >>> 26; b = x;
            int j = (int) Long.remainderUnsigned(a + b, i + 1L);
            Collections.swap(result, i, j);
        }
        return List.copyOf(result);
    }
    // Original unsigned-64 mixing and overflow, not gameplay parameters or java.util.Random.
    private static long mix(long value) {
        value ^= value >>> 33; value *= -49064778989728563L;
        value ^= value >>> 33; value *= -4265267296055464877L;
        return value ^ value >>> 33;
    }
}
