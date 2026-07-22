package com.netcracker.maas.declarative.kafka.spring.client.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoverageProbeTest {

    @Test
    void classifiesPositiveAndNonPositive() {
        var probe = new CoverageProbe();
        assertEquals("positive", probe.classify(1));
        assertEquals("non-positive", probe.classify(0));
    }
}
