-- Orb Stats / Geral. Apply in the Supabase SQL editor before enabling charts.
-- Public charts return AGGREGATES only: no account IDs, per-user rows or private ratings.
-- Community charts expose aggregated data only; no per-user rows are returned.

create index if not exists orb_stats_activity_started_artist_idx
    on public.listening_activity (started_at desc, artist);

-- Attach catalogue identities to ratings. These labels are metadata, not votes.
create table if not exists public.orb_stats_album_catalog (
    album_id text primary key,
    album_title text not null,
    artist_name text not null,
    artwork_url text,
    updated_at timestamptz not null default now(),
    constraint orb_stats_album_catalog_id_nonempty check(length(btrim(album_id)) between 1 and 512),
    constraint orb_stats_album_catalog_title_nonempty check(length(btrim(album_title)) between 1 and 300),
    constraint orb_stats_album_catalog_artist_nonempty check(length(btrim(artist_name)) between 1 and 300)
);
alter table public.orb_stats_album_catalog enable row level security;
revoke all on public.orb_stats_album_catalog from public, anon, authenticated;

create or replace function public.orb_stats_register_rated_album(
    p_album_id text, p_album_title text, p_artist_name text, p_artwork_url text default null
) returns void
language plpgsql security definer set search_path = pg_catalog, public
as $$
begin
    if auth.uid() is null or not exists (
        select 1 from public.album_ratings r
        where r.user_id = auth.uid() and r.album_id = p_album_id
    ) then
        raise exception 'Not allowed to register an unrated album';
    end if;
    if length(btrim(coalesce(p_album_id, ''))) not between 1 and 512
       or length(btrim(coalesce(p_album_title, ''))) not between 1 and 300
       or length(btrim(coalesce(p_artist_name, ''))) not between 1 and 300 then
        raise exception 'Invalid album metadata';
    end if;
    insert into public.orb_stats_album_catalog (album_id, album_title, artist_name, artwork_url)
    values (
        btrim(p_album_id), btrim(p_album_title), btrim(p_artist_name),
        case when p_artwork_url ~* '^https?://' then left(p_artwork_url, 2048) else null end
    )
    on conflict (album_id) do update
    set album_title = excluded.album_title, artist_name = excluded.artist_name,
        artwork_url = coalesce(excluded.artwork_url, public.orb_stats_album_catalog.artwork_url),
        updated_at = now();
end;
$$;
revoke all on function public.orb_stats_register_rated_album(text, text, text, text) from public, anon;
grant execute on function public.orb_stats_register_rated_album(text, text, text, text) to authenticated;

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
