package com.rronin.financialagent.schedulers;

import com.rronin.financialagent.agent.OutputPolicy;
import com.rronin.financialagent.agent.AgentRunner;
import com.rronin.financialagent.agent.ApprovalService;
import com.rronin.financialagent.agent.ApprovalBehaviour;
import com.rronin.financialagent.agent.ApprovalRule;
import com.rronin.financialagent.agent.ApprovalRuleSource;
import com.rronin.financialagent.config.AgentProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ScheduledPromptService {
    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final int DEFAULT_RETRY_DELAY_SECONDS = 60;
    private static final int DEFAULT_MAX_RUNTIME_MINUTES = 8;
    private static final int AUTO_PAUSE_FAILURES = 3;

    private static final String STATUS_IDLE = "IDLE";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_RETRYING = "RETRYING";
    private static final String STATUS_PAUSED = "PAUSED";
    private static final String STATUS_NEEDS_APPROVAL = "NEEDS_APPROVAL";

    private static final String LAST_NEVER_RUN = "NEVER_RUN";
    private static final String LAST_RUNNING = "RUNNING";
    private static final String LAST_SUCCESS = "SUCCESS";
    private static final String LAST_FAILED = "FAILED";
    private static final String LAST_TIMED_OUT = "TIMED_OUT";
    private static final String LAST_AUTO_PAUSED = "AUTO_PAUSED";
    private static final String LAST_NEEDS_APPROVAL = "NEEDS_APPROVAL";

    private final ScheduledPromptStore store;
    private final TaskScheduler scheduler;
    private final ObjectProvider<AgentRunner> runner;
    private final ApprovalService approvals;
    private final SchedulerNotificationService notifications;
    private final AgentProperties properties;
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();
    private final Map<String,java.util.concurrent.atomic.AtomicInteger> approvalWaits=new ConcurrentHashMap<>();
    private volatile java.nio.file.WatchService watchService;
    private volatile boolean watching;

    public ScheduledPromptService(ScheduledPromptStore store, TaskScheduler scheduler, ObjectProvider<AgentRunner> runner, ApprovalService approvals, SchedulerNotificationService notifications, AgentProperties properties) {
        this.store = store;
        this.scheduler = scheduler;
        this.runner = runner;
        this.approvals = approvals;
        this.notifications = notifications;
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        ensureDefaultPortfolioBrief();
        reloadSchedules();
        startFileWatcher();
    }

    private void startFileWatcher() {
        try {
            java.nio.file.Path parent=store.file().toAbsolutePath().normalize().getParent();
            java.nio.file.Files.createDirectories(parent);
            watchService=parent.getFileSystem().newWatchService();
            parent.register(watchService,java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                    java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,java.nio.file.StandardWatchEventKinds.ENTRY_DELETE);
            watching=true;Thread.ofVirtual().name("scheduler-file-watcher").start(this::watchLoop);
        } catch (Exception error) { throw new IllegalStateException("Unable to watch scheduler file",error); }
    }

    private void watchLoop() {
        while(watching){
            try{
                java.nio.file.WatchKey key=watchService.take();boolean reload=false;
                for(var event:key.pollEvents())if(store.file().getFileName().equals(event.context()))reload=true;
                key.reset();if(reload)reloadSchedules();
            }catch(InterruptedException error){Thread.currentThread().interrupt();return;}
            catch(java.nio.file.ClosedWatchServiceException ignored){return;}
            catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Scheduler file reload failed: {}",error.getClass().getSimpleName());}
        }
    }

    @PreDestroy public void closeWatcher(){watching=false;try{if(watchService!=null)watchService.close();}catch(Exception ignored){}}

    private synchronized void ensureDefaultPortfolioBrief() {
        if (properties.scheduler() == null || !properties.scheduler().portfolioNewsEnabled()) return;
        if (normalizedStore().stream().anyMatch(item -> item.skills().contains("portfolio-news-radar"))) return;
        create(new UpsertRequest("Daily Portfolio Brief",
                "Use the portfolio-news-radar skill to generate today’s Daily Portfolio Brief in English. Read portfolio data and public sources only; do not execute trades. Write the completion notification in concise English.",
                "weekdays", null, "09:00", properties.scheduler().zone(), true, 1, 60,
                List.of("portfolio-news-radar"), SchedulerApprovalPolicy.readOnlyOnly(),
                OutputPolicy.fileOutput("reports/portfolio-news-radar/daily-portfolio-brief-*.md"), 30));
    }

    public List<ScheduledPrompt> list() {
        return normalizedStore().stream()
                .sorted(Comparator.comparing(ScheduledPrompt::createdAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public synchronized ScheduledPrompt create(UpsertRequest request) {
        String prompt = safe(request.prompt());
        if (prompt.isBlank()) throw new IllegalArgumentException("Prompt is required");
        Instant now = Instant.now();
        String title = titleFor(request.title(), prompt);
        List<String> skills = normalizeSkills(request.skills(), title, prompt);
        OutputPolicy outputPolicy = normalizeOutputPolicy(request.outputPolicy(), title, prompt);
        SchedulerApprovalPolicy approvalPolicy = normalizeApprovalPolicy(request.approvalPolicy(), outputPolicy);
        ScheduledPrompt item = build(
                UUID.randomUUID().toString(), title, prompt,
                normalizeFrequency(request.frequency()), normalizeDay(request.dayOfWeek()), normalizeTime(request.time()),
                safe(request.zone()).isBlank() ? properties.scheduler().zone() : request.zone(),
                request.enabled() == null || request.enabled(), STATUS_IDLE, now, now,
                null, LAST_NEVER_RUN, "", "", 0,
                positiveOrDefault(request.maxRetries(), DEFAULT_MAX_RETRIES),
                positiveOrDefault(request.retryDelaySeconds(), DEFAULT_RETRY_DELAY_SECONDS), "",
                skills, approvalPolicy, outputPolicy, positiveOrDefault(request.maxRuntimeMinutes(), DEFAULT_MAX_RUNTIME_MINUTES), null
        );
        List<ScheduledPrompt> items = new ArrayList<>(normalizedStore());
        items.add(item);
        store.save(items);
        reloadSchedules();
        return item;
    }

    public synchronized ScheduledPrompt createOneTime(String title,String prompt,Instant at,String zone,List<String> skills,OutputPolicy outputPolicy,Integer maxRuntimeMinutes){
        if(at==null||!at.isAfter(Instant.now()))throw new IllegalArgumentException("Persistent one-time scheduler requires a future at instant");
        String effectiveZone=safe(zone).isBlank()?properties.scheduler().zone():zone;ZoneId.of(effectiveZone);Instant now=Instant.now();
        ScheduledPrompt item=new ScheduledPrompt(UUID.randomUUID().toString(),titleFor(title,prompt),prompt,"once","SUN","00:00","",effectiveZone,true,
                STATUS_IDLE,now,now,at,null,LAST_NEVER_RUN,"","",0,1,60,"",normalizeSkills(skills,title,prompt),
                SchedulerApprovalPolicy.readOnlyOnly(),normalizeOutputPolicy(outputPolicy,title,prompt),positiveOrDefault(maxRuntimeMinutes,30),null);
        List<ScheduledPrompt> items=new ArrayList<>(normalizedStore());items.add(item);store.save(items);reloadSchedules();return item;
    }

    public synchronized ScheduledPrompt update(String id, UpsertRequest request) {
        List<ScheduledPrompt> items = new ArrayList<>(normalizedStore());
        for (int i = 0; i < items.size(); i++) {
            ScheduledPrompt old = items.get(i);
            if (!old.id().equals(id)) continue;
            String prompt = safe(request.prompt()).isBlank() ? old.prompt() : request.prompt();
            String title = safe(request.title()).isBlank() ? old.title() : titleFor(request.title(), prompt);
            String frequency = safe(request.frequency()).isBlank() ? old.frequency() : normalizeFrequency(request.frequency());
            String dayOfWeek = safe(request.dayOfWeek()).isBlank() ? old.dayOfWeek() : normalizeDay(request.dayOfWeek());
            String time = safe(request.time()).isBlank() ? old.time() : normalizeTime(request.time());
            String zone = safe(request.zone()).isBlank() ? old.zone() : request.zone();
            boolean enabled = request.enabled() == null ? old.enabled() : request.enabled();
            List<String> skills = request.skills() == null ? normalizeSkills(old.skills(), title, prompt) : normalizeSkills(request.skills(), title, prompt);
            OutputPolicy outputPolicy = request.outputPolicy() == null ? normalizeOutputPolicy(old.outputPolicy(), title, prompt) : normalizeOutputPolicy(request.outputPolicy(), title, prompt);
            SchedulerApprovalPolicy approvalPolicy = request.approvalPolicy() == null ? normalizeApprovalPolicy(old.approvalPolicy(), outputPolicy) : normalizeApprovalPolicy(request.approvalPolicy(), outputPolicy);
            ScheduledPrompt updated = build(
                    id,
                    title,
                    prompt, frequency, dayOfWeek, time, zone, enabled,
                    enabled ? STATUS_IDLE : STATUS_PAUSED,
                    old.createdAt(), Instant.now(), old.lastRunAt(), old.lastRunStatus(), old.lastResultPreview(), old.lastError(),
                    enabled ? 0 : old.consecutiveFailures(),
                    request.maxRetries() == null ? safeInt(old.maxRetries(), DEFAULT_MAX_RETRIES) : positiveOrDefault(request.maxRetries(), DEFAULT_MAX_RETRIES),
                    request.retryDelaySeconds() == null ? safeInt(old.retryDelaySeconds(), DEFAULT_RETRY_DELAY_SECONDS) : positiveOrDefault(request.retryDelaySeconds(), DEFAULT_RETRY_DELAY_SECONDS),
                    enabled ? "" : nonBlank(old.pausedReason(), "Disabled by user"),
                    skills, approvalPolicy, outputPolicy,
                    request.maxRuntimeMinutes() == null ? safeInt(old.maxRuntimeMinutes(), DEFAULT_MAX_RUNTIME_MINUTES) : positiveOrDefault(request.maxRuntimeMinutes(), DEFAULT_MAX_RUNTIME_MINUTES),
                    null
            );
            items.set(i, updated);
            store.save(items);
            reloadSchedules();
            return updated;
        }
        throw new IllegalArgumentException("Scheduled prompt not found");
    }

    public synchronized void delete(String id) {
        cancel(id);
        List<ScheduledPrompt> items = new ArrayList<>(normalizedStore());
        items.removeIf(item -> item.id().equals(id));
        store.save(items);
        reloadSchedules();
    }

    public void runNow(String id) {
        ScheduledPrompt item = find(id);
        run(item, 0);
    }

    private ScheduledPrompt find(String id) {
        return normalizedStore().stream().filter(x -> x.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Scheduled prompt not found"));
    }

    private synchronized void reloadSchedules() {
        futures.keySet().forEach(this::cancel);
        List<ScheduledPrompt> items = new ArrayList<>(normalizedStore());
        boolean changed = false;
        for (int i = 0; i < items.size(); i++) {
            ScheduledPrompt original=items.get(i);
            if("once".equals(original.frequency())&&original.enabled()&&original.nextRunAt()!=null
                    &&Duration.between(original.nextRunAt(),Instant.now()).compareTo(Duration.ofHours(24))>=0){
                ScheduledPrompt missed=new ScheduledPrompt(original.id(),original.title(),original.prompt(),original.frequency(),original.dayOfWeek(),original.time(),original.cron(),original.zone(),false,
                        "MISSED",original.createdAt(),Instant.now(),null,original.lastRunAt(),"MISSED",original.lastResultPreview(),"One-time schedule was missed by at least 24 hours",original.consecutiveFailures(),original.maxRetries(),original.retryDelaySeconds(),"",original.skills(),original.approvalPolicy(),original.outputPolicy(),original.maxRuntimeMinutes(),null);
                items.set(i,missed);changed=true;continue;
            }
            boolean catchUp=!"once".equals(original.frequency())&&original.enabled()&&original.nextRunAt()!=null&&!Instant.now().isBefore(original.nextRunAt());
            ScheduledPrompt item = withNextRun(original);
            if (!item.equals(items.get(i))) { items.set(i, item); changed = true; }
            if (!item.enabled()) continue;
            ScheduledFuture<?> future="once".equals(item.frequency())
                    ?scheduler.schedule(()->run(find(item.id()),0),item.nextRunAt())
                    :scheduler.schedule(() -> run(find(item.id()), 0), new CronTrigger(item.cron(), ZoneId.of(item.zone())));
            if (future != null) futures.put(item.id(), future);
            if(catchUp)scheduler.schedule(()->run(find(item.id()),0),Instant.now().plusMillis(100));
        }
        if (changed) store.save(items);
    }

    private List<ScheduledPrompt> normalizedStore() {
        List<ScheduledPrompt> loaded = store.load();
        List<ScheduledPrompt> out = new ArrayList<>();
        boolean changed = false;
        for (ScheduledPrompt item : loaded) {
            ScheduledPrompt n = normalizeExisting(item);
            out.add(n);
            if (!n.equals(item)) changed = true;
        }
        if (changed) store.save(out);
        return out;
    }

    private ScheduledPrompt normalizeExisting(ScheduledPrompt item) {
        if("once".equalsIgnoreCase(safe(item.frequency()))){ZoneId.of(item.zone());return item;}
        String frequency = normalizeFrequency(item.frequency());
        String day = normalizeDay(item.dayOfWeek());
        String time = normalizeTime(item.time());
        String zone = safe(item.zone()).isBlank() ? properties.scheduler().zone() : item.zone();
        String status = normalizeStatus(item.status());
        boolean staleRunning = STATUS_RUNNING.equalsIgnoreCase(status) && !isRunningInThisProcess(item.id());
        boolean timedOutRunning = STATUS_RUNNING.equalsIgnoreCase(status) && item.updatedAt() != null && item.updatedAt().isBefore(Instant.now().minus(Duration.ofMinutes(DEFAULT_MAX_RUNTIME_MINUTES + 7)));
        boolean staleRetrying = STATUS_RETRYING.equalsIgnoreCase(status) && !isRunningInThisProcess(item.id());
        if (staleRunning || timedOutRunning || staleRetrying) status = STATUS_IDLE;
        String lastRunStatus = safe(item.lastRunStatus()).isBlank() ? LAST_NEVER_RUN : item.lastRunStatus();
        if ((staleRunning || timedOutRunning) && LAST_RUNNING.equalsIgnoreCase(lastRunStatus)) lastRunStatus = LAST_TIMED_OUT;
        if (staleRetrying && STATUS_RETRYING.equalsIgnoreCase(lastRunStatus)) lastRunStatus = LAST_FAILED;
        List<String> skills = normalizeSkills(item.skills(), item.title(), item.prompt());
        OutputPolicy outputPolicy = normalizeOutputPolicy(item.outputPolicy(), item.title(), item.prompt());
        SchedulerApprovalPolicy approvalPolicy = normalizeApprovalPolicy(item.approvalPolicy(), outputPolicy);
        return build(
                item.id(), item.title(), item.prompt(), frequency, day, time, zone, item.enabled(), status,
                item.createdAt() == null ? Instant.now() : item.createdAt(),
                item.updatedAt() == null ? Instant.now() : item.updatedAt(),
                item.lastRunAt(), lastRunStatus,
                safe(item.lastResultPreview()), safe(item.lastError()), safeInt(item.consecutiveFailures(), 0),
                safeInt(item.maxRetries(), DEFAULT_MAX_RETRIES), safeInt(item.retryDelaySeconds(), DEFAULT_RETRY_DELAY_SECONDS), safe(item.pausedReason()),
                skills, approvalPolicy, outputPolicy, safeInt(item.maxRuntimeMinutes(), DEFAULT_MAX_RUNTIME_MINUTES), item.pendingApproval()
        );
    }

    private boolean isRunningInThisProcess(String id) {
        AtomicBoolean lock = running.get(id);
        return lock != null && lock.get();
    }

    private ScheduledPrompt build(String id, String title, String prompt, String frequency, String dayOfWeek, String time, String zone,
                                  boolean enabled, String status, Instant createdAt, Instant updatedAt, Instant lastRunAt,
                                  String lastRunStatus, String lastResultPreview, String lastError, int consecutiveFailures,
                                  int maxRetries, int retryDelaySeconds, String pausedReason,
                                  List<String> skills, SchedulerApprovalPolicy approvalPolicy, OutputPolicy outputPolicy, int maxRuntimeMinutes, SchedulerPendingApproval pendingApproval) {
        String cron = cronFor(frequency, dayOfWeek, time);
        ZoneId.of(zone);
        ScheduledPrompt item = new ScheduledPrompt(id, title, prompt, frequency, dayOfWeek, time, cron, zone, enabled,
                enabled ? status : STATUS_PAUSED, createdAt, updatedAt, null, lastRunAt, lastRunStatus, lastResultPreview, lastError,
                consecutiveFailures, maxRetries, retryDelaySeconds, enabled ? "" : pausedReason,
                normalizeSkills(skills, title, prompt), normalizeApprovalPolicy(approvalPolicy, outputPolicy), normalizeOutputPolicy(outputPolicy, title, prompt), maxRuntimeMinutes, pendingApproval);
        return withNextRun(item);
    }

    private ScheduledPrompt withNextRun(ScheduledPrompt item) {
        if("once".equals(item.frequency()))return item;
        Instant next = null;
        if (item.enabled()) {
            try {
                next = ScheduleTiming.next(item.cron(),item.zone(),Instant.now());
            } catch (Exception ignored) { }
        }
        return new ScheduledPrompt(item.id(), item.title(), item.prompt(), item.frequency(), item.dayOfWeek(), item.time(), item.cron(), item.zone(), item.enabled(), item.status(), item.createdAt(), item.updatedAt(), next, item.lastRunAt(), item.lastRunStatus(), item.lastResultPreview(), item.lastError(), item.consecutiveFailures(), item.maxRetries(), item.retryDelaySeconds(), item.pausedReason(), item.skills(), item.approvalPolicy(), item.outputPolicy(), item.maxRuntimeMinutes(), item.pendingApproval());
    }

    private void cancel(String id) {
        ScheduledFuture<?> future = futures.remove(id);
        if (future != null) future.cancel(false);
    }

    private void run(ScheduledPrompt scheduled, int attempt) {
        AtomicBoolean lock = running.computeIfAbsent(scheduled.id(), ignored -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) return;
        markRunning(scheduled.id());
        ScheduledPrompt current = find(scheduled.id());
        runner.getObject().runToCompletion(current.prompt() + "\nScheduler local date/time: " + java.time.ZonedDateTime.now(java.time.ZoneId.of(current.zone())) + ". Use this zone for today and report filenames; preserve market-source timestamps separately.", primarySkill(current), current.title(), backgroundPolicyFor(current))
                .timeout(ActiveRuntimeBudget.elapsed(Duration.ofMinutes(safeInt(current.maxRuntimeMinutes(), DEFAULT_MAX_RUNTIME_MINUTES)),
                        ()->approvalWaits.getOrDefault(current.id(),new java.util.concurrent.atomic.AtomicInteger()).get()>0))
                .doFinally(signal -> lock.set(false))
                .subscribe(
                        result -> handleResult(current.id(), result, attempt),
                        error -> handleFailure(current.id(), error, attempt)
                );
    }


    public synchronized ScheduledPrompt decideApproval(String id, String approvalId, ApprovalService.Decision decision) {
        ScheduledPrompt current = find(id);
        SchedulerPendingApproval pending = current.pendingApproval();
        if (pending == null || !pending.id().equals(approvalId))
            throw new IllegalArgumentException("Pending scheduler approval not found");
        if (isRunningInThisProcess(id)) {
            if (!approvals.decide(approvalId, decision))
                throw new IllegalStateException("Approval is no longer pending; refresh the original session");
            return find(id);
        }
        // A restarted process must resume the persisted tool call, never re-submit the prompt.
        var original = approvals.pendingForSession(pending.scopeId()).stream()
                .filter(value -> value.id().equals(approvalId) && value.runId().equals(pending.runId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Original approval unavailable; inspect the interrupted session"));
        AtomicBoolean lock = running.computeIfAbsent(id, ignored -> new AtomicBoolean());
        if (!lock.compareAndSet(false, true)) throw new IllegalStateException("Scheduler continuation already starting");
        runner.getObject().resumeToCompletion(original.sessionId(), backgroundPolicyFor(current), () -> {
                    if (!approvals.decide(approvalId, decision))
                        throw new IllegalStateException("Original approval was already resolved");
                })
                .doFinally(signal -> lock.set(false))
                .subscribe(result -> handleResult(id, result, 0), error -> handleFailure(id, error, Integer.MAX_VALUE));
        return find(id);
    }

    private synchronized void clearPendingAfterApproval(String id, String status, String lastRunStatus, String error) {
        replace(id, item -> new ScheduledPrompt(item.id(), item.title(), item.prompt(), item.frequency(), item.dayOfWeek(), item.time(), item.cron(), item.zone(), item.enabled(), status, item.createdAt(), Instant.now(), item.nextRunAt(), item.lastRunAt(), lastRunStatus, item.lastResultPreview(), error, item.consecutiveFailures(), item.maxRetries(), item.retryDelaySeconds(), item.pausedReason(), item.skills(), item.approvalPolicy(), item.outputPolicy(), item.maxRuntimeMinutes(), null));
    }

    private AgentRunner.BackgroundRunPolicy backgroundPolicyFor(ScheduledPrompt scheduled) {
        OutputPolicy outputPolicy = normalizeOutputPolicy(scheduled.outputPolicy(), scheduled.title(), scheduled.prompt());
        SchedulerApprovalPolicy approvalPolicy = normalizeApprovalPolicy(scheduled.approvalPolicy(), outputPolicy);
        return new AgentRunner.BackgroundRunPolicy(outputPolicy, request -> {
            return reactor.core.publisher.Mono.defer(()->{
                var waiting=approvalWaits.computeIfAbsent(scheduled.id(),key->new java.util.concurrent.atomic.AtomicInteger());
                waiting.incrementAndGet();
                try{markNeedsApproval(scheduled.id(), request);notifications.create(scheduled.id(),scheduled.title(),scheduled.title()+" needs approval","Waiting for user approval: "+request.rule().toolName()+". Open the original session to approve or deny.");}
                catch(RuntimeException error){waiting.decrementAndGet();return reactor.core.publisher.Mono.error(error);}
                return approvals.await(request.id()).doFinally(signal->waiting.decrementAndGet());
            });
        }, List.of(), ApprovalRuleSource.USER_SETTINGS, scheduled.id());
    }

    private String primarySkill(ScheduledPrompt scheduled) {
        List<String> skills = normalizeSkills(scheduled.skills(), scheduled.title(), scheduled.prompt());
        return skills.isEmpty() ? "default" : skills.get(0);
    }

    private void handleResult(String id, String result, int attempt) {
        String text = safe(result);
        if (text.startsWith("Stopped after reaching the configured agent iteration limit")) {
            handleFailure(id, new IllegalStateException(text), attempt);
            return;
        }
        ScheduledPrompt item = find(id);
        OutputPolicy outputPolicy = normalizeOutputPolicy(item.outputPolicy(), item.title(), item.prompt());
        if ("reminder".equalsIgnoreCase(safe(outputPolicy.type()))) {
            notifications.createReminder(item, result);
        }
        markSuccess(id, preview(result));
        if (!"reminder".equalsIgnoreCase(safe(outputPolicy.type())))
            notifications.create(id, item.title(), item.title() + " completed", preview(result));
    }

    private void handleFailure(String id, Throwable error, int attempt) {
        ScheduledPrompt item = find(id);
        notifications.create(id, item.title(), item.title() + " failed", preview(error.getMessage()));
        if(error instanceof AgentRunner.InterruptedRunException){
            markFailure(id,STATUS_IDLE,"INTERRUPTED",error.getMessage(),false);return;
        }
        String lastRunStatus = isTimeout(error) ? LAST_TIMED_OUT : LAST_FAILED;
        if (isApprovalDenied(error)) {
            markFailure(id, STATUS_IDLE, LAST_FAILED, preview(error.getMessage()), false);
            return;
        }
        // The agent retries transient model requests within the original run. Re-submitting this
        // prompt here would create a second run and could repeat already completed side effects.
        int failures = safeInt(item.consecutiveFailures(), 0) + 1;
        boolean autoPause = failures >= AUTO_PAUSE_FAILURES;
        markFailure(id, autoPause ? STATUS_PAUSED : STATUS_IDLE, autoPause ? LAST_AUTO_PAUSED : lastRunStatus, preview(error.getMessage()), autoPause);
    }

    private synchronized void markRunning(String id) {
        replace(id, item -> {
            Instant now = Instant.now();
            return new ScheduledPrompt(item.id(), item.title(), item.prompt(), item.frequency(), item.dayOfWeek(), item.time(), item.cron(), item.zone(), item.enabled(), STATUS_RUNNING, item.createdAt(), now, item.nextRunAt(), now, LAST_RUNNING, item.lastResultPreview(), item.lastError(), item.consecutiveFailures(), item.maxRetries(), item.retryDelaySeconds(), item.pausedReason(), item.skills(), item.approvalPolicy(), item.outputPolicy(), item.maxRuntimeMinutes(), null);
        });
    }

    private synchronized void markSuccess(String id, String preview) {
        replace(id, item -> withNextRun(new ScheduledPrompt(item.id(), item.title(), item.prompt(), item.frequency(), item.dayOfWeek(), item.time(), item.cron(), item.zone(),
                !"once".equals(item.frequency())&&item.enabled(),"once".equals(item.frequency())?"COMPLETED":STATUS_IDLE,item.createdAt(),Instant.now(),
                "once".equals(item.frequency())?null:item.nextRunAt(),Instant.now(),LAST_SUCCESS,preview,"",0,item.maxRetries(),item.retryDelaySeconds(),item.pausedReason(),item.skills(),item.approvalPolicy(),item.outputPolicy(),item.maxRuntimeMinutes(),null)));
    }

    private synchronized void markNeedsApproval(String id, ApprovalService.PendingApproval request) {
        SchedulerPendingApproval pending = new SchedulerPendingApproval(request.id(), request.runId(),
                request.targetSource(), request.scopeId(), request.rule(), request.toolMode(), request.arguments(),
                request.summary(), request.createdAt());
        replace(id, item -> withNextRun(new ScheduledPrompt(item.id(), item.title(), item.prompt(), item.frequency(), item.dayOfWeek(), item.time(), item.cron(), item.zone(), item.enabled(), STATUS_NEEDS_APPROVAL, item.createdAt(), Instant.now(), item.nextRunAt(), item.lastRunAt(), LAST_NEEDS_APPROVAL, item.lastResultPreview(), "Waiting for approval to use " + request.rule().toolName(), item.consecutiveFailures(), item.maxRetries(), item.retryDelaySeconds(), item.pausedReason(), item.skills(), item.approvalPolicy(), item.outputPolicy(), item.maxRuntimeMinutes(), pending)));
    }

    private synchronized void markFailure(String id, String status, String lastRunStatus, String error, boolean autoPause) {
        replace(id, item -> {
            int failures = safeInt(item.consecutiveFailures(), 0) + (STATUS_RETRYING.equals(status) ? 0 : 1);
            boolean enabled = autoPause ? false : item.enabled();
            return withNextRun(new ScheduledPrompt(item.id(), item.title(), item.prompt(), item.frequency(), item.dayOfWeek(), item.time(), item.cron(), item.zone(), enabled, status, item.createdAt(), Instant.now(), item.nextRunAt(), Instant.now(), lastRunStatus, item.lastResultPreview(), error, failures, item.maxRetries(), item.retryDelaySeconds(), autoPause ? "Paused after 3 consecutive failures" : item.pausedReason(), item.skills(), item.approvalPolicy(), item.outputPolicy(), item.maxRuntimeMinutes(), null));
        });
        if (autoPause) reloadSchedules();
    }

    private void replace(String id, java.util.function.Function<ScheduledPrompt, ScheduledPrompt> updater) {
        List<ScheduledPrompt> items = new ArrayList<>(normalizedStore());
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).id().equals(id)) continue;
            items.set(i, updater.apply(items.get(i)));
            break;
        }
        store.save(items);
    }

    private static SchedulerApprovalPolicy normalizeApprovalPolicy(SchedulerApprovalPolicy policy, OutputPolicy outputPolicy) {
        if (policy == null) return SchedulerApprovalPolicy.readOnlyOnly();
        return new SchedulerApprovalPolicy(policy.normalizedRules());
    }

    private static OutputPolicy normalizeOutputPolicy(OutputPolicy policy, String title, String prompt) {
        if (policy == null || safe(policy.type()).isBlank()) return OutputPolicy.none();
        String type = safe(policy.type()).trim();
        if ("direct_response".equalsIgnoreCase(type)) return OutputPolicy.directResponse();
        if ("reminder".equalsIgnoreCase(type)) return OutputPolicy.reminder();
        if ("file_output".equalsIgnoreCase(type)) {
            return OutputPolicy.fileOutput(policy.pathPattern());
        }
        return OutputPolicy.none();
    }

    private static List<String> normalizeSkills(List<String> skills, String title, String prompt) {
        if (skills != null && !skills.isEmpty()) {
            List<String> normalized = skills.stream()
                    .map(ScheduledPromptService::safe)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .toList();
            if (!normalized.isEmpty()) return normalized;
        }
        return List.of("default");
    }

    private static String normalizeStatus(String status) {
        String value = safe(status).toUpperCase(Locale.ROOT);
        return switch (value) {
            case "RUNNING" -> STATUS_RUNNING;
            case "RETRYING" -> STATUS_RETRYING;
            case "PAUSED" -> STATUS_PAUSED;
            case "NEEDS_APPROVAL" -> STATUS_NEEDS_APPROVAL;
            default -> STATUS_IDLE;
        };
    }

    private static boolean isApprovalDenied(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AgentRunner.SchedulerApprovalDeniedException) return true;
            current = current.getCause();
        }
        return false;
    }

    private static boolean isTimeout(Throwable error) {
        String text = (error.getMessage() == null ? "" : error.getMessage()).toLowerCase(Locale.ROOT);
        return text.contains("timeout") || text.contains("timed out") || error.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("timeout");
    }

    private static String cronFor(String frequency, String dayOfWeek, String time) {
        String[] parts = time.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        return switch (frequency) {
            case "daily" -> "0 %d %d * * *".formatted(minute, hour);
            case "weekdays" -> "0 %d %d * * %s".formatted(minute, hour, dayOfWeek==null || dayOfWeek.isBlank() || "SUN".equalsIgnoreCase(dayOfWeek) ? "MON-FRI" : normalizeDay(dayOfWeek));
            case "weekly" -> "0 %d %d * * %s".formatted(minute, hour, normalizeDay(dayOfWeek).toUpperCase(Locale.ROOT));
            default -> throw new IllegalArgumentException("Unsupported frequency: " + frequency);
        };
    }

    private static String normalizeFrequency(String value) {
        String frequency = safe(value).toLowerCase(Locale.ROOT);
        return switch (frequency) {
            case "", "daily" -> "daily";
            case "weekly" -> "weekly";
            case "weekdays", "weekdays_only" -> "weekdays";
            case "once" -> "once";
            default -> throw new IllegalArgumentException("Unsupported frequency: " + value);
        };
    }

    private static String normalizeDay(String value) {
        if(value!=null && value.contains(","))return java.util.Arrays.stream(value.split(",")).map(ScheduledPromptService::normalizeDay).distinct().collect(java.util.stream.Collectors.joining(","));
        String day = safe(value).toLowerCase(Locale.ROOT);
        return switch (day) {
            case "", "sun", "sunday" -> "SUN";
            case "mon", "monday" -> "MON";
            case "tue", "tues", "tuesday" -> "TUE";
            case "wed", "wednesday" -> "WED";
            case "thu", "thur", "thurs", "thursday" -> "THU";
            case "fri", "friday" -> "FRI";
            case "sat", "saturday" -> "SAT";
            default -> throw new IllegalArgumentException("Unsupported dayOfWeek: " + value);
        };
    }

    private static String normalizeTime(String value) {
        String time = safe(value).isBlank() ? "09:00" : value.trim();
        if (!time.matches("([01]?[0-9]|2[0-3]):[0-5][0-9]")) throw new IllegalArgumentException("Time must be HH:mm");
        String[] parts = time.split(":");
        return "%02d:%02d".formatted(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    private static String titleFor(String requested, String prompt) {
        String title = safe(requested);
        if (!title.isBlank()) return title.length() <= 80 ? title : title.substring(0, 77) + "...";
        String cleaned = prompt.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= 48 ? cleaned : cleaned.substring(0, 45) + "...";
    }

    private static String preview(String text) {
        String cleaned = safe(text).replaceAll("\\s+", " ").trim();
        return cleaned.length() <= 600 ? cleaned : cleaned.substring(0, 597) + "...";
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static String nonBlank(String value, String fallback) { return safe(value).isBlank() ? fallback : value; }
    private static int safeInt(Integer value, int fallback) { return value == null ? fallback : value; }
    private static int positiveOrDefault(Integer value, int fallback) { return value == null || value < 0 ? fallback : value; }

    public record UpsertRequest(
            String title,
            String prompt,
            String frequency,
            String dayOfWeek,
            String time,
            String zone,
            Boolean enabled,
            Integer maxRetries,
            Integer retryDelaySeconds,
            List<String> skills,
            SchedulerApprovalPolicy approvalPolicy,
            OutputPolicy outputPolicy,
            Integer maxRuntimeMinutes
    ) {}
}
