package com.rronin.financialagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.model.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MemoryLanguageServiceTest {
    final ObjectMapper mapper=new ObjectMapper();
    @Test void semanticBoundariesPreserveAllOriginalText() throws Exception {
        String source="# Heading\n\n"+"First paragraph. ".repeat(350)+"\n\n"+"中文证据 😀 ".repeat(700);
        var units=MemoryLanguageService.units(source);
        var ends=mapper.createArrayNode().add(1).add(units.size()-1);
        var chunks=MemoryLanguageService.partition(units,ends);
        assertEquals(source,String.join("",chunks));
        assertTrue(chunks.stream().allMatch(chunk->chunk.length()<=6000));
    }
    @Test void rejectsDroppedAndOverlappingSourceUnits() throws Exception {
        var units=List.of("one","two","three");
        assertThrows(IllegalArgumentException.class,()->MemoryLanguageService.partition(units,mapper.readTree("[0,1]")));
        assertThrows(IllegalArgumentException.class,()->MemoryLanguageService.partition(units,mapper.readTree("[1,1,2]")));
    }
    @Test void modelFailureFallsBackWithoutLosingSourceAndExplicitQuerySkipsModel() {
        AtomicInteger calls=new AtomicInteger();
        ModelGateway gateway=(request,events)->{calls.incrementAndGet();return Mono.error(new IllegalStateException("offline"));};
        var service=new MemoryLanguageService(gateway,mapper);
        assertEquals("NBIS valuation",service.query("NBIS valuation",List.of()).block());assertEquals(0,calls.get());
        String source="# Header\n"+"original evidence ".repeat(900);
        assertEquals(source,String.join("",service.chunks(source).block()));assertEquals(1,calls.get());
    }
}
