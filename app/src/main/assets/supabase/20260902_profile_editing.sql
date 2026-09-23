-- Orb profile editing: custom avatars, protected username cooldown and play totals.
-- Run once in Supabase SQL Editor after the existing profile/social migrations.

alter table public.profiles
    add column if not exists username_updated_at timestamptz;

create or replace function public.enforce_orb_username_change()
returns trigger
language plpgsql
set search_path = public
as $$
begin
    if new.username is not distinct from old.username then
        return new;
    end if;

    new.username := lower(trim(leading '@' from trim(new.username)));
    if new.username !~ '^[a-z0-9._]{3,30}$' then
        raise exception 'Orb username must contain 3-30 lowercase letters, numbers, dots or underscores.'
            using errcode = 'check_violation';
    end if;

    -- Existing accounts receive one migration-time change. After the first
    -- edit, the timestamp makes the one-change-per-month rule authoritative
    -- on the server instead of relying on a disabled client text field.
    if nullif(old.username, '') is not null
       and old.username_updated_at is not null
       and old.username_updated_at + interval '1 month' > now() then
        raise exception 'Orb username can only be changed once per month.'
            using errcode = 'check_violation';
    end if;

    new.username_updated_at := now();
    return new;
end;
$$;

do $$
begin
    if not exists (
        select 1 from pg_policies
        where schemaname = 'storage'
          and tablename = 'objects'
          and policyname = 'orb profile avatars select own'
    ) then
        create policy "orb profile avatars select own"
        on storage.objects for select to authenticated
        using (
            bucket_id = 'profile-avatars'
            and (storage.foldername(name))[1] = auth.uid()::text
        );
    end if;

    if not exists (
        select 1
        from pg_trigger
        where tgname = 'enforce_orb_username_change_trigger'
          and tgrelid = 'public.profiles'::regclass
          and not tgisinternal
    ) then
        execute 'create trigger enforce_orb_username_change_trigger
                 before update of username on public.profiles
                 for each row execute function public.enforce_orb_username_change()';
    end if;
end $$;

-- Security-invoker keeps the listening_activity RLS policy authoritative:
-- visitors only receive a total when they are already allowed to see that
-- profile's listening history; the owner always receives their own total.
create or replace view public.profile_play_counts
with (security_invoker = true)
as
select
    user_id,
    count(*)::bigint as plays_count
from public.listening_activity
group by user_id;

grant select on public.profile_play_counts to authenticated;

-- Publicly readable profile pictures, but only the authenticated owner may
-- create or replace the object inside their UUID folder.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'profile-avatars',
    'profile-avatars',
    true,
    5242880,
    array['image/jpeg', 'image/png', 'image/webp', 'image/heic', 'image/heif']
)
on conflict (id) do update set
    public = excluded.public,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

do $$
begin
    if not exists (
        select 1 from pg_policies
        where schemaname = 'storage'
          and tablename = 'objects'
          and policyname = 'orb profile avatars insert own'
    ) then
        create policy "orb profile avatars insert own"
        on storage.objects for insert to authenticated
        with check (
            bucket_id = 'profile-avatars'
            and (storage.foldername(name))[1] = auth.uid()::text
        );
    end if;

    if not exists (
        select 1 from pg_policies
        where schemaname = 'storage'
          and tablename = 'objects'
          and policyname = 'orb profile avatars update own'
    ) then
        create policy "orb profile avatars update own"
        on storage.objects for update to authenticated
        using (
            bucket_id = 'profile-avatars'
            and (storage.foldername(name))[1] = auth.uid()::text
        )
        with check (
            bucket_id = 'profile-avatars'
            and (storage.foldername(name))[1] = auth.uid()::text
        );
    end if;

    if not exists (
        select 1 from pg_policies
        where schemaname = 'storage'
          and tablename = 'objects'
          and policyname = 'orb profile avatars delete own'
    ) then
        create policy "orb profile avatars delete own"
        on storage.objects for delete to authenticated
        using (
            bucket_id = 'profile-avatars'
            and (storage.foldername(name))[1] = auth.uid()::text
        );
    end if;
end $$;
