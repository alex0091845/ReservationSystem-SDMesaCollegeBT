CREATE TABLE user_roles (
  id SERIAL PRIMARY KEY,
  name TEXT UNIQUE NOT NULL,
  description TEXT
);

CREATE TABLE users (
  id SERIAL PRIMARY KEY,
  email TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  first_name TEXT,
  last_name TEXT,
  phone TEXT,
  role_id INT REFERENCES user_roles(id)
);

CREATE TABLE event_types (
  event_type TEXT PRIMARY KEY,
  description TEXT
);

CREATE TABLE events (
  id SERIAL PRIMARY KEY,
  user_id INT NOT NULL REFERENCES users(id),
  start TIMESTAMPTZ NOT NULL,
  "end" TIMESTAMPTZ NOT NULL,
  event_type TEXT REFERENCES event_types(event_type),
  description TEXT,
  title TEXT,
  is_public BOOLEAN DEFAULT false,
  CHECK ("end" > start)
);

CREATE TABLE attendee (
  id BIGSERIAL PRIMARY KEY,
  check_in TIMESTAMPTZ,
  event_id INT REFERENCES events(id),
  sdccd_id INT,
  full_name TEXT
);
