package com.ledger;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Forces FeeScheduleLookup to fully initialize during application startup,
 * before Tomcat starts accepting connections. This is the fix for the
 * planted virtual thread pinning defect: moving the blocking load out of
 * the request path entirely means no thread, virtual or platform, is ever
 * pinned on this class's static initializer again.
 */
@Component
public class FeeScheduleWarmup {

    @PostConstruct
    void warmUp() {
        int ignored = FeeScheduleLookup.TABLE_VERSION;
    }
}