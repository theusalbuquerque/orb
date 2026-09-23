-- Orb-owned song ratings.
-- This table is independent from the optional YouTube Music library. Orb keeps
-- the account state here and mirrors writes to YouTube immediately whenever
-- that integration is connected.

create table if not exists public.song_ratings (
    user_id uuid not null references auth.users(id) on delete cascade,
    video_id text not null,
    recording_key text,
    status text not null,
    title text,
    artist text,
    album text,
    duration_text text,
    updated_at timestamptz not null default now(),
    primary key (user_id, video_id),
    constraint song_ratings_video_id_not_blank check (length(btrim(video_id)) > 0),
    constraint song_ratings_status_valid check (status in ('LIKE', 'DISLIKE', 'INDIFFERENT'))
);

alter table public.song_ratings enable row level security;

grant select, insert, update, delete on public.song_ratings to authenticated;

drop policy if exists "read own song ratings" on public.song_ratings;
create policy "read own song ratings"
on public.song_ratings for select
to authenticated
using (user_id = auth.uid());

drop policy if exists "create own song ratings" on public.song_ratings;
create policy "create own song ratings"
on public.song_ratings for insert
to authenticated
with check (user_id = auth.uid());

drop policy if exists "update own song ratings" on public.song_ratings;
create policy "update own song ratings"
on public.song_ratings for update
to authenticated
using (user_id = auth.uid())
with check (user_id = auth.uid());

drop policy if exists "delete own song ratings" on public.song_ratings;
create policy "delete own song ratings"
on public.song_ratings for delete
to authenticated
using (user_id = auth.uid());

create index if not exists song_ratings_user_updated_idx
    on public.song_ratings (user_id, updated_at desc);

create index if not exists song_ratings_user_recording_idx
    on public.song_ratings (user_id, recording_key)
    where recording_key is not null;

-- Upserts from an offline/older device must never overwrite a newer action that
-- already reached the account. The client preserves action timestamps across
-- retries, so the database can safely discard stale conflict updates.
create or replace function public.orb_keep_newest_song_rating()
returns trigger
language plpgsql
as $$
begin
    if new.updated_at < old.updated_at then
        return old;
    end if;
    return new;
end;
$$;

drop trigger if exists orb_keep_newest_song_rating on public.song_ratings;
create trigger orb_keep_newest_song_rating
before update on public.song_ratings
for each row execute function public.orb_keep_newest_song_rating();
