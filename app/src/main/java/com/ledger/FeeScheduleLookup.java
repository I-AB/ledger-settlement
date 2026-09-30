package com.ledger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Simulates a downstream client whose fee schedule lookup table is loaded
 * once, the first time this class is touched, via a blocking network call.
 *
 * This is a deliberately planted defect for the virtual threads lab: doing
 * blocking work inside a static initializer pins whatever thread triggers
 * class loading, for the whole call, regardless of whether that thread is a
 * platform thread or a virtual thread. The JVM's class initialization lock
 * does not support unmounting a virtual thread the way java.util.concurrent
 * locks do since Java 24.
 */
final class FeeScheduleLookup {

    static final int TABLE_VERSION;

    static {
        TABLE_VERSION = fetchTable();
    }

    private static int fetchTable() {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5433/ledger", "ledger", "ledger")) {
            // Simulate a slow downstream dependency: a real network round
            // trip to open the connection, plus deliberate extra latency
            // representing the time a real lookup-table fetch would take.
            Thread.sleep(300);
            return 1;
        } catch (SQLException | InterruptedException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private FeeScheduleLookup() {
    }
}