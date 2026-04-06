package io.agents.pokeclaw.tool.impl.tv;

import android.view.KeyEvent;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;

public class PressMenuTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "press_menu";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_press_menu);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the Menu button on the remote. Opens context menu or settings in the current app.";
    }

    @Override
    public String getDescriptionCN() {
        return "按下遥控器菜单键。在当前应用中打开上下文菜单或设置。";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_MENU;
    }

    @Override
    protected String getKeyLabel() {
        return "Menu";
    }
}
