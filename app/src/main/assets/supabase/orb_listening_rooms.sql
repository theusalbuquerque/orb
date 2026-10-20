-- Listening rooms: private tables and a single authenticated RPC entry point.
create schema if not exists orb_rooms_private;
revoke all on schema orb_rooms_private from public, anon;
grant usage on schema orb_rooms_private to authenticated;
create table if not exists orb_rooms_private.rooms (
 id uuid primary key default gen_random_uuid(), host_id uuid not null references auth.users(id) on delete cascade,
 host_device text not null, code text not null check(code ~ '^[A-Z0-9]{6}$'),
 title text not null check(length(title) between 1 and 80), max_guests integer not null check(max_guests between 1 and 14),
 cover_path text, status text not null default 'open' check(status in ('open','closed')),
 queue jsonb not null default '[]', queue_version bigint not null default 0,
 track_index integer not null default 0, position_ms bigint not null default 0, playing boolean not null default false,
 playback_at timestamptz not null default clock_timestamp(), host_seen timestamptz not null default clock_timestamp(),
 created_at timestamptz not null default clock_timestamp(), expires_at timestamptz not null default (clock_timestamp()+interval '24 hours')
);
alter table orb_rooms_private.rooms drop constraint if exists rooms_max_guests_check;
alter table orb_rooms_private.rooms add constraint rooms_max_guests_check check(max_guests between 1 and 14);
alter table orb_rooms_private.rooms add column if not exists members_can_add boolean not null default true;
create table if not exists orb_rooms_private.create_tickets (
 id uuid primary key default gen_random_uuid(), user_id uuid not null references auth.users(id) on delete cascade,
 premium boolean not null, expires_at timestamptz not null default (clock_timestamp()+interval '1 minute')
);
create index if not exists orb_room_ticket_user on orb_rooms_private.create_tickets(user_id);
alter table orb_rooms_private.create_tickets enable row level security;
revoke all on orb_rooms_private.create_tickets from public,anon,authenticated;
create or replace function public.orb_room_create_ticket(p_user uuid,p_premium boolean)
returns uuid language plpgsql security definer set search_path='' as $$
declare ticket uuid;
begin
 delete from orb_rooms_private.create_tickets where expires_at<clock_timestamp();
 insert into orb_rooms_private.create_tickets(user_id,premium) values(p_user,p_premium) returning id into ticket;
 return ticket;
end $$;
revoke all on function public.orb_room_create_ticket(uuid,boolean) from public,anon,authenticated;
grant execute on function public.orb_room_create_ticket(uuid,boolean) to service_role;

create unique index if not exists orb_rooms_active_code on orb_rooms_private.rooms(code) where status='open';
drop index if exists orb_rooms_private.orb_rooms_active_host;
create unique index if not exists orb_rooms_playing_host on orb_rooms_private.rooms(host_id) where status='open' and playing;
create index if not exists orb_rooms_host_fk on orb_rooms_private.rooms(host_id);
create index if not exists orb_rooms_expiry on orb_rooms_private.rooms(expires_at) where status='open';
create table if not exists orb_rooms_private.members (
 user_id uuid not null references auth.users(id) on delete cascade,
 room_id uuid not null references orb_rooms_private.rooms(id) on delete cascade,
 joined_at timestamptz not null default clock_timestamp(), last_seen timestamptz not null default clock_timestamp(),
 active boolean not null default false, primary key(user_id,room_id)
);
alter table orb_rooms_private.members add column if not exists active boolean not null default false;
-- Preserve memberships when upgrading the original single-room schema.
do $$ begin
 if exists(select 1 from pg_constraint c where c.conrelid='orb_rooms_private.members'::regclass and c.contype='p' and cardinality(c.conkey)=1) then
  update orb_rooms_private.members set active=true;
  alter table orb_rooms_private.members drop constraint members_pkey;
  alter table orb_rooms_private.members add primary key(user_id,room_id);
 end if;
end $$;
create unique index if not exists orb_rooms_one_active_member on orb_rooms_private.members(user_id) where active;
create index if not exists orb_rooms_member_room on orb_rooms_private.members(room_id);
create table if not exists orb_rooms_private.join_attempts (
 user_id uuid primary key references auth.users(id) on delete cascade,
 window_at timestamptz not null default clock_timestamp(), attempts integer not null default 0
);
alter table orb_rooms_private.rooms enable row level security;
alter table orb_rooms_private.members enable row level security;
alter table orb_rooms_private.join_attempts enable row level security;
revoke all on all tables in schema orb_rooms_private from public, anon, authenticated;

create or replace function orb_rooms_private.room_action(p_action text,p_payload jsonb)
returns jsonb language plpgsql security definer set search_path='' as $$
declare
 me uuid := auth.uid(); r orb_rooms_private.rooms%rowtype; room uuid;
 device text := coalesce(p_payload->>'device',''); guests integer; candidate text; attempt integer;
 premium_account boolean; allow_members boolean; tracks jsonb; cover text; names jsonb; n integer; old_video text; idx integer; rate_count integer;
begin
 if me is null or not exists(select 1 from auth.users where id=me) then
  raise exception 'ROOM_SIGN_IN' using errcode='42501';
 end if;
 if length(device) not between 8 and 100 then raise exception 'ROOM_DEVICE'; end if;
 -- Expired rooms no longer reserve codes or memberships.
 update orb_rooms_private.rooms set status='closed',playing=false where status='open' and expires_at<clock_timestamp();
 delete from orb_rooms_private.members m using orb_rooms_private.rooms t where m.room_id=t.id and t.status='closed';
 perform pg_advisory_xact_lock(hashtextextended(me::text,1));
 if p_action in ('heartbeat','list','mine') then
  update orb_rooms_private.rooms set host_seen=clock_timestamp() where host_id=me and host_device=device and status='open';
  update orb_rooms_private.members m set last_seen=clock_timestamp() from orb_rooms_private.rooms t
   where m.room_id=t.id and m.user_id=me and t.host_id=me and t.host_device=device and t.status='open';
  if p_action='heartbeat' then return jsonb_build_object('ok',true); end if;
 end if;
 if p_action='list' then
  return coalesce((select jsonb_agg(jsonb_build_object('id',t.id,'title',t.title,'code',t.code,
   'maxGuests',t.max_guests,'coverPath',t.cover_path,'playing',t.playing,
   'count',(select count(*) from orb_rooms_private.members where room_id=t.id)) order by t.created_at)
   from orb_rooms_private.rooms t where t.status='open' and (t.host_id=me or exists(select 1 from orb_rooms_private.members m where m.room_id=t.id and m.user_id=me))), '[]'::jsonb);
 end if;
 if p_action='create' then
  if length(trim(coalesce(p_payload->>'title',''))) not between 1 and 80 then raise exception 'ROOM_TITLE'; end if;
  delete from orb_rooms_private.create_tickets where id=(p_payload->>'ticket')::uuid and user_id=me and expires_at>clock_timestamp() returning premium into premium_account;
  if premium_account is null then raise exception 'ROOM_PREMIUM_CHECK'; end if;
  guests := (p_payload->>'maxGuests')::integer;
  if guests is null or guests<1 or guests>(case when premium_account then 14 else 4 end) then raise exception 'ROOM_CAPACITY'; end if;
  allow_members := not premium_account or coalesce((p_payload->>'membersCanAdd')::boolean,true);
  -- Serialize operations for this account across devices.
  perform pg_advisory_xact_lock(hashtextextended(me::text,1));

  for attempt in 1..20 loop
   candidate := '';
   for n in 0..5 loop candidate := candidate || substr('ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789',1+(get_byte(extensions.gen_random_bytes(1),0)%36),1); end loop;
   begin
    insert into orb_rooms_private.rooms(host_id,host_device,code,title,max_guests,members_can_add)
    values(me,device,candidate,trim(p_payload->>'title'),guests,allow_members) returning * into r;
    exit;
   exception when unique_violation then
    null;
   end;
  end loop;
  if r.id is null then raise exception 'ROOM_CODE_RETRY'; end if;
  insert into orb_rooms_private.members(user_id,room_id) values(me,r.id);
 elsif p_action='join' then
  perform pg_advisory_xact_lock(hashtextextended(me::text,1));
  insert into orb_rooms_private.join_attempts(user_id,attempts) values(me,1)
  on conflict(user_id) do update set
   attempts=case when orb_rooms_private.join_attempts.window_at<clock_timestamp()-interval '1 minute' then 1 else orb_rooms_private.join_attempts.attempts+1 end,
   window_at=case when orb_rooms_private.join_attempts.window_at<clock_timestamp()-interval '1 minute' then clock_timestamp() else orb_rooms_private.join_attempts.window_at end
  returning attempts into rate_count;
  -- Return a committed error so failed codes still consume the rate limit.
  if rate_count>10 then return jsonb_build_object('error','ROOM_RATE_LIMIT'); end if;
  select * into r from orb_rooms_private.rooms where code=upper(trim(p_payload->>'code')) and status='open' for update;
  if r.id is null then return jsonb_build_object('error','ROOM_NOT_FOUND'); end if;

  select room_id into room from orb_rooms_private.members where user_id=me and active;
  if exists(select 1 from orb_rooms_private.rooms where host_id=me and status='open' and playing and id<>r.id) then return jsonb_build_object('error','ROOM_PLAYING_ELSEWHERE'); end if;
  delete from orb_rooms_private.members where room_id=r.id and user_id<>r.host_id and last_seen<clock_timestamp()-interval '2 minutes';
  if not exists(select 1 from orb_rooms_private.members where user_id=me and room_id=r.id) and (select count(*) from orb_rooms_private.members where room_id=r.id and user_id<>r.host_id)>=r.max_guests then
   return jsonb_build_object('error','ROOM_FULL');
  end if;
  insert into orb_rooms_private.members(user_id,room_id,active) values(me,r.id,false)
  on conflict(user_id,room_id) do update set last_seen=clock_timestamp();
 elsif p_action='mine' then
  select t.* into r from orb_rooms_private.rooms t join orb_rooms_private.members m on m.room_id=t.id where m.user_id=me and m.active and t.status='open';
  if r.id is null then return 'null'::jsonb; end if;
 else
  room := (p_payload->>'roomId')::uuid;
  select * into r from orb_rooms_private.rooms where id=room and status='open' for update;
  if r.id is null then raise exception 'ROOM_CLOSED'; end if;
  if not exists(select 1 from orb_rooms_private.members where room_id=r.id and user_id=me) then raise exception 'ROOM_NOT_MEMBER' using errcode='42501'; end if;
  if p_action='activate' then
   if exists(select 1 from orb_rooms_private.rooms where host_id=me and status='open' and playing and id<>r.id) then raise exception 'ROOM_PLAYING_ELSEWHERE'; end if;
   update orb_rooms_private.members set active=false where user_id=me;
   update orb_rooms_private.members set active=true where user_id=me and room_id=r.id;
   p_action := 'state';
  end if;
  if p_action='leave' then
   if r.host_id=me and r.host_device=device then
    update orb_rooms_private.rooms set status='closed',playing=false where id=r.id;
    delete from orb_rooms_private.members where room_id=r.id;
   elsif r.host_id=me then
    -- The host's other device can stop listening without closing the host session.
    return 'null'::jsonb;
   else delete from orb_rooms_private.members where room_id=r.id and user_id=me;
   end if;
   return 'null'::jsonb;
  elsif p_action in ('queue','append','publish','cover') then
   if p_action='append' then
    if not r.members_can_add and not (r.host_id=me and r.host_device=device) then raise exception 'ROOM_HOST_ONLY' using errcode='42501'; end if;
   elsif r.host_id<>me or r.host_device<>device then raise exception 'ROOM_HOST_ONLY' using errcode='42501'; end if;
   if p_action in ('queue','append') then
    tracks := p_payload->'tracks';
    if tracks is null or jsonb_typeof(tracks)<>'array' then raise exception 'ROOM_QUEUE'; end if;
    if jsonb_array_length(tracks)>100 or octet_length(tracks::text)>200000 then raise exception 'ROOM_QUEUE_LIMIT'; end if;
    if exists(select 1 from jsonb_array_elements(tracks) t where jsonb_typeof(t)<>'object' or
      coalesce(t->>'videoId','') !~ '^[A-Za-z0-9_-]{11}$' or length(coalesce(t->>'title','')) not between 1 and 300 or
      length(coalesce(t->>'artist',''))>500 or coalesce(t->>'localUri','')<>'' or coalesce(t->>'localPath','')<>'') then raise exception 'ROOM_QUEUE'; end if;
    if p_action='append' then
     select coalesce(jsonb_agg(v order by ord),'[]'::jsonb) into tracks from (
      select distinct on(v->>'videoId') v,ord from jsonb_array_elements(r.queue||tracks) with ordinality t(v,ord) order by v->>'videoId',ord
     ) q;
     if jsonb_array_length(tracks)>100 or octet_length(tracks::text)>200000 then raise exception 'ROOM_QUEUE_LIMIT'; end if;
    end if;
    old_video := r.queue->r.track_index->>'videoId';
    select ord::integer-1 into idx from jsonb_array_elements(tracks) with ordinality as t(v,ord) where v->>'videoId'=old_video limit 1;
    r.track_index := coalesce(idx,0);
    if idx is null then r.position_ms:=0; r.playing:=false;
    elsif r.playing then r.position_ms:=r.position_ms+greatest(0,floor(extract(epoch from (clock_timestamp()-r.playback_at))*1000)::bigint);
    end if;
    r.queue:=tracks; r.queue_version:=r.queue_version+1; r.playback_at:=clock_timestamp();
   elsif p_action='publish' then
    if p_payload ? 'queueVersion' and (p_payload->>'queueVersion')::bigint<>r.queue_version then
     -- A queue edit won the race; return its current clock without overwriting it.
     p_action := 'state';
    else
    idx := (p_payload->>'index')::integer;
    if idx is null or idx<0 or idx>=greatest(jsonb_array_length(r.queue),1) then raise exception 'ROOM_POSITION'; end if;
    r.track_index:=idx;
    r.position_ms:=greatest(0,least(coalesce((p_payload->>'positionMs')::bigint,0),86400000));
    r.playing:=coalesce((p_payload->>'playing')::boolean,false) and jsonb_array_length(r.queue)>0;
    if r.playing and exists(select 1 from orb_rooms_private.rooms where host_id=me and status='open' and playing and id<>r.id) then raise exception 'ROOM_PLAYING_ELSEWHERE'; end if;
    if r.playing and not exists(select 1 from orb_rooms_private.members where user_id=me and room_id=r.id and active) then raise exception 'ROOM_NOT_ACTIVE'; end if;
    r.playback_at:=clock_timestamp();
    end if;
   else
    cover:=p_payload->>'path';
    if cover<>me::text||'/'||r.id::text||'.jpg' then raise exception 'ROOM_COVER'; end if;
    if not exists(select 1 from storage.objects where bucket_id='room-covers' and name=cover) then raise exception 'ROOM_COVER'; end if;
    r.cover_path:=cover;
   end if;
   if r.host_id=me and r.host_device=device then r.host_seen:=clock_timestamp(); end if;
   update orb_rooms_private.rooms set queue=r.queue,queue_version=r.queue_version,track_index=r.track_index,
    position_ms=r.position_ms,playing=r.playing,playback_at=r.playback_at,cover_path=r.cover_path,host_seen=r.host_seen where id=r.id;
  elsif p_action<>'state' then raise exception 'ROOM_ACTION'; end if;
 end if;
 if r.host_id=me and r.host_device=device then
  r.host_seen:=clock_timestamp();
  update orb_rooms_private.rooms set host_seen=r.host_seen where id=r.id;
 end if;
 update orb_rooms_private.members set last_seen=clock_timestamp() where user_id=me and room_id=r.id;
 select coalesce(jsonb_agg(jsonb_build_object('id',m.user_id,'name',coalesce(nullif(p.display_name,''),p.username,'Orb'),
  'avatar',p.avatar_icon_url,'host',m.user_id=r.host_id,'online',m.last_seen>clock_timestamp()-interval '20 seconds') order by m.joined_at),'[]'::jsonb)
 into names from orb_rooms_private.members m left join public.profiles p on p.id=m.user_id where m.room_id=r.id;
 return jsonb_build_object('id',r.id,'title',r.title,'code',r.code,'maxGuests',r.max_guests,'coverPath',r.cover_path,
  'membersCanAdd',r.members_can_add,'canAdd',r.members_can_add or (r.host_id=me and r.host_device=device),'host',r.host_id=me and r.host_device=device,'hostOnline',r.host_seen>clock_timestamp()-interval '60 seconds',
  'queue',r.queue,'queueVersion',r.queue_version,'index',r.track_index,'positionMs',r.position_ms,
  'playing',r.playing and r.host_seen>clock_timestamp()-interval '60 seconds',
  'createdAtMs',floor(extract(epoch from r.created_at)*1000),'playbackAtMs',floor(extract(epoch from r.playback_at)*1000),'serverMs',floor(extract(epoch from clock_timestamp())*1000),'members',names);
end; $$;
revoke all on function orb_rooms_private.room_action(text,jsonb) from public,anon;
grant execute on function orb_rooms_private.room_action(text,jsonb) to authenticated;
create or replace function public.orb_room_action(p_action text,p_payload jsonb default '{}')
returns jsonb language sql security invoker set search_path='' as $$ select orb_rooms_private.room_action(p_action,p_payload); $$;
revoke all on function public.orb_room_action(text,jsonb) from public,anon;
grant execute on function public.orb_room_action(text,jsonb) to authenticated;

-- Private covers. Uploads are bound to the host and room; members may sign reads.
insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
values('room-covers','room-covers',false,2097152,array['image/jpeg']) on conflict(id) do nothing;
create or replace function orb_rooms_private.cover_access(p_name text,p_write boolean)
returns boolean language sql stable security definer set search_path='' as $$
 select auth.uid() is not null and exists(
  select 1 from orb_rooms_private.rooms r where r.status='open' and p_name=r.host_id::text||'/'||r.id::text||'.jpg'
   and (case when p_write then r.host_id=auth.uid() else exists(select 1 from orb_rooms_private.members m where m.room_id=r.id and m.user_id=auth.uid()) end)
 ); $$;
revoke all on function orb_rooms_private.cover_access(text,boolean) from public,anon;
grant execute on function orb_rooms_private.cover_access(text,boolean) to authenticated;
drop policy if exists orb_room_cover_read on storage.objects;
create policy orb_room_cover_read on storage.objects for select to authenticated using(bucket_id='room-covers' and orb_rooms_private.cover_access(name,false));
drop policy if exists orb_room_cover_insert on storage.objects;
create policy orb_room_cover_insert on storage.objects for insert to authenticated with check(bucket_id='room-covers' and orb_rooms_private.cover_access(name,true));
drop policy if exists orb_room_cover_update on storage.objects;
create policy orb_room_cover_update on storage.objects for update to authenticated using(bucket_id='room-covers' and orb_rooms_private.cover_access(name,true)) with check(bucket_id='room-covers' and orb_rooms_private.cover_access(name,true));
drop policy if exists orb_room_cover_delete on storage.objects;
create policy orb_room_cover_delete on storage.objects for delete to authenticated using(bucket_id='room-covers' and (storage.foldername(name))[1]=(select auth.uid())::text);
notify pgrst,'reload schema';
