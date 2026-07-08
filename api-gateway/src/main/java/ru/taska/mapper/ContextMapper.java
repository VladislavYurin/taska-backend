package ru.taska.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.api.common.v1.UserContext;
import ru.taska.domain.GatewayUserContext;


@Component
@RequiredArgsConstructor
public class ContextMapper {

    private final AuthMapper authMapper;

    public GatewayUserContext mapToGatewayUserContext(UserContext proto) {
        return new GatewayUserContext(
                proto.getUserId(),
                proto.getLogin(),
                proto.getEmail(),
                proto.getDisplayName(),
                authMapper.toGatewayUserStatus(proto.getStatus())
        );
    }
}
