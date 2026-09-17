package com.rronin.financialagent.memory;

import com.rronin.financialagent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TopicFileStoreTest {
    @TempDir Path root;
    private TopicFileStore store() {
        var properties = mock(AgentProperties.class);
        when(properties.memory()).thenReturn(new AgentProperties.Memory(root, true, false, 50_000, 50_000, 30, .72));
        return new TopicFileStore(properties);
    }
    @Test void indexIsDerivedAndChangedFileRejectsStaleEdit() throws Exception {
        var store = store();
        String path = "topics/research/nebius.md";
        String text = "---\ntitle: Nebius\ntype: research\ndescription: Dated AI infrastructure research\n---\n# Thesis\nUnconfirmed; verify sources.\n";
        store.commit(Map.of(path, ""), Map.of(path, text));
        assertThat(store.index()).contains("Nebius", path);
        assertThat(store.list()).hasSize(1);
        String changed = text + "External edit\n";
        Files.writeString(store.resolve(path), changed);
        assertThatThrownBy(() -> store.commit(Map.of(path, text), Map.of(path, text + "Stale edit"))).isInstanceOf(IllegalStateException.class);
        assertThat(store.read(path)).isEqualTo(changed);
    }
    @Test void permissionsAndFrontMatterAreValidated() {
        var store = store();
        assertThatThrownBy(() -> store.resolve("../secrets.yml")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.parse("topics/user/profile.md", "No front matter")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.parse("topics/user/profile.md", "---\ntitle: x\ntype: research\ndescription: x\n---\nBody")).isInstanceOf(IllegalArgumentException.class);
    }
}
