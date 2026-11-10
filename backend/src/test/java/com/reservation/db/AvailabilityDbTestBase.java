package com.reservation.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.function.Executable;
import org.postgresql.util.PSQLException;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Base for the database integration tests. Every test runs in its own database copied from a
 * template inside the shared PostgreSQL 16 test container, and acts as {@code service_role}
 * (the backend's PostgREST role) unless it says otherwise.
 */
@Testcontainers(disabledWithoutDocker = true)
abstract class AvailabilityDbTestBase {

    static final ZoneId LA = ZoneId.of("America/Los_Angeles");
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final int ADMIN = AvailabilityDatabase.ADMIN;
    static final int FACULTY = AvailabilityDatabase.FACULTY;
    static final int DISABLED_ADMIN = AvailabilityDatabase.DISABLED_ADMIN;
    static final int SECOND_ADMIN = AvailabilityDatabase.SECOND_ADMIN;

    private static final String SAVE =
        "SELECT public.save_reservation_availability(?::date, ?::date[], ?, ?::integer[], ?, ?)::text";
    private static final String SPECIAL =
        "SELECT public.create_special_reservation(?, ?, ?::jsonb)::text";

    protected String dbName;
    /** service_role connection, autocommit on. */
    protected Connection db;
    private final List<Connection> opened = new ArrayList<>();
    protected final LocalDate today = LocalDate.now(LA);
    protected final LocalDate cutoff = today.plusDays(120);
    protected final LocalDate day = today.plusDays(10);

    @BeforeAll
    static void startContainer() {
        AvailabilityDatabase.container();
    }

    protected String template() {
        return AvailabilityDatabase.NOTIFICATIONS_TEMPLATE;
    }

    @BeforeEach
    void freshDatabase() throws SQLException {
        dbName = AvailabilityDatabase.createDatabase(template());
        db = serviceRole();
        exec("UPDATE public.reservation_availability SET last_bookable_date = ?::date WHERE id = 1", cutoff);
    }

    @AfterEach
    void dropDatabase() {
        for (Connection connection : opened) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // closing anyway
            }
        }
        AvailabilityDatabase.dropDatabase(dbName);
    }

    protected Connection superuser() throws SQLException {
        Connection connection = AvailabilityDatabase.connect(dbName);
        opened.add(connection);
        return connection;
    }

    protected Connection serviceRole() throws SQLException {
        return as("service_role");
    }

    protected Connection as(String role) throws SQLException {
        Connection connection = superuser();
        exec(connection, "SET ROLE " + role);
        return connection;
    }

    // ---- SQL helpers ---------------------------------------------------------

    static void exec(Connection connection, String sql, Object... params) throws SQLException {
        if (params.length == 0) {
            try (Statement st = connection.createStatement()) {
                st.execute(sql);
            }
            return;
        }
        try (PreparedStatement st = connection.prepareStatement(sql)) {
            bind(st, params);
            st.execute();
        }
    }

    protected void exec(String sql, Object... params) throws SQLException {
        exec(db, sql, params);
    }

    static void bind(PreparedStatement st, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof LocalDate || p instanceof Instant) {
                st.setString(i + 1, p.toString());
            } else if (p == null) {
                st.setNull(i + 1, java.sql.Types.VARCHAR);
            } else {
                st.setObject(i + 1, p);
            }
        }
    }

    static Object scalar(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement st = connection.prepareStatement(sql)) {
            bind(st, params);
            try (ResultSet rs = st.executeQuery()) {
                assertTrue(rs.next(), "no row for " + sql);
                return rs.getObject(1);
            }
        }
    }

    protected long count(String sql, Object... params) throws SQLException {
        Object value = scalar(db, sql, params);
        return value == null ? 0 : ((Number) value).longValue();
    }

    protected String text(String sql, Object... params) throws SQLException {
        Object value = scalar(db, sql, params);
        return value == null ? null : value.toString();
    }

    /** Runs the action and asserts it fails with the given SQLSTATE. */
    static PSQLException fails(String sqlState, Executable action) {
        PSQLException error = assertThrows(PSQLException.class, action);
        assertEquals(sqlState, error.getSQLState(), "unexpected error: " + error.getMessage());
        return error;
    }

    static List<Map<String, Object>> rows(Connection connection, String sql, Object... params) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement st = connection.prepareStatement(sql)) {
            bind(st, params);
            try (ResultSet rs = st.executeQuery()) {
                ResultSetMetaData columns = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columns.getColumnCount(); i++) {
                        row.put(columns.getColumnName(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    // ---- Calendar fixtures ---------------------------------------------------

    static Instant at(LocalDate date, int hour, int minute) {
        return ZonedDateTime.of(date, LocalTime.of(hour, minute), LA).toInstant();
    }

    protected int event(LocalDate date, int startHour, int endHour) throws SQLException {
        return event(db, FACULTY, date, startHour, endHour);
    }

    static int event(Connection connection, int host, LocalDate date, int startHour, int endHour) throws SQLException {
        return ((Number) scalar(connection,
            "INSERT INTO public.events (host_user_id, start_time, end_time, event_type, description, title, " +
                "department, is_public) VALUES (?, ?::timestamptz, ?::timestamptz, 'Meeting', 'Weekly lab', 'Lab', " +
                "'Math', true) RETURNING id", host, at(date, startHour, 0), at(date, endHour, 0))).intValue();
    }

    protected long version() throws SQLException {
        return count("SELECT version FROM public.reservation_availability WHERE id = 1");
    }

    protected String dates(LocalDate... dates) {
        return Arrays.stream(dates).map(LocalDate::toString).collect(Collectors.joining(",", "{", "}"));
    }

    protected String ids(int... ids) {
        return Arrays.stream(ids).mapToObj(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }

    protected JsonNode save(LocalDate lastDate, String invalidDates, long version, String confirmed, int actor, String reason)
        throws SQLException {
        return MAPPER.readTree((String) scalar(db, SAVE, lastDate, invalidDates, version, confirmed, actor, reason));
    }

    /** Saves with the current version as the admin, keeping the cutoff. */
    protected JsonNode close(String confirmed, LocalDate... closedDates) throws SQLException {
        return save(cutoff, dates(closedDates), version(), confirmed, ADMIN, null);
    }

    protected String eventJson(LocalDate date, int startHour, int endHour) {
        return eventJson(FACULTY, at(date, startHour, 0), at(date, endHour, 0));
    }

    protected String eventJson(int host, Instant start, Instant end) {
        return MAPPER.createObjectNode().put("host_user_id", host).put("start_time", start.toString())
            .put("end_time", end.toString()).put("event_type", "Meeting").put("description", "Board meeting")
            .put("title", "Special").put("department", "Math").put("is_public", false).toString();
    }

    protected JsonNode special(int actor, String reason, String eventJson) throws SQLException {
        return MAPPER.readTree((String) scalar(db, SPECIAL, actor, reason, eventJson));
    }

    protected int special(LocalDate date, int startHour, int endHour) throws SQLException {
        return special(ADMIN, "Accreditation visit", eventJson(date, startHour, endHour))
            .path("reservation").path("id").asInt();
    }

    protected boolean eventExists(int id) throws SQLException {
        return count("SELECT count(*) FROM public.events WHERE id = ?", id) == 1;
    }

    /** Row images of all events, for "nothing changed" comparisons. */
    protected String eventsSnapshot() throws SQLException {
        return text("SELECT coalesce(string_agg(e::text, '|' ORDER BY e.id), '') FROM public.events e");
    }
}
