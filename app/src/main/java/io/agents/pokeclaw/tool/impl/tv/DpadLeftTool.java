package io.agents.pokeclaw.tool.impl.tv;

import android.view.KeyEvent;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;

public class DpadLeftTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "dpad_left";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_dpad_left);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the D-pad Left button on the remote. Moves focus to the element on the left of the currently focused one.";
    }

    @Override
    public String getDescriptionCN() {
        return "按下遥控器左方向键。将焦点移动到当前聚焦元素左侧的元素。";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_DPAD_LEFT;
    }

    @Override
    protected String getKeyLabel() {
        return "D-pad Left";
    }
}
