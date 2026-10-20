import { createClient } from "npm:@supabase/supabase-js@2.57.4";

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { "Content-Type": "application/json" },
});
const durationOptions = new Set(["24h", "3d", "5d", "7d", "15d", "indefinite"]);

// The Android app never sends a trustworthy Premium flag: we check the user's
// Supabase token AND the billing backend on every creation/lifetime update.
Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);
  const authorization = req.headers.get("Authorization") ?? "";
  const token = authorization.replace(/^Bearer\s+/i, "").trim();
  const url = Deno.env.get("SUPABASE_URL")!;
  const admin = createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data, error } = await admin.auth.getUser(token);
  if (error || !data.user) return json({ error: "ROOM_SIGN_IN" }, 401);

  try {
    const input = await req.json();
    const action = input?.p_action;
    const payload = input?.p_payload ?? {};
    if (action !== "create" && action !== "edit") return json({ error: "ROOM_ACTION" }, 400);
    const duration = payload.duration ?? "24h";
    if (!durationOptions.has(duration)) return json({ error: "ROOM_DURATION" }, 400);
    if (typeof payload.device !== "string" || payload.device.length < 8 || payload.device.length > 100) {
      return json({ error: "ROOM_DEVICE" }, 400);
    }
    const response = await fetch("https://orb-4mrh.onrender.com/api/billing/subscription", {
      headers: { Authorization: authorization }, signal: AbortSignal.timeout(30000),
    });
    if (!response.ok) return json({ error: "ROOM_PREMIUM_CHECK" }, 503);
    const subscription = await response.json();
    if (subscription.userId !== data.user.id || typeof subscription.premium !== "boolean") {
      return json({ error: "ROOM_PREMIUM_CHECK" }, 503);
    }
    if (duration === "indefinite" && !subscription.premium) {
      return json({ error: "ROOM_PREMIUM_ONLY" }, 403);
    }

    const client = createClient(url, Deno.env.get("SUPABASE_ANON_KEY")!, {
      global: { headers: { Authorization: authorization } },
      auth: { persistSession: false, autoRefreshToken: false },
    });

    let roomId: string;
    if (action === "create") {
      // The established one-minute, single-use Premium ticket is preserved.
      const { data: ticket, error: ticketError } = await admin.rpc("orb_room_create_ticket", {
        p_user: data.user.id, p_premium: subscription.premium,
      });
      if (ticketError || !ticket) return json({ error: "ROOM_PREMIUM_CHECK" }, 503);
      const { data: created, error: createError } = await client.rpc("orb_room_action", {
        p_action: "create", p_payload: { ...payload, ticket },
      });
      if (createError) return json({ error: createError.message.match(/ROOM_[A-Z_]+/)?.[0] ?? "ROOM_NETWORK" }, 400);
      if (!created?.id) return json({ error: "ROOM_NETWORK" }, 503);
      roomId = created.id;
    } else {
      if (typeof payload.roomId !== "string" || typeof payload.title !== "string") {
        return json({ error: "ROOM_TITLE" }, 400);
      }
      roomId = payload.roomId;
    }

    const { error: settingsError } = await admin.rpc("orb_room_configure_verified", {
      p_user: data.user.id,
      p_room: roomId,
      p_device: payload.device,
      p_duration: duration,
      p_verified_premium: subscription.premium,
      p_is_edit: action === "edit",
      p_title: action === "edit" ? payload.title : null,
      p_members_can_add: payload.membersCanAdd ?? true,
    });
    if (settingsError) {
      return json({ error: settingsError.message.match(/ROOM_[A-Z_]+/)?.[0] ?? "ROOM_NETWORK" }, 400);
    }
    // Return the *server* lifetime and existing room state, never a client clock.
    const { data: updated, error: stateError } = await client.rpc("orb_room_action", {
      p_action: "state", p_payload: { roomId, device: payload.device },
    });
    if (stateError) return json({ error: stateError.message.match(/ROOM_[A-Z_]+/)?.[0] ?? "ROOM_NETWORK" }, 400);
    return json(updated);
  } catch {
    return json({ error: "ROOM_NETWORK" }, 503);
  }
});
