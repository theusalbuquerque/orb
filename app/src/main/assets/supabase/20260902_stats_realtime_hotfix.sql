-- Orb Stats Realtime hotfix — safe to run more than once.
-- Ensures the two tables that drive the live social feed actually emit
-- Postgres Changes to authenticated clients.

alter table if exists public.now_playing replica identity full;
alter table if exists public.listening_activity replica identity full;

do $$
begin
    if to_regclass('public.now_playing') is not null and not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'now_playing'
    ) then
        alter publication supabase_realtime add table public.now_playing;
    end if;

    if to_regclass('public.listening_activity') is not null and not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'listening_activity'
    ) then
        alter publication supabase_realtime add table public.listening_activity;
    end if;
end $$;

-- Optional diagnostic result: both rows should return published = true.
select
    wanted.table_name,
    exists (
        select 1
        from pg_publication_tables p
        where p.pubname = 'supabase_realtime'
          and p.schemaname = 'public'
          and p.tablename = wanted.table_name
    ) as published
from (values ('now_playing'), ('listening_activity')) as wanted(table_name);
