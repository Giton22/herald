"""Byte-preserved proven selection helpers are included by build_screen_plugin.py."""
from importlib import import_module

_native = import_module(__name__ + '.selection_backend')
_relay = import_module(__name__ + '.selection_relay')
SelectionService, SelectionError = _native.SelectionService, _native.SelectionError
SelectionRelay, SelectionDenied = _relay.SelectionRelay, _relay.SelectionDenied
valid_id = _relay.valid_id
