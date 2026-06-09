package com.finsent.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.finsent.directory.DirectorySystem;

/**
 * Resolves config values that may reference an environment variable. Ports
 * {@code shared.resolve_secret}, supporting two forms:
 * <ul>
 *   <li>{@code "ENV:VAR_NAME"} &rarr; the value of {@code VAR_NAME}</li>
 *   <li>{@code "$VAR_NAME"} &rarr; the value of {@code VAR_NAME} (but not {@code "${...}"})</li>
 * </ul>
 * A value matching neither form is returned unchanged. Resolution prefers a real environment
 * variable ({@link System#getenv}); when that is unset it falls back to a {@code .env} file in the
 * release home (resolved via {@link DirectorySystem}, loaded once and tolerant of a missing file) -
 * the Java analogue of python-dotenv loading {@code .env} into {@code os.environ}. A
 * referenced-but-unset variable (in neither place) resolves to the empty string, as in Python.
 */
public final class Secrets
{
    private static final String ENV_PREFIX = "ENV:";
    private static final String DOTENV_NAME = ".env";
    private static final String EXPORT_PREFIX = "export ";

    private static volatile Map<String, String> dotenv_;

    private Secrets()
    {
    }

    /** Resolve a possibly env-referencing config value. */
    public static String resolve(String value)
    {
        String resolved;
        if (value == null || value.isEmpty())
        {
            resolved = value;
        }
        else if (value.startsWith(ENV_PREFIX))
        {
            resolved = lookup(value.substring(ENV_PREFIX.length()));
        }
        else if (value.startsWith("$") && !value.startsWith("${"))
        {
            resolved = lookup(value.substring(1));
        }
        else
        {
            resolved = value;
        }
        return resolved;
    }

    /** A real environment variable wins; otherwise the {@code .env} fallback; otherwise empty. */
    private static String lookup(String name)
    {
        String env = System.getenv(name);
        return env != null ? env : dotenv().getOrDefault(name, "");
    }

    private static Map<String, String> dotenv()
    {
        Map<String, String> values = dotenv_;
        if (values == null)
        {
            synchronized (Secrets.class)
            {
                values = dotenv_;
                if (values == null)
                {
                    values = loadDotenv();
                    dotenv_ = values;
                }
            }
        }
        return values;
    }

    private static Map<String, String> loadDotenv()
    {
        Map<String, String> values = new HashMap<>();
        File file = DirectorySystem.resolveToFile(DOTENV_NAME);
        if (file.isFile())
        {
            try
            {
                values = parseDotenv(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
            }
            catch (IOException unreadable)
            {
                // Tolerated: an unreadable .env simply provides no fallback values.
            }
        }
        return values;
    }

    /** Parse {@code KEY=value} lines, skipping blanks and {@code #} comments. */
    static Map<String, String> parseDotenv(List<String> lines)
    {
        Map<String, String> values = new HashMap<>();
        for (String raw : lines)
        {
            addEntry(values, raw);
        }
        return values;
    }

    private static void addEntry(Map<String, String> values, String raw)
    {
        String line = raw.trim();
        if (line.startsWith(EXPORT_PREFIX))
        {
            line = line.substring(EXPORT_PREFIX.length()).trim();
        }
        if (!line.isEmpty() && line.charAt(0) != '#')
        {
            int eq = line.indexOf('=');
            if (eq > 0)
            {
                String key = line.substring(0, eq).trim();
                if (!key.isEmpty())
                {
                    values.put(key, unquote(line.substring(eq + 1).trim()));
                }
            }
        }
    }

    /** Strip a single pair of surrounding single or double quotes, if present. */
    private static String unquote(String value)
    {
        String result = value;
        if (value.length() >= 2)
        {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\''))
            {
                result = value.substring(1, value.length() - 1);
            }
        }
        return result;
    }

    /** Clear the cached {@code .env} so the next resolve reloads it. Test seam only. */
    static synchronized void resetForTest()
    {
        dotenv_ = null;
    }
}
