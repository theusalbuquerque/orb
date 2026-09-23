-- Orb profile privacy v3 -- strict three-state profile audiences + RLS-independent projections.
--
-- PROFILE (independent per section, default EVERYONE):
--   following / followers / play count / favorite artists / favorite albums / favorite songs
--   -> everyone | followers | nobody
--
-- STATS / FRIENDS live listening stays separate:
--   ON  -> followers
--   OFF -> nobody
--
-- This migration is safe to run after the previous 2026-09-17 profile privacy migrations.

alter table public.profiles
    add column if not exists following_visibility text,
    add column if not exists followers_visibility text,
    add column if not exists play_count_visibility text,
    add column if not exists favorite_content_visibility text not null default 'everyone',
    add column if not exists favorite_artists_visibility text,
    add column if not exists favorite_albums_visibility text,
    add column if not exists favorite_songs_visibility text,
    add column if not exists listening_visibility text not null default 'followers',
    add column if not exists now_playing_visibility text not null default 'followers';

-- Repair stale favorite-category columns left by the two earlier 2026-09-17
-- migrations. Before independent categories existed, favorite_content_visibility
-- was the authoritative choice. A current client writes that legacy field as
-- EVERYONE/FOLLOWERS only when all three categories match, so propagating those
-- two values here is safe and fixes profiles that visually said "Todos" while
-- their category columns still contained a stale "nobody". Mixed current
-- choices use legacy "nobody" and are therefore preserved.
update public.profiles
set favorite_artists_visibility = case
        when lower(coalesce(favorite_content_visibility, 'everyone')) in ('everyone','public','anyone') then 'everyone'
        when lower(coalesce(favorite_content_visibility, 'everyone')) in ('followers','mutuals','friends','following') then 'followers'
        else favorite_artists_visibility end,
    favorite_albums_visibility = case
        when lower(coalesce(favorite_content_visibility, 'everyone')) in ('everyone','public','anyone') then 'everyone'
        when lower(coalesce(favorite_content_visibility, 'everyone')) in ('followers','mutuals','friends','following') then 'followers'
        else favorite_albums_visibility end,
    favorite_songs_visibility = case
        when lower(coalesce(favorite_content_visibility, 'everyone')) in ('everyone','public','anyone') then 'everyone'
        when lower(coalesce(favorite_content_visibility, 'everyone')) in ('followers','mutuals','friends','following') then 'followers'
        else favorite_songs_visibility end;

-- Normalize old/null values. Older mutual/friends/following values map to FOLLOWERS.
update public.profiles
set following_visibility = case lower(coalesce(following_visibility, 'everyone'))
        when 'everyone' then 'everyone' when 'public' then 'everyone' when 'anyone' then 'everyone'
        when 'followers' then 'followers' when 'mutuals' then 'followers' when 'friends' then 'followers' when 'following' then 'followers'
        when 'nobody' then 'nobody' when 'private' then 'nobody' when 'none' then 'nobody'
        else 'everyone' end,
    followers_visibility = case lower(coalesce(followers_visibility, 'everyone'))
        when 'everyone' then 'everyone' when 'public' then 'everyone' when 'anyone' then 'everyone'
        when 'followers' then 'followers' when 'mutuals' then 'followers' when 'friends' then 'followers' when 'following' then 'followers'
        when 'nobody' then 'nobody' when 'private' then 'nobody' when 'none' then 'nobody'
        else 'everyone' end,
    play_count_visibility = case lower(coalesce(play_count_visibility, 'everyone'))
        when 'everyone' then 'everyone' when 'public' then 'everyone' when 'anyone' then 'everyone'
        when 'followers' then 'followers' when 'mutuals' then 'followers' when 'friends' then 'followers' when 'following' then 'followers'
        when 'nobody' then 'nobody' when 'private' then 'nobody' when 'none' then 'nobody'
        else 'everyone' end,
    favorite_artists_visibility = case lower(coalesce(favorite_artists_visibility, favorite_content_visibility, 'everyone'))
        when 'everyone' then 'everyone' when 'public' then 'everyone' when 'anyone' then 'everyone'
        when 'followers' then 'followers' when 'mutuals' then 'followers' when 'friends' then 'followers' when 'following' then 'followers'
        when 'nobody' then 'nobody' when 'private' then 'nobody' when 'none' then 'nobody'
        else 'everyone' end,
    favorite_albums_visibility = case lower(coalesce(favorite_albums_visibility, favorite_content_visibility, 'everyone'))
        when 'everyone' then 'everyone' when 'public' then 'everyone' when 'anyone' then 'everyone'
        when 'followers' then 'followers' when 'mutuals' then 'followers' when 'friends' then 'followers' when 'following' then 'followers'
        when 'nobody' then 'nobody' when 'private' then 'nobody' when 'none' then 'nobody'
        else 'everyone' end,
    favorite_songs_visibility = case lower(coalesce(favorite_songs_visibility, favorite_content_visibility, 'everyone'))
        when 'everyone' then 'everyone' when 'public' then 'everyone' when 'anyone' then 'everyone'
        when 'followers' then 'followers' when 'mutuals' then 'followers' when 'friends' then 'followers' when 'following' then 'followers'
        when 'nobody' then 'nobody' when 'private' then 'nobody' when 'none' then 'nobody'
        else 'everyone' end,
    favorite_content_visibility = case lower(coalesce(favorite_content_visibility, 'everyone'))
        when 'everyone' then 'everyone' when 'public' then 'everyone' when 'anyone' then 'everyone'
        when 'followers' then 'followers' when 'mutuals' then 'followers' when 'friends' then 'followers' when 'following' then 'followers'
        when 'nobody' then 'nobody' when 'private' then 'nobody' when 'none' then 'nobody'
        else 'everyone' end,
    listening_visibility = case
        when lower(coalesce(listening_visibility, 'followers')) in ('nobody','private','none') then 'nobody'
        else 'followers' end,
    now_playing_visibility = case
        when lower(coalesce(now_playing_visibility, 'followers')) in ('nobody','private','none') then 'nobody'
        else 'followers' end;

alter table public.profiles
    alter column following_visibility set default 'everyone',
    alter column following_visibility set not null,
    alter column followers_visibility set default 'everyone',
    alter column followers_visibility set not null,
    alter column play_count_visibility set default 'everyone',
    alter column play_count_visibility set not null,
    alter column favorite_artists_visibility set default 'everyone',
    alter column favorite_artists_visibility set not null,
    alter column favorite_albums_visibility set default 'everyone',
    alter column favorite_albums_visibility set not null,
    alter column favorite_songs_visibility set default 'everyone',
    alter column favorite_songs_visibility set not null,
    alter column favorite_content_visibility set default 'everyone',
    alter column favorite_content_visibility set not null,
    alter column listening_visibility set default 'followers',
    alter column listening_visibility set not null,
    alter column now_playing_visibility set default 'followers',
    alter column now_playing_visibility set not null;

alter table public.profiles
    drop constraint if exists profiles_following_visibility_check,
    drop constraint if exists profiles_followers_visibility_check,
    drop constraint if exists profiles_play_count_visibility_check,
    drop constraint if exists profiles_favorite_artists_visibility_check,
    drop constraint if exists profiles_favorite_albums_visibility_check,
    drop constraint if exists profiles_favorite_songs_visibility_check,
    drop constraint if exists profiles_listening_visibility_check,
    drop constraint if exists profiles_now_playing_visibility_check;

alter table public.profiles
    add constraint profiles_following_visibility_check check (following_visibility in ('everyone','followers','nobody')),
    add constraint profiles_followers_visibility_check check (followers_visibility in ('everyone','followers','nobody')),
    add constraint profiles_play_count_visibility_check check (play_count_visibility in ('everyone','followers','nobody')),
    add constraint profiles_favorite_artists_visibility_check check (favorite_artists_visibility in ('everyone','followers','nobody')),
    add constraint profiles_favorite_albums_visibility_check check (favorite_albums_visibility in ('everyone','followers','nobody')),
    add constraint profiles_favorite_songs_visibility_check check (favorite_songs_visibility in ('everyone','followers','nobody')),
    add constraint profiles_listening_visibility_check check (listening_visibility in ('followers','nobody')),
    add constraint profiles_now_playing_visibility_check check (now_playing_visibility in ('followers','nobody'));

-- Generic profile gate. Blocking always wins.
create or replace function public.orb_public_profile_visible(target_user_id uuid)
returns boolean
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare viewer_id uuid := auth.uid();
begin
    if viewer_id is null or target_user_id is null then return false; end if;
    if viewer_id = target_user_id then return true; end if;
    return not exists (
        select 1 from public.profile_blocks b
        where (b.blocker_id = viewer_id and b.blocked_id = target_user_id)
           or (b.blocker_id = target_user_id and b.blocked_id = viewer_id)
    );
end;
$$;
revoke all on function public.orb_public_profile_visible(uuid) from public;
grant execute on function public.orb_public_profile_visible(uuid) to authenticated;

-- One authoritative audience evaluator for all six profile sections.
create or replace function public.orb_profile_section_visible(target_user_id uuid, section_name text)
returns boolean
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    viewer_id uuid := auth.uid();
    audience text;
begin
    if viewer_id is null or target_user_id is null then return false; end if;
    if viewer_id = target_user_id then return true; end if;
    if not public.orb_public_profile_visible(target_user_id) then return false; end if;

    select lower(case section_name
        when 'following' then coalesce(p.following_visibility, 'everyone')
        when 'followers' then coalesce(p.followers_visibility, 'everyone')
        when 'play_count' then coalesce(p.play_count_visibility, 'everyone')
        when 'favorite_artists' then coalesce(p.favorite_artists_visibility, p.favorite_content_visibility, 'everyone')
        when 'favorite_albums' then coalesce(p.favorite_albums_visibility, p.favorite_content_visibility, 'everyone')
        when 'favorite_songs' then coalesce(p.favorite_songs_visibility, p.favorite_content_visibility, 'everyone')
        else 'nobody' end)
    into audience
    from public.profiles p where p.id = target_user_id;

    if coalesce(audience, 'nobody') = 'everyone' then return true; end if;
    if coalesce(audience, 'nobody') = 'followers' then
        return exists (
            select 1 from public.follows f
            where f.follower_id = viewer_id
              and f.following_id = target_user_id
        );
    end if;
    return false;
end;
$$;
revoke all on function public.orb_profile_section_visible(uuid, text) from public;
grant execute on function public.orb_profile_section_visible(uuid, text) to authenticated;

-- IMPORTANT: Profile projections must NOT inherit the Stats/Friends RLS on
-- listening_activity/follows. These SECURITY DEFINER source functions perform
-- the profile audience check themselves, then read the underlying data as the
-- function owner. This fixes the bug where a profile set to EVERYONE/FOLLOWERS
-- still returned empty favorite sections because live-listening privacy was off.

create or replace function public.orb_profile_visible_follow_edges(section_name text)
returns table(follower_id uuid, following_id uuid)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select f.follower_id, f.following_id
    from public.follows f
    where case section_name
        when 'following' then public.orb_profile_section_visible(f.follower_id, 'following')
        when 'followers' then public.orb_profile_section_visible(f.following_id, 'followers')
        else false
    end;
$$;
revoke all on function public.orb_profile_visible_follow_edges(text) from public;
grant execute on function public.orb_profile_visible_follow_edges(text) to authenticated;

drop view if exists public.profile_visible_following;
create view public.profile_visible_following
with (security_barrier = true)
as select * from public.orb_profile_visible_follow_edges('following');
revoke all on public.profile_visible_following from public;
grant select on public.profile_visible_following to authenticated;

drop view if exists public.profile_visible_followers;
create view public.profile_visible_followers
with (security_barrier = true)
as select * from public.orb_profile_visible_follow_edges('followers');
revoke all on public.profile_visible_followers from public;
grant select on public.profile_visible_followers to authenticated;

create or replace function public.orb_profile_play_count_source()
returns table(user_id uuid, plays_count bigint)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select p.id,
           case when public.orb_profile_section_visible(p.id, 'play_count')
                then count(la.user_id)::bigint else 0::bigint end
    from public.profiles p
    left join public.listening_activity la on la.user_id = p.id
    where public.orb_public_profile_visible(p.id)
    group by p.id;
$$;
revoke all on function public.orb_profile_play_count_source() from public;
grant execute on function public.orb_profile_play_count_source() to authenticated;

drop view if exists public.profile_play_counts;
create view public.profile_play_counts
with (security_barrier = true)
as select * from public.orb_profile_play_count_source();
revoke all on public.profile_play_counts from public;
grant select on public.profile_play_counts to authenticated;

-- Category-specific SECURITY DEFINER sources. Each function exposes only the
-- metadata that its own profile section is allowed to reveal. They are safe to
-- grant to authenticated users and cannot be used to reconstruct another hidden
-- favorite category.

create or replace function public.orb_profile_favorite_artist_activity_source()
returns table(
    user_id uuid,
    video_id text,
    title text,
    artist text,
    album text,
    artwork_url text,
    started_at timestamptz,
    finished_at timestamptz,
    duration_ms bigint,
    played_ms bigint,
    language_code text,
    source_playlist_id text,
    source_playlist_title text,
    source_playlist_artwork_url text
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select
        la.user_id,
        ''::text,
        ''::text,
        la.artist,
        null::text,
        null::text,
        la.started_at,
        la.finished_at,
        la.duration_ms,
        la.played_ms,
        null::text,
        null::text,
        null::text,
        null::text
    from public.listening_activity la
    where la.started_at >= date_trunc('month', now())
      and la.duration_ms is not null
      and la.duration_ms > 0
      and coalesce(
            la.played_ms,
            greatest(0, round(extract(epoch from (coalesce(la.finished_at, la.started_at) - la.started_at)) * 1000))::bigint
          )::numeric / la.duration_ms::numeric >= 0.30
      and public.orb_profile_section_visible(la.user_id, 'favorite_artists');
$$;
revoke all on function public.orb_profile_favorite_artist_activity_source() from public;
grant execute on function public.orb_profile_favorite_artist_activity_source() to authenticated;

create or replace function public.orb_profile_favorite_album_activity_source()
returns table(
    user_id uuid,
    video_id text,
    title text,
    artist text,
    album text,
    artwork_url text,
    started_at timestamptz,
    finished_at timestamptz,
    duration_ms bigint,
    played_ms bigint,
    language_code text,
    source_playlist_id text,
    source_playlist_title text,
    source_playlist_artwork_url text
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select
        la.user_id,
        ''::text,
        ''::text,
        la.artist,
        la.album,
        la.artwork_url,
        la.started_at,
        la.finished_at,
        la.duration_ms,
        la.played_ms,
        null::text,
        null::text,
        null::text,
        null::text
    from public.listening_activity la
    where la.started_at >= date_trunc('month', now())
      and la.duration_ms is not null
      and la.duration_ms > 0
      and coalesce(
            la.played_ms,
            greatest(0, round(extract(epoch from (coalesce(la.finished_at, la.started_at) - la.started_at)) * 1000))::bigint
          )::numeric / la.duration_ms::numeric >= 0.30
      and public.orb_profile_section_visible(la.user_id, 'favorite_albums');
$$;
revoke all on function public.orb_profile_favorite_album_activity_source() from public;
grant execute on function public.orb_profile_favorite_album_activity_source() to authenticated;

create or replace function public.orb_profile_favorite_song_activity_source()
returns table(
    user_id uuid,
    video_id text,
    title text,
    artist text,
    album text,
    artwork_url text,
    started_at timestamptz,
    finished_at timestamptz,
    duration_ms bigint,
    played_ms bigint,
    language_code text,
    source_playlist_id text,
    source_playlist_title text,
    source_playlist_artwork_url text
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select
        la.user_id,
        la.video_id,
        la.title,
        la.artist,
        la.album,
        la.artwork_url,
        la.started_at,
        la.finished_at,
        la.duration_ms,
        la.played_ms,
        la.language_code,
        null::text,
        null::text,
        null::text
    from public.listening_activity la
    where la.started_at >= date_trunc('month', now())
      and la.duration_ms is not null
      and la.duration_ms > 0
      and coalesce(
            la.played_ms,
            greatest(0, round(extract(epoch from (coalesce(la.finished_at, la.started_at) - la.started_at)) * 1000))::bigint
          )::numeric / la.duration_ms::numeric >= 0.30
      and public.orb_profile_section_visible(la.user_id, 'favorite_songs');
$$;
revoke all on function public.orb_profile_favorite_song_activity_source() from public;
grant execute on function public.orb_profile_favorite_song_activity_source() to authenticated;

drop view if exists public.profile_public_favorite_artist_activity;
create view public.profile_public_favorite_artist_activity
with (security_barrier = true)
as select * from public.orb_profile_favorite_artist_activity_source();
revoke all on public.profile_public_favorite_artist_activity from public;
grant select on public.profile_public_favorite_artist_activity to authenticated;

drop view if exists public.profile_public_favorite_album_activity;
create view public.profile_public_favorite_album_activity
with (security_barrier = true)
as select * from public.orb_profile_favorite_album_activity_source();
revoke all on public.profile_public_favorite_album_activity from public;
grant select on public.profile_public_favorite_album_activity to authenticated;

drop view if exists public.profile_public_favorite_song_activity;
create view public.profile_public_favorite_song_activity
with (security_barrier = true)
as select * from public.orb_profile_favorite_song_activity_source();
revoke all on public.profile_public_favorite_song_activity from public;
grant select on public.profile_public_favorite_song_activity to authenticated;

-- Legacy unified projection for older builds. It is visible only when all three
-- favorite sections are simultaneously visible to this viewer.
drop view if exists public.profile_public_monthly_activity;
create view public.profile_public_monthly_activity
with (security_barrier = true)
as
select s.*
from public.orb_profile_favorite_song_activity_source() s
where public.orb_profile_section_visible(s.user_id, 'favorite_artists')
  and public.orb_profile_section_visible(s.user_id, 'favorite_albums');
revoke all on public.profile_public_monthly_activity from public;
grant select on public.profile_public_monthly_activity to authenticated;

-- Re-assert the Stats/Friends rule. This is intentionally NOT connected to the
-- six profile-page section controls above.
create or replace function public.orb_stats_activity_visible(target_user_id uuid, surface text)
returns boolean
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare viewer_id uuid := auth.uid(); audience text;
begin
    if viewer_id is null or target_user_id is null then return false; end if;
    if viewer_id = target_user_id then return true; end if;
    if not public.orb_public_profile_visible(target_user_id) then return false; end if;

    select case surface
        when 'now_playing' then coalesce(p.now_playing_visibility, 'followers')
        else coalesce(p.listening_visibility, 'followers') end
    into audience from public.profiles p where p.id = target_user_id;

    if lower(coalesce(audience, 'followers')) = 'nobody' then return false; end if;
    return exists (
        select 1 from public.follows f
        where f.follower_id = viewer_id and f.following_id = target_user_id
    );
end;
$$;
revoke all on function public.orb_stats_activity_visible(uuid, text) from public;
grant execute on function public.orb_stats_activity_visible(uuid, text) to authenticated;

alter table public.listening_activity enable row level security;
alter table public.now_playing enable row level security;

drop policy if exists "orb read visible profile listening" on public.listening_activity;
drop policy if exists "orb read friends listening activity" on public.listening_activity;
create policy "orb read friends listening activity"
on public.listening_activity for select to authenticated
using (public.orb_stats_activity_visible(user_id, 'listening'));

drop policy if exists "orb read visible profile now playing" on public.now_playing;
drop policy if exists "orb read friends now playing" on public.now_playing;
create policy "orb read friends now playing"
on public.now_playing for select to authenticated
using (public.orb_stats_activity_visible(user_id, 'now_playing'));
