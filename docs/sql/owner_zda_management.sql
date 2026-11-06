-- Narrow management RPCs for the private developer-only ZDA records.
-- Card creation/approval remains server-only; these RPCs cannot approve a card.
create or replace function public.orb_owner_zda_verified_uid()
returns uuid language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := (select auth.uid());
begin
  if v_uid is null then
    raise exception 'Authentication required' using errcode = '42501';
  end if;
  if not exists (
    select 1 from auth.users u where u.id = v_uid and u.email_confirmed_at is not null
    and pg_catalog.encode(extensions.digest(pg_catalog.lower(pg_catalog.btrim(u.email)), 'sha256'), 'hex')
      = '2c8c3e1d1bcef1415230705c38bafb7403905850a7f37c5206bd5cfc055c6aeb'
  ) then
    raise exception 'Account is not authorized' using errcode = '42501';
  end if;
  return v_uid;
end;
$$;
revoke all on function public.orb_owner_zda_verified_uid() from public, anon, authenticated;

create or replace function public.orb_owner_zda_status()
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := public.orb_owner_zda_verified_uid(); v_status text;
begin
  select a.status into v_status from public.orb_owner_zda_authorizations a
    where a.user_id = v_uid and a.status in ('approved', 'canceled')
    order by a.created_at desc, a.id desc limit 1;
  return pg_catalog.jsonb_build_object('userId', v_uid,
    'subscribed', coalesce(v_status = 'approved', false),
    'status', coalesce(v_status, 'not_subscribed'), 'amount', 0,
    'validationProvider', case when v_status is not null then 'mercadopago_zda' else null end);
end;
$$;
revoke all on function public.orb_owner_zda_status() from public, anon, authenticated;
grant execute on function public.orb_owner_zda_status() to authenticated;

create or replace function public.orb_owner_zda_cancel()
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := public.orb_owner_zda_verified_uid();
begin
  -- Includes in-flight validations so their compare-and-set approval cannot
  -- resurrect a test authorization after this transaction commits.
  update public.orb_owner_zda_authorizations a
    set status = 'canceled', updated_at = pg_catalog.now()
    where a.user_id = v_uid and a.status in ('pending', 'processing', 'approved');
  return public.orb_owner_zda_status();
end;
$$;
revoke all on function public.orb_owner_zda_cancel() from public, anon, authenticated;
grant execute on function public.orb_owner_zda_cancel() to authenticated;
