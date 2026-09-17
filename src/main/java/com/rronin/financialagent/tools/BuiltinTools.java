package com.rronin.financialagent.tools;

import com.rronin.financialagent.tools.file.*;
import com.rronin.financialagent.tools.search.*;
import com.rronin.financialagent.tools.finance.*;
import com.rronin.financialagent.tools.skill.*;
import com.rronin.financialagent.tools.scheduler.*;
import com.rronin.financialagent.tools.events.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Configuration("builtinToolConfiguration")
public class BuiltinTools {
    @Bean("builtinTools")
    public List<AgentTool> builtinTools(ReadFileTool read, WriteFileTool write, EditFileTool edit, DownloadFileTool download,
                                      WebSearchTool webSearch, WebFetchTool webFetch, XSearchTool xSearch,
                                      GetFinancialsTool financials, GetMacroDataTool macro, GetMarketDataTool market,
                                      StockScannerTool scanner, SkillTool skill, ManageSkillTool manageSkill,
                                      CreateSchedulerTool createScheduler, UpdateSchedulerTool updateScheduler,
                                      ListSchedulersTool listSchedulers, GetUpcomingEventsTool events,
                                      ManageUpcomingEventTool manageEvent, RefreshUpcomingEventsTool refreshEvents,
                                      com.rronin.financialagent.tools.memory.SearchSessionsTool searchSessions) {
        // Explicit capability list. Legacy memory tools and IBKR-specific tool wrappers are intentionally excluded.
        return List.of(read, write, edit, download, webSearch, webFetch, xSearch, financials, macro, market,
                scanner, skill, manageSkill, createScheduler, updateScheduler, listSchedulers, events, manageEvent, refreshEvents, searchSessions);
    }
}
