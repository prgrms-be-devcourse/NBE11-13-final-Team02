\echo ===== :q
\o /dev/null
:sql;
\o
explain (analyze, buffers) :sql;
