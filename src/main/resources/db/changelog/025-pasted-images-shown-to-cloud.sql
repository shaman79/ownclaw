--liquibase formatted sql

--changeset ownclaw:025-pasted-images-shown-to-cloud
-- An image pasted into the web chat box is shown to the cloud model (the owner's decision of
-- 2026-11-11); every other file stays private. shown_to_cloud is set at upload, and only when the
-- page said the file is clipboard image data it named itself AND the bytes decode as an image
-- (CloudImageCopy). The copy the cloud model is sent is made then, once: a sidecar next to it in
-- the uploads directory (cloud_copy), its decoded pixels encoded again, sized to what the model
-- reads -- never the pasted bytes. Its media type, pixel size, byte count and SHA-256 are kept
-- here, so every later request sends the same bytes, and the gateway sends none that changed since.
-- image_width and image_height are the pixel size of the file as pasted, as a viewer shows it,
-- which skills get; image_metadata what that file carries beside the picture (EXIF data, text, a
-- colour profile ...), or NULL: skills are handed the file, so what they read from it is private.
-- Every file that exists when this runs stays private, as it was.
ALTER TABLE file_attachments ADD COLUMN shown_to_cloud INTEGER NOT NULL DEFAULT 0;
ALTER TABLE file_attachments ADD COLUMN cloud_copy TEXT;
ALTER TABLE file_attachments ADD COLUMN cloud_media_type TEXT;
ALTER TABLE file_attachments ADD COLUMN cloud_width INTEGER;
ALTER TABLE file_attachments ADD COLUMN cloud_height INTEGER;
ALTER TABLE file_attachments ADD COLUMN cloud_bytes INTEGER;
ALTER TABLE file_attachments ADD COLUMN cloud_sha256 TEXT;
ALTER TABLE file_attachments ADD COLUMN image_width INTEGER;
ALTER TABLE file_attachments ADD COLUMN image_height INTEGER;
ALTER TABLE file_attachments ADD COLUMN image_metadata TEXT;
