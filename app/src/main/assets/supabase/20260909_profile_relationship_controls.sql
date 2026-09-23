-- Orb profile relationship controls: block, remove follower, and follow guard.

create table if not exists public.profile_blocks (
    blocker_id uuid not null references auth.users(id) on delete cascade,
    blocked_id uuid not null references auth.users(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (blocker_id, blocked_id),
    constraint profile_blocks_no_self check (blocker_id <> blocked_id)
);

alter table public.profile_blocks enable row level security;

grant select, insert, update, delete on public.profile_blocks to authenticated;

drop policy if exists "read own profile blocks" on public.profile_blocks;
create policy "read own profile blocks"
on public.profile_blocks for select
to authenticated
using (blocker_id = auth.uid());

drop policy if exists "create own profile blocks" on public.profile_blocks;
create policy "create own profile blocks"
on public.profile_blocks for insert
to authenticated
with check (blocker_id = auth.uid() and blocked_id <> auth.uid());

drop policy if exists "update own profile blocks" on public.profile_blocks;
create policy "update own profile blocks"
on public.profile_blocks for update
to authenticated
using (blocker_id = auth.uid())
with check (blocker_id = auth.uid() and blocked_id <> auth.uid());

drop policy if exists "delete own profile blocks" on public.profile_blocks;
create policy "delete own profile blocks"
on public.profile_blocks for delete
to authenticated
using (blocker_id = auth.uid());

-- The followed account may remove an incoming follower without gaining access
-- to any unrelated follows row. Existing follower-owned delete policies remain.
drop policy if exists "remove own followers" on public.follows;
create policy "remove own followers"
on public.follows for delete
to authenticated
using (following_id = auth.uid());

create or replace function public.orb_prevent_blocked_follow()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if exists (
        select 1
        from public.profile_blocks b
        where (b.blocker_id = new.follower_id and b.blocked_id = new.following_id)
           or (b.blocker_id = new.following_id and b.blocked_id = new.follower_id)
    ) then
        raise exception 'ORB_BLOCKED_RELATIONSHIP';
    end if;
    return new;
end;
$$;

drop trigger if exists orb_prevent_blocked_follow on public.follows;
create trigger orb_prevent_blocked_follow
before insert or update on public.follows
for each row execute function public.orb_prevent_blocked_follow();

create index if not exists profile_blocks_blocked_idx
    on public.profile_blocks (blocked_id);
