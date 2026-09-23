-- Orb live-listening reactions.
-- Run once in Supabase SQL Editor, after the existing social tables.

create table if not exists public.now_playing_reactions (
    listener_id uuid not null references public.profiles(id) on delete cascade,
    reactor_id uuid not null references auth.users(id) on delete cascade,
    video_id text not null,
    activity_started_at timestamptz not null,
    reaction text not null check (
        reaction in ('heart', 'clap', 'sad', 'surprised', 'smile', 'thumbs_up', 'thumbs_down')
    ),
    reacted_at timestamptz not null default now(),
    primary key (listener_id, reactor_id, video_id, activity_started_at)
);

create index if not exists now_playing_reactions_listener_idx
    on public.now_playing_reactions (listener_id, activity_started_at desc);

alter table public.now_playing_reactions enable row level security;
alter table public.now_playing_reactions replica identity full;

drop policy if exists "read visible live reactions" on public.now_playing_reactions;
create policy "read visible live reactions"
on public.now_playing_reactions
for select
to authenticated
using (
    now_playing_reactions.reactor_id = auth.uid()
    or now_playing_reactions.listener_id = auth.uid()
    or exists (
        select 1
        from public.follows f
        where f.follower_id = auth.uid()
          and f.following_id = now_playing_reactions.listener_id
    )
);

drop policy if exists "react to followed live sessions" on public.now_playing_reactions;
create policy "react to followed live sessions"
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
    and exists (
        select 1
        from public.now_playing np
        where np.user_id = now_playing_reactions.listener_id
          and np.video_id = now_playing_reactions.video_id
          and np.started_at = now_playing_reactions.activity_started_at
    )
);

drop policy if exists "change own live reaction" on public.now_playing_reactions;
create policy "change own live reaction"
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
    and exists (
        select 1
        from public.now_playing np
        where np.user_id = now_playing_reactions.listener_id
          and np.video_id = now_playing_reactions.video_id
          and np.started_at = now_playing_reactions.activity_started_at
    )
);

drop policy if exists "remove own live reaction" on public.now_playing_reactions;
create policy "remove own live reaction"
on public.now_playing_reactions
for delete
to authenticated
using (now_playing_reactions.reactor_id = auth.uid());

grant select, insert, update, delete on public.now_playing_reactions to authenticated;

-- A reaction belongs only to the current song. Changing or clearing the
-- now_playing row removes the previous session's reactions automatically.
create or replace function public.cleanup_now_playing_reactions()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if tg_op = 'DELETE' then
        delete from public.now_playing_reactions
        where listener_id = old.user_id;
        return old;
    end if;

    delete from public.now_playing_reactions
    where listener_id = new.user_id
      and (video_id, activity_started_at) is distinct from (new.video_id, new.started_at);
    return new;
end;
$$;

drop trigger if exists cleanup_now_playing_reactions_trigger on public.now_playing;
create trigger cleanup_now_playing_reactions_trigger
after insert or update or delete on public.now_playing
for each row execute function public.cleanup_now_playing_reactions();

do $$
begin
    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'now_playing_reactions'
    ) then
        alter publication supabase_realtime add table public.now_playing_reactions;
    end if;
end $$;
