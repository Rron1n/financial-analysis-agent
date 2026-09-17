package com.rronin.financialagent.agent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.AgentProperties;
import com.rronin.financialagent.model.AgentModels.Attachment;
import com.rronin.financialagent.model.MessageIntegrity;
import com.rronin.financialagent.tools.AgentTool;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class AttachmentContextBuilderTest {
 @TempDir Path root;
 @Test void boundedTextAndLargePdfRetainReferencesAndCompleteToolPairs() throws Exception {
  var properties=mock(AgentProperties.class);when(properties.filesystem()).thenReturn(new AgentProperties.Filesystem(root,50000));
  Files.writeString(root.resolve("large.txt"),"line\n".repeat(200));
  try(var pdf=new PDDocument()){for(int i=0;i<4;i++)pdf.addPage(new PDPage());pdf.save(root.resolve("large.pdf").toFile());}
  var result=new AttachmentContextBuilder(properties,new ObjectMapper()).build(List.of(new Attachment("text","large.txt",1000),new Attachment("pdf","large.pdf",1000)),new AgentTool.ApprovalContext("session","run",List.of(root),"Read attachments"));
  MessageIntegrity.validate(result);assertThat(result).hasSize(4);
  var mapper=new ObjectMapper();var text=mapper.readTree(result.get(1).content().getFirst().text());assertThat(text.path("truncated").asBoolean()).isTrue();assertThat(text.path("nextOffset").asInt()).isEqualTo(500);
  var pdf=mapper.readTree(result.get(3).content().getFirst().text());assertThat(pdf.path("totalPages").asInt()).isEqualTo(4);assertThat(pdf.path("readRequired").asBoolean()).isTrue();assertThat(pdf.has("content")).isFalse();
 }
}
