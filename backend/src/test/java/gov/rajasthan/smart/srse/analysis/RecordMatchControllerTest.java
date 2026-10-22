package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.config.ApiExceptionHandler;
import gov.rajasthan.smart.srse.monitoring.ActiveQueryKind;
import gov.rajasthan.smart.srse.monitoring.QueryExecutionMonitor;
import gov.rajasthan.smart.srse.monitoring.QueryExecutionTicket;
import gov.rajasthan.smart.srse.security.MockJwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentCaptor.forClass;
import org.mockito.ArgumentCaptor;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RecordMatchController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class RecordMatchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RecordMatchService matchService;

    @MockBean
    private MultiTargetRecordMatchService multiMatchService;

    @MockBean
    private JoinKeySuggestService joinKeySuggestService;

    @MockBean
    private AnalysisAuditService analysisAuditService;

    @MockBean
    private MockJwtService mockJwtService;

    @MockBean
    private QueryExecutionMonitor queryMonitor;

    @BeforeEach
    void monitorPassThrough() throws Exception {
        org.mockito.Mockito.lenient().when(queryMonitor.trackStream(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        org.mockito.Mockito.lenient().when(queryMonitor.track(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> ((java.util.concurrent.Callable<?>) invocation.getArgument(1)).call());
    }

    private static AuditedMatchQuery audited(RecordMatchService.MatchQuery query) {
        return new AuditedMatchQuery(query, new QueryExecutionTicket(
                "test-query-id", ActiveQueryKind.MATCH, "c.s.t", 1L, "tester", "BYPASS", true));
    }

    private static RecordMatchService.MatchQuery stubQuery() {
        return new RecordMatchService.MatchQuery("SELECT ?", java.util.List.of(), java.util.List.of("c"));
    }

    private static final String REQUEST_BODY = """
            {"sourceCriteria":[{"table":"beneficiary","column":"district","fuzzyThresholdPercent":null}],
             "targetCriteria":[{"table":"beneficiary","column":"district","fuzzyThresholdPercent":null}],
             "highlightDuplicates":false,
             "dedup":null}
            """;

    @Test
    void matchStreamsNdjsonBody() throws Exception {
        StreamingResponseBody body = out -> {
            out.write(("{\"type\":\"meta\",\"columns\":[\"source_district\",\"target_district\"],"
                    + "\"sql\":\"SELECT ...\"}\n").getBytes(StandardCharsets.UTF_8));
            out.write(("{\"type\":\"row\",\"data\":{\"source_district\":\"Jaipur\","
                    + "\"target_district\":\"Jaipur\"}}\n").getBytes(StandardCharsets.UTF_8));
            out.write("{\"type\":\"done\",\"totalRows\":1}\n".getBytes(StandardCharsets.UTF_8));
        };
        RecordMatchService.MatchQuery query = stubQuery();
        when(analysisAuditService.planMatchAudited(any())).thenReturn(audited(query));
        when(matchService.match(eq(query), any())).thenReturn(body);

        MvcResult mvcResult = mockMvc.perform(post("/api/analysis/match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"type\":\"meta\"")))
                .andExpect(content().string(containsString("\"source_district\":\"Jaipur\"")))
                .andExpect(content().string(containsString("\"type\":\"done\"")));
    }

    @Test
    void invalidRequestReturns400() throws Exception {
        // Validation throws synchronously, before any StreamingResponseBody is
        // even returned — so this stays a plain, non-async 400, unchanged from
        // the pre-streaming controller contract.
        when(analysisAuditService.planMatchAudited(any()))
                .thenThrow(new IllegalArgumentException("bad request"));

        String body = """
                {"sourceCriteria":[{"table":"beneficiary","column":"district","fuzzyThresholdPercent":null}],
                 "targetCriteria":[],
                 "highlightDuplicates":false,
                 "dedup":null}
                """;

        mockMvc.perform(post("/api/analysis/match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void matchJsonExportAuditsBeforeStreaming() throws Exception {
        StreamingResponseBody body = out -> out.write("[{\"source_district\":\"Jaipur\"}]".getBytes(StandardCharsets.UTF_8));
        RecordMatchService.MatchQuery query = stubQuery();
        when(analysisAuditService.planMatchAudited(any())).thenReturn(audited(query));
        when(matchService.matchJson(query)).thenReturn(body);

        MvcResult mvcResult = mockMvc.perform(post("/api/analysis/match.json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
        verify(analysisAuditService).recordExport(any(), eq(query), eq("JSON"));
    }

    @Test
    void matchCsvStreamsAnAttachment() throws Exception {
        StreamingResponseBody body = out ->
                out.write("source_district,target_district\r\nJaipur,Jaipur\r\n".getBytes(StandardCharsets.UTF_8));
        RecordMatchService.MatchQuery query = stubQuery();
        when(analysisAuditService.planMatchAudited(any())).thenReturn(audited(query));
        when(matchService.matchCsv(query)).thenReturn(body);

        MvcResult mvcResult = mockMvc.perform(post("/api/analysis/match.csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(containsString("Jaipur,Jaipur")));
    }

    /**
     * Validation runs before the download starts, so a bad request is a plain
     * 400 rather than a file that turns out to be an error message.
     */
    @Test
    void invalidCsvRequestReturns400() throws Exception {
        when(analysisAuditService.planMatchAudited(any()))
                .thenThrow(new IllegalArgumentException("bad request"));

        mockMvc.perform(post("/api/analysis/match.csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void matchAcceptsDisplayColumnsAndStreamsTheirMetaColumns() throws Exception {
        StreamingResponseBody body = out -> {
            out.write(("{\"type\":\"meta\",\"columns\":[\"source_district\",\"target_district\","
                    + "\"source_ifsc\",\"target_branch\"],\"sql\":\"SELECT ...\"}\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write("{\"type\":\"done\",\"totalRows\":0}\n".getBytes(StandardCharsets.UTF_8));
        };
        RecordMatchService.MatchQuery query = stubQuery();
        when(analysisAuditService.planMatchAudited(any())).thenReturn(audited(query));
        when(matchService.match(eq(query), any())).thenReturn(body);

        String bodyJson = """
                {"sourceCriteria":[{"catalog":"c","schema":"s","table":"beneficiary","column":"district","fuzzyThresholdPercent":null}],
                 "targetCriteria":[{"catalog":"c","schema":"s","table":"beneficiary","column":"district","fuzzyThresholdPercent":null}],
                 "sourceDisplayColumns":[{"catalog":"c","schema":"s","table":"beneficiary","column":"ifsc"}],
                 "targetDisplayColumns":[{"catalog":"c","schema":"s","table":"beneficiary","column":"branch"}],
                 "highlightDuplicates":false,
                 "dedup":null}
                """;

        MvcResult mvcResult = mockMvc.perform(post("/api/analysis/match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"source_ifsc\"")))
                .andExpect(content().string(containsString("\"target_branch\"")));

        verify(analysisAuditService).planMatchAudited(any());
    }

    @Test
    void matchMultiStreamsProgressAndDone() throws Exception {
        StreamingResponseBody body = out -> {
            out.write(("{\"type\":\"meta\",\"columns\":[\"match_set_label\"],"
                    + "\"targetCount\":1}\n").getBytes(StandardCharsets.UTF_8));
            out.write(("{\"type\":\"progress\",\"targetIndex\":0,\"label\":\"Bank\","
                    + "\"phase\":\"started\",\"sql\":\"SELECT ...\"}\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write("{\"type\":\"done\",\"totalRows\":0,\"perTarget\":[]}\n".getBytes(StandardCharsets.UTF_8));
        };
        when(analysisAuditService.planMultiMatchAudited(any())).thenReturn(new AuditedMultiMatch(
                new QueryExecutionTicket("multi-id", ActiveQueryKind.MULTI_MATCH, "c.s.t", 1L, "tester", "BYPASS", true)));
        when(multiMatchService.matchMulti(any(), any(), any())).thenReturn(body);

        String bodyJson = """
                {"hubCriteria":[{"catalog":"c","schema":"s","table":"golden","column":"id","fuzzyThresholdPercent":null}],
                 "hubDisplayColumns":[],
                 "hubSide":"SOURCE",
                 "targets":[{"label":"Bank","catalog":"c","schema":"s","table":"bank","joinCriteria":[{"catalog":"c","schema":"s","table":"bank","column":"id","fuzzyThresholdPercent":null}],"displayColumns":[]}],
                 "highlightDuplicates":false,"dedup":null}
                """;

        MvcResult mvcResult = mockMvc.perform(post("/api/analysis/match-multi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"phase\":\"started\"")))
                .andExpect(content().string(containsString("\"type\":\"done\"")));
    }

    @Test
    void invalidMultiMatchReturns400BeforeStream() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("bad multi request"))
                .when(analysisAuditService).planMultiMatchAudited(any());

        mockMvc.perform(post("/api/analysis/match-multi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hubCriteria\":[],\"targets\":[]}"))
                .andExpect(status().isBadRequest());
    }

}
