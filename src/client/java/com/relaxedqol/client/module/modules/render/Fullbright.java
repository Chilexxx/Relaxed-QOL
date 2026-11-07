package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;

/**
 * See in the dark without a night vision potion: no effect icon, no flicker, works in caves, the Nether and the End.
 * Applied to the lightmap in LightmapRenderStateExtractorMixin.
 * Full: every block fully lit. Gamma: like turning brightness far past "Bright" (keeps some shading).
 */
public class Fullbright extends Module {
    private final Setting<String> mode = addSetting(Setting.mode("Mode", "Full", "Full", "Gamma"));
    private final Setting<Integer> gamma = addSetting(new Setting<>("Gamma", 10, 1, 20, 1));
    private final Setting<Boolean> noDarkness = addSetting(new Setting<>("No Darkness Effect", true));

    public Fullbright() {
        super("Fullbright", "See in the dark without night vision (no potion needed)", ModuleCategory.RENDER);
    }

    public boolean fullMode() {
        return mode.getValue().equals("Full");
    }

    public float gammaValue() {
        return gamma.getValue();
    }

    public boolean removeDarkness() {
        return noDarkness.getValue();
    }

    @Override
    public String getInfo() {
        return mode.getValue();
    }
}
