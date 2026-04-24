
INSERT INTO user_roles (name, description)
VALUES 
  ('admin', 'Full access'),
  ('user', 'Regular user');

INSERT INTO users (email, password_hash, first_name, last_name, role_id)
SELECT 
  'ownerUser123@gmail.com',
  'hashed_admin_pw',
  'Bob',
  'Dill',
  role_id FROM user_roles WHERE name = 'admin';

INSERT INTO users (email, password_hash, first_name, last_name, role_id)
SELECT 
  'testUser123@gmail.com',
  'hashed_user_pw',
  'Dan',
  'Foo',
  role_id FROM user_roles WHERE name = 'user';

INSERT INTO reservations (user_id, start, "end", event_type, description)
SELECT 
  user_id,
  '2026-06-01 10:00:00-07',
  '2026-06-01 12:00:00-07',
  'meeting',
  'Admin meeting'
FROM users WHERE email = 'ownerUser123@gmail.com';

INSERT INTO reservations (user_id, start, "end", event_type, description)
SELECT 
  user_id,
  '2026-06-02 14:00:00-07',
  '2026-06-02 16:00:00-07',
  'event',
  'User event'
FROM users WHERE email = 'testUser123@gmail.com';

INSERT INTO rooms (room_number, capacity, floor)
VALUES (101, 4, 2);
