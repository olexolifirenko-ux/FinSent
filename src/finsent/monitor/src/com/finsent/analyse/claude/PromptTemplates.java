package com.finsent.analyse.claude;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Loads the Claude prompt templates from the configured prompts directory and substitutes the
 * blocks {@link PromptBuilder} produces (ports Python {@code analyse._load_prompt_template} plus the
 * {@code template.replace(...)} fill). Unlike Python there is no hardcoded fallback: a missing
 * template is a deployment error and surfaces as an {@link IOException} rather than silently
 * degrading the prompt. The directory is {@code Config.promptsDir()} resolved through the directory
 * subsystem.
 */
public final class PromptTemplates
{
    private static final String EXTENSION = ".txt";

    private PromptTemplates()
    {
    }

    private static final int VERSION_HEX_LEN = 12; // 6 bytes of the digest -> 12 hex chars

    /** Read {@code <promptsDir>/<name>.txt} as UTF-8. */
    public static String load(File promptsDir, String name) throws IOException
    {
        return Files.readString(new File(promptsDir, name + EXTENSION).toPath(), StandardCharsets.UTF_8);
    }

    /**
     * Short content hash of a prompt's text ({@value #VERSION_HEX_LEN} hex chars of its SHA-256), stamped
     * onto the analysis record so the feedback loop can attribute a score shift to a specific prompt
     * revision. Stable across runs; changes whenever the prompt's text (the rules) does.
     */
    public static String version(String prompt)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(prompt.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(VERSION_HEX_LEN);
            for (int i = 0; i < VERSION_HEX_LEN / 2; i++)
            {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException unavailable)
        {
            // SHA-256 is mandated on every conformant JVM; this branch is unreachable.
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    /** Fill the screener template's {@code {covered}} (dedup reference) + {@code {articles}} placeholders. */
    public static String fillScreener(String template, String coveredBlock, String articlesBlock)
    {
        return template.replace("{covered}", coveredBlock).replace("{articles}", articlesBlock);
    }

    /**
     * Fill the deep-analysis <b>dynamic</b> block's {@code {covered}}, {@code {article_count}},
     * {@code {market_signals}}, {@code {articles}} placeholders ({@code deep_analysis_dynamic.txt}) -- the
     * volatile per-window data that goes in the user message. {@code coveredBlock} is the cross-window
     * "ALREADY COVERED" reference (recently-resonant stories) the deep pass weighs novelty against; it is
     * {@code ""} when nothing is recent. The static instructions/examples live in a separate cached system
     * block ({@code deep_analysis.txt}), loaded as-is.
     */
    public static String fillDeepDynamic(String template, int articleCount, String marketSignals,
                                         String articlesBlock, String coveredBlock)
    {
        return template.replace("{covered}", coveredBlock)
                .replace("{article_count}", Integer.toString(articleCount))
                .replace("{market_signals}", marketSignals)
                .replace("{articles}", articlesBlock);
    }

    /**
     * Fill an article-less non-news template (econ release / macro tape breach, #21): the {@code {catalyst}}
     * block (the mechanical surprise/breach + its prior) and the {@code {market_signals}} context block.
     */
    public static String fillContext(String template, String catalystBlock, String marketSignals)
    {
        return template.replace("{catalyst}", catalystBlock).replace("{market_signals}", marketSignals);
    }
}
