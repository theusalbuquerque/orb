-- Orb profile section visibility v2.
--
-- Product rules:
--   * Profile sections default to EVERYONE for both existing and new users.
--   * Each profile section independently supports:
--       - everyone
--       - followers
--       - nobody (hidden)
--   * This applies to following, followers, play count, favorite artists,
--     favorite albums and favorite songs.
--   * The profile owner can always see their own sections.
--   * Stats/Friends live-listening privacy is unchanged:
--       - sharing ON  -> followers only
--       - sharing OFF -> nobody
--   * Blocked relationships remain invisible.

alter table public.profiles
    add column if not exists listening_visibility text not null default 'followers',
    add column if not exists now_playing_visibility text not null default 'followers',
    add column if not exists favorite_content_visibility text not null default 'everyone',
    add column if not exists favorite_artists_visibility text not null default 'everyone',
    add column if not exists favorite_albums_visibility text not null default 'everyone',
    add column if not exists following_visibility text,
    add column if not exists followers_visibility text,
    add column if not exists play_count_visibility text,
    add column if not exists favorite_songs_visibility text;

-- Stats/Friends listening status remains a separate two-state setting.
update public.profiles
set listening_visibility = case
        when lower(coalesce(listening_visibility, 'followers')) in ('nobody', 'private', 'none') then 'nobody'
        else 'followers'
    end,
    now_playing_visibility = case
        when lower(coalesce(now_playing_visibility, 'followers')) in ('nobody', 'private', 'none') then 'nobody'
        else 'followers'
    end;

alter table public.profiles
    alter column listening_visibility set default 'followers',
    alter column now_playing_visibility set default 'followers';

-- Existing users keep explicit valid choices. Missing/legacy values default to
-- EVERYONE, while older mutual/friends values are folded into FOLLOWERS.
update public.profiles
set following_visibility = case lower(coalesce(following_visibility, 'everyone'))
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
        else 'everyone'
    end,
    followers_visibility = case lower(coalesce(followers_visibility, 'everyone'))
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
        else 'everyone'
    end,
    play_count_visibility = case lower(coalesce(play_count_visibility, 'everyone'))
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
        else 'everyone'
    end,
    favorite_artists_visibility = case lower(coalesce(favorite_artists_visibility, favorite_content_visibility, 'everyone'))
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
        else 'everyone'
    end,
    favorite_albums_visibility = case lower(coalesce(favorite_albums_visibility, favorite_content_visibility, 'everyone'))
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
        else 'everyone'
    end,
    favorite_songs_visibility = case lower(coalesce(favorite_songs_visibility, favorite_content_visibility, 'everyone'))
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
        else 'everyone'
    end;

alter table public.profiles
    alter column following_visibility set default 'everyone',
    alter column following_visibility set not null,
    alter column followers_visibility set default 'everyone',
    alter column followers_visibility set not null,
    alter column play_count_visibility set default 'everyone',
    alter column play_count_visibility set not null,
    alter column favorite_content_visibility set default 'everyone',
    alter column favorite_content_visibility set not null,
    alter column favorite_artists_visibility set default 'everyone',
    alter column favorite_artists_visibility set not null,
    alter column favorite_albums_visibility set default 'everyone',
    alter column favorite_albums_visibility set not null,
    alter column favorite_songs_visibility set default 'everyone',
    alter column favorite_songs_visibility set not null;

-- Older app versions only understand one favorite-content value. Keep that
-- compatibility field privacy-safe: preserve the audience only when all three
-- favorite sections match; otherwise hide the legacy unified projection.
update public.profiles
set favorite_content_visibility = case
    when favorite_artists_visibility = favorite_albums_visibility
     and favorite_albums_visibility = favorite_songs_visibility
    then favorite_artists_visibility
    else 'nobody'
end;

alter table public.profiles
    drop constraint if exists profiles_following_visibility_check,
    drop constraint if exists profiles_followers_visibility_check,
    drop constraint if exists profiles_play_count_visibility_check,
    drop constraint if exists profiles_favorite_artists_visibility_check,
    drop constraint if exists profiles_favorite_albums_visibility_check,
    drop constraint if exists profiles_favorite_songs_visibility_check;

alter table public.profiles
    add constraint profiles_following_visibility_check
        check (following_visibility in ('everyone', 'followers', 'nobody')),
    add constraint profiles_followers_visibility_check
        check (followers_visibility in ('everyone', 'followers', 'nobody')),
    add constraint profiles_play_count_visibility_check
        check (play_count_visibility in ('everyone', 'followers', 'nobody')),
    add constraint profiles_favorite_artists_visibility_check
        check (favorite_artists_visibility in ('everyone', 'followers', 'nobody')),
    add constraint profiles_favorite_albums_visibility_check
        check (favorite_albums_visibility in ('everyone', 'followers', 'nobody')),
    add constraint profiles_favorite_songs_visibility_check
        check (favorite_songs_visibility in ('everyone', 'followers', 'nobody'));

-- Re-declare the common profile gate so this migration remains safe if applied
-- after an older profile migration. It does not alter Stats/Friends audiences.
create or replace function public.orb_public_profile_visible(target_user_id uuid)
returns boolean
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    viewer_id uuid := auth.uid();
begin
    if viewer_id is null or target_user_id is null then
        return false;
    end if;

    if viewer_id = target_user_id then
        return true;
    end if;

    return not exists (
        select 1
        from public.profile_blocks b
        where (b.blocker_id = viewer_id and b.blocked_id = target_user_id)
           or (b.blocker_id = target_user_id and b.blocked_id = viewer_id)
    );
end;
$$;

revoke all on function public.orb_public_profile_visible(uuid) from public;
grant execute on function public.orb_public_profile_visible(uuid) to authenticated;

alter table public.profiles
    drop constraint if exists profiles_listening_visibility_check,
    drop constraint if exists profiles_now_playing_visibility_check;

alter table public.profiles
    add constraint profiles_listening_visibility_check
        check (listening_visibility in ('followers', 'nobody')),
    add constraint profiles_now_playing_visibility_check
        check (now_playing_visibility in ('followers', 'nobody'));

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

    select case surface
        when 'now_playing' then coalesce(p.now_playing_visibility, 'followers')
        else coalesce(p.listening_visibility, 'followers')
    end
    into audience
    from public.profiles p
    where p.id = target_user_id;

    if lower(coalesce(audience, 'followers')) in ('nobody', 'private', 'none') then
        return false;
    end if;

    return exists (
        select 1
        from public.follows f
        where f.follower_id = viewer_id
          and f.following_id = target_user_id
    );
end;
$$;

revoke all on function public.orb_stats_activity_visible(uuid, text) from public;
grant execute on function public.orb_stats_activity_visible(uuid, text) to authenticated;

alter table public.listening_activity enable row level security;
alter table public.now_playing enable row level security;
grant select on public.listening_activity to authenticated;
grant select on public.now_playing to authenticated;

drop policy if exists "orb read visible profile listening" on public.listening_activity;
drop policy if exists "orb read friends listening activity" on public.listening_activity;
create policy "orb read friends listening activity"
on public.listening_activity
for select
to authenticated
using (public.orb_stats_activity_visible(user_id, 'listening'));

drop policy if exists "orb read visible profile now playing" on public.now_playing;
drop policy if exists "orb read friends now playing" on public.now_playing;
create policy "orb read friends now playing"
on public.now_playing
for select
to authenticated
using (public.orb_stats_activity_visible(user_id, 'now_playing'));

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

-- Do not expose the entire follow graph directly anymore. Profile lists
-- use direction-specific views below; direct table reads remain available only
-- when the signed-in user is one of the two endpoints.
alter table public.follows enable row level security;
drop policy if exists "orb read profile follow graph" on public.follows;
drop policy if exists "orb read own follow edges" on public.follows;
create policy "orb read own follow edges"
on public.follows
for select
to authenticated
using (auth.uid() = follower_id or auth.uid() = following_id);

drop view if exists public.profile_visible_following;
create view public.profile_visible_following
with (security_barrier = true)
as
select
    f.follower_id,
    f.following_id
from public.follows f
where public.orb_profile_section_visible(f.follower_id, 'following');

revoke all on public.profile_visible_following from public;
grant select on public.profile_visible_following to authenticated;

drop view if exists public.profile_visible_followers;
create view public.profile_visible_followers
with (security_barrier = true)
as
select
    f.follower_id,
    f.following_id
from public.follows f
where public.orb_profile_section_visible(f.following_id, 'followers');

revoke all on public.profile_visible_followers from public;
grant select on public.profile_visible_followers to authenticated;

-- Counts are aggregate profile metadata. Each field is independently
-- redacted to zero when the viewer is outside the selected audience. The UI also hides the field,
-- so zero is never presented as the real count for a hidden section.
drop view if exists public.profile_social_counts;
create view public.profile_social_counts
with (security_barrier = true)
as
select
    p.id as user_id,
    case
        when public.orb_profile_section_visible(p.id, 'followers') then (
            select count(*)::bigint from public.follows f where f.following_id = p.id
        )
        else 0::bigint
    end as followers_count,
    case
        when public.orb_profile_section_visible(p.id, 'following') then (
            select count(*)::bigint from public.follows f where f.follower_id = p.id
        )
        else 0::bigint
    end as following_count
from public.profiles p
where public.orb_public_profile_visible(p.id);

revoke all on public.profile_social_counts from public;
grant select on public.profile_social_counts to authenticated;

drop view if exists public.profile_play_counts;
create view public.profile_play_counts
with (security_barrier = true)
as
select
    p.id as user_id,
    case
        when public.orb_profile_section_visible(p.id, 'play_count') then count(la.user_id)::bigint
        else 0::bigint
    end as plays_count
from public.profiles p
left join public.listening_activity la on la.user_id = p.id
where public.orb_public_profile_visible(p.id)
group by p.id;

revoke all on public.profile_play_counts from public;
grant select on public.profile_play_counts to authenticated;

-- Shared monthly qualification used by all three favorite projections.
-- Each projection exposes only the metadata required to render that category,
-- preventing a hidden category from being reconstructed from another one.

drop view if exists public.profile_public_favorite_artist_activity;
create view public.profile_public_favorite_artist_activity
with (security_barrier = true)
as
select
    la.user_id,
    ''::text as video_id,
    ''::text as title,
    la.artist,
    null::text as album,
    null::text as artwork_url,
    date_trunc('month', la.started_at)::timestamptz as started_at,
    (date_trunc('month', la.started_at) + interval '1 second')::timestamptz as finished_at,
    la.duration_ms,
    coalesce(
        la.played_ms,
        greatest(0, round(extract(epoch from (coalesce(la.finished_at, la.started_at) - la.started_at)) * 1000))::bigint
    ) as played_ms,
    null::text as language_code,
    null::text as source_playlist_id,
    null::text as source_playlist_title,
    null::text as source_playlist_artwork_url
from public.listening_activity la
where la.started_at >= date_trunc('month', now())
  and la.duration_ms is not null
  and la.duration_ms > 0
  and coalesce(
        la.played_ms,
        greatest(0, round(extract(epoch from (coalesce(la.finished_at, la.started_at) - la.started_at)) * 1000))::bigint
      )::numeric / la.duration_ms::numeric >= 0.30
  and public.orb_profile_section_visible(la.user_id, 'favorite_artists');

revoke all on public.profile_public_favorite_artist_activity from public;
grant select on public.profile_public_favorite_artist_activity to authenticated;

drop view if exists public.profile_public_favorite_album_activity;
create view public.profile_public_favorite_album_activity
with (security_barrier = true)
as
select
    la.user_id,
    ''::text as video_id,
    ''::text as title,
    la.artist,
    la.album,
    la.artwork_url,
    date_trunc('month', la.started_at)::timestamptz as started_at,
    (date_trunc('month', la.started_at) + interval '1 second')::timestamptz as finished_at,
    la.duration_ms,
    coalesce(
        la.played_ms,
        greatest(0, round(extract(epoch from (coalesce(la.finished_at, la.started_at) - la.started_at)) * 1000))::bigint
    ) as played_ms,
    null::text as language_code,
    null::text as source_playlist_id,
    null::text as source_playlist_title,
    null::text as source_playlist_artwork_url
from public.listening_activity la
where la.started_at >= date_trunc('month', now())
  and la.duration_ms is not null
  and la.duration_ms > 0
  and coalesce(
        la.played_ms,
        greatest(0, round(extract(epoch from (coalesce(la.finished_at, la.started_at) - la.started_at)) * 1000))::bigint
      )::numeric / la.duration_ms::numeric >= 0.30
  and public.orb_profile_section_visible(la.user_id, 'favorite_albums');

revoke all on public.profile_public_favorite_album_activity from public;
grant select on public.profile_public_favorite_album_activity to authenticated;

drop view if exists public.profile_public_favorite_song_activity;
create view public.profile_public_favorite_song_activity
with (security_barrier = true)
as
select
    la.user_id,
    la.video_id,
    la.title,
    la.artist,
    la.album,
    la.artwork_url,
    date_trunc('month', la.started_at)::timestamptz as started_at,
    (date_trunc('month', la.started_at) + interval '1 second')::timestamptz as finished_at,
    la.duration_ms,
    coalesce(
        la.played_ms,
        greatest(0, round(extract(epoch from (coalesce(la.finished_at, la.started_at) - la.started_at)) * 1000))::bigint
    ) as played_ms,
    la.language_code,
    null::text as source_playlist_id,
    null::text as source_playlist_title,
    null::text as source_playlist_artwork_url
from public.listening_activity la
where la.started_at >= date_trunc('month', now())
  and la.duration_ms is not null
  and la.duration_ms > 0
  and coalesce(
        la.played_ms,
        greatest(0, round(extract(epoch from (coalesce(la.finished_at, la.started_at) - la.started_at)) * 1000))::bigint
      )::numeric / la.duration_ms::numeric >= 0.30
  and public.orb_profile_section_visible(la.user_id, 'favorite_songs');

revoke all on public.profile_public_favorite_song_activity from public;
grant select on public.profile_public_favorite_song_activity to authenticated;

-- Legacy projection remains for older Orb builds. It is deliberately strict:
-- an older build receives favorite data only when all three categories have
-- the same effective audience, avoiding a privacy leak through its unified UI.
drop view if exists public.profile_public_monthly_activity;
create view public.profile_public_monthly_activity
with (security_barrier = true)
as
select
    la.user_id,
    la.video_id,
    la.title,
    la.artist,
    la.album,
    la.artwork_url,
    date_trunc('month', la.started_at)::timestamptz as started_at,
    (date_trunc('month', la.started_at) + interval '1 second')::timestamptz as finished_at,
    la.duration_ms,
    coalesce(
        la.played_ms,
        greatest(0, round(extract(epoch from (coalesce(la.finished_at, la.started_at) - la.started_at)) * 1000))::bigint
    ) as played_ms,
    la.language_code,
    null::text as source_playlist_id,
    null::text as source_playlist_title,
    null::text as source_playlist_artwork_url
from public.listening_activity la
where la.started_at >= date_trunc('month', now())
  and la.duration_ms is not null
  and la.duration_ms > 0
  and coalesce(
        la.played_ms,
        greatest(0, round(extract(epoch from (coalesce(la.finished_at, la.started_at) - la.started_at)) * 1000))::bigint
      )::numeric / la.duration_ms::numeric >= 0.30
  and public.orb_profile_section_visible(la.user_id, 'favorite_artists')
  and public.orb_profile_section_visible(la.user_id, 'favorite_albums')
  and public.orb_profile_section_visible(la.user_id, 'favorite_songs');

revoke all on public.profile_public_monthly_activity from public;
grant select on public.profile_public_monthly_activity to authenticated;

-- Stats/Friends listening status is intentionally kept separate from all
-- profile-page toggles above: it remains followers-only when enabled, or nobody.
