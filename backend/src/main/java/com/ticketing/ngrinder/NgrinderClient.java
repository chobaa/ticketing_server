package com.ticketing.ngrinder;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

@Component
@RequiredArgsConstructor
public class NgrinderClient {
    private final NgrinderProperties props;

    private RestClient client() {
        String basic =
                Base64.getEncoder()
                        .encodeToString(
                                (props.username() + ":" + props.password())
                                        .getBytes(StandardCharsets.UTF_8));
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** 0-based page (Spring Data / nGrinder). Prefers {@code /list}; falls back to root listing. */
    public JsonNode listTests(int page, int size) {
        try {
            return client()
                    .get()
                    .uri("/perftest/api/list?page={page}&size={size}", page, size)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() != 404) {
                throw ex;
            }
            return client()
                    .get()
                    .uri("/perftest/api?page={page}&size={size}", page, size)
                    .retrieve()
                    .body(JsonNode.class);
        }
    }

    public JsonNode getStatus(long id) {
        return client()
                .get()
                .uri("/perftest/api/{id}/status", id)
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode getPerf(long id, String dataType, int imgWidth) {
        return client()
                .get()
                .uri("/perftest/api/{id}/perf?dataType={dataType}&imgWidth={imgWidth}", id, dataType, imgWidth)
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode createTest(JsonNode body) {
        return client()
                .post()
                .uri("/perftest/api")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode listScripts() {
        return client()
                .get()
                .uri("/perftest/api/script")
                .retrieve()
                .body(JsonNode.class);
    }

    public void deleteTests(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        String joined = StringUtils.collectionToCommaDelimitedString(ids);
        client()
                .delete()
                .uri("/perftest/api?ids={ids}", joined)
                .retrieve()
                .toBodilessEntity();
    }

    /** Log file names for the perf test (nGrinder returns a JSON array). */
    public JsonNode getLogs(long id) {
        return client()
                .get()
                .uri("/perftest/api/{id}/logs", id)
                .retrieve()
                .body(JsonNode.class);
    }

    /** Move test to READY (start scheduling / run when agents pick up). */
    public JsonNode putStatusReady(long id) {
        return client()
                .put()
                .uri("/perftest/api/{id}?action=status", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body("\"READY\"")
                .retrieve()
                .body(JsonNode.class);
    }

    public void stop(long id) {
        client()
                .put()
                .uri("/perftest/api/{id}?action=stop", id)
                .retrieve()
                .toBodilessEntity();
    }

    /** Clone configuration into a new test in READY state (for re-running finished tests). */
    public JsonNode cloneAndStart(long id) {
        return client()
                .post()
                .uri("/perftest/api/{id}/clone_and_start", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .retrieve()
                .body(JsonNode.class);
    }
}

