package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;

import java.io.File;
import java.util.Random;

/**
 * The clock screensaver's scene (layout dream_clock): the thin clock and date on black over a slow ambient
 * glow. Every minute the clock drifts to a new spot and the glow shifts colour, so nothing burns in.
 * Shared by ClockDream (the system screensaver) and ClockActivity (Home left idle).
 */
final class ClockScene {

    private static final long MOVE_MS = 60_000L;
    /** Local change for this TV: the clock stays centred and only nudges this far (share of the screen) each minute. */
    private static final float NUDGE = 0.012f;
    /**
     * Local change for this TV: the colours flow without pause, from one palette colour to the next in 20 s.
     * Redrawn 10 times a second rather than every frame, which is smooth at this speed and light on the TV.
     */
    private static final long FLOW_STEP_MS = 100L;
    private static final int FLOW_STEPS_PER_COLOR = 200;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final View content;
    private final View ambient;
    private final GradientDrawable glow;
    /** Local change for this TV: a diagonal two-colour wash under the glow. */
    private final GradientDrawable wash;
    private final ArgbEvaluator mix = new ArgbEvaluator();
    private int flowStep;
    /** clock_wallpaper or clock_glow false: something else (a picture, or plain black) replaced the coloured glow. */
    private boolean wallpaperShown;
    /** clock_wallpaper: the picture start() decodes, its darkening, and the resources to draw it with. */
    private File wallpaperFile;
    private int wallpaperShade;
    private Resources wallpaperRes;
    /** Bumped by stop(): a decode started before it finds a different number and drops its result. Main thread only. */
    private int wallpaperGeneration;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    private final Runnable flow = new Runnable() {
        @Override
        public void run() {
            if (wallpaperShown) return;
            flowStep = (flowStep + 1) % (FLOW_STEPS_PER_COLOR * PALETTE.length);
            applyFlow();
            handler.postDelayed(this, FLOW_STEP_MS);
        }
    };

    /** root is the inflated dream_clock layout. */
    ClockScene(View root) {
        content = root.findViewById(R.id.dreamContent);
        ambient = root.findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        wash = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0, 0, 0});
        ambient.setBackground(new LayerDrawable(new Drawable[]{wash, glow}));
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.45f));
        flowStep = random.nextInt(PALETTE.length) * FLOW_STEPS_PER_COLOR;
        applyFlow();
    }

    void start() {
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, MOVE_MS);
        handler.removeCallbacks(flow);
        handler.postDelayed(flow, FLOW_STEP_MS);
        if (wallpaperFile != null && !wallpaperShown) loadWallpaper();
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);   // the tick and any glow steps still queued
        content.animate().cancel();
        wallpaperGeneration++;   // a decode still running must not attach its picture to a stopped scene
    }

    /**
     * clock_wallpaper: put Home's wallpaper (the theme's single picture, or the first of its collection) behind
     * the clock, darkened by clock_wallpaper_dim, in place of the glow. Decoded off the main thread at screen size
     * in RGB_565 (4 MB at 1080p), like Home's own; until then, or if it cannot be read, the glow stays.
     * Call before start(), which starts the decode (again after a stop() that dropped an unfinished one).
     */
    void useHomeWallpaper(Context ctx) {
        Config cfg;
        try {
            cfg = Config.load(ctx);
        } catch (RuntimeException e) {
            return;
        }
        if (!cfg.clockWallpaper) {
            // clock_glow false: plain black behind the clock (OLED); the clock itself still moves every minute.
            if (!cfg.clockGlow) {
                wallpaperShown = true;   // also stops the glow's colour cycle
                ambient.setBackground(null);
            }
            return;
        }
        wallpaperFile = cfg.wallpaper != null ? cfg.wallpaper : cfg.wallpapers.isEmpty() ? null : cfg.wallpapers.get(0);
        wallpaperShade = (cfg.clockWallpaperDim * 255 / 100) << 24;
        wallpaperRes = ctx.getResources();
    }

    private void loadWallpaper() {
        File file = wallpaperFile;
        int shade = wallpaperShade;
        Resources res = wallpaperRes;
        DisplayMetrics dm = res.getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        int generation = wallpaperGeneration;
        new Thread(() -> {
            Bitmap bmp = decode(file, w, h);
            if (bmp == null) return;
            handler.post(() -> {
                if (generation != wallpaperGeneration || wallpaperShown) {
                    bmp.recycle();   // stopped meanwhile (or already shown): this picture was never attached
                    return;
                }
                wallpaperShown = true;
                ambient.setBackground(new LayerDrawable(new Drawable[]{
                        new BitmapDrawable(res, bmp), new ColorDrawable(shade)}));
            });
        }, "clock-wallpaper").start();
    }

    /** WallpaperPlayer's decode: sampled and scaled down to cover the screen, never bigger than 4K. */
    private static Bitmap decode(File file, int screenW, int screenH) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, screenW, screenH);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, screenW, screenH);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withLayer().withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).withLayer().start();
        }).start();
    }

    /** The centre of the screen, nudged a little each minute so nothing burns in, with the glow centred behind it. */
    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX / 2f + parent.getWidth() * NUDGE * (2f * random.nextFloat() - 1f);
        float y = maxY / 2f + parent.getHeight() * NUDGE * (2f * random.nextFloat() - 1f);
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    /** The glow and the wash at flowStep: each part of the way from one palette colour to the next. */
    private void applyFlow() {
        int n = PALETTE.length;
        int i = flowStep / FLOW_STEPS_PER_COLOR;
        float f = (flowStep % FLOW_STEPS_PER_COLOR) / (float) FLOW_STEPS_PER_COLOR;
        setGlow((int) mix.evaluate(f, PALETTE[i], PALETTE[(i + 1) % n]),
                (int) mix.evaluate(f, PALETTE[(i + 2) % n], PALETTE[(i + 3) % n]));
    }

    private void setGlow(int color, int second) {
        int c = color & 0x00FFFFFF, s = second & 0x00FFFFFF;
        glow.setColors(new int[]{0x4D000000 | c, 0x1A000000 | c, 0x00000000 | c});
        wash.setColors(new int[]{0x59000000 | c, 0x0D000000 | c, 0x4D000000 | s});
    }
}
