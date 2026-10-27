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
 * One throwaway PostgreSQL 16 container shared by every database test class in the
 * JVM. Nothing here reads any environment variable or connects anywhere except the
 * container Testcontainers started.
 *
 * <p>Two template databases are prepared once:
 * <ul>
 *   <li>{@code tpl_baseline}: the Supabase stand-in only (pre-migration state)</li>
 *   <li>{@code tpl_migrated}: baseline + seed users + the migration under test</li>
 * </ul>
 * Each test then gets its own database copied from a template, so tests are isolated.
 */
final class CalendarDatabase {

    static final String BASELINE_TEMPLATE = "tpl_baseline";
    static final String MIGRATED_TEMPLATE = "tpl_migrated";

    static final Path BACKEND_DIR = Path.of(System.getProperty("user.dir"));
    static final Path MIGRATION = BACKEND_DIR.resolve(
        "../database/migrations/20261021_001_semesters_unavailable_days.sql").normalize();
    static final Path ROLLBACK = BACKEND_DIR.resolve(
        "../database/migrations/20261021_001_semesters_unavailable_days.rollback.sql").normalize();
    static final Path BASELINE = BACKEND_DIR.resolve("src/test/resources/db/supabase-baseline.sql");

    // Seed users shared by the tests (ids fixed by the seed below).
    static final int ADMIN = 1;
    static final int FACULTY = 2;
    static final int DISABLED_ADMIN = 3;
    static final int SECOND_ADMIN = 4;
    static final String ADMIN_EMAIL = "admin@sdccd.edu";

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

    private CalendarDatabase() {
    }

    static synchronized PostgreSQLContainer container() {
        if (container == null) {
            PostgreSQLContainer started = new PostgreSQLContainer("postgres:16")
                .withDatabaseName("calendar_admin")
                .withUsername("calendar_test")
                .withPassword("calendar_test");
            started.start();
            container = started;
            Runtime.getRuntime().addShutdownHook(new Thread(started::stop));
            prepareTemplates();
        }
        return container;
    }

    private static void prepareTemplates() {
        try (Connection admin = connect("calendar_admin"); Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + BASELINE_TEMPLATE);
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
        try (Connection db = connect(BASELINE_TEMPLATE); Statement st = db.createStatement()) {
            st.execute(read(BASELINE));
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
        try (Connection admin = connect("calendar_admin"); Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + MIGRATED_TEMPLATE + " TEMPLATE " + BASELINE_TEMPLATE);
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
        try (Connection db = connect(MIGRATED_TEMPLATE); Statement st = db.createStatement()) {
            st.execute(SEED);
            st.execute(read(MIGRATION));
        } catch (SQLException error) {
            throw new IllegalStateException(error);
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
        try (Connection admin = connect("calendar_admin"); Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + name + " TEMPLATE " + template);
        }
        return name;
    }

    static void dropDatabase(String name) {
        try (Connection admin = connect("calendar_admin"); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        } catch (SQLException ignored) {
            // The container is discarded at the end of the run anyway.
        }
    }
}
