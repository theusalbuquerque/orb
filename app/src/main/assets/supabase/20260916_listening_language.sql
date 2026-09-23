-- Orb Stats: store the detected language of the actual recording/listen.
-- The app derives this primarily from the loaded lyrics and only falls back to
-- high-confidence metadata signals. Existing rows stay NULL rather than being
-- guessed retroactively from short titles.

alter table public.listening_activity
    add column if not exists language_code text;

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'listening_activity_language_code_format'
          and conrelid = 'public.listening_activity'::regclass
    ) then
        alter table public.listening_activity
            add constraint listening_activity_language_code_format
            check (
                language_code is null
                or language_code ~ '^[a-z]{2,3}$'
            );
    end if;
end $$;

create index if not exists listening_activity_user_language_idx
    on public.listening_activity (user_id, language_code)
    where language_code is not null;

comment on column public.listening_activity.language_code is
    'ISO 639 language code detected for the recording, preferably from lyrics; NULL when uncertain.';
