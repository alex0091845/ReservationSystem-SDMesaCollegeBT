CREATE TABLE `Reservation` (
  `PK reservation_id` int,
  `FK user_id` int,
  `check_in_date` date,
  `check_out_date` date,
  `event_type` string,
  `description` string
);

CREATE TABLE `UserRole` (
  `PK role_id` int,
  `name "UNIQUE"` string,
  `description` string
);

CREATE TABLE `User` (
  `PK user_id` int,
  `email "UNIQUE"` string,
  `password_hash` string,
  `first_name` string,
  `last_name` string,
  `phone` string,
  `FK role_id` int
);

