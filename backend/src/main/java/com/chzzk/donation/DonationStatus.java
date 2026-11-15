package com.chzzk.donation;

/**
 * PENDING: created, waiting for the payment. CONFIRMED: Toss approved it and the server verified it (the only state
 * that counts in totals). FAILED: rejected or could not be verified. CANCELED: the buyer closed/cancelled the window.
 * PENDING is the only state that can change; the other three are final.
 */
public enum DonationStatus {
    PENDING, CONFIRMED, FAILED, CANCELED
}
