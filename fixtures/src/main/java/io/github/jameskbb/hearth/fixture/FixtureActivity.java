package io.github.jameskbb.hearth.fixture;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** One plain screen showing the fixture's name. Back returns to the launcher. */
public class FixtureActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView name = new TextView(this);
        name.setText(getApplicationInfo().loadLabel(getPackageManager()));
        name.setTextSize(40f);
        name.setTextColor(Color.WHITE);
        name.setBackgroundColor(Color.rgb(20, 24, 40));
        name.setGravity(Gravity.CENTER);
        setContentView(name);
    }
}
