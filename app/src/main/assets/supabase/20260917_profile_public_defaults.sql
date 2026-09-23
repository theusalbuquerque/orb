-- Orb profile visibility + Stats/Friends listening privacy (corrected scope).
-- Run once in Supabase SQL Editor. This file supersedes the earlier
-- 20260917_profile_public_defaults.sql draft.
--
-- Product rules:
--   PROFILE (visited user page)
--     * followers/following graph is visible to authenticated users by default;
--     * total plays are visible on the profile;
--     * favorite artists/albums/songs use favorite_content_visibility and
--       default to EVERYONE;
--   STATS / FRIENDS LISTENING STATUS
--     * now playing + recent listening activity are NEVER public-to-everyone;
--     * sharing ON means FOLLOWERS ONLY;
--     * sharing OFF means NOBODY;
--   Blocked relationships stay hidden on both surfaces.

alter table public.profiles
    add column if not exists listening_visibility text not null default 'followers',
    add column if not exists now_playing_visibility text not null default 'followers',
    add column if not exists favorite_content_visibility text not null default 'everyone',
    add column if not exists favorite_artists_visibility text not null default 'everyone',
    add column if not exists favorite_albums_visibility text not null default 'everyone';

-- Friends activity defaults to followers. Profile favorites remain public by
-- default and are a separate privacy surface.
alter table public.profiles
    alter column listening_visibility set default 'followers',
    alter column now_playing_visibility set default 'followers',
    alter column favorite_content_visibility set default 'everyone',
    alter column favorite_artists_visibility set default 'everyone',
    alter column favorite_albums_visibility set default 'everyone';

-- Repair installs that ran the earlier draft, which temporarily wrote
-- "everyone" into friends listening visibility. Preserve explicit NOBODY;
-- every other legacy value becomes FOLLOWERS because this surface only has the
-- two supported states: followers or nobody.
update public.profiles
set listening_visibility = case
        when lower(coalesce(listening_visibility, 'followers')) in ('nobody', 'private', 'none')
            then 'nobody'
        else 'followers'
    end,
    now_playing_visibility = case
        when lower(coalesce(now_playing_visibility, 'followers')) in ('nobody', 'private', 'none')
            then 'nobody'
        else 'followers'
    end;

-- Friends activity intentionally supports only these two states. Tightening the
-- constraints prevents a future client from accidentally making Stats activity
-- public again.
alter table public.profiles
    drop constraint if exists profiles_listening_visibility_check,
    drop constraint if exists profiles_now_playing_visibility_check;

alter table public.profiles
    add constraint profiles_listening_visibility_check
        check (listening_visibility in ('followers', 'nobody')),
    add constraint profiles_now_playing_visibility_check
        check (now_playing_visibility in ('followers', 'nobody'));

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'profiles_favorite_content_visibility_check'
          and conrelid = 'public.profiles'::regclass
    ) then
        alter table public.profiles
            add constraint profiles_favorite_content_visibility_check
            check (favorite_content_visibility in ('everyone', 'followers', 'mutuals', 'nobody'));
    end if;
end
$$;

-- Common block gate used by public profile projections.
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

-- Stats/Friends activity has exactly two effective states. Even if an older app
-- writes EVERYONE/MUTUALS, the server still treats sharing as FOLLOWERS ONLY.
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

-- Profile favorites keep the richer audience selector and are independent from
-- the friends listening-status toggle.
create or replace function public.orb_profile_favorites_visible(target_user_id uuid)
returns boolean
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    viewer_id uuid := auth.uid();
    audience text;
    viewer_follows_target boolean;
    target_follows_viewer boolean;
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

    select lower(coalesce(
        p.favorite_content_visibility,
        p.favorite_artists_visibility,
        p.favorite_albums_visibility,
        'everyone'
    ))
    into audience
    from public.profiles p
    where p.id = target_user_id;

    if audience in ('everyone', 'public', 'anyone') then
        return true;
    end if;

    if audience in ('nobody', 'private', 'none') then
        return false;
    end if;

    select exists (
        select 1 from public.follows f
        where f.follower_id = viewer_id and f.following_id = target_user_id
    ) into viewer_follows_target;

    if audience = 'followers' then
        return viewer_follows_target;
    end if;

    if audience in ('mutuals', 'friends', 'following') then
        select exists (
            select 1 from public.follows f
            where f.follower_id = target_user_id and f.following_id = viewer_id
        ) into target_follows_viewer;
        return viewer_follows_target and target_follows_viewer;
    end if;

    -- Unknown legacy favorite values follow the profile default, not the Stats
    -- activity default.
    return true;
end;
$$;

revoke all on function public.orb_profile_favorites_visible(uuid) from public;
grant execute on function public.orb_profile_favorites_visible(uuid) to authenticated;

-- Followers/following are profile information. This function reveals only the
-- graph rows themselves and still respects blocks.
create or replace function public.orb_follow_row_visible(
    row_follower_id uuid,
    row_following_id uuid
)
returns boolean
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    viewer_id uuid := auth.uid();
begin
    if viewer_id is null then
        return false;
    end if;

    if viewer_id = row_follower_id or viewer_id = row_following_id then
        return true;
    end if;

    if exists (
        select 1
        from public.profile_blocks b
        where (b.blocker_id = viewer_id and b.blocked_id in (row_follower_id, row_following_id))
           or (b.blocked_id = viewer_id and b.blocker_id in (row_follower_id, row_following_id))
    ) then
        return false;
    end if;

    return true;
end;
$$;

revoke all on function public.orb_follow_row_visible(uuid, uuid) from public;
grant execute on function public.orb_follow_row_visible(uuid, uuid) to authenticated;

alter table public.follows enable row level security;
alter table public.listening_activity enable row level security;
alter table public.now_playing enable row level security;

grant select on public.follows to authenticated;
grant select on public.listening_activity to authenticated;
grant select on public.now_playing to authenticated;

-- Public profile social graph. Existing write policies are untouched.
drop policy if exists "orb read profile follow graph" on public.follows;
create policy "orb read profile follow graph"
on public.follows
for select
to authenticated
using (public.orb_follow_row_visible(follower_id, following_id));

-- Remove the incorrectly-public policies from the earlier draft and replace
-- them with follower-only Stats/Friends visibility.
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

-- Profile-only monthly projection. It deliberately bypasses listening_activity
-- RLS so favorite music can be public without making live/recent friend status
-- public. Timestamps are coarsened to the month; the view is for ranking only,
-- not for reconstructing somebody's listening timeline.
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
        greatest(
            0,
            round(extract(epoch from (coalesce(la.finished_at, la.started_at) - la.started_at)) * 1000)
        )::bigint
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
        greatest(
            0,
            round(extract(epoch from (coalesce(la.finished_at, la.started_at) - la.started_at)) * 1000)
        )::bigint
      )::numeric / la.duration_ms::numeric >= 0.30
  and public.orb_profile_favorites_visible(la.user_id);

-- PostgreSQL views execute with the view owner's privileges by default. Keep
-- this profile projection read-only to app users and expose only the columns
-- needed by the existing ranking code.
revoke all on public.profile_public_monthly_activity from public;
grant select on public.profile_public_monthly_activity to authenticated;

-- Play totals are profile metadata, not the Friends activity feed. This view is
-- intentionally aggregate-only and bypasses listening_activity RLS without
-- exposing individual plays.
drop view if exists public.profile_play_counts;
create view public.profile_play_counts
with (security_barrier = true)
as
select
    la.user_id,
    count(*)::bigint as plays_count
from public.listening_activity la
where public.orb_public_profile_visible(la.user_id)
group by la.user_id;

revoke all on public.profile_play_counts from public;
grant select on public.profile_play_counts to authenticated;

-- Social counts remain profile metadata. The follows SELECT policy above keeps
-- blocked relationships out of the caller's view.
create or replace view public.profile_social_counts
with (security_invoker = true)
as
select
    p.id as user_id,
    count(distinct incoming.follower_id)::bigint as followers_count,
    count(distinct outgoing.following_id)::bigint as following_count
from public.profiles p
left join public.follows incoming on incoming.following_id = p.id
left join public.follows outgoing on outgoing.follower_id = p.id
group by p.id;

grant select on public.profile_social_counts to authenticated;
