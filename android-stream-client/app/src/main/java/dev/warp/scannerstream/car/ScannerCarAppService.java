package dev.warp.scannerstream.car;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

/** Entry point for the Android Auto host; exposes the scanner routing map. */
public final class ScannerCarAppService extends CarAppService {

  @NonNull
  @Override
  public HostValidator createHostValidator() {
    // Development build: accept any host (Android Auto, DHU, emulator).
    // Tighten to the template host allowlist before any store distribution.
    return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
  }

  @NonNull
  @Override
  public Session onCreateSession() {
    return new ScannerCarSession();
  }
}
