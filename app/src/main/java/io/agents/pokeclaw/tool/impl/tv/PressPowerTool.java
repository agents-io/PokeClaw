// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl.tv;

import android.view.KeyEvent;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;

public class PressPowerTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "press_power";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_press_power);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the Power button. May turn off the screen or put the device to sleep.";
    }

    @Override
    public String getDescriptionCN() {
        return "按下电源键。可能会关闭屏幕或使设备进入休眠。";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_POWER;
    }

    @Override
    protected String getKeyLabel() {
        return "Power";
    }
}
