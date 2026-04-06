// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl.tv;

import android.view.KeyEvent;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;

public class DpadDownTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "dpad_down";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_dpad_down);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the D-pad Down button on the remote. Moves focus to the element below the currently focused one.";
    }

    @Override
    public String getDescriptionCN() {
        return "按下遥控器下方向键。将焦点移动到当前聚焦元素下方的元素。";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_DPAD_DOWN;
    }

    @Override
    protected String getKeyLabel() {
        return "D-pad Down";
    }
}
