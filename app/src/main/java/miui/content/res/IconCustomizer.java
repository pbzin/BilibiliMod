package miui.content.res;

import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

public final class IconCustomizer {
    private IconCustomizer() {
    }

    public static int getCustomizedIconWidth() {
        return -1;
    }

    public static BitmapDrawable getCustomizedIconFromCache(String packageName, String customizedIconName) {
        return null;
    }

    public static void clearCustomizedIcons(String packageName) {
    }

    public static Drawable generateIconStyleDrawable(Drawable drawable, boolean cropOutside) {
        return drawable;
    }

    public static BitmapDrawable generateIconStyleDrawable(
            Drawable drawable,
            android.graphics.Bitmap mask,
            android.graphics.Bitmap background,
            android.graphics.Bitmap pattern,
            android.graphics.Bitmap border,
            boolean isHd) {
        return drawable instanceof BitmapDrawable ? (BitmapDrawable) drawable : null;
    }
}
