package dev.warp.scannerstream;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import java.util.Random;

/**
 * Animated bar visualizer that reflects the audio energy (rms) of the scanner
 * call that produced a location mention. Amplitude is normalized 0..1.
 */
public class AudioVisualizerView extends View {
  private static final int BAR_COUNT = 27;

  private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF barRect = new RectF();
  private final float[] phases = new float[BAR_COUNT];
  private final float[] speeds = new float[BAR_COUNT];
  private float amplitude = 0.2f;
  private boolean animating = false;

  public AudioVisualizerView(Context context) {
    this(context, null);
  }

  public AudioVisualizerView(Context context, AttributeSet attrs) {
    super(context, attrs);
    barPaint.setColor(Color.parseColor("#2B6BE6"));
    Random random = new Random(42L);
    for (int i = 0; i < BAR_COUNT; i++) {
      phases[i] = random.nextFloat() * (float) Math.PI * 2f;
      speeds[i] = 3f + (random.nextFloat() * 5f);
    }
  }

  /** Sets the normalized (0..1) audio energy driving bar heights. */
  public void setAmplitude(float normalized) {
    amplitude = Math.max(0.08f, Math.min(1f, normalized));
  }

  public void start() {
    if (!animating) {
      animating = true;
      postInvalidateOnAnimation();
    }
  }

  public void stop() {
    animating = false;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float width = getWidth();
    float height = getHeight();
    if (width <= 0f || height <= 0f) {
      return;
    }
    float slot = width / BAR_COUNT;
    float barWidth = slot * 0.62f;
    float nowSeconds = (System.currentTimeMillis() % 100000L) / 1000f;
    for (int i = 0; i < BAR_COUNT; i++) {
      float wave = (float) Math.abs(Math.sin((nowSeconds * speeds[i]) + phases[i]));
      float barHeight = height * (0.12f + (0.88f * amplitude * wave));
      float left = (i * slot) + ((slot - barWidth) / 2f);
      float top = (height - barHeight) / 2f;
      barRect.set(left, top, left + barWidth, top + barHeight);
      canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, barPaint);
    }
    if (animating) {
      postInvalidateOnAnimation();
    }
  }

  @Override
  protected void onDetachedFromWindow() {
    stop();
    super.onDetachedFromWindow();
  }
}
