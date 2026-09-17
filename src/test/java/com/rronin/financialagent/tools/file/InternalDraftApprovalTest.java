package com.rronin.financialagent.tools.file;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.AgentProperties;
import com.rronin.financialagent.agent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class InternalDraftApprovalTest {
 @TempDir Path root;
 @Test void onlyDedicatedScratchDirectoryAllowsEditingWithoutApproval() {
  var properties=mock(AgentProperties.class);
  when(properties.filesystem()).thenReturn(new AgentProperties.Filesystem(root,50000));
  var mapper=new ObjectMapper();var tool=new EditFileTool(new FileGuard(properties),mapper);
  var policy=new ApprovalPolicyEngine();
  assertEquals(ApprovalBehaviour.ALLOW,policy.evaluate(tool,mapper.createObjectNode().put("path",".financial-agent/scratch/draft.md"),null,List.of()).behaviour());
  assertEquals(ApprovalBehaviour.ASK,policy.evaluate(tool,mapper.createObjectNode().put("path","reports/report.md"),null,List.of()).behaviour());
  assertEquals(ApprovalBehaviour.ASK,policy.evaluate(tool,mapper.createObjectNode().put("path",".financial-agent/scratch/../../report.md"),null,List.of()).behaviour());
 }
}
