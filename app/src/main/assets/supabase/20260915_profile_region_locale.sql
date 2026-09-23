-- Orb: coarse account region/language metadata.
-- No GPS coordinates, address or raw IP address are stored.
alter table public.profiles
    add column if not exists country_code text,
    add column if not exists language_code text,
    add column if not exists locale_tag text,
    add column if not exists locale_updated_at timestamptz;

do $$
begin
    if not exists (
        select 1 from pg_constraint where conname = 'profiles_country_code_format'
    ) then
        alter table public.profiles
            add constraint profiles_country_code_format
            check (country_code is null or country_code ~ '^[A-Z]{2}$');
    end if;

    if not exists (
        select 1 from pg_constraint where conname = 'profiles_language_code_format'
    ) then
        alter table public.profiles
            add constraint profiles_language_code_format
            check (language_code is null or language_code ~ '^[a-z]{2,3}$' or language_code = 'und');
    end if;
end
$$;

comment on column public.profiles.country_code is
    'Coarse ISO 3166-1 alpha-2 country inferred on-device from SIM/network/locale; no precise location.';
comment on column public.profiles.language_code is
    'Effective Orb/Android language code at last regional metadata sync.';
comment on column public.profiles.locale_tag is
    'Effective BCP-47 locale tag at last regional metadata sync.';
