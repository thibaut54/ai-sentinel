package pro.softcom.aisentinel.application.jira.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AdfContentParser - ADF JSON to plain text conversion")
class AdfContentParserTest {

    private final AdfContentParser parser = new AdfContentParser();

    // ========== Null / Empty / Invalid Input ==========

    @Nested
    @DisplayName("Null, empty, and invalid input handling")
    class NullEmptyInvalidInput {

        @Test
        @DisplayName("Should_ReturnEmpty_When_InputIsNull")
        void Should_ReturnEmpty_When_InputIsNull() {
            // When
            String result = parser.toPlainText(null);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should_ReturnEmpty_When_InputIsEmpty")
        void Should_ReturnEmpty_When_InputIsEmpty() {
            // When
            String result = parser.toPlainText("");

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should_ReturnEmpty_When_InputIsBlank")
        void Should_ReturnEmpty_When_InputIsBlank() {
            // When
            String result = parser.toPlainText("   ");

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should_ReturnEmpty_When_InputIsInvalidJson")
        void Should_ReturnEmpty_When_InputIsInvalidJson() {
            // When
            String result = parser.toPlainText("not valid json {{{");

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should_ReturnEmpty_When_InputIsJsonButNotAdf")
        void Should_ReturnEmpty_When_InputIsJsonButNotAdf() {
            // Given
            String json = """
                {"key": "value", "number": 42}
                """;

            // When
            String result = parser.toPlainText(json);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========== Simple Document ==========

    @Nested
    @DisplayName("Simple document parsing")
    class SimpleDocument {

        @Test
        @DisplayName("Should_ExtractText_When_SingleParagraph")
        void Should_ExtractText_When_SingleParagraph() {
            // Given
            String adf = """
                {
                  "version": 1,
                  "type": "doc",
                  "content": [
                    {
                      "type": "paragraph",
                      "content": [
                        { "type": "text", "text": "Hello world" }
                      ]
                    }
                  ]
                }
                """;

            // When
            String result = parser.toPlainText(adf);

            // Then
            assertThat(result).isEqualTo("Hello world");
        }

        @Test
        @DisplayName("Should_ConcatenateText_When_MultipleParagraphs")
        void Should_ConcatenateText_When_MultipleParagraphs() {
            // Given
            String adf = """
                {
                  "version": 1,
                  "type": "doc",
                  "content": [
                    {
                      "type": "paragraph",
                      "content": [
                        { "type": "text", "text": "First paragraph" }
                      ]
                    },
                    {
                      "type": "paragraph",
                      "content": [
                        { "type": "text", "text": "Second paragraph" }
                      ]
                    }
                  ]
                }
                """;

            // When
            String result = parser.toPlainText(adf);

            // Then
            assertThat(result).isEqualTo("First paragraph\nSecond paragraph");
        }

        @Test
        @DisplayName("Should_ConcatenateInlineText_When_MultipleTextNodes")
        void Should_ConcatenateInlineText_When_MultipleTextNodes() {
            // Given
            String adf = """
                {
                  "version": 1,
                  "type": "doc",
                  "content": [
                    {
                      "type": "paragraph",
                      "content": [
                        { "type": "text", "text": "Hello " },
                        { "type": "text", "text": "world", "marks": [{"type": "strong"}] }
                      ]
                    }
                  ]
                }
                """;

            // When
            String result = parser.toPlainText(adf);

            // Then
            assertThat(result).isEqualTo("Hello world");
        }

        @Test
        @DisplayName("Should_ReturnEmpty_When_DocHasNoContent")
        void Should_ReturnEmpty_When_DocHasNoContent() {
            // Given
            String adf = """
                {
                  "version": 1,
                  "type": "doc",
                  "content": []
                }
                """;

            // When
            String result = parser.toPlainText(adf);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========== Mentions ==========

    @Nested
    @DisplayName("Mention node parsing")
    class Mentions {

        @Test
        @DisplayName("Should_ExtractDisplayName_When_MentionWithAttrsText")
        void Should_ExtractDisplayName_When_MentionWithAttrsText() {
            // Given
            String adf = """
                {
                  "version": 1,
                  "type": "doc",
                  "content": [
                    {
                      "type": "paragraph",
                      "content": [
                        { "type": "text", "text": "Assigned to " },
                        {
                          "type": "mention",
                          "attrs": {
                            "id": "user-123",
                            "text": "@John Smith",
                            "accessLevel": ""
                          }
                        }
                      ]
                    }
                  ]
                }
                """;

            // When
            String result = parser.toPlainText(adf);

            // Then
            assertThat(result).isEqualTo("Assigned to @John Smith");
        }

        @Test
        @DisplayName("Should_ReturnEmpty_When_MentionHasNoAttrsText")
        void Should_ReturnEmpty_When_MentionHasNoAttrsText() {
            // Given
            String adf = """
                {
                  "version": 1,
                  "type": "doc",
                  "content": [
                    {
                      "type": "paragraph",
                      "content": [
                        { "type": "mention", "attrs": { "id": "user-123" } }
                      ]
                    }
                  ]
                }
                """;

            // When
            String result = parser.toPlainText(adf);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========== Headings ==========

    @Nested
    @DisplayName("Heading node parsing")
    class Headings {

        @Test
        @DisplayName("Should_ExtractHeadingText_When_HeadingWithLevel")
        void Should_ExtractHeadingText_When_HeadingWithLevel() {
            // Given
            String adf = """
                {
                  "version": 1,
                  "type": "doc",
                  "content": [
                    {
                      "type": "heading",
                      "attrs": { "level": 2 },
                      "content": [
                        { "type": "text", "text": "Section Title" }
                      ]
                    }
                  ]
                }
                """;

            // When
            String result = parser.toPlainText(adf);

            // Then
            assertThat(result).isEqualTo("Section Title");
        }

        @Test
        @DisplayName("Should_SeparateByNewlines_When_HeadingFollowedByParagraph")
        void Should_SeparateByNewlines_When_HeadingFollowedByParagraph() {
            // Given
            String adf = """
                {
                  "version": 1,
                  "type": "doc",
                  "content": [
                    {
                      "type": "heading",
                      "attrs": { "level": 1 },
                      "content": [
                        { "type": "text", "text": "Title" }
                      ]
                    },
                    {
                      "type": "paragraph",
                      "content": [
                        { "type": "text", "text": "Body text" }
                      ]
                    }
                  ]
                }
                """;

            // When
            String result = parser.toPlainText(adf);

            // Then
            assertThat(result).isEqualTo("Title\nBody text");
        }
    }

    // ========== Code Block ==========

    @Nested
    @DisplayName("CodeBlock node parsing")
    class CodeBlock {

        @Test
        @DisplayName("Should_ExtractCode_When_CodeBlockWithContent")
        void Should_ExtractCode_When_CodeBlockWithContent() {
            // Given
            String adf = """
                {
                  "version": 1,
                  "type": "doc",
                  "content": [
                    {
                      "type": "codeBlock",
                      "attrs": { "language": "java" },
                      "content": [
                        { "type": "text", "text": "System.out.println(\\"hello\\");" }
                      ]
                    }
                  ]
                }
                """;

            // When
            String result = parser.toPlainText(adf);

            // Then
            assertThat(result).isEqualTo("System.out.println(\"hello\");");
        }

        @Test
        @DisplayName("Should_ReturnEmpty_When_CodeBlockIsEmpty")
        void Should_ReturnEmpty_When_CodeBlockIsEmpty() {
            // Given
            String adf = """
                {
                  "version": 1,
                  "type": "doc",
                  "content": [
                    {
                      "type": "codeBlock",
                      "attrs": { "language": "java" }
                    }
                  ]
                }
                """;

            // When
            String result = parser.toPlainText(adf);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========== Lists ==========

    @Nested
    @DisplayName("List node parsing")
    class Lists {

        @Test
        @DisplayName("Should_ExtractListItems_When_BulletList")
        void Should_ExtractListItems_When_BulletList() {
            // Given
            String adf = """
                {
                  "version": 1,
                  "type": "doc",
                  "content": [
                    {
                      "type": "bulletList",
                      "content": [
                        {
                          "type": "listItem",
                          "content": [
                            {
                              "type": "paragraph",
                              "content": [
                                { "type": "text", "text": "Item 1" }
                              ]
                            }
                          ]
                        },
                        {
                          "type": "listItem",
                          "content": [
                            {
                              "type": "paragraph",
                              "content": [
                                { "type": "text", "text": "Item 2" }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;

            // When
            String result = parser.toPlainText(adf);

            // Then
            assertThat(result).contains("Item 1");
            assertThat(result).contains("Item 2");
        }

        @Test
        @DisplayName("Should_ExtractListItems_When_OrderedList")
        void Should_ExtractListItems_When_OrderedList() {
            // Given
            String adf = """
                {
                  "version": 1,
                  "type": "doc",
                  "content": [
                    {
                      "type": "orderedList",
                      "content": [
                        {
                          "type": "listItem",
                          "content": [
                            {
                              "type": "paragraph",
                              "content": [
                                { "type": "text", "text": "First" }
                              ]
                            }
                          ]
                        },
                        {
                          "type": "listItem",
                          "content": [
                            {
                              "type": "paragraph",
                              "content": [
                                { "type": "text", "text": "Second" }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;

            // When
            String result = parser.toPlainText(adf);

            // Then
            assertThat(result).contains("First");
            assertThat(result).contains("Second");
        }
    }

    // ========== Table ==========

    @Nested
    @DisplayName("Table node parsing")
    class Table {

        @Test
        @DisplayName("Should_ExtractCellContent_When_TableWithRows")
        void Should_ExtractCellContent_When_TableWithRows() {
            // Given
            String adf = """
                {
                  "version": 1,
                  "type": "doc",
                  "content": [
                    {
                      "type": "table",
                      "content": [
                        {
                          "type": "tableRow",
                          "content": [
                            {
                              "type": "tableHeader",
                              "content": [
                                {
                                  "type": "paragraph",
                                  "content": [
                                    { "type": "text", "text": "Name" }
                                  ]
                                }
                              ]
                            },
                            {
                              "type": "tableHeader",
                              "content": [
                                {
                                  "type": "paragraph",
                                  "content": [
                                    { "type": "text", "text": "Email" }
                                  ]
                                }
                              ]
                            }
                          ]
                        },
                        {
                          "type": "tableRow",
                          "content": [
                            {
                              "type": "tableCell",
                              "content": [
                                {
                                  "type": "paragraph",
                                  "content": [
                                    { "type": "text", "text": "John" }
                                  ]
                                }
                              ]
                            },
                            {
                              "type": "tableCell",
                              "content": [
                                {
                                  "type": "paragraph",
                                  "content": [
                                    { "type": "text", "text": "john@example.com" }
                                  ]
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;

            // When
            String result = parser.toPlainText(adf);

            // Then
            assertThat(result).contains("Name");
            assertThat(result).contains("Email");
            assertThat(result).contains("John");
            assertThat(result).contains("john@example.com");
        }
    }

    // ========== Emoji ==========

    @Nested
    @DisplayName("Emoji node parsing")
    class Emoji {

        @Test
        @DisplayName("Should_ExtractShortName_When_EmojiWithAttrs")
        void Should_ExtractShortName_When_EmojiWithAttrs() {
            // Given
            String adf = """
                {
                  "version": 1,
                  "type": "doc",
                  "content": [
                    {
                      "type": "paragraph",
                      "content": [
                        { "type": "text", "text": "Great job " },
                        { "type": "emoji", "attrs": { "shortName": ":thumbsup:", "id": "1f44d" } }
                      ]
                    }
                  ]
                }
                """;

            // When
            String result = parser.toPlainText(adf);

            // Then
            assertThat(result).isEqualTo("Great job :thumbsup:");
        }
    }

    // ========== HardBreak ==========

    @Nested
    @DisplayName("HardBreak node parsing")
    class HardBreak {

        @Test
        @DisplayName("Should_InsertNewline_When_HardBreak")
        void Should_InsertNewline_When_HardBreak() {
            // Given
            String adf = """
                {
                  "version": 1,
                  "type": "doc",
                  "content": [
                    {
                      "type": "paragraph",
                      "content": [
                        { "type": "text", "text": "Line 1" },
                        { "type": "hardBreak" },
                        { "type": "text", "text": "Line 2" }
                      ]
                    }
                  ]
                }
                """;

            // When
            String result = parser.toPlainText(adf);

            // Then
            assertThat(result).isEqualTo("Line 1\nLine 2");
        }
    }

    // ========== Blockquote ==========

    @Nested
    @DisplayName("Blockquote node parsing")
    class Blockquote {

        @Test
        @DisplayName("Should_ExtractQuotedText_When_Blockquote")
        void Should_ExtractQuotedText_When_Blockquote() {
            // Given
            String adf = """
                {
                  "version": 1,
                  "type": "doc",
                  "content": [
                    {
                      "type": "blockquote",
                      "content": [
                        {
                          "type": "paragraph",
                          "content": [
                            { "type": "text", "text": "This is a quote" }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;

            // When
            String result = parser.toPlainText(adf);

            // Then
            assertThat(result).contains("This is a quote");
        }
    }

    // ========== Panel ==========

    @Nested
    @DisplayName("Panel node parsing")
    class Panel {

        @Test
        @DisplayName("Should_ExtractPanelContent_When_PanelWithText")
        void Should_ExtractPanelContent_When_PanelWithText() {
            // Given
            String adf = """
                {
                  "version": 1,
                  "type": "doc",
                  "content": [
                    {
                      "type": "panel",
                      "attrs": { "panelType": "info" },
                      "content": [
                        {
                          "type": "paragraph",
                          "content": [
                            { "type": "text", "text": "Important note" }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;

            // When
            String result = parser.toPlainText(adf);

            // Then
            assertThat(result).contains("Important note");
        }
    }

    // ========== Media (ignored) ==========

    @Nested
    @DisplayName("Media nodes (ignored)")
    class Media {

        @Test
        @DisplayName("Should_IgnoreMedia_When_MediaGroupPresent")
        void Should_IgnoreMedia_When_MediaGroupPresent() {
            // Given
            String adf = """
                {
                  "version": 1,
                  "type": "doc",
                  "content": [
                    {
                      "type": "paragraph",
                      "content": [
                        { "type": "text", "text": "See attached" }
                      ]
                    },
                    {
                      "type": "mediaGroup",
                      "content": [
                        {
                          "type": "media",
                          "attrs": { "id": "abc-123", "type": "file", "collection": "attachments" }
                        }
                      ]
                    }
                  ]
                }
                """;

            // When
            String result = parser.toPlainText(adf);

            // Then
            assertThat(result).isEqualTo("See attached");
            assertThat(result).doesNotContain("abc-123");
        }

        @Test
        @DisplayName("Should_IgnoreMedia_When_MediaSinglePresent")
        void Should_IgnoreMedia_When_MediaSinglePresent() {
            // Given
            String adf = """
                {
                  "version": 1,
                  "type": "doc",
                  "content": [
                    {
                      "type": "mediaSingle",
                      "content": [
                        {
                          "type": "media",
                          "attrs": { "id": "img-456", "type": "file" }
                        }
                      ]
                    },
                    {
                      "type": "paragraph",
                      "content": [
                        { "type": "text", "text": "Caption below" }
                      ]
                    }
                  ]
                }
                """;

            // When
            String result = parser.toPlainText(adf);

            // Then
            assertThat(result).isEqualTo("Caption below");
        }
    }

    // ========== Complex Document ==========

    @Nested
    @DisplayName("Complex document parsing")
    class ComplexDocument {

        @Test
        @DisplayName("Should_ExtractAllText_When_ComplexMixedDocument")
        void Should_ExtractAllText_When_ComplexMixedDocument() {
            // Given - a realistic Jira issue description
            String adf = """
                {
                  "version": 1,
                  "type": "doc",
                  "content": [
                    {
                      "type": "heading",
                      "attrs": { "level": 1 },
                      "content": [
                        { "type": "text", "text": "Bug Report" }
                      ]
                    },
                    {
                      "type": "paragraph",
                      "content": [
                        { "type": "text", "text": "Reported by " },
                        { "type": "mention", "attrs": { "id": "u1", "text": "@Alice" } },
                        { "type": "text", "text": " on 2024-01-15" }
                      ]
                    },
                    {
                      "type": "heading",
                      "attrs": { "level": 2 },
                      "content": [
                        { "type": "text", "text": "Steps to Reproduce" }
                      ]
                    },
                    {
                      "type": "orderedList",
                      "content": [
                        {
                          "type": "listItem",
                          "content": [
                            {
                              "type": "paragraph",
                              "content": [
                                { "type": "text", "text": "Open the app" }
                              ]
                            }
                          ]
                        },
                        {
                          "type": "listItem",
                          "content": [
                            {
                              "type": "paragraph",
                              "content": [
                                { "type": "text", "text": "Click submit" }
                              ]
                            }
                          ]
                        }
                      ]
                    },
                    {
                      "type": "codeBlock",
                      "attrs": { "language": "bash" },
                      "content": [
                        { "type": "text", "text": "ERROR: NullPointerException" }
                      ]
                    }
                  ]
                }
                """;

            // When
            String result = parser.toPlainText(adf);

            // Then
            assertThat(result)
                .contains("Bug Report")
                .contains("Reported by @Alice on 2024-01-15")
                .contains("Steps to Reproduce")
                .contains("Open the app")
                .contains("Click submit")
                .contains("ERROR: NullPointerException");
        }
    }
}
