package pro.softcom.aisentinel.infrastructure.confluence.adapter.out;

import lombok.Getter;

/**
 * Exception raised when the parsing of a date from the Confluence API fails.
 * This may indicate a change in the date format or corrupted data.
 */
@Getter
public final class ConfluenceDateParseException extends ConfluenceException {
    private final String invalidDateString;

    public ConfluenceDateParseException(String invalidDateString, Throwable cause) {
        super(String.format("Failed to parse Confluence date format: '%s'. " +
            "This may indicate a change in the Confluence API.", invalidDateString), cause);
        this.invalidDateString = invalidDateString;
    }
}
