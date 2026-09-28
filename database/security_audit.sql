--1. For to check whether RLS is enabled on public tables
SELECT 
    c.relname AS table_name
    c.relrowsecurity AS rls_enabled
FROM pg_class AS c
JOIN pg_namespace AS n
    ON n.oid = c.relnamespace
WHERE n.nspname ='public'
    AND c.relkind ='r'
ORDER BY c.relname;

-- 2. Check privileges for anon and authenticated roles

SELECT
    c.relname AS table_name,
    has_table_privilege('anon', c.oid, 'SELECT') AS anon_select,
    has_table_privilege('anon', c.oid, 'INSERT') AS anon_insert,
    has_table_privilege('anon', c.oid, 'UPDATE') AS anon_update,
    has_table_privilege('anon', c.oid, 'DELETE') AS anon_delete,
    has_table_privilege('authenticated', c.oid, 'SELECT') AS authenticated_select,
    has_table_privilege('authenticated', c.oid, 'INSERT') AS authenticated_insert,
    has_table_privilege('authenticated', c.oid, 'UPDATE') AS authenticated_update,
    has_table_privilege('authenticated', c.oid, 'DELETE') AS authenticated_delete
FROM pg_class c
JOIN pg_namespace n
    ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
  AND c.relkind = 'r'
ORDER BY c.relname;


-- 3. List existing RLS policies

SELECT
    tablename,
    policyname,
    roles,
    cmd
FROM pg_policies
WHERE schemaname = 'public'
ORDER BY tablename, policyname;