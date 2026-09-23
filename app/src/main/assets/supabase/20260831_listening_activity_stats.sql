-- Orb Stats: persist playlist attribution on the existing listening_activity table.
-- This does NOT create a new auth system, client, or social table, and does not
-- change/disable any existing RLS policy. Existing rows remain valid.

alter table public.listening_activity
    add column if not exists source_playlist_id text,
    add column if not exists source_playlist_title text,
    add column if not exists source_playlist_artwork_url text;

-- Stats reads are always scoped to the current auth.uid() by the app/RLS.
-- This index keeps period reads efficient as the history grows.
create index if not exists listening_activity_user_started_idx
    on public.listening_activity (user_id, started_at desc);
