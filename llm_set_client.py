"""Compatibility re-export of llm/client/llm_set_client.py for legacy imports."""
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

_CLIENT_PATH = Path(__file__).resolve().parent / "llm" / "client" / "llm_set_client.py"
_spec = spec_from_file_location("_llm_set_client_impl", _CLIENT_PATH)
if _spec is None or _spec.loader is None:
    raise ImportError(f"Unable to load scout client from {_CLIENT_PATH}")
_mod = module_from_spec(_spec)
_spec.loader.exec_module(_mod)

globals().update({k: v for k, v in vars(_mod).items() if not k.startswith("__")})
