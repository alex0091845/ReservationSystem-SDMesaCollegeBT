package com.reservation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reservation.controller.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataDemo implements CommandLineRunner {

    private final UserRoleController userRoleController;
    private final UserController userController;
    private final EventTypeController eventTypeController;
    private final ReservationController reservationController;
    private final AttendeeController attendeeController;
    private final ObjectMapper mapper = new ObjectMapper();

    public DataDemo(UserRoleController userRoleController,
                    UserController userController,
                    EventTypeController eventTypeController,
                    ReservationController reservationController,
                    AttendeeController attendeeController) {
        this.userRoleController = userRoleController;
        this.userController = userController;
        this.eventTypeController = eventTypeController;
        this.reservationController = reservationController;
        this.attendeeController = attendeeController;
    }

    private int extractId(String responseBody) {
        try {
            JsonNode node = mapper.readTree(responseBody);
            if (node.isArray() && node.size() > 0) return node.get(0).get("id").asInt();
        } catch (Exception ignored) {}
        return -1;
    }

    @Override
    public void run(String... args) {
        System.out.println("  \nSUPABASE DEMO - POST DATA\n");

        // User Roles
        System.out.println("[POST] user_roles -> " +
                userRoleController.create("""
                {"name":"admin","description":"Full access"}
                """).getBody());

        System.out.println("[POST] user_roles -> " +
                userRoleController.create("""
                {"name":"user","description":"Regular user"}
                """).getBody());

        // Users
        String user1Response = userController.create("""
                {"email":"marco.reyes@sdmesa.edu","password_hash":"hashed_pw_xK9","first_name":"Marco","last_name":"Reyes","role_id":1,"role_name":"admin"}
                """).getBody();
        System.out.println("[POST] users      -> " + user1Response);
        int user1Id = extractId(user1Response);

        String user2Response = userController.create("""
                {"email":"priya.nair@sdmesa.edu","password_hash":"hashed_pw_qL4","first_name":"Priya","last_name":"Nair","role_id":2,"role_name":"user"}
                """).getBody();
        System.out.println("[POST] users      -> " + user2Response);
        int user2Id = extractId(user2Response);

        // Event Types
        System.out.println("[POST] event_types -> " +
                eventTypeController.create("""
                {"event_type":"workshop","description":"Hands-on learning session"}
                """).getBody());

        System.out.println("[POST] event_types -> " +
                eventTypeController.create("""
                {"event_type":"study_group","description":"Collaborative student study"}
                """).getBody());

        // Events
        String event1Response = reservationController.create(String.format("""
                {"user_id":%d,"start":"2026-06-10T09:00:00-07:00","end":"2026-06-10T11:00:00-07:00","event_type":"workshop","title":"CS Workshop","description":"Intro to data structures","department":"Computer Science","is_public":true}
                """, user1Id)).getBody();
        System.out.println("[POST] events      -> " + event1Response);
        int event1Id = extractId(event1Response);

        String event2Response = reservationController.create(String.format("""
                {"user_id":%d,"start":"2026-06-12T13:00:00-07:00","end":"2026-06-12T15:00:00-07:00","event_type":"study_group","title":"Math Study Group","description":"Calculus II midterm prep","department":"Mathematics","is_public":false}
                """, user2Id)).getBody();
        System.out.println("[POST] events      -> " + event2Response);
        int event2Id = extractId(event2Response);

        // Attendees
        System.out.println("[POST] attendee    -> " +
                attendeeController.create(String.format("""
                {"check_in":"2026-06-10T09:08:00-07:00","event_id":%d,"sdccd_id":204831,"full_name":"Tariq Osman"}
                """, event1Id)).getBody());

        System.out.println("[POST] attendee    -> " +
                attendeeController.create(String.format("""
                {"check_in":"2026-06-12T13:02:00-07:00","event_id":%d,"sdccd_id":198274,"full_name":"Lena Vasquez"}
                """, event2Id)).getBody());

        // FETCH
        System.out.println("\n  SUPABASE DEMO - GET DATA\n");

        System.out.println("[GET] user_roles:\n  " + userRoleController.getAll().getBody() + "\n");
        System.out.println("[GET] users (with role):\n  " + userController.getAll().getBody() + "\n");
        System.out.println("[GET] event_types:\n  " + eventTypeController.getAll().getBody() + "\n");
        System.out.println("[GET] events (with user):\n  " + reservationController.getAll().getBody() + "\n");
        System.out.println("[GET] attendee (with event title):\n  " + attendeeController.getAll().getBody() + "\n");
    }
}
