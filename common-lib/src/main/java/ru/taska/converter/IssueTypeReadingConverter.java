package ru.taska.converter;

import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import ru.taska.domain.IssueType;

@ReadingConverter
public class IssueTypeReadingConverter implements Converter<String, IssueType> {
    @Override
    public IssueType convert(String source) {
        return IssueType.valueOf(source);
    }
}
