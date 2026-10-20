-- Orb Music: optional encrypted integration-backup support.
-- First apply 20261014_account_settings_sync.sql and enable Supabase Vault
-- in Dashboard > Database > Extensions. Run only from a privileged SQL editor.
-- Last.fm, ListenBrainz, and Discord credential sync require this migration.
-- Spotify sp_dc cookies, YouTube sessions, and hardware auth never sync.

create schema if not exists extensions;
create extension if not exists pgcrypto with schema extensions;
-- Enable Supabase Vault through the Dashboard before running this migration.
-- The Vault extension installation/schema varies across Supabase instances;
-- do not attempt to recreate an extension already managed by Supabase.
do $$
begin
    if to_regclass('vault.secrets') is null then
        raise exception 'Supabase Vault is not enabled. Enable the Vault extension first.';
    end if;
end; $$;

-- SQL access to integration secrets is *never* granted directly to clients.
-- The RPCs below use the signed-in Supabase user's auth.uid() and a key held
-- only in Supabase Vault, and encrypt the payload via pgcrypto before storage.
create table if not exists public.orb_private_integrations (
    user_id uuid primary key references auth.users(id) on delete cascade,
    lastfm_cipher bytea,
    listenbrainz_cipher bytea,
    discord_cipher bytea,
    updated_at timestamptz not null default now()
);
alter table public.orb_private_integrations enable row level security;
revoke all on public.orb_private_integrations from public, anon, authenticated;

-- Seed exactly one random 256-bit key, outside the APK and source repository.
-- Supabase Vault persists/re-encrypts it using its internal key management.
do $$
begin
    if not exists (select 1 from vault.secrets where name = 'orb_integration_cloud_key') then
        perform vault.create_secret(
            encode(extensions.gen_random_bytes(32), 'hex'),
            'orb_integration_cloud_key',
            'Private encryption key for Orb account integration sync'
        );
    end if;
end; $$;

create or replace function public.orb_integrations_read()
returns table(lastfm_session_key text, listenbrainz_token text, discord_token text)
language plpgsql security definer set search_path = '' as $$
declare
    v_user uuid := auth.uid();
    v_key text;
begin
    if v_user is null then raise exception 'Not authenticated' using errcode = '28000'; end if;
    select decrypted_secret into v_key from vault.decrypted_secrets
      where name = 'orb_integration_cloud_key' limit 1;
    if v_key is null then raise exception 'Orb Vault key not configured'; end if;
    return query
    select
        case when i.lastfm_cipher is null then ''::text
             else extensions.pgp_sym_decrypt(i.lastfm_cipher, v_key) end,
        case when i.listenbrainz_cipher is null then ''::text
             else extensions.pgp_sym_decrypt(i.listenbrainz_cipher, v_key) end,
        case when i.discord_cipher is null then ''::text
             else extensions.pgp_sym_decrypt(i.discord_cipher, v_key) end
    from public.orb_private_integrations i where i.user_id = v_user;
end; $$;

create or replace function public.orb_integrations_write(p_provider text, p_secret text)
returns void language plpgsql security definer set search_path = '' as $$
declare
    v_user uuid := auth.uid();
    v_key text;
    v_cipher bytea;
begin
    if v_user is null then raise exception 'Not authenticated' using errcode = '28000'; end if;
    if p_provider not in ('lastfm','listenbrainz','discord') then
        raise exception 'Unsupported integration';
    end if;
    if p_secret is null or length(p_secret) > 8192 then raise exception 'Invalid credential length'; end if;
    select decrypted_secret into v_key from vault.decrypted_secrets
      where name = 'orb_integration_cloud_key' limit 1;
    if v_key is null then raise exception 'Orb Vault key not configured'; end if;
    v_cipher := case when p_secret = '' then null
             else extensions.pgp_sym_encrypt(p_secret, v_key, 'cipher-algo=aes256') end;
    insert into public.orb_private_integrations(user_id, lastfm_cipher, listenbrainz_cipher, discord_cipher)
    values (
        v_user,
        case when p_provider = 'lastfm' then v_cipher else null end,
        case when p_provider = 'listenbrainz' then v_cipher else null end,
        case when p_provider = 'discord' then v_cipher else null end
    )
    on conflict (user_id) do update set
        lastfm_cipher = case when p_provider = 'lastfm' then excluded.lastfm_cipher else orb_private_integrations.lastfm_cipher end,
        listenbrainz_cipher = case when p_provider = 'listenbrainz' then excluded.listenbrainz_cipher else orb_private_integrations.listenbrainz_cipher end,
        discord_cipher = case when p_provider = 'discord' then excluded.discord_cipher else orb_private_integrations.discord_cipher end,
        updated_at = now();
end; $$;

revoke all on function public.orb_integrations_read() from public, anon;
revoke all on function public.orb_integrations_write(text, text) from public, anon;
grant execute on function public.orb_integrations_read() to authenticated;
grant execute on function public.orb_integrations_write(text, text) to authenticated;

-- Important: if Vault is not installed/enabled in the Supabase instance,
-- enable it in Supabase Dashboard -> Database -> Extensions before applying.
