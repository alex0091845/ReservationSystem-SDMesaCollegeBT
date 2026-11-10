package com.reservation.db;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One throwaway PostgreSQL 16 container shared by every database test class in the JVM.
 * Nothing here reads any environment variable or connects anywhere except the container
 * Testcontainers started.
 *
 * <p>One template database is prepared once: the Supabase stand-in, seed users, then
 * reservation_availability.sql and admin_override_notifications.sql in their deploy order.
 * Each test then gets its own database copied from it, so tests are isolated.
 */
final class AvailabilityDatabase {

    static final String NOTIFICATIONS_TEMPLATE = "tpl_notifications";

    static final Path BACKEND_DIR = Path.of(System.getProperty("user.dir"));
    static final Path BASELINE = BACKEND_DIR.resolve("src/test/resources/db/supabase-baseline.sql");
    static final Path AVAILABILITY = BACKEND_DIR.resolve("sql/reservation_availability.sql");
    static final Path NOTIFICATIONS = BACKEND_DIR.resolve("sql/admin_override_notifications.sql");
    static final Path NOTIFICATIONS_ROLLBACK = BACKEND_DIR.resolve("sql/admin_override_notifications.rollback.sql");

    // Seed users shared by the tests (ids fixed by the seed below).
    static final int ADMIN = 1;
    static final int FACULTY = 2;
    static final int DISABLED_ADMIN = 3;
    static final int SECOND_ADMIN = 4;

    static final String SEED = """
        INSERT INTO public.user_roles (name, description) VALUES ('admin', 'Admin'), ('faculty', 'Faculty');
        INSERT INTO public.users (id, email, password_hash, first_name, last_name, role_name, enabled) VALUES
          (1, 'admin@sdccd.edu', '$2a$10$x', 'Ada', 'Admin', 'admin', true),
          (2, 'faculty@sdccd.edu', '$2a$10$x', 'Fay', 'Faculty', 'faculty', true),
          (3, 'disabled-admin@sdccd.edu', '$2a$10$x', 'Dee', 'Disabled', 'admin', false),
          (4, 'second-admin@sdccd.edu', '$2a$10$x', 'Sam', 'Second', 'admin', true);
        SELECT setval('public.users_user_id_seq', 100);
        INSERT INTO public.event_types (event_type, description) VALUES ('Meeting', 'Meeting');
        """;

    private static PostgreSQLContainer container;
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private AvailabilityDatabase() {
    }

    static synchronized PostgreSQLContainer container() {
        if (container == null) {
            PostgreSQLContainer started = new PostgreSQLContainer("postgres:16")
                .withDatabaseName("availability_admin")
                .withUsername("availability_test")
                .withPassword("availability_test");
            started.start();
            container = started;
            Runtime.getRuntime().addShutdownHook(new Thread(started::stop));
            prepareTemplate();
        }
        return container;
    }

    private static void prepareTemplate() {
        try {
            createFrom(NOTIFICATIONS_TEMPLATE, "template0");
            try (Connection db = connect(NOTIFICATIONS_TEMPLATE); Statement st = db.createStatement()) {
                st.execute(read(BASELINE));
                st.execute(SEED);
                st.execute(read(AVAILABILITY));
                st.execute(read(NOTIFICATIONS));
            }
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void createFrom(String name, String template) throws SQLException {
        try (Connection admin = connect("availability_admin"); Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + name + " TEMPLATE " + template);
        }
    }

    static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read " + file, error);
        }
    }

    /** Superuser connection to a database inside the test container only. */
    static Connection connect(String database) throws SQLException {
        PostgreSQLContainer pg = container;
        String url = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + database;
        return DriverManager.getConnection(url, pg.getUsername(), pg.getPassword());
    }

    static String createDatabase(String template) throws SQLException {
        String name = "t_" + COUNTER.incrementAndGet() + "_" + ProcessHandle.current().pid();
        createFrom(name, template);
        return name;
    }

    static void dropDatabase(String name) {
        try (Connection admin = connect("availability_admin"); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        } catch (SQLException ignored) {
            // The container is discarded at the end of the run anyway.
        }
    }
}
