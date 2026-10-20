-- Run as an admin in a transaction: creates temporary users, then rolls everything back.
begin;
select set_config('orb.test.host',gen_random_uuid()::text,true);
select set_config('orb.test.guest',gen_random_uuid()::text,true);
select set_config('orb.test.outsider',gen_random_uuid()::text,true);
insert into auth.users(id,email,raw_app_meta_data,raw_user_meta_data)
select current_setting('orb.test.'||v)::uuid,'room-test-'||current_setting('orb.test.'||v)||'@example.invalid','{}','{}'
from (values('host'),('guest'),('outsider')) t(v);
select set_config('orb.test.ticket1',public.orb_room_create_ticket(current_setting('orb.test.host')::uuid,true)::text,true);
select set_config('orb.test.ticket2',public.orb_room_create_ticket(current_setting('orb.test.host')::uuid,true)::text,true);
select set_config('orb.test.ticketFree',public.orb_room_create_ticket(current_setting('orb.test.guest')::uuid,false)::text,true);
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('orb.test.host'),true);
do $$ declare r jsonb; begin
 r:=public.orb_room_action('create',jsonb_build_object('device','test-host-device','title','Room integration test','maxGuests',1,'membersCanAdd',false,'ticket',current_setting('orb.test.ticket1')));
 if (r->>'code') !~ '^[A-Z0-9]{6}$' or not (r->>'host')::boolean or (r->>'maxGuests')::integer<>1 then raise exception 'create assertion'; end if;
 perform set_config('orb.test.room',r->>'id',true);
 perform set_config('orb.test.code',r->>'code',true);
 r:=public.orb_room_action('queue',jsonb_build_object('device','test-host-device','roomId',r->>'id','tracks',
 '[{"videoId":"abcdefghijk","title":"Test track","artist":"Test"}]'::jsonb));
 if jsonb_array_length(r->'queue')<>1 or (r->>'queueVersion')::integer<>1 then raise exception 'queue assertion'; end if;
 perform public.orb_room_action('activate',jsonb_build_object('device','test-host-device','roomId',r->>'id'));
 r:=public.orb_room_action('publish',jsonb_build_object('device','test-host-device','roomId',r->>'id','index',0,'positionMs',1000,'playing',true,'queueVersion',1));
 if not (r->>'playing')::boolean or (r->>'positionMs')::integer<>1000 then raise exception 'publish assertion'; end if;
 r:=public.orb_room_action('create',jsonb_build_object('device','test-host-device','title','Second room while first plays','maxGuests',14,'ticket',current_setting('orb.test.ticket2')));
 perform set_config('orb.test.second',r->>'id',true);
 if jsonb_array_length(public.orb_room_action('list','{"device":"test-host-device"}'))<>2 then raise exception 'multiple room listing assertion'; end if;
 begin
  perform public.orb_room_action('activate',jsonb_build_object('device','test-host-device','roomId',r->>'id'));
  raise exception 'switch while playing allowed';
 exception when others then if sqlerrm<>'ROOM_PLAYING_ELSEWHERE' then raise; end if; end;
 r:=public.orb_room_action('queue',jsonb_build_object('device','test-host-device','roomId',r->>'id','tracks','[{"videoId":"lmnopqrstuv","title":"Second track","artist":"Test"}]'::jsonb));
 begin
  perform public.orb_room_action('publish',jsonb_build_object('device','test-host-device','roomId',r->>'id','index',0,'positionMs',0,'playing',true));
  raise exception 'simultaneous playback allowed';
 exception when others then if sqlerrm<>'ROOM_PLAYING_ELSEWHERE' then raise; end if; end;
 perform public.orb_room_action('publish',jsonb_build_object('device','test-host-device','roomId',current_setting('orb.test.room'),'index',0,'positionMs',1500,'playing',false));
 perform public.orb_room_action('activate',jsonb_build_object('device','test-host-device','roomId',r->>'id'));
 r:=public.orb_room_action('publish',jsonb_build_object('device','test-host-device','roomId',r->>'id','index',0,'positionMs',0,'playing',true));
 if not (r->>'playing')::boolean then raise exception 'play second after pause assertion'; end if;
 r:=public.orb_room_action('mine','{"device":"test-host-device"}');
 if r->>'id' is distinct from current_setting('orb.test.second') then raise exception 'single active room assertion'; end if;
 perform public.orb_room_action('leave',jsonb_build_object('device','test-host-device','roomId',r->>'id'));
 perform public.orb_room_action('activate',jsonb_build_object('device','test-host-device','roomId',current_setting('orb.test.room')));

end $$;
select set_config('request.jwt.claim.sub',current_setting('orb.test.guest'),true);
do $$ declare r jsonb; begin
 r:=public.orb_room_action('join',jsonb_build_object('device','test-guest-device','code',lower(current_setting('orb.test.code'))));
 if (r->>'host')::boolean or jsonb_array_length(r->'members')<>2 then raise exception 'join assertion'; end if;
 if public.orb_room_action('mine','{"device":"test-guest-device"}')<>'null'::jsonb then raise exception 'guest autoplay on join'; end if;
 perform public.orb_room_action('activate',jsonb_build_object('device','test-guest-device','roomId',r->>'id'));
 if public.orb_room_action('mine','{"device":"test-guest-device"}')->>'id' is distinct from r->>'id' then raise exception 'explicit listening activation'; end if;

 begin
  perform public.orb_room_action('append',jsonb_build_object('device','test-guest-device','roomId',r->>'id','tracks','[{"videoId":"lmnopqrstuv","title":"Guest track","artist":"Guest"}]'::jsonb));
  raise exception 'host-only guest append allowed';
 exception when others then if sqlerrm<>'ROOM_HOST_ONLY' then raise; end if; end;

 begin
  perform public.orb_room_action('queue',jsonb_build_object('device','test-guest-device','roomId',r->>'id','tracks','[]'::jsonb));
  raise exception 'guest queue write allowed';
 exception when others then if sqlerrm<>'ROOM_HOST_ONLY' then raise; end if; end;
 begin
  perform public.orb_room_action('publish',jsonb_build_object('device','test-guest-device','roomId',r->>'id','index',0,'positionMs',5000,'playing',true));
  raise exception 'guest transport allowed';
 exception when others then if sqlerrm<>'ROOM_HOST_ONLY' then raise; end if; end;
 if not orb_rooms_private.cover_access(current_setting('orb.test.host')||'/'||current_setting('orb.test.room')||'.jpg',false) then raise exception 'member cover read assertion'; end if;
 if orb_rooms_private.cover_access(current_setting('orb.test.host')||'/'||current_setting('orb.test.room')||'.jpg',true) then raise exception 'guest cover upload allowed'; end if;
end $$;
select set_config('request.jwt.claim.sub',current_setting('orb.test.outsider'),true);
do $$ declare r jsonb; begin
 r:=public.orb_room_action('join',jsonb_build_object('device','test-outsider-device','code',current_setting('orb.test.code')));
 if r->>'error' is distinct from 'ROOM_FULL' then raise exception 'capacity assertion'; end if;
 begin
  perform public.orb_room_action('state',jsonb_build_object('device','test-outsider-device','roomId',current_setting('orb.test.room')));
  raise exception 'outsider read allowed';
 exception when others then if sqlerrm<>'ROOM_NOT_MEMBER' then raise; end if; end;
 if orb_rooms_private.cover_access(current_setting('orb.test.host')||'/'||current_setting('orb.test.room')||'.jpg',false) then raise exception 'outsider cover read allowed'; end if;
 for i in 1..10 loop
  r:=public.orb_room_action('join',jsonb_build_object('device','test-outsider-device','code','!!!!!!'));
 end loop;
 if r->>'error' is distinct from 'ROOM_RATE_LIMIT' then raise exception 'rate limit assertion'; end if;
end $$;
reset role;
update orb_rooms_private.rooms set host_seen=clock_timestamp()-interval '2 minutes' where id=current_setting('orb.test.room')::uuid;
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('orb.test.guest'),true);
do $$ declare r jsonb; begin
 perform public.orb_room_action('heartbeat','{"device":"test-guest-device"}');
 r:=public.orb_room_action('state',jsonb_build_object('device','test-guest-device','roomId',current_setting('orb.test.room')));
 if (r->>'hostOnline')::boolean then raise exception 'guest spoofed host presence'; end if;
end $$;
select set_config('request.jwt.claim.sub',current_setting('orb.test.host'),true);
do $$ begin
 perform public.orb_room_action('heartbeat','{"device":"test-host-device"}');
end $$;
select set_config('request.jwt.claim.sub',current_setting('orb.test.guest'),true);
do $$ declare r jsonb; begin
 r:=public.orb_room_action('state',jsonb_build_object('device','test-guest-device','roomId',current_setting('orb.test.room')));
 if not (r->>'hostOnline')::boolean or (r->>'playing')::boolean then raise exception 'paused host presence assertion'; end if;
 if (r->>'positionMs')::integer<>1500 then raise exception 'heartbeat changed playback position'; end if;
end $$;
select set_config('request.jwt.claim.sub',current_setting('orb.test.host'),true);
do $$ declare r jsonb; begin
 r:=public.orb_room_action('leave',jsonb_build_object('device','test-host-device','roomId',current_setting('orb.test.room')));
 if r<>'null'::jsonb then raise exception 'close assertion'; end if;
end $$;
select set_config('request.jwt.claim.sub',current_setting('orb.test.guest'),true);
do $$ declare r jsonb; begin
 r:=public.orb_room_action('join',jsonb_build_object('device','test-guest-device','code',current_setting('orb.test.code')));
 if r->>'error' is distinct from 'ROOM_NOT_FOUND' then raise exception 'closed code assertion'; end if;
 if public.orb_room_action('mine','{"device":"test-guest-device"}')<>'null'::jsonb then raise exception 'membership cleanup assertion'; end if;
 begin
  perform public.orb_room_action('create',jsonb_build_object('device','test-guest-device','title','Free limit','maxGuests',5,'ticket',current_setting('orb.test.ticketFree')));
  raise exception 'free capacity above five allowed';
 exception when others then if sqlerrm<>'ROOM_CAPACITY' then raise; end if; end;
 r:=public.orb_room_action('create',jsonb_build_object('device','test-guest-device','title','Free room','maxGuests',4,'membersCanAdd',false,'ticket',current_setting('orb.test.ticketFree')));
 if not (r->>'membersCanAdd')::boolean or (r->>'maxGuests')::integer<>4 then raise exception 'free room policy assertion'; end if;
 perform set_config('orb.test.freeRoom',r->>'id',true);
 perform set_config('orb.test.freeCode',r->>'code',true);
 begin
  perform public.orb_room_action('create',jsonb_build_object('device','test-guest-device','title','Reused ticket','maxGuests',4,'ticket',current_setting('orb.test.ticketFree')));
  raise exception 'ticket reuse allowed';
 exception when others then if sqlerrm<>'ROOM_PREMIUM_CHECK' then raise; end if; end;
 if has_function_privilege('authenticated','public.orb_room_create_ticket(uuid,boolean)','execute') then raise exception 'client premium forgery allowed'; end if;
 if has_function_privilege('anon','public.orb_room_action(text,jsonb)','execute') then raise exception 'anon RPC allowed'; end if;
 if has_table_privilege('authenticated','orb_rooms_private.rooms','select') then raise exception 'raw room listing allowed'; end if;
end $$;
select set_config('request.jwt.claim.sub',current_setting('orb.test.host'),true);
do $$ declare r jsonb; begin
 r:=public.orb_room_action('join',jsonb_build_object('device','test-host-device','code',current_setting('orb.test.freeCode')));
 if not (r->>'canAdd')::boolean then raise exception 'everyone can add assertion'; end if;
 r:=public.orb_room_action('append',jsonb_build_object('device','test-host-device','roomId',r->>'id','tracks','[{"videoId":"lmnopqrstuv","title":"Guest contribution","artist":"Guest"}]'::jsonb));
 if jsonb_array_length(r->'queue')<>1 then raise exception 'guest append assertion'; end if;
 begin
  perform public.orb_room_action('queue',jsonb_build_object('device','test-host-device','roomId',r->>'id','tracks','[]'::jsonb));
  raise exception 'guest replacing queue allowed';
 exception when others then if sqlerrm<>'ROOM_HOST_ONLY' then raise; end if; end;
end $$;
reset role;
select 'Room integration checks passed; all temporary data rolled back' as result;
rollback;
