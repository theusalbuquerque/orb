-- Orb profile privacy controls.
-- Existing installations can apply this migration without changing rows or RLS.
alter table public.profiles
    add column if not exists favorite_artists_visibility text not null default 'followers',
    add column if not exists favorite_albums_visibility text not null default 'followers';

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'profiles_favorite_artists_visibility_check'
    ) then
        alter table public.profiles
            add constraint profiles_favorite_artists_visibility_check
            check (favorite_artists_visibility in ('everyone', 'followers', 'mutuals', 'nobody'));
    end if;
end $$;

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'profiles_favorite_albums_visibility_check'
    ) then
        alter table public.profiles
            add constraint profiles_favorite_albums_visibility_check
            check (favorite_albums_visibility in ('everyone', 'followers', 'mutuals', 'nobody'));
    end if;
end $$;
