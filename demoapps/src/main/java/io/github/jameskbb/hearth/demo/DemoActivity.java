package io.github.jameskbb.hearth.demo;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Button;
import android.widget.LinearLayout;

/** An offline demo destination, never an official streaming-service client. */
public class DemoActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String service = getApplicationInfo().loadLabel(getPackageManager()).toString();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(48, 48, 48, 48);
        content.setBackgroundColor(Color.rgb(12, 14, 30));
        TextView name = line(service, 40f);
        content.addView(name);
        TextView note = line("Hearth demo, not the official " + service + " app.\n"
                + "No streaming, sign-in, or network connection.", 18f);
        note.setPadding(0, 24, 0, 32);
        content.addView(note);
        Button back = new Button(this);
        back.setText("Return to Hearth");
        back.setAllCaps(false);
        back.setOnClickListener(view -> finish());
        content.addView(back, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(content);
        back.requestFocus();
    }

    private TextView line(String text, float size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        return view;
    }
}
