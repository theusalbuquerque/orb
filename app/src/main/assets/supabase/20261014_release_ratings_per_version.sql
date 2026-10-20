-- Each edition, single and EP is a distinct release identified by album_id.
-- Personal ratings already use (user_id, album_id); no data migration needed.
-- Replace v7 release-family aggregates, which leaked the same community rating
-- into every edition and collapsed editions in artist scores.

create or replace function public.orb_community_album_rating(p_album_id text)
returns table(score double precision, ratings_count bigint)
language sql stable security definer set search_path = pg_catalog, public
as $$
    select round(avg(r.rating_tenths::numeric) / 10, 2)::double precision,
           count(*)::bigint
    from public.album_ratings r
    where (select auth.uid()) is not null and r.album_id = p_album_id;
$$;
revoke all on function public.orb_community_album_rating(text) from public, anon;
grant execute on function public.orb_community_album_rating(text) to authenticated;

-- Keep the legacy RPC signature for older app releases, but use only the
-- first requested ID (the currently opened edition) rather than its siblings.
create or replace function public.orb_community_release_versions_rating(p_album_ids text[])
returns table(score double precision, ratings_count bigint)
language sql stable security definer set search_path = pg_catalog, public
as $$
    select round(avg(r.rating_tenths::numeric) / 10, 2)::double precision,
           count(*)::bigint
    from public.album_ratings r
    where (select auth.uid()) is not null
      and r.album_id = (select nullif(btrim(u.album_id), '')
                        from unnest(p_album_ids) with ordinality u(album_id, n)
                        where nullif(btrim(u.album_id), '') is not null
                        order by u.n limit 1);
$$;
revoke all on function public.orb_community_release_versions_rating(text[]) from public, anon;
grant execute on function public.orb_community_release_versions_rating(text[]) to authenticated;

create or replace function public.orb_album_rating_averages(p_album_ids text[])
returns table(album_id text, average_tenths double precision, ratings_count bigint)
language sql stable security definer set search_path = pg_catalog, public
as $$
    select r.album_id, avg(r.rating_tenths::numeric)::double precision,
           count(*)::bigint
    from public.album_ratings r
    where (select auth.uid()) is not null
      and r.album_id = any(p_album_ids)
    group by r.album_id;
$$;
revoke all on function public.orb_album_rating_averages(text[]) from public, anon;
grant execute on function public.orb_album_rating_averages(text[]) to authenticated;

-- A user's artist score averages each release rating once; the community
-- score averages each user's artist score once, avoiding prolific-rater bias.
create or replace function public.orb_community_artist_rating(p_artist_name text)
returns table(score double precision, ratings_count bigint)
language sql stable security definer set search_path = pg_catalog, public
as $$
    with per_person as (
        select r.user_id, avg(r.rating_tenths::numeric) as artist_tenths
        from public.album_ratings r
        join public.orb_stats_album_catalog c on c.album_id = r.album_id
        where (select auth.uid()) is not null
          and lower(btrim(c.artist_name)) = lower(btrim(p_artist_name))
        group by r.user_id
    )
    select round(avg(artist_tenths) / 10, 2)::double precision,
           count(*)::bigint
    from per_person;
$$;
revoke all on function public.orb_community_artist_rating(text) from public, anon;
grant execute on function public.orb_community_artist_rating(text) to authenticated;

create or replace function public.orb_stats_global_rankings(p_days integer default 7)
returns table (
    category text, title text, subtitle text, artwork_url text,
    plays bigint, listeners bigint, score double precision, ratings_count bigint
)
language sql stable security definer set search_path = pg_catalog, public
as $$
with valid_plays as materialized (
    select a.user_id, btrim(a.video_id) as video_id, btrim(a.title) as track_title,
        btrim(a.artist) as artist_name, btrim(coalesce(a.album, '')) as album_name,
        lower(btrim(coalesce(a.language_code, ''))) as language_code,
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
languages as (
    select 'language'::text category, language_code title, null::text subtitle,
           null::text artwork_url, count(*)::bigint plays, count(distinct user_id)::bigint listeners,
           null::double precision score, 0::bigint ratings_count
    from valid_plays where language_code ~ '^[a-z]{2,3}$'
    group by language_code order by plays desc, title limit 30
),
artist_user_ratings as (
    select artist_name, user_id,
           avg(rating_tenths::numeric) as user_artist_rating_tenths,
           max(updated_at) as updated_at,
           (array_agg(artwork_url order by updated_at desc) filter(where artwork_url is not null))[1] artwork_url
    from (
        select c.artist_name, r.user_id, r.rating_tenths, c.updated_at, c.artwork_url
        from public.album_ratings r
        join public.orb_stats_album_catalog c on c.album_id = r.album_id
        where (select auth.uid()) is not null
    ) rated_editions
    group by artist_name, user_id
),
artist_ratings as (
    select 'rated_artist'::text category, aur.artist_name title, null::text subtitle,
           (array_agg(aur.artwork_url order by aur.updated_at desc) filter(where aur.artwork_url is not null))[1] artwork_url,
           0::bigint plays, count(*)::bigint listeners,
           round(avg(aur.user_artist_rating_tenths) / 10, 2)::double precision score,
           count(*)::bigint ratings_count
    from artist_user_ratings aur
    group by aur.artist_name
    order by score desc, ratings_count desc, title limit 30
)
select * from tracks
union all select * from artists
union all select * from albums
union all select * from artist_ratings
union all select * from languages;
$$;
revoke all on function public.orb_stats_global_rankings(integer) from public, anon;
grant execute on function public.orb_stats_global_rankings(integer) to authenticated;
notify pgrst, 'reload schema';
