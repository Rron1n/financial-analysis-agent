package com.rronin.financialagent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

/** The only boundary translating internal content blocks into OpenAI Responses wire objects. */
public final class ResponsesCodec {
    private final ObjectMapper mapper;
    public ResponsesCodec(ObjectMapper mapper) { this.mapper = mapper; }

    public ObjectNode encode(ModelGateway.Request request, String model, String reasoningEffort, int modelMaxOutput, boolean streaming) {
        MessageIntegrity.validate(request.messages());
        ObjectNode body = mapper.createObjectNode().put("model", model).put("instructions", request.instructions())
                .put("store", false).put("stream", streaming)
                .put("max_output_tokens", Math.min(request.maxOutputTokens(), modelMaxOutput));
        body.putObject("reasoning").put("effort", reasoningEffort);
        if (model.startsWith("qwen") && request.outputSchema() != null)
            body.put("instructions", request.instructions()+"\nReturn ONLY a JSON object matching this schema. No Markdown fences or commentary. Schema: "+request.outputSchema());
        var input = body.putArray("input");
        for (var message : request.messages()) {
            for (var block : message.content()) {
                switch (block.type()) {
                    case TEXT -> {
                        String value = block.text() == null ? "" : block.text();
                        if (message.meta()) value = "<system-reminder>\n" + value + "\n</system-reminder>";
                        input.addObject().put("role", message.role() == AgentMessage.Role.ASSISTANT ? "assistant" : "user").put("content", value);
                    }
                    case TOOL_USE -> input.addObject().put("type", "function_call").put("call_id", block.toolCallId())
                            .put("name", block.name()).put("arguments", block.input().toString());
                    case TOOL_RESULT -> input.addObject().put("type", "function_call_output").put("call_id", block.toolCallId())
                            .put("output", block.text() == null ? "" : block.text());
                    case PROGRESS, THINKING -> { /* Never fabricate provider reasoning items from UI summaries. */ }
                }
            }
        }
        if (!request.tools().isEmpty()) {
            var tools = body.putArray("tools");
            for (var tool : request.tools()) tools.addObject().put("type", "function").put("name", tool.name())
                    .put("description", tool.description()).put("strict", false).set("parameters", tool.inputSchema());
        }
        if (request.tools().isEmpty()) body.put("tool_choice", "none");
        if (request.outputSchema() != null) {
            body.putObject("text").putObject("format").put("type", "json_schema").put("name", "business_result")
                    .put("strict", true).set("schema", request.outputSchema());
        }
        return body;
    }

    public ModelGateway.Reply decode(String runId, JsonNode response) {
        String status = response.path("status").asText();
        if (!"completed".equals(status) && !"incomplete".equals(status)) throw new IllegalArgumentException("Model response did not finish");
        var blocks = new ArrayList<AgentMessage.Block>();
        for (var item : response.path("output")) {
            switch (item.path("type").asText()) {
                case "message" -> {
                    for (var part : item.path("content")) {
                        if ("output_text".equals(part.path("type").asText())) {
                            String text=part.path("text").asText();
                            var progress=java.util.regex.Pattern.compile("(?s)^\\s*<progress>(.*?)</progress>\\s*").matcher(text);
                            if(progress.find()){
                                blocks.add(new AgentMessage.Block(AgentMessage.BlockType.PROGRESS,progress.group(1),null,null,null,false));
                                text=text.substring(progress.end());
                            }
                            if(!text.isBlank())blocks.add(AgentMessage.Block.text(text));
                        }
                        if ("refusal".equals(part.path("type").asText())) blocks.add(AgentMessage.Block.text(part.path("refusal").asText()));
                    }
                }
                case "function_call" -> {
                    try {
                        JsonNode arguments=mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(item.path("arguments").asText());
                        if(arguments==null||!arguments.isObject())throw new IllegalArgumentException("Function arguments must be a JSON object");
                        blocks.add(AgentMessage.Block.toolUse(item.path("call_id").asText(),item.path("name").asText(),arguments));
                    } catch (Exception error) {
                        if (!"incomplete".equals(status)) blocks.add(AgentMessage.Block.toolUse(item.path("call_id").asText(),item.path("name").asText(),
                                mapper.createObjectNode().put("_invalid_function_arguments",true)));
                        // Invalid calls receive an error tool_result; their arguments are never executed or guessed.
                    }
                }
                case "reasoning" -> {
                    StringBuilder summary = new StringBuilder();
                    for (var part : item.path("summary")) summary.append(part.path("text").asText());
                    if (!summary.isEmpty()) blocks.add(new AgentMessage.Block(AgentMessage.BlockType.THINKING, summary.toString(), null, null, null, false));
                }
                default -> { }
            }
        }
        String stop = "incomplete".equals(status) ? response.path("incomplete_details").path("reason").asText("incomplete") : "completed";
        return new ModelGateway.Reply(new AgentMessage(UUID.randomUUID().toString(), runId, AgentMessage.Role.ASSISTANT,
                false, Instant.now(), blocks), response.path("usage").path("input_tokens").asLong(),
                response.path("usage").path("output_tokens").asLong(), stop, response.path("model").asText());
    }
}
