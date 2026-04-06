package io.agents.pokeclaw.tool.impl.tv;

import android.view.KeyEvent;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;

public class VolumeUpTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "volume_up";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_volume_up);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the Volume Up button to increase the volume.";
    }

    @Override
    public String getDescriptionCN() {
        return "按下音量增大键增加音量。";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_VOLUME_UP;
    }

    @Override
    protected String getKeyLabel() {
        return "Volume Up";
    }
}
