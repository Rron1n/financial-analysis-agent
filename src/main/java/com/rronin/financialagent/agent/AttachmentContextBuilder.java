package com.rronin.financialagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.AgentProperties;
import com.rronin.financialagent.model.AgentMessage;
import com.rronin.financialagent.model.AgentModels.Attachment;
import com.rronin.financialagent.tools.AgentTool;
import com.rronin.financialagent.tools.file.FileGuard;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** User-supplied files are data. Automatic reads are bounded and retain exact file references. */
public final class AttachmentContextBuilder {
    private final FileGuard guard;
    private final ObjectMapper mapper;
    public AttachmentContextBuilder(AgentProperties properties, ObjectMapper mapper) {
        this.guard=new FileGuard(properties);this.mapper=mapper;
    }
    public List<AgentMessage> build(List<Attachment> attachments, AgentTool.ApprovalContext context) throws Exception {
        if(attachments==null||attachments.isEmpty())return List.of();
        List<AgentMessage> messages=new ArrayList<>();int remaining=12_000;
        for(var attachment:attachments) {
            var path=guard.resolveRead(attachment.path(),context.allowedRoots());
            if(!Files.isRegularFile(path))throw new IllegalArgumentException("Attachment file not found");
            Map<String,Object> data=new LinkedHashMap<>();data.put("path",attachment.path());data.put("untrustedData",true);
            int limit=Math.min(4000,remaining);
            if(path.toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                try(var pdf=PDDocument.load(path.toFile())) {
                    data.put("totalPages",pdf.getNumberOfPages());
                    if(pdf.getNumberOfPages()<=3 && limit>0) {
                        String content=new PDFTextStripper().getText(pdf);
                        if(content.length()<=limit){data.put("content",content);remaining-=content.length();}
                        else data.put("readRequired",true);
                    } else data.put("readRequired",true);
                }
                data.put("readingInstructions","Use read_file with start_page and max_pages (maximum 60); prefer 2-3 pages per request. Image-only PDFs require OCR outside this reader.");
            } else if(path.toString().toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|webp)$")) {
                data.put("readRequired",true);
                data.put("readingInstructions","Image attachment retained. This text reader does not decode image pixels; do not infer its visual contents.");
            } else if(limit>0) {
                StringBuilder text=new StringBuilder();boolean truncated=false;
                try(var reader=Files.newBufferedReader(path)) {
                    int ch,lines=0;
                    while((ch=reader.read())!=-1){if(text.length()>=limit||lines>=100){truncated=true;break;}text.append((char)ch);if(ch=='\n')lines++;}
                }
                data.put("content",text.toString());data.put("truncated",truncated);data.put("nextOffset",text.length());remaining-=text.length();
            } else data.put("readRequired",true);
            String callId="attachment-"+UUID.randomUUID();
            messages.add(new AgentMessage(UUID.randomUUID().toString(),context.runId(),AgentMessage.Role.ASSISTANT,true,Instant.now(),
                    List.of(AgentMessage.Block.toolUse(callId,"read_file",mapper.createObjectNode().put("path",attachment.path())))));
            messages.add(new AgentMessage(UUID.randomUUID().toString(),context.runId(),AgentMessage.Role.TOOL,true,Instant.now(),
                    List.of(AgentMessage.Block.toolResult(callId,mapper.writeValueAsString(data),false))));
        }
        return List.copyOf(messages);
    }
}
