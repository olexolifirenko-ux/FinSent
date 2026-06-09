package com.finsent.util.xml;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import static org.junit.Assert.*;

public class XMLBeautify_test
{
    @Test
    public void testExpandFileTree() throws IOException
    {        
        Path tmpDir = Files.createTempDirectory(getClass().getSimpleName());
        Path xmlFile = tmpDir.resolve("test.xml");
        Path txtFile = tmpDir.resolve("test.txt");
        try
        {
            Files.createFile(xmlFile);
            Files.createFile(txtFile);
            List<File> actual = XMLBeautify.expandFileTree(tmpDir.toFile());
            assertEquals(new TreeSet<>(Arrays.asList(xmlFile.toFile())), new TreeSet<>(actual));
            
            actual = XMLBeautify.expandFileTree(xmlFile.toFile());
            assertEquals(new TreeSet<>(Arrays.asList(xmlFile.toFile())), new TreeSet<>(actual));
            
            // files explicitly specified as XMLBeautify args should be processes regardless of their extension
            actual = XMLBeautify.expandFileTree(txtFile.toFile());
            assertEquals(new TreeSet<>(Arrays.asList(txtFile.toFile())), new TreeSet<>(actual));
        }
        finally
        {
            Files.deleteIfExists(txtFile);
            Files.deleteIfExists(xmlFile);
            Files.deleteIfExists(tmpDir);
        }
    }

}
