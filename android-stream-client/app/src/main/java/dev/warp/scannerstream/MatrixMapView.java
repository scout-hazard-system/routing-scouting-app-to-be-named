package dev.warp.scannerstream;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MatrixMapView extends View {
  private static final float PADDING_RATIO = 0.12f;
  private static final int GRID_LINES = 10;

  private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint devicePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint headingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  private final List<double[]> routePoints = new ArrayList<>();
  private double deviceLat = Double.NaN;
  private double deviceLon = Double.NaN;
  private double targetLat = Double.NaN;
  private double targetLon = Double.NaN;
  private float headingDeg = 0f;
  private boolean hasFix = false;
  private boolean threeDMode = true;

  public MatrixMapView(Context context) {
    super(context);
    initPaints();
  }

  public MatrixMapView(Context context, AttributeSet attrs) {
    super(context, attrs);
    initPaints();
  }

  public MatrixMapView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initPaints();
  }

  private void initPaints() {
    backgroundPaint.setColor(Color.parseColor("#101820"));

    gridPaint.setColor(Color.parseColor("#55FFFFFF"));
    gridPaint.setStrokeWidth(1.8f);
    gridPaint.setStyle(Paint.Style.STROKE);

    routePaint.setColor(Color.parseColor("#2B9BFF"));
    routePaint.setStrokeWidth(8f);
    routePaint.setStyle(Paint.Style.STROKE);
    routePaint.setStrokeCap(Paint.Cap.ROUND);
    routePaint.setStrokeJoin(Paint.Join.ROUND);

    devicePaint.setColor(Color.parseColor("#26D07C"));
    devicePaint.setStyle(Paint.Style.FILL);

    targetPaint.setColor(Color.parseColor("#FF6A5A"));
    targetPaint.setStyle(Paint.Style.FILL);

    headingPaint.setColor(Color.parseColor("#D6F3FF"));
    headingPaint.setStrokeWidth(4f);
    headingPaint.setStyle(Paint.Style.STROKE);
    headingPaint.setStrokeCap(Paint.Cap.ROUND);

    textPaint.setColor(Color.parseColor("#C9DAEA"));
    textPaint.setTextSize(28f);
  }

  public void setThreeDMode(boolean enabled) {
    threeDMode = enabled;
    invalidate();
  }

  public void renderScene(
      double deviceLat,
      double deviceLon,
      double targetLat,
      double targetLon,
      List<double[]> route,
      float headingDeg,
      boolean hasFix) {
    this.deviceLat = deviceLat;
    this.deviceLon = deviceLon;
    this.targetLat = targetLat;
    this.targetLon = targetLon;
    this.headingDeg = headingDeg;
    this.hasFix = hasFix;
    routePoints.clear();
    if (route != null) {
      routePoints.addAll(route);
    }
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float width = getWidth();
    float height = getHeight();
    if (width < 2 || height < 2) {
      return;
    }
    canvas.drawRect(0, 0, width, height, backgroundPaint);
    drawGrid(canvas, width, height);
    drawRouteAndMarkers(canvas, width, height);
    drawLegend(canvas, width, height);
  }

  private void drawGrid(Canvas canvas, float width, float height) {
    float left = width * 0.04f;
    float right = width * 0.96f;
    float top = height * 0.06f;
    float bottom = height * 0.94f;
    for (int i = 0; i <= GRID_LINES; i++) {
      float t = i / (float) GRID_LINES;
      float x = left + ((right - left) * t);
      float y = top + ((bottom - top) * t);
      canvas.drawLine(x, top, x, bottom, gridPaint);
      canvas.drawLine(left, y, right, y, gridPaint);
    }
  }

  private void drawRouteAndMarkers(Canvas canvas, float width, float height) {
    if (!Double.isFinite(deviceLat) || !Double.isFinite(deviceLon)) {
      return;
    }
    Bounds bounds = computeBounds();
    if (routePoints.size() >= 2) {
      Path routePath = new Path();
      float[] first = project(routePoints.get(0), bounds, width, height);
      routePath.moveTo(first[0], first[1]);
      for (int i = 1; i < routePoints.size(); i++) {
        float[] p = project(routePoints.get(i), bounds, width, height);
        routePath.lineTo(p[0], p[1]);
      }
      canvas.drawPath(routePath, routePaint);
    }

    float[] device = project(new double[] {deviceLat, deviceLon}, bounds, width, height);
    float[] target = project(new double[] {targetLat, targetLon}, bounds, width, height);
    float markerRadius = Math.max(10f, width * 0.015f);
    canvas.drawCircle(target[0], target[1], markerRadius * 0.95f, targetPaint);
    canvas.drawCircle(device[0], device[1], markerRadius, devicePaint);

    float headingLength = markerRadius * 2.5f;
    double headingRad = Math.toRadians(headingDeg - 90f);
    float hx = (float) (device[0] + (Math.cos(headingRad) * headingLength));
    float hy = (float) (device[1] + (Math.sin(headingRad) * headingLength));
    canvas.drawLine(device[0], device[1], hx, hy, headingPaint);
  }

  private void drawLegend(Canvas canvas, float width, float height) {
    String mode = threeDMode ? "3D matrix" : "2D matrix";
    String fix = hasFix ? "gps: live" : "gps: waiting";
    String text =
        String.format(
            Locale.ROOT,
            "%s  |  %s  |  pts:%d",
            mode,
            fix,
            routePoints.size());
    canvas.drawText(text, width * 0.05f, height * 0.08f, textPaint);
  }

  private Bounds computeBounds() {
    double minLat = deviceLat;
    double maxLat = deviceLat;
    double minLon = deviceLon;
    double maxLon = deviceLon;
    if (Double.isFinite(targetLat) && Double.isFinite(targetLon)) {
      minLat = Math.min(minLat, targetLat);
      maxLat = Math.max(maxLat, targetLat);
      minLon = Math.min(minLon, targetLon);
      maxLon = Math.max(maxLon, targetLon);
    }
    for (double[] point : routePoints) {
      if (point == null || point.length < 2) {
        continue;
      }
      minLat = Math.min(minLat, point[0]);
      maxLat = Math.max(maxLat, point[0]);
      minLon = Math.min(minLon, point[1]);
      maxLon = Math.max(maxLon, point[1]);
    }

    double latSpan = Math.max(0.00025d, maxLat - minLat);
    double lonSpan = Math.max(0.00025d, maxLon - minLon);
    double latPad = latSpan * PADDING_RATIO;
    double lonPad = lonSpan * PADDING_RATIO;
    return new Bounds(minLat - latPad, maxLat + latPad, minLon - lonPad, maxLon + lonPad);
  }

  private float[] project(double[] point, Bounds bounds, float width, float height) {
    double lat = point[0];
    double lon = point[1];
    float nx = (float) ((lon - bounds.minLon) / Math.max(1e-9, bounds.maxLon - bounds.minLon));
    float ny = (float) ((lat - bounds.minLat) / Math.max(1e-9, bounds.maxLat - bounds.minLat));
    float x = width * 0.06f + (nx * width * 0.88f);
    float y = height * 0.94f - (ny * height * 0.88f);
    if (!threeDMode) {
      return new float[] {x, y};
    }

    float normalizedY = Math.max(0f, Math.min(1f, y / Math.max(1f, height)));
    float perspectiveY = (float) (Math.pow(normalizedY, 1.35d));
    float projectedY = (height * 0.12f) + (perspectiveY * height * 0.82f);
    float centerX = width * 0.5f;
    float depthScale = 0.68f + (0.32f * normalizedY);
    float projectedX = centerX + ((x - centerX) * depthScale);
    return new float[] {projectedX, projectedY};
  }

  private static final class Bounds {
    private final double minLat;
    private final double maxLat;
    private final double minLon;
    private final double maxLon;

    private Bounds(double minLat, double maxLat, double minLon, double maxLon) {
      this.minLat = minLat;
      this.maxLat = maxLat;
      this.minLon = minLon;
      this.maxLon = maxLon;
    }
  }
}
