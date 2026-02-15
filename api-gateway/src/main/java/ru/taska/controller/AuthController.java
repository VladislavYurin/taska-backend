package ru.taska.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.PermitAll;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.taska.services.RedirectAuthService;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Auth Management", description = "Регистрация и авторизация")
public class AuthController {
    private final RedirectAuthService authService;

    @Operation(
            summary = "Регистрация пользователя",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Регистрация успешна"),
                    @ApiResponse(responseCode = "400", description = "Пользователь уже существует"),
            }
    )
    @PostMapping("/reg")
    @PermitAll
    public JwtAuthResponse registration(@RequestBody @Valid RegRequest request) {
        return authService.registration(request);
    }

    @Operation(summary = "Авторизация пользователя")
    @PostMapping("/login")
    @PermitAll
    public JwtAuthResponse login(@RequestBody @Valid AuthRequest request) {
        return authService.authentication(request);
    }
}
