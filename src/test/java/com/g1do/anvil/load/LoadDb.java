package com.g1do.anvil.load;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * The containerized Postgres the GIN budget and the load runs measure (#79):
 * the compose image, {@code postgres:18.6}, with its default settings, so the
 * numbers describe the stock configuration rather than a tuned one.
 */
final class LoadDb implements AutoCloseable {

    static final String IMAGE = "postgres:18.6";
    static final String USER = "anvil";
    static final String PASSWORD = "anvil";

    private final GenericContainer<?> pg = new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withEnv("POSTGRES_USER", USER)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .withEnv("POSTGRES_DB", "postgres")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                    .withStartupTimeout(Duration.ofSeconds(90)));

    LoadDb start() {
        pg.start();
        return this;
    }

    String url(String database) {
        return "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + database;
    }

    /** A new, empty database migrated to the latest schema. */
    String migrated(String database) throws SQLException {
        try (Connection c = connect("postgres")) {
            c.createStatement().execute("CREATE DATABASE " + database);
        }
        Flyway.configure().dataSource(url(database), USER, PASSWORD).load().migrate();
        return database;
    }

    Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(url(database), USER, PASSWORD);
    }

    @Override
    public void close() {
        pg.stop();
    }
}
