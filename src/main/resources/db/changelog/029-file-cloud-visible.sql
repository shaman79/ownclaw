--liquibase formatted sql

--changeset ownclaw:029-file-cloud-visible
-- The owner chooses, file by file before he sends it, whether the cloud model may see it (his
-- decision of 2026-11-26: "I want to be able to decide if attachment (file, image, whatever) should
-- be sent to cloud or not"). An image he lets it see is shown as a picture (shown_to_cloud, with its
-- copy, as a pasted one is); any other file he lets it see is no private file: cloud_visible, and
-- then what skills read from it is not private, and a text file's text is given to the cloud model.
-- Every file that exists when this runs keeps what it had: not visible.
ALTER TABLE file_attachments ADD COLUMN cloud_visible INTEGER NOT NULL DEFAULT 0;
