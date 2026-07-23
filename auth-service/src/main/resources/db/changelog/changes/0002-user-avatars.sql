--liquibase formatted sql

-- changeset taska:0002-user-avatars
-- comment: Таблица аватаров пользователей

CREATE TABLE IF NOT EXISTS taska.user_avatars
(
    id           uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid         NOT NULL REFERENCES taska.users (id) ON DELETE CASCADE,
    object_key   varchar(512) NOT NULL,
    file_name    varchar(255) NOT NULL,
    content_type varchar(128) NOT NULL,
    size_bytes   bigint       NOT NULL,
    created_at   timestamptz  NOT NULL,

    CONSTRAINT user_avatars_user_id_uq         UNIQUE (user_id),
    CONSTRAINT user_avatars_size_positive_chk  CHECK (size_bytes > 0)
);
