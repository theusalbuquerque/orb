-- Orb Music: room lifetimes, server-verified Premium and unattended expiry.
-- Apply after orb_listening_rooms.sql in the Supabase SQL Editor.
-- All elevated functions are private/service_role only; the Android client
-- never decides whether a user is Premium.
BEGIN;

ALTER TABLE orb_rooms_private.rooms
    ADD COLUMN IF NOT EXISTS duration_code text NOT NULL DEFAULT '24h';
ALTER TABLE orb_rooms_private.rooms
    DROP CONSTRAINT IF EXISTS orb_rooms_duration_code_check;
ALTER TABLE orb_rooms_private.rooms
    ADD CONSTRAINT orb_rooms_duration_code_check
    CHECK (duration_code IN ('24h','3d','5d','7d','15d','indefinite'));
ALTER TABLE orb_rooms_private.rooms
    DROP CONSTRAINT IF EXISTS orb_rooms_duration_expiry_check;
ALTER TABLE orb_rooms_private.rooms
    ADD CONSTRAINT orb_rooms_duration_expiry_check
    CHECK ((duration_code = 'indefinite') = (expires_at IS NULL));
-- Legacy rooms default to the existing 24-hour expiration. Do not
-- retroactively extend or alter the original expiry timestamp.

-- Called ONLY from the authenticated orb-room-create Edge Function using its
-- service role after subscription verification against the billing backend.
-- Creating a room still uses the original one-time ticket system.
CREATE OR REPLACE FUNCTION public.orb_room_configure_verified(
    p_user uuid, p_room uuid, p_device text, p_duration text,
    p_verified_premium boolean, p_is_edit boolean,
    p_title text, p_members_can_add boolean
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    r orb_rooms_private.rooms%ROWTYPE;
    expires timestamptz;
    current_time timestamptz := clock_timestamp();
BEGIN
    IF p_duration NOT IN ('24h','3d','5d','7d','15d','indefinite') OR p_duration IS NULL THEN
        RAISE EXCEPTION 'ROOM_DURATION';
    END IF;
    IF p_duration = 'indefinite' AND NOT coalesce(p_verified_premium,false) THEN
        RAISE EXCEPTION 'ROOM_PREMIUM_ONLY';
    END IF;
    SELECT * INTO r FROM orb_rooms_private.rooms WHERE id = p_room FOR UPDATE;
    IF r.id IS NULL OR r.status <> 'open' OR
       (r.expires_at IS NOT NULL AND r.expires_at <= current_time) THEN
        RAISE EXCEPTION 'ROOM_CLOSED';
    END IF;
    IF r.host_id <> p_user OR r.host_device <> p_device THEN
        RAISE EXCEPTION 'ROOM_HOST_ONLY';
    END IF;
    IF p_is_edit AND (p_title IS NULL OR length(trim(p_title)) NOT BETWEEN 1 AND 80) THEN
        RAISE EXCEPTION 'ROOM_TITLE';
    END IF;

    expires := CASE p_duration
        WHEN '24h' THEN (CASE WHEN p_is_edit THEN current_time ELSE r.created_at END) + interval '24 hours'
        WHEN '3d' THEN (CASE WHEN p_is_edit THEN current_time ELSE r.created_at END) + interval '3 days'
        WHEN '5d' THEN (CASE WHEN p_is_edit THEN current_time ELSE r.created_at END) + interval '5 days'
        WHEN '7d' THEN (CASE WHEN p_is_edit THEN current_time ELSE r.created_at END) + interval '7 days'
        WHEN '15d' THEN (CASE WHEN p_is_edit THEN current_time ELSE r.created_at END) + interval '15 days'
        ELSE NULL
    END;

    UPDATE orb_rooms_private.rooms
    SET duration_code = p_duration,
        expires_at = expires,
        title = CASE WHEN p_is_edit THEN trim(p_title) ELSE r.title END,
        members_can_add = CASE WHEN p_is_edit THEN
            (NOT coalesce(p_verified_premium,false) OR coalesce(p_members_can_add, true))
            ELSE r.members_can_add END
    WHERE id = p_room;
    RETURN jsonb_build_object('ok',true);
END; $$;
REVOKE ALL ON FUNCTION public.orb_room_configure_verified(uuid,uuid,text,text,boolean,boolean,text,boolean)
    FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.orb_room_configure_verified(uuid,uuid,text,text,boolean,boolean,text,boolean)
    TO service_role;

-- The existing room RPC is permitted to read only its own authorized room;
-- this helper exposes the two clock fields without granting table SELECT.
CREATE OR REPLACE FUNCTION orb_rooms_private.room_lifetime(p_room uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE r orb_rooms_private.rooms%ROWTYPE;
BEGIN
    SELECT * INTO r FROM orb_rooms_private.rooms t
    WHERE t.id = p_room AND t.status = 'open' AND
      (t.host_id = auth.uid() OR EXISTS (
          SELECT 1 FROM orb_rooms_private.members m
          WHERE m.room_id = t.id AND m.user_id = auth.uid()))
    LIMIT 1;
    IF r.id IS NULL THEN RETURN '{}'::jsonb; END IF;
    RETURN jsonb_build_object(
        'durationCode',r.duration_code,
        'expiresAtMs', CASE WHEN r.expires_at IS NULL THEN 0::bigint
            ELSE floor(extract(epoch FROM r.expires_at) * 1000)::bigint END
    );
END; $$;
REVOKE ALL ON FUNCTION orb_rooms_private.room_lifetime(uuid) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION orb_rooms_private.room_lifetime(uuid) TO authenticated;

-- Enrich all normal room state RPC responses; the original private action
-- remains intact, preserving queue handling, members and playback sync.
CREATE OR REPLACE FUNCTION public.orb_room_action(
    p_action text, p_payload jsonb DEFAULT '{}'::jsonb
) RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path='' AS $$
DECLARE state jsonb;
BEGIN
    state := orb_rooms_private.room_action(p_action, p_payload);
    IF jsonb_typeof(state) = 'object' AND state ? 'id' THEN
        state := state || orb_rooms_private.room_lifetime((state->>'id')::uuid);
    END IF;
    RETURN state;
END; $$;
REVOKE ALL ON FUNCTION public.orb_room_action(text,jsonb) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.orb_room_action(text,jsonb) TO authenticated;

-- Delete expired rooms and memberships, even when nobody sends RPC requests.
-- FK ON DELETE CASCADE removes participants; room codes become immediately free.
CREATE OR REPLACE FUNCTION orb_rooms_private.delete_expired_rooms()
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
    DELETE FROM orb_rooms_private.rooms
    WHERE expires_at IS NOT NULL AND expires_at <= clock_timestamp();
END; $$;
REVOKE ALL ON FUNCTION orb_rooms_private.delete_expired_rooms() FROM PUBLIC,anon,authenticated;

COMMIT;

-- IMPORTANT: run this section after enabling Supabase Cron (pg_cron).
-- Executing the migration with Cron unavailable keeps SQL features working,
-- but automatic expiry requires the scheduled job below.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron') THEN
        IF EXISTS (SELECT 1 FROM cron.job WHERE jobname='orb-room-expire-1min') THEN
            PERFORM cron.unschedule('orb-room-expire-1min');
        END IF;
        PERFORM cron.schedule('orb-room-expire-1min','* * * * *',
            'select orb_rooms_private.delete_expired_rooms()');
    ELSE
        RAISE NOTICE 'Supabase Cron/pg_cron is not enabled. Enable it, then run the cron.schedule command in the README.';
    END IF;
END $$;
