// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl.tv;

import android.view.KeyEvent;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;

public class DpadRightTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "dpad_right";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_dpad_right);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the D-pad Right button on the remote. Moves focus to the element on the right of the currently focused one.";
    }

    @Override
    public String getDescriptionCN() {
        return "按下遥控器右方向键。将焦点移动到当前聚焦元素右侧的元素。";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    @Override
    protected String getKeyLabel() {
        return "D-pad Right";
    }
}
