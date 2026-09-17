package com.rronin.financialagent.schedulers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class SchedulerNotificationServiceTest {
 @TempDir Path root;
 @Test void deletionPersistsAndPreservesOtherNotifications(){
  var properties=mock(AgentProperties.class);
  when(properties.scheduler()).thenReturn(new AgentProperties.Scheduler(false,"","",root));
  var store=new SchedulerNotificationStore(new ObjectMapper().findAndRegisterModules(),properties);
  var service=new SchedulerNotificationService(store);
  var first=service.create("test","test","first","one");
  var second=service.create("test","test","second","two");
  assertThat(first.readAt()).isNull();
  service.markRead(java.util.List.of(first.id()));
  var reloaded=new SchedulerNotificationService(store).list();
  assertThat(reloaded.stream().filter(n->n.id().equals(first.id())).findFirst().orElseThrow().readAt()).isNotNull();
  assertThat(reloaded.stream().filter(n->n.id().equals(second.id())).findFirst().orElseThrow().readAt()).isNull();
  service.delete(first.id());service.delete(first.id());
  assertThat(new SchedulerNotificationService(store).list()).extracting(SchedulerNotification::id).containsExactly(second.id());
 }
}
