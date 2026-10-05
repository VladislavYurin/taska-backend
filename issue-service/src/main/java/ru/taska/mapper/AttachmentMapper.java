package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import org.springframework.stereotype.Component;
import ru.taska.api.common.v1.UserSummaryResponse;
import ru.taska.api.issue.attachment.v1.AttachmentResponse;
import ru.taska.api.issue.attachment.v1.CreateAttachmentUploadUrlResponse;
import ru.taska.api.issue.attachment.v1.GetAttachmentDownloadUrlResponse;
import ru.taska.api.issue.attachment.v1.ListAttachmentsResponse;
import ru.taska.domain.dto.AttachmentDownloadUrlDto;
import ru.taska.domain.dto.AttachmentDto;
import ru.taska.domain.entity.IssueAttachment;
import ru.taska.domain.dto.UserSummary;
import ru.taska.storage.dto.PresignedUploadResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class AttachmentMapper {

    public AttachmentResponse toAttachmentResponse(AttachmentDto attachmentDto) {
        IssueAttachment attachment = attachmentDto.issueAttachment();

        AttachmentResponse.Builder builder = AttachmentResponse.newBuilder()
                .setId(attachment.getId().toString())
                .setIssueId(attachment.getIssueId().toString())
                .setUploadedBy(attachment.getUploadedBy().toString())
                .setObjectKey(attachment.getObjectKey())
                .setFileName(attachment.getFileName())
                .setContentType(attachment.getContentType())
                .setSizeBytes(attachment.getSizeBytes())
                .setChecksum(attachment.getChecksum())
                .setCreatedAt(Timestamp.newBuilder()
                        .setSeconds(attachment.getCreatedAt().getEpochSecond())
                        .setNanos(attachment.getCreatedAt().getNano())
                        .build())
                .setUrl(attachmentDto.url());

        return builder.build();
    }

    public ListAttachmentsResponse toListAttachmentsResponse(List<AttachmentDto> attachments) {
        return ListAttachmentsResponse.newBuilder()
                .addAllAttachments(
                        attachments.stream()
                                .map(this::toAttachmentResponse)
                                .toList()
                )
                .build();
    }

    public CreateAttachmentUploadUrlResponse toUploadUrlResponse(PresignedUploadResult result) {
        return CreateAttachmentUploadUrlResponse.newBuilder()
                .setUploadUrl(result.url())
                .setObjectKey(result.objectKey())
              //  .setExpiresIn(result.expiresIn())
                .build();
    }

    public GetAttachmentDownloadUrlResponse toDownloadUrlResponse(AttachmentDownloadUrlDto dto) {
        return GetAttachmentDownloadUrlResponse.newBuilder()
                .setUrl(dto.url())
                .setChecksum(dto.checksum())
                .build();
    }


    public AttachmentResponse toAttachmentResponse(AttachmentDto attachmentDto, Map<UUID, UserSummary> profiles) {
        AttachmentResponse.Builder builder = toAttachmentResponse(attachmentDto).toBuilder();

        UUID uploadedById = attachmentDto.issueAttachment().getUploadedBy();
        UserSummaryResponse userSummaryResponse = IssueMapperUtils.resolveUser(uploadedById, profiles);
        if (userSummaryResponse != null) {
            builder.setUploadedByUser(userSummaryResponse);
        }
        return builder.build();
    }

    public ListAttachmentsResponse toListAttachmentsResponse(List<AttachmentDto> attachments, Map<UUID, UserSummary> profiles) {
        return ListAttachmentsResponse.newBuilder()
                .addAllAttachments(
                        attachments.stream()
                                .map(attachmentDto -> toAttachmentResponse(attachmentDto, profiles))
                                .toList()
                )
                .build();
    }
}
