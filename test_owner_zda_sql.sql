begin;
do $$
declare
 v_owner uuid; v_other uuid; v_a uuid := pg_catalog.gen_random_uuid();
 v_b uuid := pg_catalog.gen_random_uuid(); v_c uuid := pg_catalog.gen_random_uuid(); r jsonb;
begin
 if pg_catalog.has_function_privilege('anon', 'public.orb_owner_zda_status()', 'EXECUTE')
    or pg_catalog.has_function_privilege('anon', 'public.orb_owner_zda_cancel()', 'EXECUTE')
    or pg_catalog.has_function_privilege('authenticated', 'public.orb_owner_zda_verified_uid()', 'EXECUTE') then
   raise exception 'Unexpected anonymous/private helper permission';
 end if;
 if not pg_catalog.has_function_privilege('authenticated', 'public.orb_owner_zda_status()', 'EXECUTE')
    or not pg_catalog.has_function_privilege('authenticated', 'public.orb_owner_zda_cancel()', 'EXECUTE') then
   raise exception 'Missing management permission';
 end if;
 perform pg_catalog.set_config('request.jwt.claims', '{}', true);
 begin
   perform public.orb_owner_zda_status();
   raise exception 'Unauthenticated status call was accepted';
 exception when insufficient_privilege then null;
 end;
 select u.id into v_owner from auth.users u where u.email_confirmed_at is not null
   and pg_catalog.encode(extensions.digest(pg_catalog.lower(pg_catalog.btrim(u.email)), 'sha256'),'hex')
   = '2c8c3e1d1bcef1415230705c38bafb7403905850a7f37c5206bd5cfc055c6aeb' limit 1;
 if v_owner is null then raise exception 'Verified developer account is absent'; end if;
 select u.id into v_other from auth.users u where u.id <> v_owner limit 1;
 if v_other is not null then
   perform pg_catalog.set_config('request.jwt.claims', pg_catalog.jsonb_build_object('sub',v_other,'role','authenticated')::text, true);
   begin
     perform public.orb_owner_zda_cancel();
     raise exception 'Non-owner cancellation was accepted';
   exception when insufficient_privilege then null;
   end;
 end if;
 -- All fixture writes and cancellation effects below are rolled back.
 perform pg_catalog.set_config('request.jwt.claims', pg_catalog.jsonb_build_object('sub',v_owner,'role','authenticated')::text, true);
 insert into public.orb_owner_zda_authorizations(id,user_id,checkout_secret_hash,status,expires_at,created_at)
 values(v_a,v_owner,pg_catalog.repeat('0',64),'approved',now()+interval '2 hours',now()+interval '1 hour'),
       (v_b,v_owner,pg_catalog.repeat('1',64),'processing',now()+interval '2 hours',now()+interval '1 hour');
 if v_other is not null then
   insert into public.orb_owner_zda_authorizations(id,user_id,checkout_secret_hash,status,expires_at)
   values(v_c,v_other,pg_catalog.repeat('2',64),'approved',now()+interval '2 hours');
 end if;
 r := public.orb_owner_zda_status();
 if r->>'userId' <> v_owner::text or r->>'subscribed' <> 'true' then raise exception 'Status mismatch'; end if;
 r := public.orb_owner_zda_cancel();
 if r->>'subscribed' <> 'false' then raise exception 'Cancellation was not confirmed'; end if;
 if exists(select 1 from public.orb_owner_zda_authorizations where id in (v_a,v_b) and status <> 'canceled') then
   raise exception 'Approved/in-flight authorization survived cancellation';
 end if;
 if v_other is not null and not exists(select 1 from public.orb_owner_zda_authorizations where id=v_c and status='approved') then
   raise exception 'Another account was modified';
 end if;
 r := public.orb_owner_zda_cancel();
 if r->>'subscribed' <> 'false' then raise exception 'Cancellation was not idempotent'; end if;
end;
$$;
rollback;
select 'owner management assertions passed; all test writes rolled back' as result;
