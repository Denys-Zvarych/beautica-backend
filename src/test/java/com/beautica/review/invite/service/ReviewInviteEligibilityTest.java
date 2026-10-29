package com.beautica.review.invite.service;

import com.beautica.booking.enums.BookingSource;
import com.beautica.common.security.PhoneHasher;
import com.beautica.config.BookingSmsProperties;
import com.beautica.config.PhoneHashPepperConfig;
import com.beautica.review.invite.config.ReviewInviteProperties;
import com.beautica.review.invite.entity.ReviewInviteSkipReason;
import com.beautica.review.invite.repository.SmsOptOutRepository;
import com.beautica.review.invite.service.ReviewInviteEligibility.Verdict;
import com.beautica.review.invite.support.ReviewInviteTestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewInviteEligibility — phase 400 D2, one gate at a time")
class ReviewInviteEligibilityTest {

    private static final String PEPPER = "unit-test-phone-hash-pepper-min-32-chars!";
    private static final String PHONE = "+380501234567";
    private static final String POLICY = ReviewInviteTestProperties.MIN_POLICY_VERSION;

    private final PhoneHasher phoneHasher = new PhoneHasher(new PhoneHashPepperConfig(PEPPER));

    @Mock
    private SmsOptOutRepository smsOptOutRepository;

    private BookingSmsProperties smsOn;

    @BeforeEach
    void setUp() {
        smsOn = new BookingSmsProperties();
        smsOn.getSms().setEnabled(true);
    }

    @Test
    @DisplayName("every gate passing → eligible, carrying the PhoneHasher key of the guest phone")
    void should_beEligible_when_everyGatePasses() {
        Verdict verdict = eligibility(ReviewInviteTestProperties.enabled(), smsOn).evaluate(linkVisit());

        assertThat(verdict.isEligible()).isTrue();
        assertThat(verdict.phone()).isEqualTo(phoneHasher.hash(PHONE));
    }

    @Test
    @DisplayName("invites flag off → INVITES_DISABLED, no phone hashed, no opt-out lookup")
    void should_skipInvitesDisabled_when_inviteFlagOff() {
        Verdict verdict = eligibility(ReviewInviteTestProperties.disabled(), smsOn).evaluate(linkVisit());

        assertSkipped(verdict, ReviewInviteSkipReason.INVITES_DISABLED);
        assertThat(verdict.phone()).isNull();
        verify(smsOptOutRepository, never()).existsByPhoneHash(any());
    }

    @Test
    @DisplayName("booking SMS flag off → SMS_DISABLED")
    void should_skipSmsDisabled_when_smsFlagOff() {
        Verdict verdict = eligibility(ReviewInviteTestProperties.enabled(), new BookingSmsProperties())
                .evaluate(linkVisit());

        assertSkipped(verdict, ReviewInviteSkipReason.SMS_DISABLED);
        assertThat(verdict.phone()).isNull();
    }

    @Test
    @DisplayName("guest PII anonymised → ANONYMISED")
    void should_skipAnonymised_when_guestPiiAnonymised() {
        VisitSnapshot visit = new VisitSnapshot(UUID.randomUUID(), BookingSource.LINK, false, null, true, POLICY, true);

        Verdict verdict = eligibility(ReviewInviteTestProperties.enabled(), smsOn).evaluate(visit);

        assertSkipped(verdict, ReviewInviteSkipReason.ANONYMISED);
    }

    @Test
    @DisplayName("no guest phone → NO_PHONE")
    void should_skipNoPhone_when_phoneMissing() {
        VisitSnapshot visit = new VisitSnapshot(UUID.randomUUID(), BookingSource.LINK, false, null, false, POLICY, true);

        Verdict verdict = eligibility(ReviewInviteTestProperties.enabled(), smsOn).evaluate(visit);

        assertSkipped(verdict, ReviewInviteSkipReason.NO_PHONE);
    }

    @Test
    @DisplayName("an unparseable stored phone → NO_PHONE (never a 400 from a background job)")
    void should_skipNoPhone_when_phoneUnparseable() {
        VisitSnapshot visit = new VisitSnapshot(UUID.randomUUID(), BookingSource.LINK, false, "not-a-phone", false,
                POLICY, true);

        Verdict verdict = eligibility(ReviewInviteTestProperties.enabled(), smsOn).evaluate(visit);

        assertSkipped(verdict, ReviewInviteSkipReason.NO_PHONE);
    }

    @Test
    @DisplayName("phone hash in sms_opt_outs → OPTED_OUT, looked up by the PhoneHasher key")
    void should_skipOptedOut_when_phoneOptedOut() {
        byte[] key = phoneHasher.hash(PHONE).hash();
        when(smsOptOutRepository.existsByPhoneHash(key)).thenReturn(true);

        Verdict verdict = eligibility(ReviewInviteTestProperties.enabled(), smsOn).evaluate(linkVisit());

        assertSkipped(verdict, ReviewInviteSkipReason.OPTED_OUT);
        assertThat(verdict.phone()).isEqualTo(phoneHasher.hash(PHONE));
    }

    @Test
    @DisplayName("accepted policy older than min-policy-version → POLICY_VERSION")
    void should_skipPolicyVersion_when_acceptedPolicyOlder() {
        VisitSnapshot visit = new VisitSnapshot(UUID.randomUUID(), BookingSource.LINK, false, PHONE, false,
                "2026-10-19", true);

        Verdict verdict = eligibility(ReviewInviteTestProperties.enabled(), smsOn).evaluate(visit);

        assertSkipped(verdict, ReviewInviteSkipReason.POLICY_VERSION);
    }

    @Test
    @DisplayName("no policy stamp (pre-381 visit) → POLICY_VERSION")
    void should_skipPolicyVersion_when_noPolicyAccepted() {
        VisitSnapshot visit = new VisitSnapshot(UUID.randomUUID(), BookingSource.LINK, false, PHONE, false, null, true);

        Verdict verdict = eligibility(ReviewInviteTestProperties.enabled(), smsOn).evaluate(visit);

        assertSkipped(verdict, ReviewInviteSkipReason.POLICY_VERSION);
    }

    @Test
    @DisplayName("a newer policy revision than the minimum passes")
    void should_beEligible_when_acceptedPolicyNewerRevision() {
        VisitSnapshot visit = new VisitSnapshot(UUID.randomUUID(), BookingSource.LINK, false, PHONE, false,
                "2026-10-20.1", true);

        Verdict verdict = eligibility(ReviewInviteTestProperties.enabled(), smsOn).evaluate(visit);

        assertThat(verdict.isEligible()).isTrue();
    }

    @Test
    @DisplayName("every COMPLETED booking already reviewed → NOTHING_TO_REVIEW")
    void should_skipNothingToReview_when_allCompletedReviewed() {
        VisitSnapshot visit = new VisitSnapshot(UUID.randomUUID(), BookingSource.LINK, false, PHONE, false, POLICY,
                false);

        Verdict verdict = eligibility(ReviewInviteTestProperties.enabled(), smsOn).evaluate(visit);

        assertSkipped(verdict, ReviewInviteSkipReason.NOTHING_TO_REVIEW);
    }

    @Test
    @DisplayName("STAFF and APP visits are never guest-link visits; reaching the predicate they are not eligible")
    void should_neverBeEligible_when_staffOrAppVisit() {
        VisitSnapshot staff = new VisitSnapshot(UUID.randomUUID(), BookingSource.STAFF, false, PHONE, false, POLICY,
                true);
        VisitSnapshot app = new VisitSnapshot(UUID.randomUUID(), BookingSource.APP, true, PHONE, false, POLICY, true);
        ReviewInviteEligibility eligibility = eligibility(ReviewInviteTestProperties.enabled(), smsOn);

        Verdict staffVerdict = eligibility.evaluate(staff);
        Verdict appVerdict = eligibility.evaluate(app);

        assertThat(staff.isGuestLinkVisit()).isFalse();
        assertThat(app.isGuestLinkVisit()).isFalse();
        assertSkipped(staffVerdict, ReviewInviteSkipReason.NOTHING_TO_REVIEW);
        assertSkipped(appVerdict, ReviewInviteSkipReason.NOTHING_TO_REVIEW);
    }

    @Test
    @DisplayName("VisitSnapshot.toString never prints the phone")
    void should_redactPhone_when_snapshotPrinted() {
        assertThat(linkVisit().toString()).doesNotContain(PHONE).doesNotContain("501234567");
    }

    @Test
    @DisplayName("batch preload: ONE query for the whole batch; the predicate answers by hash value, not identity")
    void should_queryOnceAndMatchByValue_when_preloadingOptOuts() {
        String otherPhone = "+380509876543";
        byte[] optedOutKey = phoneHasher.hash(PHONE).hash();
        when(smsOptOutRepository.findExistingHashes(any())).thenReturn(List.of(optedOutKey));
        ReviewInviteEligibility eligibility = eligibility(ReviewInviteTestProperties.enabled(), smsOn);

        Predicate<byte[]> optedOut = eligibility.preload(List.of(linkVisit(), linkVisit(otherPhone))).optedOut();

        assertThat(optedOut.test(phoneHasher.hash(PHONE).hash())).isTrue();
        assertThat(optedOut.test(phoneHasher.hash(otherPhone).hash())).isFalse();
        verify(smsOptOutRepository, times(1)).findExistingHashes(any());
    }

    @Test
    @DisplayName("evaluate with a preloaded lookup: OPTED_OUT from memory, never the per-row query")
    void should_skipOptedOutWithoutPerRowQuery_when_lookupPreloaded() {
        byte[] key = phoneHasher.hash(PHONE).hash();
        when(smsOptOutRepository.findExistingHashes(any())).thenReturn(List.of(key));
        ReviewInviteEligibility eligibility = eligibility(ReviewInviteTestProperties.enabled(), smsOn);
        VisitSnapshot visit = linkVisit();

        Verdict verdict = eligibility.evaluate(visit, eligibility.preload(List.of(visit)).optedOut());

        assertSkipped(verdict, ReviewInviteSkipReason.OPTED_OUT);
        verify(smsOptOutRepository, never()).existsByPhoneHash(any());
    }

    @Test
    @DisplayName("evaluate with a preloaded lookup: a phone not in the set passes the gate (same verdict as per-row)")
    void should_beEligible_when_preloadedLookupMisses() {
        when(smsOptOutRepository.findExistingHashes(any())).thenReturn(List.of());
        ReviewInviteEligibility eligibility = eligibility(ReviewInviteTestProperties.enabled(), smsOn);
        VisitSnapshot visit = linkVisit();

        Verdict verdict = eligibility.evaluate(visit, eligibility.preload(List.of(visit)).optedOut());

        assertThat(verdict.isEligible()).isTrue();
    }

    @Test
    @DisplayName("batch preload runs no query when a flag is off or no snapshot has a hashable phone")
    void should_runNoQuery_when_flagOffOrNoPhones() {
        ReviewInviteEligibility off = eligibility(ReviewInviteTestProperties.disabled(), smsOn);
        ReviewInviteEligibility on = eligibility(ReviewInviteTestProperties.enabled(), smsOn);

        off.preload(List.of(linkVisit()));
        on.preload(List.of(linkVisit(null), linkVisit("not-a-phone")));
        on.preload(List.of());

        verify(smsOptOutRepository, never()).findExistingHashes(any());
    }

    @Test
    @DisplayName("audit cycle 2 — send path hashes each phone ONCE per tick (preload + evaluateInBatch), not twice")
    void should_hashEachPhoneOnce_when_batchPreloadedAndEvaluated() {
        String otherPhone = "+380509876543";
        PhoneHasher spyHasher = spy(phoneHasher);
        when(smsOptOutRepository.findExistingHashes(any())).thenReturn(List.of());
        ReviewInviteEligibility eligibility = new ReviewInviteEligibility(
                ReviewInviteTestProperties.enabled(), smsOn, spyHasher, smsOptOutRepository);
        List<VisitSnapshot> visits = List.of(linkVisit(), linkVisit(), linkVisit(otherPhone));

        ReviewInviteEligibility.PreloadedBatch batch = eligibility.preload(visits);
        List<Verdict> verdicts = visits.stream().map(visit -> eligibility.evaluateInBatch(visit, batch)).toList();

        assertThat(verdicts).allSatisfy(verdict -> assertThat(verdict.isEligible()).isTrue());
        // tryHash delegates to hash, so hash(...) counts every HMAC regardless of entry point.
        verify(spyHasher, times(1)).hash(PHONE);
        verify(spyHasher, times(1)).hash(otherPhone);
    }

    @Test
    @DisplayName("audit cycle 2 — evaluateInBatch gives the same verdict as the per-row path for every gate")
    void should_matchPerRowVerdicts_when_evaluatedInBatch() {
        String optedOutPhone = "+380509876543";
        when(smsOptOutRepository.findExistingHashes(any()))
                .thenReturn(List.of(phoneHasher.hash(optedOutPhone).hash()));
        ReviewInviteEligibility eligibility = eligibility(ReviewInviteTestProperties.enabled(), smsOn);
        List<VisitSnapshot> visits = List.of(linkVisit(), linkVisit(optedOutPhone), linkVisit(null),
                linkVisit("not-a-phone"),
                new VisitSnapshot(UUID.randomUUID(), BookingSource.LINK, false, null, true, POLICY, true),
                new VisitSnapshot(UUID.randomUUID(), BookingSource.LINK, false, PHONE, false, null, true));

        ReviewInviteEligibility.PreloadedBatch batch = eligibility.preload(visits);

        for (VisitSnapshot visit : visits) {
            assertThat(eligibility.evaluateInBatch(visit, batch))
                    .as(visit.toString())
                    .isEqualTo(eligibility.evaluate(visit, batch.optedOut()));
        }
    }

    @Test
    @DisplayName("audit cycle 2 — a snapshot the batch never saw is hashed on demand, not reported NO_PHONE")
    void should_hashOnDemand_when_visitMissingFromBatch() {
        ReviewInviteEligibility eligibility = eligibility(ReviewInviteTestProperties.enabled(), smsOn);

        Verdict verdict = eligibility.evaluateInBatch(linkVisit(), ReviewInviteEligibility.PreloadedBatch.EMPTY);

        assertThat(verdict.isEligible()).isTrue();
        assertThat(verdict.phone()).isEqualTo(phoneHasher.hash(PHONE));
    }

    @Test
    @DisplayName("EMPTY batch with a null guest phone: NO_PHONE, not an NPE from the phone lookup")
    void should_skipNoPhone_when_emptyBatchAndNullPhone() {
        ReviewInviteEligibility eligibility = eligibility(ReviewInviteTestProperties.enabled(), smsOn);

        Verdict verdict = eligibility.evaluateInBatch(linkVisit(null), ReviewInviteEligibility.PreloadedBatch.EMPTY);

        assertSkipped(verdict, ReviewInviteSkipReason.NO_PHONE);
    }

    @Test
    @DisplayName("audit cycle 2 — preload with a flag off hashes nothing and runs no query")
    void should_hashNothing_when_preloadedWithFlagOff() {
        PhoneHasher spyHasher = spy(phoneHasher);
        ReviewInviteEligibility off = new ReviewInviteEligibility(
                ReviewInviteTestProperties.disabled(), smsOn, spyHasher, smsOptOutRepository);

        ReviewInviteEligibility.PreloadedBatch batch = off.preload(List.of(linkVisit()));

        assertThat(batch).isSameAs(ReviewInviteEligibility.PreloadedBatch.EMPTY);
        assertThat(batch.toString()).doesNotContain(PHONE);
        verify(spyHasher, never()).tryHash(any());
        verify(smsOptOutRepository, never()).findExistingHashes(any());
    }

    private ReviewInviteEligibility eligibility(ReviewInviteProperties props, BookingSmsProperties sms) {
        return new ReviewInviteEligibility(props, sms, phoneHasher, smsOptOutRepository);
    }

    private static VisitSnapshot linkVisit() {
        return linkVisit(PHONE);
    }

    private static VisitSnapshot linkVisit(String phone) {
        return new VisitSnapshot(UUID.randomUUID(), BookingSource.LINK, false, phone, false, POLICY, true);
    }

    private static void assertSkipped(Verdict verdict, ReviewInviteSkipReason reason) {
        assertThat(verdict.isEligible()).isFalse();
        assertThat(verdict.skipReason()).isEqualTo(reason);
    }
}
