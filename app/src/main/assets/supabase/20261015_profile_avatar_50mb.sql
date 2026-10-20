-- Execute once in the existing Orb Supabase project to allow profile photos up to 50 MiB.
-- The project's global Storage file size limit must also be >= 50 MiB.
update storage.buckets
set file_size_limit = 52428800
where id = 'profile-avatars';
