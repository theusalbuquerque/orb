-- Orb 1.7.2: protected cross-device Spotify session restoration.
-- Prerequisites: 20261014_account_integration_vault.sql and Supabase Vault enabled.
-- Run from a privileged Supabase SQL Editor. No service-role token goes into APKs.
-- Only the authenticated Orb account may read/write its own cookie via these
-- SECURITY DEFINER functions. Never put sp_dc in app_settings JSON.
-- Note: sp_dc is a bearer session cookie. Treat Supabase admin/Vault access
-- and access to the signed-in Orb account as sensitive. Revoking the Spotify
-- account session at its source remains the definitive way to invalidate it.

alter table public.orb_private_integrations
    add column if not exists spotify_cipher bytea;

-- Explicit NULL means 'never uploaded' (migration/backfill); encrypted empty
-- string means 'disconnected on another device'. This prevents upgrading an
-- existing user from silently disconnecting Spotify before the first backup.
create or replace function public.orb_spotify_credential_read()
returns table(spotify_cookie text)
language plpgsql security definer set search_path = '' as $$
declare
    v_user uuid := auth.uid();
    v_key text;
begin
    if v_user is null then
        raise exception 'Not authenticated' using errcode = '28000';
    end if;
    select decrypted_secret into v_key from vault.decrypted_secrets
        where name = 'orb_integration_cloud_key' limit 1;
    if v_key is null then
        raise exception 'Orb Vault key not configured';
    end if;
    return query
    select case when i.spotify_cipher is null then null::text
           else extensions.pgp_sym_decrypt(i.spotify_cipher, v_key) end
    from public.orb_private_integrations i
    where i.user_id = v_user;
end; $$;

create or replace function public.orb_spotify_credential_write(p_cookie text)
returns void language plpgsql security definer set search_path = '' as $$
declare
    v_user uuid := auth.uid();
    v_key text;
    v_cipher bytea;
begin
    if v_user is null then
        raise exception 'Not authenticated' using errcode = '28000';
    end if;
    if p_cookie is null or length(p_cookie) > 8192 then
        raise exception 'Invalid session length';
    end if;
    select decrypted_secret into v_key from vault.decrypted_secrets
        where name = 'orb_integration_cloud_key' limit 1;
    if v_key is null then
        raise exception 'Orb Vault key not configured';
    end if;
    -- Encrypt even the empty string to distinguish explicit disconnect from
    -- credentials that have never been migrated to the account.
    v_cipher := extensions.pgp_sym_encrypt(p_cookie, v_key, 'cipher-algo=aes256');
    insert into public.orb_private_integrations (user_id, spotify_cipher)
    values (v_user, v_cipher)
    on conflict (user_id) do update set
        spotify_cipher = excluded.spotify_cipher,
        updated_at = now();
end; $$;

revoke all on function public.orb_spotify_credential_read() from public, anon;
revoke all on function public.orb_spotify_credential_write(text) from public, anon;
grant execute on function public.orb_spotify_credential_read() to authenticated;
grant execute on function public.orb_spotify_credential_write(text) to authenticated;
