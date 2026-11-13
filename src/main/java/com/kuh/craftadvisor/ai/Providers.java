package com.kuh.craftadvisor.ai;

import com.kuh.craftadvisor.ClientConfig;

/** The AI settings currently in the config file. Read on every use, so config changes apply without a restart. */
public final class Providers {
    private Providers() {
    }

    public static ProviderSettings current() {
        return ProviderSettings.of(ClientConfig.PROVIDER.get(), ClientConfig.BASE_URL.get(), ClientConfig.API_KEY.get(),
                ClientConfig.MODEL.get(), ClientConfig.ADVICE_MAX_TOKENS.get());
    }
}
