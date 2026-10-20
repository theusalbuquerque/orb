-- Orb Music: per-account preference backup (no secrets).
-- Run in Supabase SQL Editor as a privileged project administrator.
-- Run this migration BEFORE installing the new APK.
-- The Android client uses only the authenticated publishable/anon key plus RLS.

create table if not exists public.orb_account_settings (
    user_id uuid primary key references auth.users(id) on delete cascade,
    schema_version integer not null default 1,
    app_settings jsonb not null default '{}'::jsonb,
    source_configs jsonb not null default '[]'::jsonb,
    artist_preferences jsonb not null default '{}'::jsonb,
    album_exclusions jsonb not null default '{}'::jsonb,
    album_recordings jsonb not null default '{}'::jsonb,
    app_locale text not null default '',
    updated_at timestamptz not null default now()
);
alter table public.orb_account_settings add column if not exists app_locale text not null default '';
alter table public.orb_account_settings add column if not exists updated_at timestamptz not null default now();

alter table public.orb_account_settings enable row level security;
revoke all on public.orb_account_settings from anon, public;
grant select, insert, update, delete on public.orb_account_settings to authenticated;

-- Remove previous permissive policies, if any; account data must be private.
do $$
declare p record;
begin
    for p in select policyname from pg_policies
             where schemaname = 'public' and tablename = 'orb_account_settings' loop
        execute format('drop policy if exists %I on public.orb_account_settings', p.policyname);
    end loop;
end; $$;

drop policy if exists orb_account_settings_owner_insert on public.orb_account_settings;
drop policy if exists orb_account_settings_owner_update on public.orb_account_settings;
drop policy if exists orb_account_settings_owner_delete on public.orb_account_settings;
create policy orb_account_settings_owner_select on public.orb_account_settings for select to authenticated
 using (auth.uid() = user_id);
create policy orb_account_settings_owner_insert on public.orb_account_settings for insert to authenticated
 with check (auth.uid() = user_id);
create policy orb_account_settings_owner_update on public.orb_account_settings for update to authenticated
 using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy orb_account_settings_owner_delete on public.orb_account_settings for delete to authenticated
 using (auth.uid() = user_id);

create or replace function public.orb_account_settings_touched()
returns trigger language plpgsql set search_path = '' as $$
begin
    new.updated_at := now();
    return new;
end; $$;
drop trigger if exists orb_account_settings_updated_at on public.orb_account_settings;
create trigger orb_account_settings_updated_at before update on public.orb_account_settings
 for each row execute function public.orb_account_settings_touched();

