package com.chzzk.donation;

import com.chzzk.auth.CurrentUser;
import com.chzzk.donation.DonationDtos.ConfirmRequest;
import com.chzzk.donation.DonationDtos.CreateRequest;
import com.chzzk.donation.DonationDtos.DonationResponse;
import com.chzzk.donation.DonationDtos.FailRequest;
import com.chzzk.donation.DonationDtos.ListResponse;
import com.chzzk.donation.DonationDtos.OrderResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creating/confirming/failing a donation needs a login (SecurityConfig, 401) and is limited to the donor's own
 * orders (DonationService, 403). The per-broadcast list is public, like comments.
 */
@RestController
public class DonationController {

    private final DonationService service;

    public DonationController(DonationService service) {
        this.service = service;
    }

    /** Step 2: create a PENDING donation and get what the Toss payment window needs. */
    @PostMapping("/api/donations")
    public ResponseEntity<OrderResponse> create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(CurrentUser.id(jwt), request));
    }

    /** Step 4: the success page reports what Toss gave it; the SERVER verifies it with Toss. Idempotent. */
    @PostMapping("/api/donations/confirm")
    public DonationResponse confirm(@AuthenticationPrincipal Jwt jwt, @RequestBody ConfirmRequest request) {
        return service.confirm(CurrentUser.id(jwt), request);
    }

    /** Step 5: the fail page closes the order as CANCELED (window closed) or FAILED. */
    @PostMapping("/api/donations/{orderId}/fail")
    public DonationResponse fail(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderId,
                                 @RequestBody(required = false) FailRequest request) {
        return service.reportFailure(CurrentUser.id(jwt), orderId, request);
    }

    /** Public: CONFIRMED donations of a broadcast (newest first) plus their total. */
    @GetMapping("/api/broadcasts/{broadcastId}/donations")
    public ListResponse list(@PathVariable long broadcastId, @RequestParam(required = false) Integer limit) {
        return service.list(broadcastId, limit);
    }
}
