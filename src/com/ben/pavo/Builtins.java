package com.ben.pavo;

import android.content.Context;

/**
 * Maps Pavo's built-in activities to the same labels and icons the app drawer
 * uses, so a built-in can appear as its own card in the recents sheet.
 */
final class Builtins {
    private Builtins() {}

    /** True when the activity is one of the launcher's own built-in screens. */
    static boolean isBuiltinClass(String cls) {
        return cls != null && labelFor(cls) != null;
    }

    static String labelFor(String cls) {
        if (cls == null) return null;
        switch (cls) {
            case "com.ben.pavo.PerformanceTunerActivity": return "Performance Tuner";
            case "com.ben.pavo.PerfLogActivity": return "Perf Logs";
            case "com.ben.pavo.DisplayActivity": return "Display";
            case "com.ben.pavo.SoundTunerActivity": return "Sound Tuner";
            case "com.ben.pavo.ControllerMapActivity": return "Controller Remapper";
            case "com.ben.pavo.JoystickActivity": return "JOYSTICKS";
            case "com.ben.pavo.RgbActivity": return "RGB Lighting";
            case "com.ben.pavo.OverlayActivity": return "Overlay & Drawer";
            case "com.ben.pavo.FanActivity": return "Fan Control";
            case "com.ben.pavo.UpdatesActivity": return "Pavo Updates";
            case "com.ben.pavo.TouchMapperActivity": return "Touch Mapper";
            case "com.ben.pavo.DeviceHealthActivity": return "Device Health";
            case "com.ben.pavo.BenchmarkActivity": return "Stress Test";
            case "com.ben.pavo.FileManagerActivity": return "File Manager";
            case "com.ben.pavo.GalleryActivity": return "Pavo Gallery";
            case "com.ben.pavo.ScreenRecorderActivity": return "Screen Recorder";
            case "com.ben.pavo.FtpActivity":
                return "FTP";
            default:
                return null;
        }
    }

    /** Card entry for a built-in activity, with the drawer's own icon. */
    static Apps.Entry entryFor(Context c, String cls, int px) {
        String label = labelFor(cls);
        if (c == null || label == null) return null;
        int accent = ColorTuning.colour(ThemeColors.accent());
        Apps.Entry e = new Apps.Entry(Loc.s(c, label), c.getPackageName(), cls);
        switch (cls) {
            case "com.ben.pavo.PerformanceTunerActivity":
                e.icon = Apps.performanceTunerIcon(px, accent);
                break;
            case "com.ben.pavo.PerfLogActivity":
                e.icon = Apps.performanceLogsIcon(px, accent);
                break;
            case "com.ben.pavo.DisplayActivity":
                e.icon = Apps.displayIcon(px, accent);
                break;
            case "com.ben.pavo.SoundTunerActivity":
                e.icon = Apps.soundIcon(px, accent);
                break;
            case "com.ben.pavo.ControllerMapActivity":
                e.icon = Apps.controllerMapIcon(px, accent);
                break;
            case "com.ben.pavo.JoystickActivity":
                e.icon = Apps.joystickIcon(px, accent);
                break;
            case "com.ben.pavo.RgbActivity":
                e.icon = Apps.rgbIcon(px, accent);
                break;
            case "com.ben.pavo.OverlayActivity":
                e.icon = Apps.overlayIcon(px, accent);
                break;
            case "com.ben.pavo.FanActivity":
                e.icon = Apps.fanIcon(px, accent);
                break;
            case "com.ben.pavo.UpdatesActivity":
                e.icon = Apps.updateIcon(px, accent);
                break;
            case "com.ben.pavo.TouchMapperActivity":
                e.icon = Apps.touchMapperIcon(px, accent);
                break;
            case "com.ben.pavo.DeviceHealthActivity":
                e.icon = Apps.deviceHealthIcon(px, accent);
                break;
            case "com.ben.pavo.BenchmarkActivity":
                e.icon = Apps.benchmarkIcon(px, accent);
                break;
            case "com.ben.pavo.FileManagerActivity":
                e.icon = Apps.fileManagerIcon(px, accent);
                break;
            case "com.ben.pavo.GalleryActivity":
                e.icon = Apps.galleryIcon(px, accent);
                break;
            case "com.ben.pavo.ScreenRecorderActivity":
                e.icon = Apps.screenRecorderIcon(px, accent);
                break;
            case "com.ben.pavo.FtpActivity":
                e.icon = Apps.ftpIcon(px, accent);
                break;
            default:
                return null;
        }
        return e;
    }
}
