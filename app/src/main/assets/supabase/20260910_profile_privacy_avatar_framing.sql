-- Orb profile privacy persistence + independent avatar icon framing.
-- Run once in Supabase SQL Editor.

alter table public.profiles
    add column if not exists favorite_artists_visibility text not null default 'followers',
    add column if not exists favorite_albums_visibility text not null default 'followers',
    add column if not exists avatar_icon_url text;

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'profiles_favorite_artists_visibility_check'
          and conrelid = 'public.profiles'::regclass
    ) then
        alter table public.profiles
            add constraint profiles_favorite_artists_visibility_check
            check (favorite_artists_visibility in ('everyone', 'followers', 'mutuals', 'nobody'));
    end if;

    if not exists (
        select 1 from pg_constraint
        where conname = 'profiles_favorite_albums_visibility_check'
          and conrelid = 'public.profiles'::regclass
    ) then
        alter table public.profiles
            add constraint profiles_favorite_albums_visibility_check
            check (favorite_albums_visibility in ('everyone', 'followers', 'mutuals', 'nobody'));
    end if;
end $$;

-- Existing accounts fall back to avatar_url until a separately framed icon is saved.
update public.profiles
set avatar_icon_url = avatar_url
where avatar_icon_url is null
  and avatar_url is not null;
