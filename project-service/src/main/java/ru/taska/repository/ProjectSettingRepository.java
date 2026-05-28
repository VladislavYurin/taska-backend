package ru.taska.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import ru.taska.entity.ProjectSetting;
import java.util.UUID;

@Repository
public interface ProjectSettingRepository extends ReactiveCrudRepository<ProjectSetting, UUID> {
}