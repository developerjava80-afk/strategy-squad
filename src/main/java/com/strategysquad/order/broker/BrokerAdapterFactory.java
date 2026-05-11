package com.strategysquad.order.broker;

import com.strategysquad.ingestion.kite.KiteLiveSessionManager;
import com.strategysquad.ingestion.live.session.LiveSessionState;
import com.strategysquad.platform.config.AppConfig;

import java.util.Objects;

public final class BrokerAdapterFactory {

    public BrokerAdapter create(
            AppConfig config,
            KiteLiveSessionManager sessionManager,
            LiveSessionState liveSessionState
    ) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        Objects.requireNonNull(liveSessionState, "liveSessionState must not be null");

        return switch (config.getOrderBrokerMode()) {
            case KITE -> new KiteBrokerAdapter(sessionManager);
            case PAPER -> new PaperBrokerAdapter(liveSessionState);
        };
    }
}
