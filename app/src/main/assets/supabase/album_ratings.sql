create table if not exists public.album_ratings (
  user_id uuid not null references auth.users(id) on delete cascade,
  album_id text not null check (length(btrim(album_id)) between 1 and 512),
  rating_tenths smallint not null check (rating_tenths between 0 and 50),
  updated_at timestamptz not null default now(),
  primary key (user_id, album_id)
);
alter table public.album_ratings enable row level security;
revoke all on public.album_ratings from anon;
grant select, insert, update on public.album_ratings to authenticated;
create policy "album_ratings_read_own" on public.album_ratings for select to authenticated using ((select auth.uid()) = user_id);
create policy "album_ratings_insert_own" on public.album_ratings for insert to authenticated with check ((select auth.uid()) = user_id);
create policy "album_ratings_update_own" on public.album_ratings for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create or replace function public.orb_album_rating_timestamp() returns trigger language plpgsql security invoker set search_path = pg_catalog as $$
begin new.updated_at := clock_timestamp(); return new; end $$;
revoke all on function public.orb_album_rating_timestamp() from public, anon;
grant execute on function public.orb_album_rating_timestamp() to authenticated;
create trigger album_rating_timestamp before insert or update on public.album_ratings for each row execute function public.orb_album_rating_timestamp();
notify pgrst, 'reload schema';
