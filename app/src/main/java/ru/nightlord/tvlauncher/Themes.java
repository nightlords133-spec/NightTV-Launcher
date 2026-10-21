package ru.nightlord.tvlauncher;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;

/** Встроенные темы: фон-градиент и акцентный цвет рамки фокуса. */
final class Themes {
    static final String[] NAMES = {"Ночь", "Океан", "Лес", "Закат", "Графит"};

    private static final int[][] GRADIENTS = {
            {0xFF0F2027, 0xFF203A43, 0xFF2C5364},
            {0xFF000428, 0xFF003366, 0xFF004E92},
            {0xFF07261C, 0xFF0B3D2E, 0xFF1F6F50},
            {0xFF3A1C71, 0xFF8E3B6E, 0xFFC0605A},
            {0xFF0B0B0C, 0xFF1C1C1E, 0xFF2C2C2E},
    };

    private static final int[] ACCENTS = {
            0xFF4FC3F7, 0xFF80D8FF, 0xFF69F0AE, 0xFFFFD180, 0xFFFFFFFF,
    };

    private Themes() {
    }

    static int safe(int index) {
        return (index >= 0 && index < NAMES.length) ? index : 0;
    }

    static Drawable background(int index) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, GRADIENTS[safe(index)]);
        // лёгкое затемнение, чтобы текст читался на любой теме
        return new LayerDrawable(new Drawable[]{g, new ColorDrawable(0x33000000)});
    }

    static int accent(int index) {
        return ACCENTS[safe(index)];
    }
}
