// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl.tv;

import android.view.KeyEvent;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;

public class DpadCenterTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "dpad_center";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_dpad_center);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the OK/Center/Select button on the remote. Confirms the selection or clicks the currently focused element.";
    }

    @Override
    public String getDescriptionCN() {
        return "按下遥控器确认/OK键。确认选择或点击当前聚焦的元素。";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_DPAD_CENTER;
    }

    @Override
    protected String getKeyLabel() {
        return "OK/Center";
    }
}
