// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.qqbot;

public class QBotException extends Exception {
    public QBotException(String message) {
        super(message);
    }
    
    public QBotException(String message, Throwable cause) {
        super(message, cause);
    }
}
