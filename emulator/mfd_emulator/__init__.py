"""A simulated Raymarine MFD, so the app can be developed and tested away from the boat.

It speaks the display's side of all three protocols -- mDNS discovery, the RRC control channel
and RTSP video -- and can be told to fail any of them on demand. Wire formats live in
``rrc_codec`` (control) and ``pt_codec`` (pan/tilt, deferred). See README.md.
"""

__version__ = "0.1.0"
