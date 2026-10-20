-- Orb server-driven entitlements + remote config.
-- Run once in the Supabase SQL editor. These tables intentionally have RLS
-- enabled without client policies: only the backend service-role may read/write.

create table if not exists public.user_entitlements (
    user_id uuid primary key references auth.users(id) on delete cascade,
    account_hash text unique null,
    premium_override boolean null,
    premium_plan text null,
    premium_source text not null default 'manual',
    premium_expires_at timestamptz null,
    notes text null,
    updated_at timestamptz not null default now()
);

alter table public.user_entitlements add column if not exists account_hash text;
create unique index if not exists idx_user_entitlements_account_hash
    on public.user_entitlements(account_hash) where account_hash is not null;

alter table public.user_entitlements enable row level security;
revoke all on table public.user_entitlements from anon, authenticated;

create table if not exists public.app_remote_config (
    id text primary key,
    config jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now(),
    constraint app_remote_config_orb_id check (id = 'orb')
);

alter table public.app_remote_config enable row level security;
revoke all on table public.app_remote_config from anon, authenticated;

insert into public.app_remote_config(id, config)
values (
    'orb',
    jsonb_build_object(
        'automix25Enabled', true,
        'orbSwitchEnabled', true,
        'premiumCardEnabled', true,
        'roomsEnabled', true,
        'losslessMobileEnabled', true,
        'refreshIntervalSeconds', 60
    )
)
on conflict (id) do nothing;

-- Examples (run as project owner/service role, never from the Android client):
-- Grant Premium indefinitely:
-- insert into public.user_entitlements(user_id, premium_override, premium_plan, premium_source)
-- values ('USER_UUID', true, 'manual', 'manual')
-- on conflict (user_id) do update set premium_override=true, premium_plan='manual',
-- premium_source='manual', premium_expires_at=null, updated_at=now();
--
-- Force Premium off:
-- update public.user_entitlements set premium_override=false, updated_at=now()
-- where user_id='USER_UUID';
--
-- Return the account to automatic billing entitlement:
-- update public.user_entitlements set premium_override=null, updated_at=now()
-- where user_id='USER_UUID';
--
-- Disable a shipped feature remotely:
-- update public.app_remote_config
-- set config = jsonb_set(config, '{orbSwitchEnabled}', 'false'::jsonb), updated_at=now()
-- where id='orb';
