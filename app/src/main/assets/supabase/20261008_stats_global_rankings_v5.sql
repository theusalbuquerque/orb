-- Orb Stats Global Rankings v5: safely discover historical album IDs which
-- are missing artist metadata in the legacy rating storage.
-- Run after 20261008_stats_global_rankings_v4.sql.
-- Only the signed-in account can request IDs, and only IDs it rated itself.
-- The Android client resolves the release from the music catalogue and invokes
-- orb_stats_register_rated_album; old ratings are never modified or re-dated.
create or replace function public.orb_stats_missing_rated_albums()
returns table (album_id text)
language sql stable security definer
set search_path = pg_catalog, public
as $$
    select r.album_id
    from public.album_ratings r
    where (select auth.uid()) is not null
      and r.user_id = (select auth.uid())
      and not exists (
          select 1 from public.orb_stats_album_catalog c where c.album_id = r.album_id
      )
    order by r.updated_at desc
    limit 500;
$$;
revoke all on function public.orb_stats_missing_rated_albums() from public, anon;
grant execute on function public.orb_stats_missing_rated_albums() to authenticated;
notify pgrst, 'reload schema';
