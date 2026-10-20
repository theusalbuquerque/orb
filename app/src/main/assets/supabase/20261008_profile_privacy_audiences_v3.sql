-- Orb profile privacy audiences v3
--
-- Unifies Edit Profile privacy under one Everyone / Followers / Nobody model:
--   * listening + now playing
--   * favorite artists / albums / songs
--   * recent ratings
--   * following / followers / total plays
--
-- Recent-rating privacy is profile-display privacy only. Aggregate catalogue
-- scores stay available through an aggregate-only RPC that does not expose who
-- rated an album or what an individual user's score was.

alter table public.profiles
    add column if not exists recent_ratings_visibility text;

-- Preserve the previous public/private album-rating preference where possible.
update public.profiles p
set recent_ratings_visibility = case
    when exists (
        select 1
        from public.album_ratings r
        where r.user_id = p.id and r.is_public
    ) then 'everyone'
    else 'nobody'
end
where recent_ratings_visibility is null;

alter table public.profiles
    alter column recent_ratings_visibility set default 'nobody',
    alter column recent_ratings_visibility set not null;

-- Listening used to be Followers/Nobody only. It now uses the same audience
-- vocabulary as every other profile-sharing control.
update public.profiles
set listening_visibility = case lower(coalesce(listening_visibility, 'followers'))
        when 'everyone' then 'everyone'
        when 'public' then 'everyone'
        when 'anyone' then 'everyone'
        when 'followers' then 'followers'
        when 'mutuals' then 'followers'
        when 'friends' then 'followers'
        when 'following' then 'followers'
        when 'nobody' then 'nobody'
        when 'private' then 'nobody'
        when 'none' then 'nobody'
        else 'followers'
    end,
    now_playing_visibility = case lower(coalesce(now_playing_visibility, 'followers'))
        when 'everyone' then 'everyone'
        when 'public' then 'everyone'
        when 'anyone' then 'everyone'
        when 'followers' then 'followers'
        when 'mutuals' then 'followers'
        when 'friends' then 'followers'
        when 'following' then 'followers'
        when 'nobody' then 'nobody'
        when 'private' then 'nobody'
        when 'none' then 'nobody'
        else 'followers'
    end,
    recent_ratings_visibility = case lower(coalesce(recent_ratings_visibility, 'nobody'))
        when 'everyone' then 'everyone'
        when 'public' then 'everyone'
        when 'anyone' then 'everyone'
        when 'followers' then 'followers'
        when 'mutuals' then 'followers'
        when 'friends' then 'followers'
        when 'following' then 'followers'
        else 'nobody'
    end;

alter table public.profiles
    drop constraint if exists profiles_listening_visibility_check,
    drop constraint if exists profiles_now_playing_visibility_check,
    drop constraint if exists profiles_recent_ratings_visibility_check;

alter table public.profiles
    add constraint profiles_listening_visibility_check
        check (listening_visibility in ('everyone', 'followers', 'nobody')),
    add constraint profiles_now_playing_visibility_check
        check (now_playing_visibility in ('everyone', 'followers', 'nobody')),
    add constraint profiles_recent_ratings_visibility_check
        check (recent_ratings_visibility in ('everyone', 'followers', 'nobody'));

-- Listening/now-playing gate with the same audience semantics as profile rows.
create or replace function public.orb_stats_activity_visible(
    target_user_id uuid,
    surface text
)
returns boolean
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    viewer_id uuid := auth.uid();
    audience text;
begin
    if viewer_id is null or target_user_id is null then
        return false;
    end if;

    if viewer_id = target_user_id then
        return true;
    end if;

    if not public.orb_public_profile_visible(target_user_id) then
        return false;
    end if;

    select lower(case surface
        when 'now_playing' then coalesce(p.now_playing_visibility, 'followers')
        else coalesce(p.listening_visibility, 'followers')
    end)
    into audience
    from public.profiles p
    where p.id = target_user_id;

    if coalesce(audience, 'nobody') in ('everyone', 'public', 'anyone') then
        return true;
    end if;

    if coalesce(audience, 'nobody') = 'followers' then
        return exists (
            select 1
            from public.follows f
            where f.follower_id = viewer_id
              and f.following_id = target_user_id
        );
    end if;

    return false;
end;
$$;

revoke all on function public.orb_stats_activity_visible(uuid, text) from public;
grant execute on function public.orb_stats_activity_visible(uuid, text) to authenticated;

-- Extend the generic per-section gate to recent ratings.
create or replace function public.orb_profile_section_visible(
    target_user_id uuid,
    section_name text
)
returns boolean
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    viewer_id uuid := auth.uid();
    audience text;
begin
    if viewer_id is null or target_user_id is null then
        return false;
    end if;

    if viewer_id = target_user_id then
        return true;
    end if;

    if not public.orb_public_profile_visible(target_user_id) then
        return false;
    end if;

    select lower(case section_name
        when 'following' then coalesce(p.following_visibility, 'everyone')
        when 'followers' then coalesce(p.followers_visibility, 'everyone')
        when 'play_count' then coalesce(p.play_count_visibility, 'everyone')
        when 'favorite_artists' then coalesce(p.favorite_artists_visibility, p.favorite_content_visibility, 'everyone')
        when 'favorite_albums' then coalesce(p.favorite_albums_visibility, p.favorite_content_visibility, 'everyone')
        when 'favorite_songs' then coalesce(p.favorite_songs_visibility, p.favorite_content_visibility, 'everyone')
        when 'recent_ratings' then coalesce(p.recent_ratings_visibility, 'nobody')
        else 'nobody'
    end)
    into audience
    from public.profiles p
    where p.id = target_user_id;

    if coalesce(audience, 'nobody') in ('everyone', 'public', 'anyone') then
        return true;
    end if;

    if coalesce(audience, 'nobody') = 'followers' then
        return exists (
            select 1
            from public.follows f
            where f.follower_id = viewer_id
              and f.following_id = target_user_id
        );
    end if;

    return false;
end;
$$;

revoke all on function public.orb_profile_section_visible(uuid, text) from public;
grant execute on function public.orb_profile_section_visible(uuid, text) to authenticated;

-- Recent ratings are row-readable only by their owner or by viewers allowed by
-- the profile's recent-ratings audience.
alter table public.album_ratings enable row level security;
drop policy if exists "album_ratings_read_own" on public.album_ratings;
create policy "album_ratings_read_own"
on public.album_ratings
for select
to authenticated
using (
    (select auth.uid()) = user_id
    or (
        is_public
        and public.orb_profile_section_visible(user_id, 'recent_ratings')
    )
);

-- Keep the recent-activity RPC aligned with the same privacy gate.
create or replace function public.orb_recent_album_ratings(p_album_ids text[], p_limit integer default 30)
returns table(user_id uuid, album_id text, rating_tenths smallint, updated_at timestamptz, username text, avatar_url text)
language sql stable security invoker set search_path = pg_catalog, public as $$
 select r.user_id, r.album_id, r.rating_tenths, r.updated_at, p.username,
        coalesce(nullif(to_jsonb(p)->>'avatar_icon_url', ''), p.avatar_url)
 from public.album_ratings r
 join public.profiles p on p.id = r.user_id
 where (select auth.uid()) is not null
   and r.is_public
   and public.orb_profile_section_visible(r.user_id, 'recent_ratings')
   and r.album_id = any(p_album_ids)
 order by r.updated_at desc, r.user_id, r.album_id
 limit greatest(1, least(coalesce(p_limit, 30), 100));
$$;

revoke all on function public.orb_recent_album_ratings(text[], integer) from public, anon;
grant execute on function public.orb_recent_album_ratings(text[], integer) to authenticated;

-- Aggregate-only rating endpoint. It intentionally bypasses per-profile display
-- privacy but exposes no user identity or individual score, so hiding recent
-- ratings does not make album/artist community averages viewer-dependent.
create or replace function public.orb_album_rating_averages(p_album_ids text[])
returns table(album_id text, average_tenths double precision, ratings_count bigint)
language sql
stable
security definer
set search_path = pg_catalog, public
as $$
    select
        r.album_id,
        avg(r.rating_tenths::double precision) as average_tenths,
        count(*)::bigint as ratings_count
    from public.album_ratings r
    where r.is_public
      and r.album_id = any(p_album_ids)
    group by r.album_id;
$$;

revoke all on function public.orb_album_rating_averages(text[]) from public, anon;
grant execute on function public.orb_album_rating_averages(text[]) to authenticated;

notify pgrst, 'reload schema';
