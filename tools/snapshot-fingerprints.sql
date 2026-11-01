\set ON_ERROR_STOP on
SELECT format(
    'SELECT %L, count(*), md5(coalesce(string_agg(row_to_json(t)::text, E''\n'' ORDER BY row_to_json(t)::text COLLATE "C"), '''')) FROM %I.%I AS t;',
    tablename, schemaname, tablename
)
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY tablename COLLATE "C"
\gexec
