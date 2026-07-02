package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.workflow.v1.IssueStatus;

@Component
public class StatusMapper {

    /**
     * Конвертирует строковый ключ статуса в IssueStatus enum.
     *
     * @param statusKey строковый ключ статуса (TODO, IN_PROGRESS, DONE)
     * @return IssueStatus enum
     */
    public IssueStatus toProtoStatus(String statusKey) {
        if (statusKey == null || statusKey.isEmpty()) {
            return IssueStatus.ISSUE_STATUS_UNSPECIFIED;
        }

        switch (statusKey.toUpperCase()) {
            case "TODO":
                return IssueStatus.ISSUE_STATUS_TODO;
            case "IN_PROGRESS":
                return IssueStatus.ISSUE_STATUS_IN_PROGRESS;
            case "DONE":
                return IssueStatus.ISSUE_STATUS_DONE;
            default:
                return IssueStatus.ISSUE_STATUS_UNSPECIFIED;
        }
    }

    /**
     * Конвертирует IssueStatus enum в строковый ключ статуса.
     *
     * @param status IssueStatus enum
     * @return строковый ключ статуса
     */
    public String fromProtoStatus(IssueStatus status) {
        if (status == null) {
            return null;
        }

        switch (status) {
            case ISSUE_STATUS_TODO:
                return "TODO";
            case ISSUE_STATUS_IN_PROGRESS:
                return "IN_PROGRESS";
            case ISSUE_STATUS_DONE:
                return "DONE";
            case ISSUE_STATUS_UNSPECIFIED:
            default:
                return null;
        }
    }

    /**
     * Проверяет, является ли статус валидным.
     *
     * @param statusKey строковый ключ статуса
     * @return true если статус валидный
     */
    public boolean isValidStatus(String statusKey) {
        if (statusKey == null || statusKey.isEmpty()) {
            return false;
        }

        switch (statusKey.toUpperCase()) {
            case "TODO":
            case "IN_PROGRESS":
            case "DONE":
                return true;
            default:
                return false;
        }
    }
}
