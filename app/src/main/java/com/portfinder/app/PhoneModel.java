package com.portfinder.app;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A short, readable name for this phone for the network map ("Z Fold 7", "S25 Ultra", "Pixel 9 Pro"),
 * from Android's manufacturer and model strings. Samsung reports codes like "SM-F966U", so common
 * Samsung families are decoded from the code; anything unknown falls back to the model string.
 * Never uses the user-editable device name, which often contains the owner's name. Pure Java.
 */
public final class PhoneModel {

    private PhoneModel() {}

    private static final Pattern SAMSUNG = Pattern.compile("^SM-([A-Z])(\\d)(\\d)(\\d)([A-Z0-9]*)$");

    public static String shortName(String manufacturer, String model) {
        String man = manufacturer == null ? "" : manufacturer.trim();
        String mod = model == null ? "" : model.trim();
        if (mod.isEmpty()) return "This phone";
        if (man.equalsIgnoreCase("samsung")) {
            String s = samsung(mod.toUpperCase(Locale.US));
            if (s != null) return s;
        }
        // Many brands already report a readable model ("Pixel 9 Pro", "moto g power")
        if (!man.isEmpty() && mod.toLowerCase(Locale.US).startsWith(man.toLowerCase(Locale.US))) {
            mod = mod.substring(man.length()).trim();
        }
        return mod.length() <= 16 ? mod : mod.substring(0, 15) + "…";
    }

    static String samsung(String code) {
        Matcher m = SAMSUNG.matcher(code);
        if (!m.matches()) return null;
        char series = m.group(1).charAt(0);
        int d1 = m.group(2).charAt(0) - '0', d2 = m.group(3).charAt(0) - '0', d3 = m.group(4).charAt(0) - '0';
        switch (series) {
            case 'F':
                // Foldables: Fold3 = F926, Fold4 = F936 ... Fold7 = F966; Flip3 = F711 ... Flip6 = F741, Flip7 = F766
                if (d1 == 9 && d3 == 6 && d2 >= 2) return "Z Fold " + (d2 + 1);
                if (d1 == 7 && d3 == 1 && d2 >= 1) return "Z Flip " + (d2 + 2);
                if (d1 == 7 && d2 == 6 && d3 == 6) return "Z Flip 7";
                if (d1 == 9) return "Galaxy Z Fold";
                if (d1 == 7) return "Galaxy Z Flip";
                return "Galaxy Z";
            case 'S':
                // S22 = S901/S906/S908 ... S25 = S931/S936/S938; 7 = Edge
                if (d1 == 9) {
                    String gen = "S" + (22 + d2);
                    return gen + (d3 == 8 ? " Ultra" : d3 == 6 ? "+" : d3 == 7 ? " Edge" : "");
                }
                return "Galaxy S";
            case 'G':
                if (d1 == 9 && d2 == 9) return "S21" + (d3 == 8 ? " Ultra" : d3 == 6 ? "+" : "");
                return "Galaxy";
            case 'A': return "Galaxy A" + d1 + d2;
            case 'M': return "Galaxy M" + d1 + d2;
            case 'N': return "Galaxy Note";
            case 'X':
            case 'T':
            case 'P': return "Galaxy Tab";
            default: return null;
        }
    }
}
