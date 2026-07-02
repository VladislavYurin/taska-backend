package ru.taska.mapper;

import org.springframework.stereotype.Component;

@Component
public class IssueMapper {

    public boolean isValidType(String issueType) {
        if (issueType == null || issueType.isEmpty()){
            return false;
        }

        switch (issueType.toUpperCase()) {
            case "TASK":
            case "BUG":
            case "STORY":
                return true;
            default:
                return false;
        }
    }
}
