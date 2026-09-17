package com.rronin.financialagent.memory;

import com.rronin.financialagent.model.AgentMessage;
import java.util.*;

/** Memory is a semantic summary, not a duplicate archive of raw search responses. */
final class MemoryInputBudget {
    static List<AgentMessage> fit(List<AgentMessage> messages){
        int remaining=40_000;List<AgentMessage> result=new ArrayList<>();
        for(var message:messages){
            List<AgentMessage.Block> blocks=new ArrayList<>();
            for(var block:message.content()){
                if(block.type()==AgentMessage.BlockType.THINKING)continue;
                if(block.type()==AgentMessage.BlockType.TOOL_USE&&block.input()!=null&&block.input().isObject()){
                    var input=(com.fasterxml.jackson.databind.node.ObjectNode)block.input().deepCopy();
                    for(String field:List.of("content","old_text","new_text")){
                        if(input.path(field).isTextual()&&input.path(field).asText().length()>1800){
                            String value=input.path(field).asText();int end=1800;if(Character.isHighSurrogate(value.charAt(end-1)))end--;
                            input.put(field,value.substring(0,end)+"\n[Argument excerpt only; full call retained in transcript. Use its file path as the source locator.]");
                        }
                    }
                    blocks.add(AgentMessage.Block.toolUse(block.toolCallId(),block.name(),input));continue;
                }
                if(block.type()==AgentMessage.BlockType.TOOL_RESULT&&block.text()!=null){
                    String text=block.text();int limit=Math.min(1800,remaining),end=Math.min(limit,text.length());
                    if(end>0&&end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;
                    remaining-=end;
                    if(end<text.length())text=text.substring(0,end)+"\n[Raw result excerpt only; original retained in transcript. Do not infer omitted facts.]";
                    blocks.add(AgentMessage.Block.toolResult(block.toolCallId(),text,block.error()));
                }else blocks.add(block);
            }
            result.add(new AgentMessage(message.id(),message.runId(),message.role(),message.meta(),message.createdAt(),blocks));
        }
        return List.copyOf(result);
    }
}
