-- Orb Stats / Geral follow-up. Apply after 20261008_stats_global_rankings.sql.
-- It removes the minimum-participant gate, filters placeholder unknown artists,
-- and expands community rankings from 20 to 30 positions.

create or replace function public.orb_stats_global_rankings(p_days integer default 7)
returns table (
    category text, title text, subtitle text, artwork_url text,
    plays bigint, listeners bigint, score double precision, ratings_count bigint
)
language sql stable security definer set search_path = pg_catalog, public
as $$
with
valid_plays as materialized (
    select a.user_id, btrim(a.video_id) as video_id, btrim(a.title) as track_title,
        btrim(a.artist) as artist_name, btrim(coalesce(a.album, '')) as album_name,
        a.artwork_url, a.started_at
    from public.listening_activity a
    where (select auth.uid()) is not null
      and a.started_at >= now() - make_interval(days => greatest(1, least(p_days, 365)))
      and a.duration_ms > 0 and a.played_ms >= a.duration_ms::numeric * 0.30
      and length(btrim(a.artist)) > 0 and length(btrim(a.title)) > 0
      and lower(btrim(a.artist)) not in ('unknown artist', '<unknown>', 'unknown')
),
tracks as (
    select 'song'::text category, track_title title, artist_name subtitle,
           (array_agg(artwork_url order by started_at desc) filter(where artwork_url is not null))[1] artwork_url,
           count(*)::bigint plays, count(distinct user_id)::bigint listeners, null::double precision score, 0::bigint ratings_count
    from valid_plays where video_id <> '' group by video_id, track_title, artist_name
    order by plays desc, title limit 30
),
artists as (
    select 'artist'::text category, artist_name title, null::text subtitle,
           (array_agg(artwork_url order by started_at desc) filter(where artwork_url is not null))[1] artwork_url,
           count(*)::bigint plays, count(distinct user_id)::bigint listeners, null::double precision score, 0::bigint ratings_count
    from valid_plays group by artist_name
    order by plays desc, title limit 30
),
albums as (
    select 'album'::text category, album_name title, artist_name subtitle,
           (array_agg(artwork_url order by started_at desc) filter(where artwork_url is not null))[1] artwork_url,
           count(*)::bigint plays, count(distinct user_id)::bigint listeners, null::double precision score, 0::bigint ratings_count
    from valid_plays where album_name <> '' group by album_name, artist_name
    order by plays desc, title limit 30
),
artist_ratings as (
    select 'rated_artist'::text category, c.artist_name title, null::text subtitle,
           (array_agg(c.artwork_url order by c.updated_at desc) filter(where c.artwork_url is not null))[1] artwork_url,
           0::bigint plays, count(distinct r.user_id)::bigint listeners,
           round(avg(r.rating_tenths::numeric) / 10, 2)::double precision score,
           count(*)::bigint ratings_count
    from public.album_ratings r
    join public.orb_stats_album_catalog c on c.album_id = r.album_id
    where (select auth.uid()) is not null and r.is_public
    group by c.artist_name
    order by score desc, ratings_count desc, title limit 30
)
select * from tracks
union all select * from artists
union all select * from albums
union all select * from artist_ratings;
$$;
revoke all on function public.orb_stats_global_rankings(integer) from public, anon;
grant execute on function public.orb_stats_global_rankings(integer) to authenticated;
notify pgrst, 'reload schema';
