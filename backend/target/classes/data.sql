INSERT INTO user_roles (name, description) VALUES ('ADMIN', 'Full access');
INSERT INTO user_roles (name, description) VALUES ('STAFF', 'Manage reservations');
INSERT INTO user_roles (name, description) VALUES ('GUEST', 'Basic access');

INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id) VALUES ('admin@rooms.com', 'hash123', 'Alice', 'Admin', '555-0001', 1);
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id) VALUES ('bob@rooms.com', 'hash456', 'Bob', 'Smith', '555-0002', 2);
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id) VALUES ('carol@rooms.com', 'hash789', 'Carol', 'Jones', '555-0003', 3);

INSERT INTO rooms (room_number, current_people, floor) VALUES (101, 0, 1);
INSERT INTO rooms (room_number, current_people, floor) VALUES (102, 4, 1);
INSERT INTO rooms (room_number, current_people, floor) VALUES (201, 2, 2);
INSERT INTO rooms (room_number, current_people, floor) VALUES (202, 0, 2);

INSERT INTO reservations (user_id, start, "end", event_type, description) VALUES (2, TIMESTAMPADD(HOUR, 1, NOW()), TIMESTAMPADD(HOUR, 3, NOW()), 'MEETING', 'Weekly sync');
INSERT INTO reservations (user_id, start, "end", event_type, description) VALUES (3, TIMESTAMPADD(HOUR, 4, NOW()), TIMESTAMPADD(HOUR, 6, NOW()), 'WORKSHOP', 'Design review');
INSERT INTO reservations (user_id, start, "end", event_type, description) VALUES (2, TIMESTAMPADD(DAY, 1, NOW()), TIMESTAMPADD(DAY, 1, TIMESTAMPADD(HOUR, 2, NOW())), 'INTERVIEW', 'Candidate interview');
