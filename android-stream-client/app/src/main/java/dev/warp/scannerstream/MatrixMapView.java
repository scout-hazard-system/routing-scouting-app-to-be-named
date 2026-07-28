package dev.warp.scannerstream;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MatrixMapView extends View {
  private static final String TAG = "MatrixMapView";
  private static final float PADDING_RATIO = 0.12f;
  private static final int GRID_STREET_LINES = 8;
  private static final double DEFAULT_LAT = 37.7749;
  private static final double DEFAULT_LON = -122.4194;

  private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint gridStreetMajorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint gridStreetMinorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint gridLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint streetMajorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint streetMinorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint streetLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint devicePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint headingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  private final List<double[]> routePoints = new ArrayList<>();
  private final List<StreetSegment> streetSegments = new ArrayList<>();
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

    gridStreetMajorPaint.setColor(Color.parseColor("#6E87AC"));
    gridStreetMajorPaint.setStrokeWidth(4.2f);
    gridStreetMajorPaint.setStyle(Paint.Style.STROKE);
    gridStreetMajorPaint.setStrokeCap(Paint.Cap.ROUND);

    gridStreetMinorPaint.setColor(Color.parseColor("#3D5270"));
    gridStreetMinorPaint.setStrokeWidth(2.2f);
    gridStreetMinorPaint.setStyle(Paint.Style.STROKE);
    gridStreetMinorPaint.setStrokeCap(Paint.Cap.ROUND);

    gridLabelPaint.setColor(Color.parseColor("#8FA6C6"));
    gridLabelPaint.setTextSize(20f);

    routePaint.setColor(Color.parseColor("#2B9BFF"));
    routePaint.setStrokeWidth(8f);
    routePaint.setStyle(Paint.Style.STROKE);
    routePaint.setStrokeCap(Paint.Cap.ROUND);
    routePaint.setStrokeJoin(Paint.Join.ROUND);

    streetMajorPaint.setColor(Color.parseColor("#DCE6F7"));
    streetMajorPaint.setStrokeWidth(5f);
    streetMajorPaint.setStyle(Paint.Style.STROKE);
    streetMajorPaint.setStrokeCap(Paint.Cap.ROUND);
    streetMajorPaint.setStrokeJoin(Paint.Join.ROUND);

    streetMinorPaint.setColor(Color.parseColor("#88A0C4"));
    streetMinorPaint.setStrokeWidth(2.8f);
    streetMinorPaint.setStyle(Paint.Style.STROKE);
    streetMinorPaint.setStrokeCap(Paint.Cap.ROUND);
    streetMinorPaint.setStrokeJoin(Paint.Join.ROUND);

    streetLabelPaint.setColor(Color.parseColor("#BFD3ED"));
    streetLabelPaint.setTextSize(22f);

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
      List<StreetSegment> streets,
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
    streetSegments.clear();
    if (streets != null) {
      streetSegments.addAll(streets);
    }
    Log.d(
        TAG,
        "renderScene route_pts="
            + routePoints.size()
            + " street_segments="
            + streetSegments.size()
            + " has_fix="
            + hasFix);
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
    Bounds bounds = computeBounds();
    drawProceduralStreetGrid(canvas, bounds, width, height);
    drawStreets(canvas, bounds, width, height);
    drawRouteAndMarkers(canvas, bounds, width, height);
    drawLegend(canvas, width, height);
  }

  /**
   * Draws a deterministic procedural street matrix that always covers the visible viewport, so the
   * map never renders as an empty vertex grid even before GPS fixes or server street data arrive.
   */
  private void drawProceduralStreetGrid(Canvas canvas, Bounds bounds, float width, float height) {
    double latSpan = bounds.maxLat - bounds.minLat;
    double lonSpan = bounds.maxLon - bounds.minLon;
    if (latSpan <= 0d || lonSpan <= 0d) {
      return;
    }
    double latStep = latSpan / GRID_STREET_LINES;
    double lonStep = lonSpan / GRID_STREET_LINES;

    long latIdx = (long) Math.floor(bounds.minLat / latStep);
    for (double lat = latIdx * latStep;
        lat <= bounds.maxLat + (latStep * 0.5d);
        lat += latStep, latIdx++) {
      boolean major = Math.floorMod(latIdx, 3L) == 0L;
      float[] a = project(new double[] {lat, bounds.minLon}, bounds, width, height);
      float[] b = project(new double[] {lat, bounds.maxLon}, bounds, width, height);
      canvas.drawLine(a[0], a[1], b[0], b[1], major ? gridStreetMajorPaint : gridStreetMinorPaint);
      if (major) {
        canvas.drawText("St " + Math.floorMod(latIdx, 99L), a[0] + 10f, a[1] - 7f, gridLabelPaint);
      }
    }

    long lonIdx = (long) Math.floor(bounds.minLon / lonStep);
    for (double lon = lonIdx * lonStep;
        lon <= bounds.maxLon + (lonStep * 0.5d);
        lon += lonStep, lonIdx++) {
      boolean major = Math.floorMod(lonIdx, 3L) == 0L;
      float[] a = project(new double[] {bounds.minLat, lon}, bounds, width, height);
      float[] b = project(new double[] {bounds.maxLat, lon}, bounds, width, height);
      canvas.drawLine(a[0], a[1], b[0], b[1], major ? gridStreetMajorPaint : gridStreetMinorPaint);
      if (major) {
        canvas.drawText("Ave " + Math.floorMod(lonIdx, 99L), b[0] + 6f, b[1] + 24f, gridLabelPaint);
      }
    }
  }

  private void drawStreets(Canvas canvas, Bounds bounds, float width, float height) {
    for (StreetSegment segment : streetSegments) {
      if (segment == null || segment.points.size() < 2) {
        continue;
      }
      Path streetPath = new Path();
      float[] first = project(segment.points.get(0), bounds, width, height);
      streetPath.moveTo(first[0], first[1]);
      for (int i = 1; i < segment.points.size(); i++) {
        float[] p = project(segment.points.get(i), bounds, width, height);
        streetPath.lineTo(p[0], p[1]);
      }
      canvas.drawPath(streetPath, segment.major ? streetMajorPaint : streetMinorPaint);
      if (!segment.name.isEmpty()) {
        float[] labelPoint = project(segment.points.get(segment.points.size() / 2), bounds, width, height);
        canvas.drawText(segment.name, labelPoint[0] + 6f, labelPoint[1] - 6f, streetLabelPaint);
      }
    }
  }

  private void drawRouteAndMarkers(Canvas canvas, Bounds bounds, float width, float height) {
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

    double anchorLat = Double.isFinite(deviceLat) ? deviceLat : DEFAULT_LAT;
    double anchorLon = Double.isFinite(deviceLon) ? deviceLon : DEFAULT_LON;
    float[] device = project(new double[] {anchorLat, anchorLon}, bounds, width, height);
    float markerRadius = Math.max(10f, width * 0.015f);
    if (Double.isFinite(targetLat) && Double.isFinite(targetLon)) {
      float[] target = project(new double[] {targetLat, targetLon}, bounds, width, height);
      canvas.drawCircle(target[0], target[1], markerRadius * 0.95f, targetPaint);
    }
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
            "%s  |  %s  |  grid:on streets:%d route_pts:%d",
            mode,
            fix,
            streetSegments.size(),
            routePoints.size());
    canvas.drawText(text, width * 0.05f, height * 0.08f, textPaint);
  }

  private Bounds computeBounds() {
    double anchorLat = Double.isFinite(deviceLat) ? deviceLat : DEFAULT_LAT;
    double anchorLon = Double.isFinite(deviceLon) ? deviceLon : DEFAULT_LON;
    double minLat = anchorLat;
    double maxLat = anchorLat;
    double minLon = anchorLon;
    double maxLon = anchorLon;
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
    for (StreetSegment segment : streetSegments) {
      if (segment == null) {
        continue;
      }
      for (double[] point : segment.points) {
        if (point == null || point.length < 2) {
          continue;
        }
        minLat = Math.min(minLat, point[0]);
        maxLat = Math.max(maxLat, point[0]);
        minLon = Math.min(minLon, point[1]);
        maxLon = Math.max(maxLon, point[1]);
      }
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

  public static final class StreetSegment {
    public final String name;
    public final boolean major;
    public final List<double[]> points;

    public StreetSegment(String name, boolean major, List<double[]> points) {
      this.name = name == null ? "" : name;
      this.major = major;
      this.points = points == null ? List.of() : points;
    }
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
