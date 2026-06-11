package com.finsent.analyse.claude;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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

    /** Read {@code <promptsDir>/<name>.txt} as UTF-8. */
    public static String load(File promptsDir, String name) throws IOException
    {
        return Files.readString(new File(promptsDir, name + EXTENSION).toPath(), StandardCharsets.UTF_8);
    }

    /** Fill the screener template's {@code {covered}} (dedup reference) + {@code {articles}} placeholders. */
    public static String fillScreener(String template, String coveredBlock, String articlesBlock)
    {
        return template.replace("{covered}", coveredBlock).replace("{articles}", articlesBlock);
    }

    /** Fill the deep-analysis template's {@code {article_count}}, {@code {market_signals}}, {@code {articles}}. */
    public static String fillDeep(String template, int articleCount, String marketSignals, String articlesBlock)
    {
        return template.replace("{article_count}", Integer.toString(articleCount))
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
