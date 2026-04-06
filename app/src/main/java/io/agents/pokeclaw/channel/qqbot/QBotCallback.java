// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.qqbot;

public interface QBotCallback<T> {
    void onSuccess(T result);
    void onFailure(QBotException e);
}
