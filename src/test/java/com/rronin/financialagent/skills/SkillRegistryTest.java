package com.rronin.financialagent.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {
    @TempDir Path root;
    @Test void fileEditsAreVisibleWithoutApplicationRestart() throws Exception {
        var registry=new SkillRegistry(new DefaultResourceLoader(),root);
        registry.upsert("sample","---\nname: sample\ndescription: First\n---\n# Original");
        assertTrue(registry.instructionsFor("sample").contains("Original"));
        Files.writeString(root.resolve("sample/SKILL.md"),"---\nname: sample\ndescription: Changed\n---\n# Updated");
        assertTrue(registry.instructionsFor("sample").contains("Updated"));
        assertEquals("Changed",registry.list().getFirst().get("description"));
    }
    @Test void symbolicLinksCannotRedirectSkillWritesOrDeletion() throws Exception {
        var folder=Files.createDirectory(root.resolve("skills"));
        var outside=Files.createDirectory(root.resolve("outside"));
        var file=outside.resolve("SKILL.md");Files.writeString(file,"Keep me");
        Files.createSymbolicLink(folder.resolve("redirect"),outside);
        var registry=new SkillRegistry(new DefaultResourceLoader(),folder);
        assertThrows(IllegalArgumentException.class,()->registry.upsert("redirect","overwrite"));
        assertThrows(IllegalArgumentException.class,()->registry.delete("redirect"));
        assertThrows(IllegalArgumentException.class,()->registry.content("redirect"));
        assertEquals("Keep me",Files.readString(file));
    }
}
