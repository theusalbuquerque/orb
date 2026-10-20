import { createClient } from "npm:@supabase/supabase-js@2.57.4";

const json = (body: unknown, status: number) => new Response(JSON.stringify(body), {
  status, headers: { "Content-Type": "application/json" },
});
Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);
  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "").trim();
  if (!token) return json({ error: "Unauthorized" }, 401);
  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data, error } = await admin.auth.getUser(token);
  if (error || !data.user) return json({ error: "Unauthorized" }, 401);
  const userId = data.user.id; // Never accept a user ID from the request body.
  try {
    async function collectFiles(bucket: string, prefix: string, depth = 0): Promise<string[]> {
      if (depth > 8) throw new Error("Unexpected storage depth");
      const files: string[] = [];
      for (let offset = 0; ; offset += 100) {
        const { data: objects, error: listError } = await admin.storage.from(bucket).list(prefix, {
          limit: 100, offset, sortBy: { column: "name", order: "asc" },
        });
        if (listError) throw listError;
        for (const object of objects ?? []) {
          const path = prefix + "/" + object.name;
          if (object.id) files.push(path);
          else files.push(...await collectFiles(bucket, path, depth + 1));
        }
        if (!objects || objects.length < 100) break;
      }
      return files;
    }
    for (const bucket of ["profile-avatars", "feedback-attachments", "room-covers"]) {
      const files = await collectFiles(bucket, userId);
      for (let offset = 0; offset < files.length; offset += 100) {
        const { error: removeError } = await admin.storage.from(bucket).remove(files.slice(offset, offset + 100));
        if (removeError) throw removeError;
      }
    }
    const { error: logoutError } = await admin.auth.admin.signOut(token, "global");
    if (logoutError) throw logoutError;
    const { error: deleteError } = await admin.auth.admin.deleteUser(userId, false);
    if (deleteError) throw deleteError;
    // Account-owned database rows have ON DELETE CASCADE.
    return json({ deleted: true }, 200);
  } catch (error) {
    console.error("Account deletion failed", error);
    return json({ error: "Account deletion failed" }, 500);
  }
});
