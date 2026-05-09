CREATE TABLE public.attendee (
  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
  check_in_time timestamp with time zone NOT NULL DEFAULT now(),
  event_id integer,
  sdccd_id integer,
  first_name text,
  last_name text,
  email text,
  CONSTRAINT attendee_pkey PRIMARY KEY (id),
  CONSTRAINT attendee_event_id_fkey FOREIGN KEY (event_id) REFERENCES public.events(id)
);
CREATE TABLE public.event_types (
  event_type text NOT NULL,
  description text,
  CONSTRAINT event_types_pkey PRIMARY KEY (event_type)
);
CREATE TABLE public.events (
  id integer NOT NULL DEFAULT nextval('reservations_reservation_id_seq'::regclass),
  host_user_id integer NOT NULL,
  start_time timestamp with time zone NOT NULL,
  end_time timestamp with time zone NOT NULL,
  event_type text,
  description text,
  title text,
  department text,
  is_public boolean,
  CONSTRAINT events_pkey PRIMARY KEY (id),
  CONSTRAINT reservations_user_id_fkey FOREIGN KEY (host_user_id) REFERENCES public.users(id),
  CONSTRAINT events_event_type_fkey FOREIGN KEY (event_type) REFERENCES public.event_types(event_type)
);
CREATE TABLE public.user_roles (
  name text NOT NULL UNIQUE,
  description text,
  CONSTRAINT user_roles_pkey PRIMARY KEY (name)
);
CREATE TABLE public.users (
  id integer NOT NULL DEFAULT nextval('users_user_id_seq'::regclass),
  email text NOT NULL UNIQUE,
  password_hash text NOT NULL,
  first_name text,
  last_name text,
  phone text,
  role_name text,
  CONSTRAINT users_pkey PRIMARY KEY (id),
  CONSTRAINT users_role_name_fkey FOREIGN KEY (role_name) REFERENCES public.user_roles(name)
);