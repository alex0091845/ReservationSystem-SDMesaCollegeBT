CREATE TABLE user_roles (
  role_id SERIAL PRIMARY KEY,
  name TEXT UNIQUE,
  description TEXT
);

CREATE TABLE users ( 
  user_id SERIAL PRIMARY KEY,
  email TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  first_name TEXT,
  last_name TEXT,
  phone TEXT,
  role_id INT NOT NULL,
  FOREIGN KEY (role_id) REFERENCES user_roles(role_id)
);

CREATE TABLE reservations (
  reservation_id SERIAL PRIMARY KEY,
  user_id INT NOT NULL,
  check_in_date DATE NOT NULL,
  check_out_date DATE NOT NULL,
  event_type TEXT,
  description TEXT,
  FOREIGN KEY (user_id) REFERENCES users(user_id),
  CHECK(check_out_date > check_in_date)
);
