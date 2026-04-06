// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl.tv;

import android.view.KeyEvent;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;

public class VolumeDownTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "volume_down";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_volume_down);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the Volume Down button to decrease the volume.";
    }

    @Override
    public String getDescriptionCN() {
        return "按下音量减小键降低音量。";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_VOLUME_DOWN;
    }

    @Override
    protected String getKeyLabel() {
        return "Volume Down";
    }
}
