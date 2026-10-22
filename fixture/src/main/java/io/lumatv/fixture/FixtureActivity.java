package io.lumatv.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.view.Gravity;

/** Installed only during emulator acceptance; not part of the Launcher APK. */
public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("Luma TV 应用发现验证\n返回键回到桌面");
        text.setTextSize(28);
        text.setTextColor(0xffeeeeff);
        text.setBackgroundColor(0xff19243b);
        text.setGravity(Gravity.CENTER);
        setContentView(text);
    }
}
