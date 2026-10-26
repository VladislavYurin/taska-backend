package ru.taska.service;

import ru.taska.api.project.v1.ProjectIssueTypeResponse;
import ru.taska.entity.WorkflowBindingEntity;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** The generation fences a deleted code from bindings created during its previous lifecycle. */
public final class IssueTypeBindingIdentity {
    private IssueTypeBindingIdentity() { }

    public static UUID generation(ProjectIssueTypeResponse type) {
        if (type.getGeneration().isBlank()) {
            throw new DomainException(DomainStatus.UNAVAILABLE, "Project registry must be upgraded before workflow operations");
        }
        return UUID.fromString(type.getGeneration());
    }

    public static UUID initialGeneration(UUID project, String code) {
        try {
            String hex = HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest((project + ":" + code).getBytes(StandardCharsets.UTF_8)));
            return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                    + "-" + hex.substring(16, 20) + "-" + hex.substring(20));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static boolean matches(WorkflowBindingEntity binding, ProjectIssueTypeResponse type) {
        if (!binding.getIssueType().equals(type.getCode())) return false;
        UUID boundGeneration = binding.getIssueTypeGeneration() == null
                ? initialGeneration(binding.getProjectId(), binding.getIssueType()) : binding.getIssueTypeGeneration();
        return boundGeneration.equals(generation(type));
    }
}
