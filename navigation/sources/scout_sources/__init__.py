"""Scout hazard sources: official/commercial feeds normalized into signed,
citable hazard events (NWS, state 511, USDOT WZDx, TomTom)."""

from .events import HazardEvent, verify  # noqa: F401
