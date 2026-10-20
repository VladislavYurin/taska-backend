--liquibase formatted sql

--changeset taska:0001-add-description-and-color-for-projects
--comment: Добавление описание и цвета к проекту

ALTER TABLE taska.projects
    ADD COLUMN description text NULL,
    ADD COLUMN color varchar(7) NULL;