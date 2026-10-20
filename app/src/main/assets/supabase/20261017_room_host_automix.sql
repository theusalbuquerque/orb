-- Orb 1.7.2: host-authoritative Automix state; apply after orb_listening_rooms.sql.
-- No existing room RPC is replaced. Only authenticated room members may read;
-- only the authenticated host on the original host device may publish.
BEGIN;
CREATE TABLE IF NOT EXISTS orb_rooms_private.room_mix_state (
  room_id uuid PRIMARY KEY REFERENCES orb_rooms_private.rooms(id) ON DELETE CASCADE,
  payload jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON orb_rooms_private.room_mix_state FROM public, anon, authenticated;

CREATE OR REPLACE FUNCTION public.orb_room_mix_state(
  p_room_id uuid,
  p_device text DEFAULT NULL,
  p_snapshot jsonb DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  r orb_rooms_private.rooms%ROWTYPE;
  snapshot jsonb;
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'ROOM_SIGN_IN' USING ERRCODE='42501'; END IF;
  SELECT * INTO r FROM orb_rooms_private.rooms
    WHERE id = p_room_id AND status = 'open' AND
      (expires_at IS NULL OR expires_at > clock_timestamp());
  IF r.id IS NULL THEN RAISE EXCEPTION 'ROOM_CLOSED'; END IF;
  IF NOT EXISTS (SELECT 1 FROM orb_rooms_private.members
      WHERE room_id = r.id AND user_id = auth.uid()) THEN
    RAISE EXCEPTION 'ROOM_NOT_MEMBER' USING ERRCODE='42501';
  END IF;
  IF p_snapshot IS NOT NULL THEN
    IF r.host_id <> auth.uid() OR r.host_device <> p_device THEN
      RAISE EXCEPTION 'ROOM_HOST_ONLY' USING ERRCODE='42501';
    END IF;
    IF jsonb_typeof(p_snapshot) <> 'object' OR
       octet_length(p_snapshot::text) > 65536 OR
       (p_snapshot->>'format')::integer IS DISTINCT FROM 1 OR
       (p_snapshot->>'queueVersion')::bigint IS DISTINCT FROM r.queue_version OR
       coalesce(p_snapshot->>'version','') NOT IN ('2.0','2.5') OR
       jsonb_typeof(p_snapshot->'enabled') IS DISTINCT FROM 'boolean' THEN
      RAISE EXCEPTION 'ROOM_MIX_INVALID';
    END IF;
    INSERT INTO orb_rooms_private.room_mix_state(room_id,payload,updated_at)
    VALUES(r.id,p_snapshot,clock_timestamp())
    ON CONFLICT(room_id) DO UPDATE SET payload=EXCLUDED.payload,updated_at=EXCLUDED.updated_at;
  END IF;
  SELECT payload INTO snapshot FROM orb_rooms_private.room_mix_state WHERE room_id = r.id;
  IF snapshot IS NULL OR (snapshot->>'queueVersion')::bigint <> r.queue_version OR
      (SELECT updated_at FROM orb_rooms_private.room_mix_state WHERE room_id=r.id)
           < clock_timestamp() - interval '2 minutes' THEN
    RETURN jsonb_build_object('format',1,'queueVersion',r.queue_version,
                              'enabled',false,'version','2.0');
  END IF;
  RETURN snapshot;
END $$;
REVOKE ALL ON FUNCTION public.orb_room_mix_state(uuid,text,jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.orb_room_mix_state(uuid,text,jsonb) TO authenticated;
COMMIT;
