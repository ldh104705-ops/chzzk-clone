-- Step 4: donations paid through Toss Payments (test mode).
-- A donation row is created PENDING when the donor starts a payment and becomes CONFIRMED only after the SERVER
-- has asked Toss to approve it and checked the answer. The constraints below are what make a repeated approval
-- notice harmless: the database itself refuses a second row for the same order or the same payment.

CREATE TABLE donation (
    id           BIGSERIAL PRIMARY KEY,
    -- Our own order number ("donation_" + random UUID), sent to Toss; Toss allows [A-Za-z0-9_=-]{6,64}.
    order_id     VARCHAR(64)  NOT NULL,
    -- Toss's payment identifier, known only after approval (opaque string, max 200 chars; case-sensitive).
    payment_key  VARCHAR(200),
    amount       BIGINT       NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    donor_id     BIGINT       NOT NULL REFERENCES users (id),
    -- The broadcaster at the moment the donation was started (= broadcast.owner_id then); the approval re-checks it.
    recipient_id BIGINT       NOT NULL REFERENCES users (id),
    -- Kept (set to NULL) when the broadcast is deleted: the broadcaster still received that money.
    broadcast_id BIGINT       REFERENCES broadcast (id) ON DELETE SET NULL,
    message      VARCHAR(100),
    fail_code    VARCHAR(100),
    fail_message VARCHAR(500),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    approved_at  TIMESTAMP WITH TIME ZONE,

    -- One donation per order, and one donation per Toss payment. payment_key is nullable: PostgreSQL treats NULLs
    -- as distinct in a UNIQUE constraint, so any number of PENDING rows may exist, but a payment_key is used once.
    CONSTRAINT uq_donation_order_id UNIQUE (order_id),
    CONSTRAINT uq_donation_payment_key UNIQUE (payment_key),
    CONSTRAINT chk_donation_order_id CHECK (order_id ~ '^[A-Za-z0-9_=-]{6,64}$'),
    CONSTRAINT chk_donation_amount CHECK (amount BETWEEN 1000 AND 1000000),
    CONSTRAINT chk_donation_status CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED', 'CANCELED')),
    -- A confirmed donation always carries the payment that proves it.
    CONSTRAINT chk_donation_confirmed_shape CHECK (
        status <> 'CONFIRMED' OR (payment_key IS NOT NULL AND approved_at IS NOT NULL)
    ),
    -- Policy: nobody donates to themselves (it would only inflate their own totals).
    CONSTRAINT chk_donation_not_self CHECK (donor_id <> recipient_id),
    CONSTRAINT chk_donation_message CHECK (message IS NULL OR char_length(message) BETWEEN 1 AND 100)
);

-- Newest-first listing and SUM of one broadcast's confirmed donations.
CREATE INDEX idx_donation_broadcast_confirmed ON donation (broadcast_id, approved_at DESC, id DESC)
    WHERE status = 'CONFIRMED';
CREATE INDEX idx_donation_donor ON donation (donor_id);
CREATE INDEX idx_donation_recipient ON donation (recipient_id);
