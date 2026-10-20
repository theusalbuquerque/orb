-- Apply after album_ratings.sql. Existing private ratings stay private.
alter table public.album_ratings add column if not exists is_public boolean not null default false;
drop policy if exists "album_ratings_read_own" on public.album_ratings;
create policy "album_ratings_read_own" on public.album_ratings for select to authenticated
using ((select auth.uid()) = user_id or is_public);
create index if not exists album_ratings_public_recent_idx
on public.album_ratings(album_id, updated_at desc) where is_public;
create index if not exists album_ratings_profile_recent_idx
on public.album_ratings(user_id, updated_at desc);

create or replace function public.orb_recent_album_ratings(p_album_ids text[], p_limit integer default 30)
returns table(user_id uuid, album_id text, rating_tenths smallint, updated_at timestamptz, username text, avatar_url text)
language sql stable security invoker set search_path = pg_catalog, public as $$
 select r.user_id, r.album_id, r.rating_tenths, r.updated_at, p.username,
        coalesce(nullif(to_jsonb(p)->>'avatar_icon_url', ''), p.avatar_url)
 from public.album_ratings r join public.profiles p on p.id = r.user_id
 where (select auth.uid()) is not null and r.is_public and r.album_id = any(p_album_ids)
 order by r.updated_at desc, r.user_id, r.album_id
 limit greatest(1, least(coalesce(p_limit, 30), 100));
$$;
revoke all on function public.orb_recent_album_ratings(text[], integer) from public, anon;
grant execute on function public.orb_recent_album_ratings(text[], integer) to authenticated;
notify pgrst, 'reload schema';
