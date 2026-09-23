-- Orb unified favorites privacy
-- Run once in Supabase SQL Editor before releasing this app version.
--
-- Product rule:
--   - favorite artists, albums and songs share ONE audience;
--   - default is EVERYONE for new users;
--   - all existing users migrate to EVERYONE once.

alter table public.profiles
    add column if not exists favorite_content_visibility text not null default 'everyone';

alter table public.profiles
    alter column favorite_content_visibility set default 'everyone';

alter table public.profiles
    alter column favorite_artists_visibility set default 'everyone';

alter table public.profiles
    alter column favorite_albums_visibility set default 'everyone';

update public.profiles
set
    favorite_content_visibility = 'everyone',
    favorite_artists_visibility = 'everyone',
    favorite_albums_visibility = 'everyone';

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'profiles_favorite_content_visibility_check'
    ) then
        alter table public.profiles
            add constraint profiles_favorite_content_visibility_check
            check (favorite_content_visibility in ('everyone', 'followers', 'mutuals', 'nobody'));
    end if;
end
$$;
