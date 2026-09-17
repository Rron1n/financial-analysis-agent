package com.rronin.financialagent.model;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
class AttachmentMessageTest {
 @Test void attachmentMetadataSurvivesStorageAndLegacyMessagesRemainReadable() throws Exception {
  var mapper=new ObjectMapper().findAndRegisterModules();
  var message=new AgentMessage("id","run",AgentMessage.Role.USER,false,Instant.now(),List.of(AgentMessage.Block.text("Analyze")),List.of(new AgentModels.Attachment("report.pdf","uploads/report.pdf",123)));
  assertThat(mapper.readValue(mapper.writeValueAsString(message),AgentMessage.class).attachments()).isEqualTo(message.attachments());
  var legacy=mapper.valueToTree(message);((com.fasterxml.jackson.databind.node.ObjectNode)legacy).remove("attachments");
  assertThat(mapper.treeToValue(legacy,AgentMessage.class).attachments()).isEmpty();
 }
}
