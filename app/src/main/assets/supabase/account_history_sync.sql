-- Private cross-device account history. Safe to reapply; existing social tables unchanged.
begin;
create table if not exists public.account_history_events (
    user_id uuid not null references auth.users(id) on delete cascade,
    id text not null check (length(id) between 1 and 200),
    sequence bigint generated always as identity unique,
    kind text not null check (kind in ('search_record','search_remove','search_clear','playback')),
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    received_at timestamptz not null default now(),
    primary key (user_id, id),
    constraint history_payload_valid check (coalesce((
        (kind = 'search_clear') or
        (kind in ('search_record','search_remove') and length(trim(payload->>'query')) > 0) or
        (kind = 'playback' and payload ? 'eventId' and payload ? 'videoId' and
         payload->>'playedAt' ~ '^[0-9]{1,16}$')
    ), false))
);
create index if not exists account_history_user_kind_sequence
    on public.account_history_events (user_id, kind, sequence desc);
alter table public.account_history_events enable row level security;
drop policy if exists account_history_read_own on public.account_history_events;
create policy account_history_read_own on public.account_history_events
    for select to authenticated using ((select auth.uid()) = user_id);
drop policy if exists account_history_insert_own on public.account_history_events;
create policy account_history_insert_own on public.account_history_events
    for insert to authenticated with check ((select auth.uid()) = user_id);
revoke all on public.account_history_events from anon, authenticated;
grant select, insert on public.account_history_events to authenticated;
grant usage on sequence public.account_history_events_sequence_seq to authenticated;
-- Invoker view respects private per-account RLS, including all subqueries.
-- Deleted searches remain events so another device cannot resurrect stale cache.
create or replace view public.account_history_snapshot with (security_invoker = true) as
with clears as (
    select user_id, max(sequence) as cleared_at
    from public.account_history_events where kind = 'search_clear' group by user_id
), search_latest as (
    select e.*, row_number() over (
        partition by e.user_id, lower(trim(e.payload->>'query')) order by e.sequence desc
    ) as query_rank
    from public.account_history_events e left join clears c using (user_id)
    where e.kind in ('search_record','search_remove') and e.sequence > coalesce(c.cleared_at, 0)
), searches as (
    select *, row_number() over (partition by user_id order by sequence desc) as search_rank
    from search_latest where query_rank = 1 and kind = 'search_record'
), plays as (
    select *, row_number() over (
        partition by user_id order by (payload->>'playedAt')::bigint desc, sequence desc
    ) as play_rank from public.account_history_events where kind = 'playback'
)
select id, user_id, kind, payload, sequence from searches where search_rank <= 6
union all
select id, user_id, kind, payload, sequence from plays where play_rank <= 200;
revoke all on public.account_history_snapshot from anon, authenticated;
grant select on public.account_history_snapshot to authenticated;
commit;
