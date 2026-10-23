--liquibase formatted sql

-- changeset taska:0005-avatar-object-key-unique
-- comment: Один объект хранилища может принадлежать только одному аватару
CREATE UNIQUE INDEX user_avatars_object_key_uq ON taska.user_avatars (object_key);
