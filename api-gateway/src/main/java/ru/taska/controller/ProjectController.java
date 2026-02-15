package ru.taska.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
@Tag(name = "Project Management", description = "Работа с проектами")
public class ProjectController {
    private final RedirectProjectService projectService;

    @Operation(
            summary = "Добавление участника в проект",
            description = "Добавить участника в проект по его ID",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Пользователь добавлен в проект"),
                    @ApiResponse(responseCode = "400", description = "Пользователь уже является участником проекта")
            }
    )
    @PostMapping("/{id}/admin/members")
    public ResponseEntity<?> addMember(@RequestParam Long projectId, @RequestBody AddMemberRequest memberId) {
        return projectService.addMember(projectId, memberId);
    }

    @Operation(
            summary = "Создание проекта",
            description = "Создание проекта с помощью Названия и Буквенного ключа",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Проект создан"),
                    @ApiResponse(responseCode = "400", description = "Проект с таким именем или ключом уже существует")
            }
    )
    @PostMapping("/create")
    public ResponseEntity<?> createProject(@RequestBody CreateProjectRequest)
}
