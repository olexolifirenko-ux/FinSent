package com.finsent.core;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.finsent.directory.DirectorySystem;

/**
 * Verifies {@link Secrets}: the {@code ENV:}/{@code $VAR} resolution forms, that a real environment
 * variable wins over the {@code .env} fallback, the {@code .env} fallback itself, and the
 * {@code .env} line parsing (comments, blanks, {@code export}, quotes, {@code =} in the value).
 */
public class Secrets_utest
{
    @After
    public void tearDown()
    {
        // Leave global state clean for other test classes (Secrets caches the .env statically).
        DirectorySystem.setDirectory(null);
        Secrets.resetForTest();
    }

    @Test
    public void resolvesLiteralAndEnvForms()
    {
        assertEquals(null, Secrets.resolve(null));
        assertEquals("", Secrets.resolve(""));
        assertEquals("plain-value", Secrets.resolve("plain-value"));
        assertEquals("${KEEP}", Secrets.resolve("${KEEP}")); // ${...} is left untouched
        // PATH is set in every environment we run in; both forms read it.
        assertEquals(System.getenv("PATH"), Secrets.resolve("ENV:PATH"));
        assertEquals(System.getenv("PATH"), Secrets.resolve("$PATH"));
    }

    @Test
    public void unsetVariableResolvesToEmpty()
    {
        assertEquals("", Secrets.resolve("ENV:FS_DEFINITELY_UNSET_VAR_XYZ"));
    }

    @Test
    public void fallsBackToDotenvWhenEnvVarUnset() throws IOException
    {
        Path dir = Files.createTempDirectory("fs-secrets-utest");
        try
        {
            Files.write(dir.resolve(".env"), List.of(
                    "# a comment",
                    "",
                    "FS_TEST_SECRET=from_dotenv",
                    "export FS_EXPORTED=exported_value",
                    "FS_QUOTED=\"quoted value\"",
                    "FS_URLISH=https://host/path?a=b"), StandardCharsets.UTF_8);
            DirectorySystem.setDirectory(dir.toFile());
            Secrets.resetForTest();

            assertEquals("from_dotenv", Secrets.resolve("ENV:FS_TEST_SECRET"));
            assertEquals("exported_value", Secrets.resolve("ENV:FS_EXPORTED"));
            assertEquals("quoted value", Secrets.resolve("ENV:FS_QUOTED"));
            assertEquals("https://host/path?a=b", Secrets.resolve("ENV:FS_URLISH"));
            assertEquals("", Secrets.resolve("ENV:FS_NOT_IN_DOTENV"));
            // A real env var still wins over the .env fallback.
            assertEquals(System.getenv("PATH"), Secrets.resolve("ENV:PATH"));
        }
        finally
        {
            Files.deleteIfExists(dir.resolve(".env"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    public void parseDotenvHandlesCommentsBlanksExportQuotesAndEquals()
    {
        Map<String, String> values = Secrets.parseDotenv(List.of(
                "  # comment ",
                "   ",
                "  KEY = value ",
                "export EXPORTED=ex",
                "QUOTED='single quoted'",
                "URL=https://h/p?x=1&y=2",
                "=novalue",
                "NOEQUALS"));

        assertEquals("value", values.get("KEY"));
        assertEquals("ex", values.get("EXPORTED"));
        assertEquals("single quoted", values.get("QUOTED"));
        assertEquals("https://h/p?x=1&y=2", values.get("URL"));
        assertEquals("no empty key", 4, values.size()); // KEY, EXPORTED, QUOTED, URL
    }
}
