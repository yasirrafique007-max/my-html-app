package com.neospos.tap;

import com.stripe.stripeterminal.external.callable.ConnectionTokenCallback;
import com.stripe.stripeterminal.external.callable.ConnectionTokenProvider;
import com.stripe.stripeterminal.external.models.ConnectionTokenException;

public final class NeosposConnectionTokenProvider implements ConnectionTokenProvider {
    @Override
    public void fetchConnectionToken(ConnectionTokenCallback callback) {
        try {
            Backend.Bootstrap bootstrap = Backend.get().terminalBootstrap();
            callback.onSuccess(bootstrap.secret);
        } catch (Exception e) {
            callback.onFailure(new ConnectionTokenException("NEOSPOS could not fetch a Stripe Terminal connection token: " + e.getMessage(), e));
        }
    }
}
