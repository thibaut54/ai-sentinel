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
        super(String.format("Échec du parsing du format de date Confluence: '%s'. " +
            "Cela peut indiquer un changement dans l'API Confluence.", invalidDateString), cause);
        this.invalidDateString = invalidDateString;
    }
}
