package com.beautica.moderation;

import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 415 add-on — {@code GET /api/v1/reports/mine}: every target the caller has reported, OPEN and RESOLVED,
 * newest first, with per-viewer caching headers. One report per (reporter, target) is permanent, so a resolved
 * report stays listed and the app keeps the report action hidden; another user's reports never appear.
 */
@Import(TestSecurityConfig.class)
// Same property as the other moderation ITs, so this class shares their Spring context.
@TestPropertySource(properties = "app.support.email=" + ContentReportIT.SUPPORT_EMAIL)
@DisplayName("GET /reports/mine — all own reported targets (phase 415 add-on)")
class MyReportsIT extends AbstractModerationIT {

    private static final String MINE_URL = REPORTS_URL + "/mine";

    @Test
    @DisplayName("lists only the caller's reports, newest first; a resolved report stays listed and still blocks a "
            + "repeat")
    void should_listOnlyOwnReports_and_keepResolved() throws Exception {
        SalonFixture salon = createSalon();
        UUID firstReview = insertReview(createUser(unique("a1"), "CLIENT", null), salon, "Перший");
        UUID sharedReview = insertReview(createUser(unique("a2"), "CLIENT", null), salon, "Другий");
        UUID otherReview = insertReview(createUser(unique("a3"), "CLIENT", null), salon, "Третій");
        String meEmail = unique("me");
        UUID me = createUser(meEmail, "CLIENT", null);
        String otherEmail = unique("other");
        createUser(otherEmail, "CLIENT", null);
        String myToken = tokenFor(meEmail);
        String otherToken = tokenFor(otherEmail);
        assertCreated(postReport(myToken, reportBody("REVIEW", firstReview, "SPAM", null)));
        assertCreated(postReport(myToken, reportBody("SALON_PROFILE", salon.salonId(), "SPAM", null)));
        assertCreated(postReport(otherToken, reportBody("REVIEW", otherReview, "SPAM", null)));
        // The other user's report on a target I have NOT reported must never leak into my list.
        assertCreated(postReport(otherToken, reportBody("REVIEW", sharedReview, "SPAM", null)));

        ResponseEntity<String> before = exchange(HttpMethod.GET, MINE_URL, myToken);

        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(before.getHeaders().getCacheControl()).contains("no-store");
        assertThat(before.getHeaders().getVary()).as("Vary names Authorization (header names are case-insensitive)")
                .anyMatch("Authorization"::equalsIgnoreCase);
        assertThat(targets(before)).containsExactly(
                "SALON_PROFILE:" + salon.salonId(), "REVIEW:" + firstReview);

        assertThat(resolve(reportIdOn(me, firstReview), "DISMISS").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(targets(exchange(HttpMethod.GET, MINE_URL, myToken)))
                .as("the resolved report stays listed, so the report action stays hidden")
                .containsExactly("SALON_PROFILE:" + salon.salonId(), "REVIEW:" + firstReview);
        ResponseEntity<String> repeat = postReport(myToken, reportBody("REVIEW", firstReview, "SPAM", null));
        assertThat(repeat.getStatusCode()).as("a repeat after resolution answers the existing report")
                .isEqualTo(HttpStatus.OK);
        assertThat(data(repeat).path("status").asText()).isEqualTo("RESOLVED");
        assertThat(targets(exchange(HttpMethod.GET, MINE_URL, otherToken)))
                .as("the other user sees only their own reports")
                .containsExactly("REVIEW:" + sharedReview, "REVIEW:" + otherReview);
    }

    @Test
    @DisplayName("a caller with no reports gets an empty list; anonymous gets 401")
    void should_returnEmpty_when_noReports_and_401_when_anonymous() throws Exception {
        String email = unique("empty");
        createUser(email, "CLIENT", null);

        ResponseEntity<String> mine = exchange(HttpMethod.GET, MINE_URL, tokenFor(email));
        ResponseEntity<String> anonymous = getAnonymous(MINE_URL);

        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(mine).isArray()).isTrue();
        assertThat(data(mine)).isEmpty();
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static void assertCreated(ResponseEntity<String> resp) {
        assertThat(resp.getStatusCode()).as("report: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    private List<String> targets(ResponseEntity<String> resp) {
        List<String> out = new ArrayList<>();
        for (JsonNode node : data(resp)) {
            out.add(node.path("targetType").asText() + ":" + node.path("targetId").asText());
        }
        return out;
    }
}
