-- Orb Stats v7: the same album's standard/deluxe/expanded/remastered editions
-- contribute to ONE community release rating. Apply AFTER Stats v6.
-- No votes are rewritten. One person's several versions of an album count as
-- one rater for that album and contribute their average, not multiple votes.
-- Artist score: mean across release families for each listener, then mean of
-- all listeners; each listener counts once in the artist's rating count.
-- Version matching is CONSERVATIVE: same credited artist and title after
-- removing edition qualifiers. IDs of differently named official versions are
-- also resolved by the client when YouTube's Other versions shelf provides them.
-- Previously saved ratings enter as soon as their album IDs have metadata in
-- orb_stats_album_catalog (the app backfills that catalog in the background).

create or replace function public.orb_stats_release_family_key(p_title text)
returns text
language sql immutable parallel safe
set search_path = pg_catalog, public
as $$
    select btrim(regexp_replace(
        regexp_replace(
            regexp_replace(
                regexp_replace(lower(btrim(coalesce(p_title, ''))),
                    '[[:space:]]*(\([^)]*(deluxe|expanded|extended|special edition|anniversary|super deluxe|collector.?s? edition|digital deluxe|bonus tracks?|remaster(ed)?|ultimate edition|legacy edition|platinum edition|tour edition|complete edition|edicao deluxe|edicion deluxe|edicao especial|edicion especial)[^)]*\)|\[[^]]*(deluxe|expanded|extended|special edition|anniversary|super deluxe|collector.?s? edition|digital deluxe|bonus tracks?|remaster(ed)?|ultimate edition|legacy edition|platinum edition|tour edition|complete edition|edicao deluxe|edicion deluxe|edicao especial|edicion especial)[^]]*\])', ' ', 'gi'),
                '[[:space:]]*[-–—:][[:space:]]*(deluxe|expanded|extended|special edition|anniversary|super deluxe|digital deluxe|remaster(ed)?|ultimate edition|legacy edition|platinum edition|tour edition|complete edition|edicao deluxe|edicion deluxe|edicao especial|edicion especial).*$', '', 'gi'),
            '[[:space:]]+(deluxe|expanded|extended|remaster(ed)?|super deluxe|digital deluxe)$', '', 'gi'),
        '[[:space:]]+', ' ', 'g'));
$$;
revoke all on function public.orb_stats_release_family_key(text) from public, anon;
grant execute on function public.orb_stats_release_family_key(text) to authenticated;

-- For a release page, include ratings for every registered edition in that
-- release family (and the exact ID even if metadata is not registered yet).
create or replace function public.orb_community_album_rating(p_album_id text)
returns table (score double precision, ratings_count bigint)
language sql stable security definer set search_path = pg_catalog, public
as $$
with target as (
    select lower(btrim(artist_name)) artist_key,
           public.orb_stats_release_family_key(album_title) album_key
    from public.orb_stats_album_catalog
    where album_id = p_album_id
),
per_person as (
    select r.user_id, avg(r.rating_tenths::numeric) average_tenths
    from public.album_ratings r
    left join public.orb_stats_album_catalog c on c.album_id = r.album_id
    where (select auth.uid()) is not null and (
        r.album_id = p_album_id or exists (
            select 1 from target t
            where lower(btrim(c.artist_name)) = t.artist_key
              and public.orb_stats_release_family_key(c.album_title) = t.album_key
        )
    )
    group by r.user_id
)
select round(avg(average_tenths) / 10, 2)::double precision,
       count(*)::bigint
from per_person;
$$;
revoke all on function public.orb_community_album_rating(text) from public, anon;
grant execute on function public.orb_community_album_rating(text) to authenticated;

-- When the catalogue's official Other versions shelf supplies sibling IDs,
-- combine them as one release even if an old sibling has not yet had its
-- metadata backfilled. Count each listener once across those versions.
create or replace function public.orb_community_release_versions_rating(p_album_ids text[])
returns table(score double precision, ratings_count bigint)
language sql stable security definer set search_path = pg_catalog, public
as $$
with requested_ids as (
    select distinct btrim(u.album_id) as album_id
    from unnest(coalesce(p_album_ids, array[]::text[])) u(album_id)
    where u.album_id is not null and btrim(u.album_id) <> ''
),
requested_families as (
    select distinct lower(btrim(c.artist_name)) as artist_key,
           public.orb_stats_release_family_key(c.album_title) as album_key
    from public.orb_stats_album_catalog c
    join requested_ids i on i.album_id = c.album_id
),
per_person as (
    select r.user_id, avg(r.rating_tenths::numeric) as mean_tenths
    from public.album_ratings r
    left join public.orb_stats_album_catalog c on c.album_id = r.album_id
    where (select auth.uid()) is not null
      and (exists (select 1 from requested_ids i where i.album_id = r.album_id)
        or exists (
            select 1 from requested_families f
            where lower(btrim(c.artist_name)) = f.artist_key
              and public.orb_stats_release_family_key(c.album_title) = f.album_key
        ))
    group by r.user_id
)
select round(avg(mean_tenths) / 10, 2)::double precision,
       count(*)::bigint from per_person;
$$;
revoke all on function public.orb_community_release_versions_rating(text[]) from public, anon;
grant execute on function public.orb_community_release_versions_rating(text[]) to authenticated;

-- Existing cards request several album IDs in one call. Return one row per
-- actual release family rather than one row per edition, avoiding double
-- weighting when canonical and deluxe IDs appear in the same request.
create or replace function public.orb_album_rating_averages(p_album_ids text[])
returns table(album_id text, average_tenths double precision, ratings_count bigint)
language sql stable security definer set search_path = pg_catalog, public
as $$
with requested as (
    select distinct u.album_id,
           coalesce(lower(btrim(c.artist_name)) || chr(31) || public.orb_stats_release_family_key(c.album_title),
                    u.album_id) as family_key
    from unnest(p_album_ids) u(album_id)
    left join public.orb_stats_album_catalog c on c.album_id = u.album_id
    where u.album_id is not null and length(btrim(u.album_id)) > 0
),
families as (
    select family_key, min(album_id) as representative_album_id
    from requested group by family_key
),
rated as (
    select r.user_id, r.rating_tenths,
           coalesce(lower(btrim(c.artist_name)) || chr(31) || public.orb_stats_release_family_key(c.album_title),
                    r.album_id) as family_key
    from public.album_ratings r
    left join public.orb_stats_album_catalog c on c.album_id = r.album_id
    where (select auth.uid()) is not null
),
per_person as (
    select f.representative_album_id, r.user_id,
           avg(r.rating_tenths::numeric) as average_tenths
    from families f join rated r on r.family_key = f.family_key
    group by f.representative_album_id, r.user_id
)
select representative_album_id, avg(average_tenths)::double precision,
       count(*)::bigint
from per_person
 group by representative_album_id;
$$;
revoke all on function public.orb_album_rating_averages(text[]) from public, anon;
grant execute on function public.orb_album_rating_averages(text[]) to authenticated;

-- Release family is the unit between each account's ratings and an artist's
-- score. Without this layer, rating both standard and deluxe would over-weight
-- that single album compared with other releases.
create or replace function public.orb_community_artist_rating(p_artist_name text)
returns table (score double precision, ratings_count bigint)
language sql stable security definer set search_path = pg_catalog, public
as $$
with per_album_family as (
    select r.user_id, public.orb_stats_release_family_key(c.album_title) as family_key,
           avg(r.rating_tenths::numeric) as edition_mean_tenths
    from public.album_ratings r
    join public.orb_stats_album_catalog c on c.album_id = r.album_id
    where (select auth.uid()) is not null
      and lower(btrim(c.artist_name)) = lower(btrim(p_artist_name))
    group by r.user_id, public.orb_stats_release_family_key(c.album_title)
),
per_person as (
    select user_id, avg(edition_mean_tenths) as artist_tenths
    from per_album_family group by user_id
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
artist_family_ratings as (
    select c.artist_name, r.user_id,
           public.orb_stats_release_family_key(c.album_title) as family_key,
           avg(r.rating_tenths::numeric) as family_rating_tenths,
           max(c.updated_at) as updated_at,
           (array_agg(c.artwork_url order by c.updated_at desc) filter(where c.artwork_url is not null))[1] artwork_url
    from public.album_ratings r
    join public.orb_stats_album_catalog c on c.album_id = r.album_id
    where (select auth.uid()) is not null
    group by c.artist_name, r.user_id, public.orb_stats_release_family_key(c.album_title)
),
artist_user_ratings as (
    select artist_name, user_id,
           avg(family_rating_tenths) as user_artist_rating_tenths,
           max(updated_at) as updated_at,
           (array_agg(artwork_url order by updated_at desc) filter(where artwork_url is not null))[1] artwork_url
    from artist_family_ratings
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
