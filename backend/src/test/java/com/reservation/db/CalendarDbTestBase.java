package com.reservation.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.function.Executable;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Base for the database integration tests. Every test runs in its own database copied
 * from a template inside the shared PostgreSQL 16 test container, and acts as
 * {@code service_role} (the backend's PostgREST role) unless it says otherwise.
 */
@Testcontainers(disabledWithoutDocker = true)
abstract class CalendarDbTestBase {

    static final ZoneId LA = ZoneId.of("America/Los_Angeles");
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final int ADMIN = CalendarDatabase.ADMIN;
    static final int FACULTY = CalendarDatabase.FACULTY;
    static final int DISABLED_ADMIN = CalendarDatabase.DISABLED_ADMIN;
    static final int SECOND_ADMIN = CalendarDatabase.SECOND_ADMIN;

    protected String dbName;
    /** service_role connection, autocommit on. */
    protected Connection db;
    private final List<Connection> opened = new ArrayList<>();
    protected final LocalDate today = LocalDate.now(LA);

    @BeforeAll
    static void startContainer() {
        CalendarDatabase.container();
    }

    protected String template() {
        return CalendarDatabase.MIGRATED_TEMPLATE;
    }

    @BeforeEach
    void freshDatabase() throws SQLException {
        dbName = CalendarDatabase.createDatabase(template());
        db = serviceRole();
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
        CalendarDatabase.dropDatabase(dbName);
    }

    protected Connection superuser() throws SQLException {
        Connection connection = CalendarDatabase.connect(dbName);
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
            } else {
                st.setObject(i + 1, p);
            }
        }
    }

    static long scalarLong(Connection connection, String sql, Object... params) throws SQLException {
        Object value = scalar(connection, sql, params);
        return value == null ? 0 : ((Number) value).longValue();
    }

    protected long count(String sql, Object... params) throws SQLException {
        return scalarLong(db, sql, params);
    }

    static Object scalar(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement st = connection.prepareStatement(sql)) {
            bind(st, params);
            try (ResultSet rs = st.executeQuery()) {
                assertTrue(rs.next(), "no row for " + sql);
                Object value = rs.getObject(1);
                if (value instanceof Array array) {
                    return Arrays.asList((Object[]) array.getArray());
                }
                return value;
            }
        }
    }

    protected Object scalar(String sql, Object... params) throws SQLException {
        return scalar(db, sql, params);
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

    static JsonNode detail(PSQLException error) {
        ServerErrorMessage server = error.getServerErrorMessage();
        assertNotNull(server, "no server error message");
        assertNotNull(server.getDetail(), "no DETAIL on " + error.getMessage());
        return MAPPER.readTree(server.getDetail());
    }

    static List<String> texts(JsonNode array) {
        assertTrue(array.isArray(), "expected array, got " + array);
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    // ---- Calendar fixtures ---------------------------------------------------

    static Instant at(LocalDate day, int hour, int minute) {
        return ZonedDateTime.of(day, LocalTime.of(hour, minute), LA).toInstant();
    }

    protected long semester(String name, LocalDate start, LocalDate end) throws SQLException {
        return scalarLong(db,
            "INSERT INTO public.semesters (name, start_date, end_date) VALUES (?, ?::date, ?::date) RETURNING id",
            name, start, end);
    }

    /** A semester covering roughly the past month and the next ~300 days. */
    protected long currentSemester() throws SQLException {
        return semester("Current", today.minusDays(30), today.plusDays(300));
    }

    protected long closure(LocalDate day) throws SQLException {
        return closure(db, day);
    }

    static long closure(Connection connection, LocalDate day) throws SQLException {
        return scalarLong(connection,
            "INSERT INTO public.unavailable_days (unavailable_date, reason, created_by) " +
                "VALUES (?::date, 'Closed', 1) RETURNING id", day);
    }

    protected int event(LocalDate day, int startHour, int endHour) throws SQLException {
        return eventAt(at(day, startHour, 0), at(day, endHour, 0));
    }

    protected int eventAt(Instant start, Instant end) throws SQLException {
        return eventAt(db, start, end);
    }

    static int eventAt(Connection connection, Instant start, Instant end) throws SQLException {
        return (int) scalarLong(connection,
            "INSERT INTO public.events (host_user_id, start_time, end_time, event_type, description, title, " +
                "department, is_public) VALUES (2, ?::timestamptz, ?::timestamptz, 'Meeting', 'd', 'Lab', 'Math', true) " +
                "RETURNING id", start, end);
    }

    /** Row images of all events, for "nothing changed" comparisons. */
    protected String eventsSnapshot() throws SQLException {
        return text("SELECT coalesce(string_agg(e::text, '|' ORDER BY e.id), '') FROM public.events e");
    }

    /** JSON array of reservation rows for the batch helpers (PostgREST-style bulk insert). */
    static String eventsJson(Instant[][] ranges) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < ranges.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"host_user_id\":2,\"start_time\":\"").append(ranges[i][0])
                .append("\",\"end_time\":\"").append(ranges[i][1])
                .append("\",\"event_type\":\"Meeting\",\"description\":\"d\",\"title\":\"Batch ")
                .append(i).append("\",\"department\":\"Math\",\"is_public\":true,\"recurrence_group_id\":\"g1\"}");
        }
        return json.append(']').toString();
    }

    /** Inserts all rows in one statement, the way PostgREST does a bulk POST. */
    protected void bulkInsert(String eventsJson) throws SQLException {
        exec(
            "INSERT INTO public.events (host_user_id, start_time, end_time, event_type, description, title, " +
                "department, is_public, recurrence_group_id) " +
                "SELECT host_user_id, start_time, end_time, event_type, description, title, department, " +
                "is_public, recurrence_group_id FROM jsonb_populate_recordset(NULL::public.events, ?::jsonb)",
            eventsJson);
    }

    /** The local date of the next DST change in Los Angeles where the offset moves the given way. */
    static LocalDate nextDstChange(boolean fallBack) {
        Instant cursor = Instant.now();
        while (true) {
            ZoneOffsetTransition transition = LA.getRules().nextTransition(cursor);
            boolean isFallBack = transition.getOffsetAfter().getTotalSeconds()
                < transition.getOffsetBefore().getTotalSeconds();
            LocalDate day = transition.getInstant().atZone(LA).toLocalDate();
            if (isFallBack == fallBack && day.isAfter(LocalDate.now(LA))) {
                return day;
            }
            cursor = transition.getInstant().plusSeconds(1);
        }
    }
}
