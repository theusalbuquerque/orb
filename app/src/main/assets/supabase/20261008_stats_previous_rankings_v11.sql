-- Stats v11 (incremental after v7): real, comparable previous-window totals.
-- Keep public.orb_stats_global_rankings unchanged for backwards compatibility.
-- Only ranked subjects from the current window are returned; previous plays
-- are calculated from the entire preceding window, not a truncated top list.
create or replace function public.orb_stats_previous_rankings(p_days integer default 7)
returns table (
    category text, title text, subtitle text, artwork_url text,
    plays bigint, listeners bigint, score double precision, ratings_count bigint
)
language sql stable security definer set search_path = pg_catalog, public
as $$
with span as (select greatest(1, least(p_days,365)) days),
valid as materialized (
    select a.user_id, btrim(a.video_id) video_id, btrim(a.title) track_title,
           btrim(a.artist) artist_name, btrim(coalesce(a.album,'')) album_name,
           a.started_at
    from public.listening_activity a cross join span s
    where (select auth.uid()) is not null
      and a.started_at >= now() - make_interval(days => s.days * 2)
      and a.started_at < now()
      and a.duration_ms > 0 and a.played_ms >= a.duration_ms::numeric * .30
      and btrim(a.artist) <> '' and btrim(a.title) <> ''
      and lower(btrim(a.artist)) not in ('unknown artist','<unknown>','unknown')
),
current_window as (
    select v.* from valid v cross join span s
    where v.started_at >= now() - make_interval(days => s.days)
),
prior_window as (
    select v.* from valid v cross join span s
    where v.started_at < now() - make_interval(days => s.days)
),
current_keys as (
    select 'song'::text category, track_title title, artist_name subtitle
    from current_window where video_id <> ''
    group by track_title, artist_name
    order by count(*) desc, track_title limit 30
),
current_artists as (
    select 'artist'::text category, artist_name title, null::text subtitle
    from current_window group by artist_name
    order by count(*) desc, artist_name limit 30
),
current_albums as (
    select 'album'::text category, album_name title, artist_name subtitle
    from current_window where album_name <> '' group by album_name, artist_name
    order by count(*) desc, album_name limit 30
),
keys as (
    select * from current_keys union all
    select * from current_artists union all
    select * from current_albums
),
previous_counts as (
    select 'song'::text category, track_title title, artist_name subtitle,
           count(*)::bigint plays, count(distinct user_id)::bigint listeners
    from prior_window where video_id <> '' group by track_title, artist_name
    union all
    select 'artist'::text, artist_name, null::text,
           count(*)::bigint, count(distinct user_id)::bigint
    from prior_window group by artist_name
    union all
    select 'album'::text, album_name, artist_name,
           count(*)::bigint, count(distinct user_id)::bigint
    from prior_window where album_name <> '' group by album_name, artist_name
)
select k.category, k.title, k.subtitle,
       null::text artwork_url, coalesce(p.plays,0)::bigint plays,
       coalesce(p.listeners,0)::bigint listeners, null::double precision score,
       0::bigint ratings_count
from keys k left join previous_counts p
  on p.category = k.category and p.title = k.title
 and p.subtitle is not distinct from k.subtitle;
$$;
revoke all on function public.orb_stats_previous_rankings(integer) from public, anon;
grant execute on function public.orb_stats_previous_rankings(integer) to authenticated;
notify pgrst, 'reload schema';
