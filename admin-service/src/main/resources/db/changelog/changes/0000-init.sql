--liquibase formatted sql

-- changeset taska:0000-initial
-- comment: Инициализация миграций admin-service

CREATE SCHEMA IF NOT EXISTS taska;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
