package com.nobodiiiii.createbiotech.content.biocomputing.network.carrier;

import java.util.Optional;

/** An exact game-unit scalar, independent of wavelength, phase, and symbol rate. */
public record CarrierFrequency(int numerator, int denominator) {
    public CarrierFrequency {
        if (numerator < 0 || denominator <= 0) throw new IllegalArgumentException("Invalid carrier frequency");
        int common = (int) gcd(numerator, denominator);
        numerator /= common;
        denominator /= common;
    }

    public static CarrierFrequency whole(int frequency) { return new CarrierFrequency(frequency, 1); }

    /** Reject a result outside the finite representation instead of rounding it onto a preset. */
    Optional<CarrierFrequency> scaled(int multiplier, int divisor) {
        if (multiplier < 1 || divisor < 1) throw new IllegalArgumentException("Invalid carrier ratio");
        long top = (long) numerator * multiplier;
        long bottom = (long) denominator * divisor;
        long common = gcd(top, bottom);
        top /= common;
        bottom /= common;
        if (top > Integer.MAX_VALUE || bottom > Integer.MAX_VALUE) return Optional.empty();
        return Optional.of(new CarrierFrequency((int) top, (int) bottom));
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long rest = a % b;
            a = b;
            b = rest;
        }
        return a;
    }

    @Override public String toString() {
        return denominator == 1 ? Integer.toString(numerator) : numerator + "/" + denominator;
    }
}
