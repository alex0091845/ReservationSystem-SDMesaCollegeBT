package com.reservation.config;

import java.beans.JavaBean;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

//A util class for converting JSON to string and returning parsed JSON
@Component
public class MockJSONParser {
    
    //JSON converted to string format
    private String mockJSON;

    //Constructor converts JSON file data (From mock_data.json) into string
    public MockJSONParser(ResourceLoader resourceLoader) {
        try {
            //Get mock_data.json and convert to string
            Resource resource = resourceLoader.getResource("classpath:mock_data.JSON");
            InputStream input = resource.getInputStream();
            mockJSON = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        }
        catch(Exception e) {
            System.out.println(e.getMessage());
            mockJSON = "404";
        }
        System.out.println("mockJSON: " + mockJSON);
    }

    //Parses mock JSON to match the endpoint's request
    public String parse(String endpoint) {
    
        //Parsed JSON. 404 by default
        String parsedJSON = "404";

        //AttendeeContoller
        if(endpoint.contains("attendee")) {
            //Get all attendees and the title of their events
            if(endpoint.contains("?select=*,events(title)")) {
                parsedJSON = 
                """
                [
                    {
                        "id": 1,
                        "full_name": "Bob Dill",
                        "student_email": "ownerUser123@gmail.com",
                        "checked_in": "2026-06-01T10:05:00Z",
                        "sdccd_id": 1234567,
                        "event_id": 101,
                        "events": {
                            "title": "Admin meeting"
                        }
                    },
                    {
                        "id": 2,
                        "full_name": "Dan Foo",
                        "student_email": "testUser123@gmail.com",
                        "checked_in": null,
                        "sdccd_id": 7654321,
                        "event_id": 102,
                        "events": {
                            "title": "User event"
                        }
                    }
                ]
                """;
            }
            //Get the mock attendee with id 1
            //OR the mock attendee with event id 101
            else if(endpoint.contains("?id=eq.1&select=*,events(title)") ||
                    endpoint.contains("?event_id=eq.101&select=*,events(title)")) {
                parsedJSON = 
                """
                [
                    {
                        "id": 1,
                        "full_name": "Bob Dill",
                        "student_email": "ownerUser123@gmail.com",
                        "checked_in": "2026-06-01T10:05:00Z",
                        "sdccd_id": 1234567,
                        "event_id": 101,
                        "events": {
                            "title": "Admin meeting"
                        }
                    }
                ]
                """;
            }
            //Get the mock attendee with id 2
             //OR the mock attendee with event id 102
            else if(endpoint.contains("?id=eq.2&select=*,events(title)") ||
                    endpoint.contains("?event_id=eq.102&select=*,events(title)")) {
                parsedJSON = 
                """
                [
                    {
                        "id": 2,
                        "full_name": "Dan Foo",
                        "student_email": "testUser123@gmail.com",
                        "checked_in": null,
                        "sdccd_id": 7654321,
                        "event_id": 102,
                        "events": {
                            "title": "User event"
                        }
                    }
                ]
                """;
            }
        }
        //User/Auth Contoller
        else if(endpoint.contains("users")) {
            //Get user with the specified gmail and their role name
            if(endpoint.contains("?email=eq.admin_host@gmail.com&select=*,user_roles(name)")) {
                parsedJSON = 
                """
                [
                    {
                        "id": 500,
                        "email": "admin_host@gmail.com",
                        "first_name": "System",
                        "last_name": "Admin",
                        "role_id": 1,
                        "role_name": "admin",
                        "user_roles": {
                            "name": "admin"
                        }
                     }
                ]
                 """;
            }
            //Get user with the specified gmail and their role name
            else if(endpoint.contains("?email=eq.coord_user@gmail.com&select=*,user_roles(name)")) {
                parsedJSON =
                """
                [
                    {
                        "id": 501,
                        "email": "coord_user@gmail.com",
                        "first_name": "Staff",
                        "last_name": "Member",
                        "role_id": 2,
                        "role_name": "user",
                        "user_roles": {
                            "name": "user"
                        }
                    }
                ]     
                """;
            }
            //Get all users and their role names
            else if(endpoint.contains("?select=*,user_roles(name)")) {
                parsedJSON =
                """
                [
                    {
                        "id": 500,
                        "email": "admin_host@gmail.com",
                        "first_name": "System",
                        "last_name": "Admin",
                        "role_id": 1,
                        "role_name": "admin",
                        "user_roles": {
                            "name": "admin"
                        }
                    },
                    {
                        "id": 501,
                        "email": "coord_user@gmail.com",
                        "first_name": "Staff",
                        "last_name": "Member",
                        "role_id": 2,
                        "role_name": "user",
                        "user_roles": {
                            "name": "user"
                        }
                    }
                ]
                """;
            }
        }
        //EventType
        else if(endpoint.contains("event_types")) {
            //Gets all event event types
            if(endpoint.contains("?select=*")) {
                parsedJSON = 
                """
                [
                    {
                        "event_type": "event",
                        "description": "An event :)"
                    },
                    {
                        "event_type": "meeting",
                        "description": "People meeting."
                    }
                ]
                """;
            }
            //Gets the event type with name event
            else if(endpoint.contains("?event_type=eq.event")) {
                parsedJSON = 
                """
                [
                    {
                        "event_type": "event",
                        "description": "An event :)"
                    }
                ]
                """;
            }
            //Gets the event type with name meeting
            else if(endpoint.contains("?event_type=eq.meeting")) {
                parsedJSON = 
                """
                [
                    {
                        "event_type": "meeting",
                        "description": "People meeting."
                    }
                ]
                """;
            }
        }
        //Reservation
        else if(endpoint.contains("events")) {
            //Gets all events and the first/last names of their users
            if(endpoint.contains("?select=*,users(first_name,last_name)")) {
                parsedJSON =
                """
                [
                    {
                        "id": 101,
                        "user_id": 500,
                        "start": "2026-06-01T10:00:00-07:00",
                        "end": "2026-06-01T12:00:00-07:00",
                        "event_type": "meeting",
                        "description": "Admin meeting",
                        "title": "Spring Planning",
                        "department": "IT",
                        "is_public": true,
                        "users": {
                            "first_name": "System",
                            "last_name": "Admin"
                        }
                    },
                    {
                        "id": 102,
                        "user_id": 501,
                        "start": "2026-06-02T14:00:00-07:00",
                        "end": "2026-06-02T16:00:00-07:00",
                        "event_type": "event",
                        "description": "User event",
                        "title": "Community Mixer",
                        "department": "Student Life",
                        "is_public": false,
                        "users": {
                            "first_name": "Staff",
                            "last_name": "Member"
                        }
                    }
                ]
                """;
            }
            //Gets the event with the id 101 and the first/last name of its users
            //OR the event with the user id 500
            //OR all events that are public
            else if(endpoint.contains("?id=eq.101&select=*,users(first_name,last_name)") ||
                    endpoint.contains("?user_id=eq.500&select=*,users(first_name,last_name)") ||
                    endpoint.contains("?is_public=eq.true&select=*,users(first_name,last_name)")
                    ) {
                parsedJSON =
                """
                [
                    {
                        "id": 101,
                        "user_id": 500,
                        "start": "2026-06-01T10:00:00-07:00",
                        "end": "2026-06-01T12:00:00-07:00",
                        "event_type": "meeting",
                        "description": "Admin meeting",
                        "title": "Spring Planning",
                        "department": "IT",
                        "is_public": true,
                        "users": {
                            "first_name": "System",
                            "last_name": "Admin"
                        }
                    }
                ]
                """;
            }
            //Gets the event with the id 102 and the first/last name of its users
            //OR the event with the user id 501
            else if(endpoint.contains("?id=eq.102&select=*,users(first_name,last_name)") ||
                    endpoint.contains("?user_id=eq.501&select=*,users(first_name,last_name)")) {
                parsedJSON =
                """
                ]
                    {
                        "id": 102,
                        "user_id": 501,
                        "start": "2026-06-02T14:00:00-07:00",
                        "end": "2026-06-02T16:00:00-07:00",
                        "event_type": "event",
                        "description": "User event",
                        "title": "Community Mixer",
                        "department": "Student Life",
                        "is_public": false,
                        "users": {
                            "first_name": "Staff",
                            "last_name": "Member"
                        }
                    }
                ]
                """;
            }
        }
        //UserRole
        else if(endpoint.contains("user_roles")) {
            //Gets all user roles
            if(endpoint.contains("user_roles?select=*")) {
               parsedJSON =
                """
               [
                    {
                        "name": "admin",
                        "description": "Administrator"
                    },
                    {
                        "name": "user",
                        "description": "Regular user"
                    }
                ]
                        
                """;
            }
            else if(endpoint.contains("user_roles?id=eq.0")) {
                //The current ERD does not include an id for each user_role
            }
            else if(endpoint.contains("user_roles?id=eq.1")) {
                //The current ERD does not include an id for each user_role
            }
        }
        return parsedJSON;
    }
}
