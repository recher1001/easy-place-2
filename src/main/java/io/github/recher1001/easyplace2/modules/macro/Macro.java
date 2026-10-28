package io.github.recher1001.easyplace2.modules.macro;

import java.util.ArrayList;
import java.util.List;

/** Ein aufgenommenes oder selbst gebautes Makro. */
public final class Macro
{
    public String name = "Makro";
    public List<MacroStep> steps = new ArrayList<>();
    /** Wie oft abspielen; 0 = endlos (zum AFK-Farmen). */
    public int repeats = 0;
    /** Bei Schaden anhalten. */
    public boolean stopOnDamage = true;

    public Macro()
    {
    }

    public Macro(String name, List<MacroStep> steps)
    {
        this.name = name;
        this.steps = new ArrayList<>(steps);
    }
}
