package com.rronin.financialagent.schedulers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rronin.financialagent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;

@Component
public class ScheduledPromptStore {
    private final ObjectMapper mapper;
    private final Path file;
    private final Path legacyFile;

    public ScheduledPromptStore(ObjectMapper mapper, AgentProperties properties) {
        this.mapper = mapper;
        Path root = properties.scheduler().root();
        if (root == null) {
            root = Path.of(System.getProperty("user.home"), ".financial-analysis-agent", "schedulers");
        }
        this.file = root.resolve("schedulers.json");
        this.legacyFile = root.resolve("scheduled-prompts.json");
    }

    public Path file() { return file; }

    public synchronized List<ScheduledPrompt> load() {
        try {
            Path source=Files.exists(file)?file:legacyFile;
            if (!Files.exists(source)) return new ArrayList<>();
            JsonNode root = mapper.readTree(Files.readString(source));
            if(!root.isArray())throw new IllegalArgumentException("Schedulers file must contain an array");
            List<ScheduledPrompt> prompts=new ArrayList<>();boolean legacy=false;
            for(JsonNode node:root){
                if("PERSISTENT".equalsIgnoreCase(node.path("type").asText()))prompts.add(fromPersistent(node));
                else{legacy=true;discardLegacyApprovalPolicy((ObjectNode)node);prompts.add(mapper.treeToValue(node,ScheduledPrompt.class));}
            }
            validate(prompts);
            if (legacy || source.equals(legacyFile)) save(prompts);
            return new ArrayList<>(prompts);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to load scheduled prompts", error);
        }
    }

    public synchronized void save(List<ScheduledPrompt> prompts) {
        try {
            validate(prompts);
            Files.createDirectories(file.getParent());
            new com.rronin.financialagent.persistence.AtomicFileWriter().write(file,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(prompts.stream().map(this::toPersistent).toList()), false);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to save scheduled prompts", error);
        }
    }

    /** Legacy scheduler grants are discarded, never promoted to user or session grants. */
    private void discardLegacyApprovalPolicy(ObjectNode schedule) {
        schedule.putObject("approvalPolicy").putArray("rules");
    }

    private ObjectNode toPersistent(ScheduledPrompt item){
        ObjectNode root=mapper.createObjectNode();root.put("schemaVersion",2).put("id",item.id()).put("name",item.title())
                .put("type","PERSISTENT").put("prompt",item.prompt());
        ObjectNode schedule=root.putObject("schedule").put("recurrenceType","once".equals(item.frequency())?"ONCE":"RECURRING").put("timezone",item.zone());
        if("once".equals(item.frequency())){schedule.putNull("cron");put(schedule,"at",item.nextRunAt());}else{schedule.put("cron",ScheduleTiming.normalizeCron(item.cron()));schedule.putNull("at");}
        root.putObject("execution").put("sessionStrategy","NEW_SESSION").put("approvalMode","AUTO").put("model","default")
                .put("maxLoops",30).put("maxLooptimeMinutes",item.maxRuntimeMinutes()==null?30:item.maxRuntimeMinutes());
        root.putObject("runPolicy").put("overlapPolicy","SKIP").put("misfirePolicy","FIRE_ONCE")
                .put("retryPolicy","RETRY_TRANSIENT_FAILURES").put("maxRetries",item.maxRetries()==null?1:item.maxRetries());
        root.putObject("notification").put("onSuccess",true).put("onFailure",true).put("onApprovalRequired",true);
        root.put("status",item.enabled()?"ACTIVE":"PAUSED");
        ObjectNode lifecycle=root.putObject("lifecycle");put(lifecycle,"createdAt",item.createdAt());put(lifecycle,"updatedAt",item.updatedAt());
        put(lifecycle,"lastScheduledAt",item.lastRunAt());put(lifecycle,"lastFiredAt",item.lastRunAt());put(lifecycle,"nextFireAt",item.nextRunAt());
        lifecycle.putNull("createdBySessionId");lifecycle.putNull("expiresAt");
        if(item.pendingApproval()==null){lifecycle.putNull("lastRunId");lifecycle.putNull("lastSessionId");}
        else{lifecycle.put("lastRunId",item.pendingApproval().runId());lifecycle.put("lastSessionId",item.pendingApproval().scopeId());}
        lifecycle.put("lastRunStatus",item.lastRunStatus()==null?"NEVER_RUN":item.lastRunStatus());
        root.set("skills",mapper.valueToTree(item.skills()==null?List.of():item.skills()));
        var policy=com.rronin.financialagent.agent.OutputPolicy.normalize(item.outputPolicy());
        root.putObject("outputPolicy").put("type",policy.type()).put("completeWhen",policy.completeWhen()).put("pathPattern",policy.pathPattern());
        ObjectNode runtime=root.putObject("runtime").put("status",item.status()).put("lastResultPreview",safe(item.lastResultPreview()))
                .put("lastError",safe(item.lastError())).put("consecutiveFailures",item.consecutiveFailures()==null?0:item.consecutiveFailures())
                .put("retryDelaySeconds",item.retryDelaySeconds()==null?60:item.retryDelaySeconds()).put("pausedReason",safe(item.pausedReason()));
        if(item.pendingApproval()!=null)runtime.set("pendingApproval",mapper.valueToTree(item.pendingApproval()));
        root.putObject("ui").put("frequency",item.frequency()).put("dayOfWeek",item.dayOfWeek()).put("time",item.time());return root;
    }

    private ScheduledPrompt fromPersistent(JsonNode node)throws Exception{
        JsonNode schedule=node.path("schedule"),execution=node.path("execution"),lifecycle=node.path("lifecycle"),runtime=node.path("runtime"),ui=node.path("ui");
        boolean once="ONCE".equalsIgnoreCase(schedule.path("recurrenceType").asText());String cron=once?"":ScheduleTiming.normalizeCron(schedule.path("cron").asText()),zone=schedule.path("timezone").asText("Asia/Shanghai");
        String frequency=once?"once":ui.path("frequency").asText(deriveFrequency(cron)),day=ui.path("dayOfWeek").asText("SUN"),time=ui.path("time").asText(once?"00:00":deriveTime(cron));
        boolean enabled="ACTIVE".equalsIgnoreCase(node.path("status").asText());
        JsonNode outputNode=node.path("outputPolicy");var output=outputNode.isObject()?new com.rronin.financialagent.agent.OutputPolicy(
                outputNode.path("type").asText("none"),outputNode.path("completeWhen").asText("default_agent_done"),outputNode.path("pathPattern").asText(""))
                :com.rronin.financialagent.agent.OutputPolicy.none();
        var pending=runtime.has("pendingApproval")?mapper.treeToValue(runtime.path("pendingApproval"),SchedulerPendingApproval.class):null;
        return new ScheduledPrompt(node.path("id").asText(),node.path("name").asText(),node.path("prompt").asText(),frequency,day,time,cron,zone,enabled,
                runtime.path("status").asText(enabled?"IDLE":"PAUSED"),instant(lifecycle,"createdAt"),instant(lifecycle,"updatedAt"),once?instant(schedule,"at"):instant(lifecycle,"nextFireAt"),
                instant(lifecycle,"lastFiredAt"),lifecycle.path("lastRunStatus").asText("NEVER_RUN"),runtime.path("lastResultPreview").asText(""),runtime.path("lastError").asText(""),
                runtime.path("consecutiveFailures").asInt(0),node.path("runPolicy").path("maxRetries").asInt(1),runtime.path("retryDelaySeconds").asInt(60),runtime.path("pausedReason").asText(""),
                mapper.convertValue(node.path("skills"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}),SchedulerApprovalPolicy.readOnlyOnly(),output,
                execution.path("maxLooptimeMinutes").asInt(30),pending);
    }
    private static void put(ObjectNode node,String name,Instant value){if(value==null)node.putNull(name);else node.put(name,value.toString());}
    private static Instant instant(JsonNode node,String name){String value=node.path(name).asText("");return value.isBlank()?null:Instant.parse(value);}
    private static String deriveFrequency(String cron){return cron.endsWith("MON-FRI")?"weekdays":"daily";}
    private static String deriveTime(String cron){String[] p=cron.split(" ");return "%02d:%02d".formatted(Integer.parseInt(p[2]),Integer.parseInt(p[1]));}
    private static String safe(String value){return value==null?"":value;}
    private static void validate(List<ScheduledPrompt> prompts){java.util.Set<String> ids=new java.util.HashSet<>();for(var item:prompts){
        if(item.id()==null||!item.id().matches("[A-Za-z0-9_-]{1,100}")||!ids.add(item.id()))throw new IllegalArgumentException("Invalid or duplicate scheduler id");
        if(item.prompt()==null||item.prompt().isBlank()||item.prompt().length()>50_000)throw new IllegalArgumentException("Scheduler prompt is required and bounded");
    }}
}
