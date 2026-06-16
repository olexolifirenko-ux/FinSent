package com.finsent.tools;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-time migration from the flat data layout to per-day folders.
 *
 * <p>Old layout: every file directly under the data dir, named {@code <prefix><YYYYMMDD><suffix>}
 * (e.g. {@code articles_20260611.jsonl}, {@code analysis_20260611.json}). New layout: the same files
 * grouped under a {@code <YYYYMMDD>/} folder (e.g. {@code 20260611/articles_20260611.jsonl}), so all of
 * a day's files sit together. This tool moves every dated file in the data dir into its day folder;
 * files without a {@code _YYYYMMDD.} day stamp (e.g. a legacy aggregate {@code outcomes.jsonl}) are left
 * in place. Run it once with the app stopped. Pure file I/O -- no framework bootstrap.
 *
 * <p>Usage: {@code java -cp <classpath> com.finsent.tools.MigrateDataLayout <dataDir>}
 */
public final class MigrateDataLayout
{
    private static final Pattern DAY_STAMP = Pattern.compile("_(\\d{8})\\.");

    private MigrateDataLayout()
    {
    }

    public static void main(String[] args) throws IOException
    {
        File dir = new File(args.length > 0 ? args[0] : "data");
        File[] files = dir.listFiles(File::isFile);
        if (files == null || files.length == 0)
        {
            System.out.println("No files directly under " + dir.getAbsolutePath() + " -- nothing to migrate.");
        }
        else
        {
            migrate(dir, files);
        }
    }

    private static void migrate(File dir, File[] files) throws IOException
    {
        int moved = 0;
        int left = 0;
        for (File file : files)
        {
            Matcher matcher = DAY_STAMP.matcher(file.getName());
            if (matcher.find())
            {
                File dayDir = new File(dir, matcher.group(1));
                Files.createDirectories(dayDir.toPath());
                Files.move(file.toPath(), new File(dayDir, file.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                moved++;
            }
            else
            {
                left++; // no _YYYYMMDD. stamp (e.g. a legacy aggregate outcomes file): leave it in place
            }
        }
        System.out.printf("Moved %d dated file(s) into <day>/ folders; left %d undated file(s) in %s.%n",
                moved, left, dir.getAbsolutePath());
    }
}
