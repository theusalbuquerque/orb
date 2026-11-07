-- A server-verified owner dashboard snapshot. This is never used to grant Premium.
-- The direct table API is denied; only the verified Orb owner can call its RPCs.
CREATE TABLE IF NOT EXISTS public.orb_owner_premium_count_snapshot (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    active_premium_users integer NOT NULL CHECK (active_premium_users >= 0),
    paid_premium_users integer NOT NULL CHECK (paid_premium_users >= 0),
    courtesy_premium_users integer NOT NULL CHECK (courtesy_premium_users >= 0),
    unlinked_paid_subscriptions integer NOT NULL CHECK (unlinked_paid_subscriptions >= 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (active_premium_users >= paid_premium_users),
    CHECK (active_premium_users >= courtesy_premium_users),
    CHECK (active_premium_users <= paid_premium_users + courtesy_premium_users),
    CHECK (unlinked_paid_subscriptions <= paid_premium_users)
);

ALTER TABLE public.orb_owner_premium_count_snapshot ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.orb_owner_premium_count_snapshot FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.orb_owner_premium_count_snapshot_get()
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path TO ''
AS $function$
DECLARE v_snapshot record;
BEGIN
  IF auth.uid() IS NULL OR NOT EXISTS (
    SELECT 1 FROM auth.users u WHERE u.id=auth.uid()
      AND encode(sha256(convert_to(lower(btrim(u.email)), 'UTF8')), 'hex')
        = '2c8c3e1d1bcef1415230705c38bafb7403905850a7f37c5206bd5cfc055c6aeb'
  ) THEN
    RAISE EXCEPTION 'Owner access required' USING ERRCODE='42501';
  END IF;
  SELECT * INTO v_snapshot FROM public.orb_owner_premium_count_snapshot WHERE singleton=true;
  IF NOT FOUND THEN RETURN NULL; END IF;
  RETURN jsonb_build_object(
    'activePremiumUsers', v_snapshot.active_premium_users,
    'paidPremiumUsers', v_snapshot.paid_premium_users,
    'courtesyPremiumUsers', v_snapshot.courtesy_premium_users,
    'unlinkedPaidSubscriptions', v_snapshot.unlinked_paid_subscriptions,
    'updatedAt', v_snapshot.updated_at
  );
END;
$function$;

CREATE OR REPLACE FUNCTION public.orb_owner_premium_count_snapshot_save(
  p_active integer, p_paid integer, p_courtesy integer, p_unlinked integer
)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path TO ''
AS $function$
BEGIN
  IF auth.uid() IS NULL OR NOT EXISTS (
    SELECT 1 FROM auth.users u WHERE u.id=auth.uid()
      AND encode(sha256(convert_to(lower(btrim(u.email)), 'UTF8')), 'hex')
        = '2c8c3e1d1bcef1415230705c38bafb7403905850a7f37c5206bd5cfc055c6aeb'
  ) THEN
    RAISE EXCEPTION 'Owner access required' USING ERRCODE='42501';
  END IF;
  INSERT INTO public.orb_owner_premium_count_snapshot
    (singleton, active_premium_users, paid_premium_users, courtesy_premium_users,
     unlinked_paid_subscriptions, updated_at)
  VALUES (true, p_active, p_paid, p_courtesy, p_unlinked, now())
  ON CONFLICT (singleton) DO UPDATE SET
    active_premium_users=EXCLUDED.active_premium_users,
    paid_premium_users=EXCLUDED.paid_premium_users,
    courtesy_premium_users=EXCLUDED.courtesy_premium_users,
    unlinked_paid_subscriptions=EXCLUDED.unlinked_paid_subscriptions,
    updated_at=now();
  RETURN public.orb_owner_premium_count_snapshot_get();
END;
$function$;

REVOKE ALL ON FUNCTION public.orb_owner_premium_count_snapshot_get() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.orb_owner_premium_count_snapshot_save(integer,integer,integer,integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.orb_owner_premium_count_snapshot_get() TO authenticated;
GRANT EXECUTE ON FUNCTION public.orb_owner_premium_count_snapshot_save(integer,integer,integer,integer) TO authenticated;

COMMENT ON TABLE public.orb_owner_premium_count_snapshot IS
  'Last owner-verified Mercado Pago subscriber count; not an entitlement or purchase record.';
