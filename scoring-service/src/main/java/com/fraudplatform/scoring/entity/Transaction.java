package com.fraudplatform.scoring.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_tx_user_id",   columnList = "user_id"),
        @Index(name = "idx_tx_created_at", columnList = "created_at"),
        @Index(name = "idx_tx_idem_key",  columnList = "idempotency_key", unique = true)
})
public class Transaction {

    @Id
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "user_id",    nullable = false) private String userId;
    @Column(nullable = false)                       private BigDecimal amount;
    @Column(name = "merchant_id")                   private String merchantId;
    @Column(name = "device_id")                     private String deviceId;
    @Column(name = "ip_address")                    private String ipAddress;
    @Column(name = "card_last4", length = 4)        private String cardLast4;
    @Column(name = "card_country", length = 2)      private String cardCountry;
    @Column(name = "ip_country",   length = 2)      private String ipCountry;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
