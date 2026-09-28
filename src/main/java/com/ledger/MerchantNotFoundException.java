package com.ledger;

public class MerchantNotFoundException extends RuntimeException {

    public MerchantNotFoundException(String merchantId) {
        super("Merchant not found: " + merchantId);
    }
}