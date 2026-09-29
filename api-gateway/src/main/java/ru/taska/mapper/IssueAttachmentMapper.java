package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.api.issue.attachment.v1.AttachmentResponse;
import ru.taska.api.issue.attachment.v1.CreateAttachmentUploadUrlResponse;
import ru.taska.api.issue.attachment.v1.GetAttachmentDownloadUrlResponse;
import ru.taska.api.issue.attachment.v1.ListAttachmentsResponse;
import ru.taska.domain.dto.CreateAttachmentUploadUrlResponseDto;
import ru.taska.domain.dto.GetAttachmentDownloadUrlResponseDto;
import ru.taska.domain.dto.IssueAttachmentDto;
import ru.taska.domain.dto.IssueAttachmentsResponseDto;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Маппер для преобразования Protobuf-ответов gRPC-сервиса в REST DTO вложений.
 */
@Component
@RequiredArgsConstructor
public class IssueAttachmentMapper {
    public final UserProfileMapper userProfileMapper;

    public IssueAttachmentsResponseDto toIssueAttachmentsResponseDto(ListAttachmentsResponse response) {
        var restDto = new IssueAttachmentsResponseDto();
        restDto.setItems(toRestIssueAttachmentList(response));
        return restDto;
    }

    public List<IssueAttachmentDto> toRestIssueAttachmentList(ListAttachmentsResponse response) {
        return response.getAttachmentsList().stream()
                .map(this::toIssueAttachmentDto)
                .toList();
    }

    public IssueAttachmentDto toIssueAttachmentDto(AttachmentResponse r) {
        var dto = new IssueAttachmentDto();
        dto.setId(UUID.fromString(r.getId()));
        dto.setFileName(r.getFileName());
        dto.setContentType(r.getContentType());
        dto.setIssueId(UUID.fromString(r.getIssueId()));
        dto.setUploadedBy(UUID.fromString(r.getUploadedBy()));
        dto.setSizeBytes(r.getSizeBytes());
        dto.setCreatedAt(toOffsetDateTime(r.getCreatedAt()));

        String checksum = r.getChecksum();
        dto.setChecksum(checksum.isBlank() ? null : checksum);

        if (r.hasUploadedByUser()) {
            dto.uploadedByUser(userProfileMapper.toUserSummaryDto(r.getUploadedByUser()));
        }
        return dto;
    }

    public CreateAttachmentUploadUrlResponseDto toCreateAttachmentUploadUrlResponseDto(
            CreateAttachmentUploadUrlResponse response
    ) {
        var restDto = new CreateAttachmentUploadUrlResponseDto();
        restDto.setObjectKey(response.getObjectKey());
        restDto.setUploadUrl(URI.create(response.getUploadUrl()));
        return restDto;
    }

    public GetAttachmentDownloadUrlResponseDto toGetAttachmentDownloadUrlResponseDto(
            GetAttachmentDownloadUrlResponse response
    ) {
        var restDto = new GetAttachmentDownloadUrlResponseDto();
        restDto.setDownloadUrl(URI.create(response.getUrl()));

        String checksum = response.getChecksum();
        restDto.setChecksum(checksum.isBlank() ? null : checksum);
        return restDto;
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos())
                .atOffset(ZoneOffset.UTC);
    }
}
