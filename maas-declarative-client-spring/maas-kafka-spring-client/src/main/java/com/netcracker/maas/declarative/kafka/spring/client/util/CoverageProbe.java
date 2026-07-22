package com.netcracker.maas.declarative.kafka.spring.client.util;

/**
 * Throwaway class added only to exercise Sonar coverage-on-new-code reporting.
 * Does nothing useful.
 */
public class CoverageProbe {

    public String classify(int value) {
        if (value > 0) {
            return "positive";
        }
        return "non-positive";
    }
}
