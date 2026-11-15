package com.chzzk.donation;

/** SUM and COUNT of CONFIRMED donations of one broadcast, computed by the database in one query. */
public record DonationTotals(long totalAmount, long count) {
}
