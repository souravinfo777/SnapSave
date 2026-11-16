package com.snapsave.app.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.animation.DecelerateInterpolator;
import android.widget.ProgressBar;

import com.snapsave.app.R;
import com.snapsave.app.util.Insets;

public class SplashActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        Insets.applyToContent(this);

        final String sharedUrl = extractShared(getIntent());

        final ProgressBar pb = (ProgressBar) findViewById(R.id.splash_progress);
        ValueAnimator va = ValueAnimator.ofInt(0, 100);
        va.setDuration(1300);
        va.setInterpolator(new DecelerateInterpolator());
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                pb.setProgress((Integer) a.getAnimatedValue());
            }
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                Intent i = new Intent(SplashActivity.this, MainActivity.class);
                if (sharedUrl != null) {
                    i.putExtra("url", sharedUrl);
                }
                startActivity(i);
                finish();
            }
        });
        va.start();
    }

    private String extractShared(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://\\S+").matcher(text);
                if (m.find()) {
                    return m.group();
                }
            }
        }
        return null;
    }
}
