package ai.opencode.fixture.wifidirect;

import android.app.Activity;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Standalone app, not instrumentation. Opening it alone never calls the setter. */
public final class Probe extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 160, 32, 32);
        TextView output = new TextView(this);
        for (boolean target : new boolean[]{false, true}) {
            Button b = new Button(this);
            b.setText(target ? "Request on" : "Request off");
            b.setOnClickListener(v -> {
                String result;
                try {
                    boolean value = getApplicationContext().getSystemService(WifiManager.class).setWifiEnabled(target);
                    result = "returned=" + value;
                } catch (SecurityException e) {
                    result = "exception=" + e;
                }
                String line = "uid=" + Process.myUid() + " targetSdk=" + getApplicationInfo().targetSdkVersion
                    + " focused=" + hasWindowFocus() + " requested=" + target + " " + result;
                output.setText(line);
                Log.i("WifiDirectProbe", line);
            });
            layout.addView(b);
        }
        layout.addView(output);
        setContentView(layout);
    }
}
