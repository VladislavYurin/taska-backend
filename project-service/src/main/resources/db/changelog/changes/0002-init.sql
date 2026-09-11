--liquibase formatted sql

--changeset taska:0002-add-version-to-projects
--comment: Добавление version для оптимистичной блокировки при конкурентном обновлении проекта (updateProject)

ALTER TABLE taska.projects
    ADD COLUMN version integer NOT NULL DEFAULT 0;
