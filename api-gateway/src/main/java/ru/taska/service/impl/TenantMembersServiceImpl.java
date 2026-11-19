package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.profile.v1.UserDetails;
import ru.taska.api.b2b.v1.Membership;
import ru.taska.api.b2b.v1.MembershipPage;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.MembershipPageDto;
import ru.taska.domain.dto.TenantMemberDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.service.TenantMembersService;
import ru.taska.service.grpc.client.TenantMembershipsClient;
import ru.taska.transport.grpc.GrpcUserProfileServiceClient;

import java.util.*;

@Service
@RequiredArgsConstructor
public class TenantMembersServiceImpl implements TenantMembersService {

    private final TenantMembershipsClient tenantMembershipsClient;
    private final GrpcUserProfileServiceClient profilesClient;

    @Override
    public Mono<MembershipPageDto> list(UUID tenantId, String cursor, int limit, GatewayContext context) {
        return tenantMembershipsClient.list(tenantId, cursor, limit, context)
                .switchIfEmpty(Mono.error(new DomainException(
                        DomainStatus.UNAVAILABLE,
                        "Incomplete or invalid directory response"
                )))
                .flatMap(membershipPage -> {
                    if (membershipPage.getItemsCount() == 0) {
                        return Mono.just(toResponse(membershipPage, Map.of()));
                    }
                    List<UUID> uuids = membershipPage.getItemsList()
                            .stream()
                            .map(Membership::getUserId)
                            .map(UUID::fromString)
                            .toList();

                    var expectedIds = new HashSet<>(
                            membershipPage.getItemsList()
                                    .stream()
                                    .map(Membership::getUserId)
                                    .toList()
                    );
                    return profilesClient.getUserDetailsByIds(uuids, context)
                            .switchIfEmpty(Mono.error(new DomainException(
                                    DomainStatus.UNAVAILABLE,
                                    "Incomplete or invalid directory response"
                            )))
                            .flatMap(response -> {
                                Map<String, UserDetails> profiles = new HashMap<>();
                                for (var userDetails : response.getUserDetailsList()) {
                                    String userId = userDetails.getUserId();
                                    if (!expectedIds.contains(userId) ||
                                        profiles.putIfAbsent(userId, userDetails) != null
                                    ) {
                                        return Mono.error(new DomainException(
                                                DomainStatus.UNAVAILABLE,
                                                "Duplicate user in profile response"
                                        ));
                                    }
                                }
                                return Mono.just(toResponse(membershipPage, profiles));
                            });
                });
    }

    private MembershipPageDto toResponse(
            MembershipPage page,
            Map<String, UserDetails> profiles
    ) {
        List<TenantMemberDto> items = page.getItemsList().stream()
                .map(member -> toItem(member, profiles.get(member.getUserId())))
                .toList();

        var result = new MembershipPageDto().items(items);

        if (page.hasNextCursor()) {
            result.setNextCursor(page.getNextCursor());
        }

        return result;
    }

    private TenantMemberDto toItem(
            Membership member,
            UserDetails profile
    ) {
        if (profile == null) {
            throw new DomainException(
                    DomainStatus.UNAVAILABLE,
                    "Incomplete or invalid directory response"
            );
        }

        var role = switch (member.getRole()) {
            case ROLE_MEMBER -> TenantMemberDto.RoleEnum.MEMBER;
            case ROLE_TENANT_ADMIN -> TenantMemberDto.RoleEnum.TENANT_ADMIN;
            default -> throw new DomainException(
                    DomainStatus.UNAVAILABLE,
                    "Incomplete or invalid directory response"
            );
        };

        var state = switch (member.getState()) {
            case STATE_ACTIVE -> TenantMemberDto.StateEnum.ACTIVE;
            case STATE_SUSPENDED -> TenantMemberDto.StateEnum.SUSPENDED;
            default -> throw new DomainException(
                    DomainStatus.UNAVAILABLE,
                    "Incomplete or invalid directory response"
            );
        };

        var item = new TenantMemberDto()
                .id(UUID.fromString(member.getId()))
                .tenantId(UUID.fromString(member.getTenantId()))
                .userId(UUID.fromString(member.getUserId()))
                .role(role)
                .state(state)
                .isOwner(member.getIsOwner())
                .version(member.getVersion())
                .displayName(profile.getDisplayName())
                .email(profile.getEmail());

        if (profile.hasAvatar()
            && !profile.getAvatar().getDownloadUrl().isBlank()) {
            item.setAvatarPath(profile.getAvatar().getDownloadUrl());
        }

        return item;
    }
}