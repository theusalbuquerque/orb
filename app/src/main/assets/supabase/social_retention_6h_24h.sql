-- Expand recent-session reactions to six hours; existing 24-hour cleanup remains unchanged.
begin;
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
              and coalesce(la.finished_at, la.started_at) >= now() - interval '6 hours'
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
              and coalesce(la.finished_at, la.started_at) >= now() - interval '6 hours'
        )
    )
);

commit;
