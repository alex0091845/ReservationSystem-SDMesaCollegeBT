-- Test-only stand-in for the Supabase project, used by the database
-- integration tests. It recreates the platform roles the migrations grant to,
-- Supabase's default privileges for new objects, and the tables that existed
-- before migration 20261021_001 (from erd.sql, reordered so foreign keys resolve).
-- Never run this against a real Supabase project.

DO $roles$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
    CREATE ROLE anon NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
    CREATE ROLE authenticated NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'service_role') THEN
    CREATE ROLE service_role NOLOGIN BYPASSRLS;
  END IF;
END
$roles$;

GRANT USAGE ON SCHEMA public TO anon, authenticated, service_role;

-- Supabase grants every new table, sequence, and function in public to these
-- roles by default. Migrations must revoke what anon/authenticated should not have.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO anon, authenticated, service_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO anon, authenticated, service_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON FUNCTIONS TO anon, authenticated, service_role;

CREATE SEQUENCE public.users_user_id_seq;
CREATE SEQUENCE public.reservations_reservation_id_seq;
CREATE SEQUENCE public.sessions_id_seq;

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
  enabled boolean NOT NULL DEFAULT true,
  CONSTRAINT users_pkey PRIMARY KEY (id),
  CONSTRAINT users_role_name_fkey FOREIGN KEY (role_name) REFERENCES public.user_roles(name)
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
  recurrence_group_id text,
  CONSTRAINT events_pkey PRIMARY KEY (id),
  CONSTRAINT reservations_user_id_fkey FOREIGN KEY (host_user_id) REFERENCES public.users(id),
  CONSTRAINT events_event_type_fkey FOREIGN KEY (event_type) REFERENCES public.event_types(event_type)
);
CREATE TABLE public.attendees (
  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
  check_in_time timestamp with time zone NOT NULL DEFAULT now(),
  event_id integer,
  sdccd_id integer,
  first_name text,
  last_name text,
  email text,
  CONSTRAINT attendees_pkey PRIMARY KEY (id),
  CONSTRAINT attendee_event_id_fkey FOREIGN KEY (event_id) REFERENCES public.events(id)
);
CREATE TABLE public.sessions (
  id bigint NOT NULL DEFAULT nextval('sessions_id_seq'::regclass),
  session_id text NOT NULL UNIQUE,
  user_id integer NOT NULL,
  expires_at timestamp with time zone NOT NULL,
  created_at timestamp with time zone NOT NULL DEFAULT now(),
  last_seen_at timestamp with time zone,
  invalidated_at timestamp with time zone,
  CONSTRAINT sessions_pkey PRIMARY KEY (id),
  CONSTRAINT sessions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id)
);
CREATE TABLE public.reservation_drafts (
  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
  user_id integer NOT NULL,
  host_user_id integer,
  source_event_id integer,
  draft_type text NOT NULL,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamp with time zone NOT NULL DEFAULT now(),
  updated_at timestamp with time zone NOT NULL DEFAULT now(),
  discarded_at timestamp with time zone,
  CONSTRAINT reservation_drafts_pkey PRIMARY KEY (id),
  CONSTRAINT reservation_drafts_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE,
  CONSTRAINT reservation_drafts_host_user_id_fkey FOREIGN KEY (host_user_id) REFERENCES public.users(id) ON DELETE SET NULL,
  CONSTRAINT reservation_drafts_source_event_id_fkey FOREIGN KEY (source_event_id) REFERENCES public.events(id) ON DELETE CASCADE,
  CONSTRAINT reservation_drafts_type_check CHECK (draft_type IN ('create', 'edit'))
);

-- The state after the security hardening migration: only the backend role
-- can reach the existing tables.
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM anon, authenticated;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM anon, authenticated;
GRANT ALL ON ALL TABLES IN SCHEMA public TO service_role;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO service_role;
