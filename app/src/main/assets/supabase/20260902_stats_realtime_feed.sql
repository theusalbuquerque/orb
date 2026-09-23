-- Orb Stats social feed: four-hour listening history + six-hour reaction notifications.
-- Run once in Supabase SQL Editor after the existing social/reaction migrations.

-- listening_activity is now the durable feed source. Realtime UPDATE matters too:
-- a short track may create its row at 30%, then become feed-visible when played_ms
-- reaches 30 seconds; long tracks create the row at 30 seconds and Stats continues
-- ignoring it until the independent 30% rule is reached.
alter table public.listening_activity replica identity full;

-- now_playing is the immediate session signal. At the 30-second edge the
-- playback reporter touches it only after listening_activity has committed, so
-- followers already sitting on Stats receive an immediate push cue.
alter table public.now_playing replica identity full;


create index if not exists listening_activity_recent_feed_idx
    on public.listening_activity (user_id, started_at desc, finished_at desc);

do $$
begin
    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'listening_activity'
    ) then
        alter publication supabase_realtime add table public.listening_activity;
    end if;
end $$;

do $$
begin
    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'now_playing'
    ) then
        alter publication supabase_realtime add table public.now_playing;
    end if;
end $$;

-- Reactions must outlive now_playing, otherwise a completed song cannot remain in
-- Stats for four hours and the listener cannot see its reactions for six hours.
drop trigger if exists cleanup_now_playing_reactions_trigger on public.now_playing;
drop function if exists public.cleanup_now_playing_reactions();

-- Keep reaction rows compact without deleting anything the UI can still show.
create or replace function public.prune_old_orb_reactions()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    delete from public.now_playing_reactions
    where reacted_at < now() - interval '24 hours';
    return null;
end;
$$;

drop trigger if exists prune_old_orb_reactions_trigger on public.now_playing_reactions;
create trigger prune_old_orb_reactions_trigger
after insert or update on public.now_playing_reactions
for each statement execute function public.prune_old_orb_reactions();

-- A reaction is valid for the current live session OR for a recent 4-hour
-- listening_activity row. This preserves the existing "followed users only"
-- rule while allowing reactions on cards after playback has ended. The existing
-- SELECT/DELETE policies are intentionally kept: listeners can still see their
-- received reactions and reactors can still remove their own reaction.
drop policy if exists "react to followed live sessions" on public.now_playing_reactions;
drop policy if exists "react to followed recent sessions" on public.now_playing_reactions;
create policy "react to followed recent sessions"
on public.now_playing_reactions
for insert
to authenticated
with check (
    now_playing_reactions.reactor_id = auth.uid()
    and now_playing_reactions.listener_id <> auth.uid()
    and exists (
        select 1
        from public.follows f
        where f.follower_id = auth.uid()
          and f.following_id = now_playing_reactions.listener_id
    )
    and (
        exists (
            select 1
            from public.now_playing np
            where np.user_id = now_playing_reactions.listener_id
              and np.video_id = now_playing_reactions.video_id
              and np.started_at = now_playing_reactions.activity_started_at
        )
        or exists (
            select 1
            from public.listening_activity la
            where la.user_id = now_playing_reactions.listener_id
              and la.video_id = now_playing_reactions.video_id
              and la.started_at = now_playing_reactions.activity_started_at
              and coalesce(la.finished_at, la.started_at) >= now() - interval '4 hours'
        )
    )
);

drop policy if exists "change own live reaction" on public.now_playing_reactions;
drop policy if exists "change own recent reaction" on public.now_playing_reactions;
create policy "change own recent reaction"
on public.now_playing_reactions
for update
to authenticated
using (now_playing_reactions.reactor_id = auth.uid())
with check (
    now_playing_reactions.reactor_id = auth.uid()
    and now_playing_reactions.listener_id <> auth.uid()
    and exists (
        select 1
        from public.follows f
        where f.follower_id = auth.uid()
          and f.following_id = now_playing_reactions.listener_id
    )
    and (
        exists (
            select 1
            from public.now_playing np
            where np.user_id = now_playing_reactions.listener_id
              and np.video_id = now_playing_reactions.video_id
              and np.started_at = now_playing_reactions.activity_started_at
        )
        or exists (
            select 1
            from public.listening_activity la
            where la.user_id = now_playing_reactions.listener_id
              and la.video_id = now_playing_reactions.video_id
              and la.started_at = now_playing_reactions.activity_started_at
              and coalesce(la.finished_at, la.started_at) >= now() - interval '4 hours'
        )
    )
);
