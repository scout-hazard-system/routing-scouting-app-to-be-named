package dev.warp.scannerstream.car;

import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;

/** One car session == one route map screen. */
public final class ScannerCarSession extends Session {

  @NonNull
  @Override
  public Screen onCreateScreen(@NonNull Intent intent) {
    return new RouteMapScreen(getCarContext());
  }
}
