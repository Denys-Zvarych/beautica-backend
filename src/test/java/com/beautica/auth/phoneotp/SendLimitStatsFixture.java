package com.beautica.auth.phoneotp;

/** Test fixture for the one-row {@link PhoneOtpRepository#sendLimitStats} aggregate (audit-fix single query). */
record SendLimitStatsFixture(long recentSends, Long latestSamePurposeMillis, long recentFailures)
        implements PhoneOtpRepository.SendLimitStats {

    static final SendLimitStatsFixture NONE = new SendLimitStatsFixture(0, null, 0);

    @Override
    public long getRecentSends() {
        return recentSends;
    }

    @Override
    public Long getLatestSamePurposeMillis() {
        return latestSamePurposeMillis;
    }

    @Override
    public long getRecentFailures() {
        return recentFailures;
    }
}
