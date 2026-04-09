CREATE TABLE user_roles (
  role_id SERIAL PRIMARY KEY,
  name TEXT UNIQUE,
  description TEXT
);

CREATE TABLE users ( 
  user_id SERIAL PRIMARY KEY,
  email TEXT UNIQUE,
  password_hash TEXT,
  first_name TEXT,
  last_name TEXT,
  phone TEXT,
  role_id INT,
  FOREIGN KEY (role_id) REFERENCES user_roles(role_id)
);

CREATE TABLE reservations (
  reservation_id SERIAL PRIMARY KEY,
  user_id INT,
  check_in_date DATE,
  check_out_date DATE,
  event_type TEXT,
  description TEXT,
  FOREIGN KEY (user_id) REFERENCES users(user_id)
);