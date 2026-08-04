"""Fault injection — scripted failure scenarios to exercise the app's error/reconnect paths.

Each fault maps to a real failure shape a boat produces, and to the app behaviour it exists to
test:

- ``toggle_rtsp_discovery``  stop/restart advertising ``_rtsp._tcp`` -> the app's discovery
                             window elapses -> "No MFD found" + Scan again.
- ``close_rrc``              close the control socket(s), listener stays up -> the app sees the
                             drop, dims its controls, reconnects on the next attempt. The
                             *transient* disruption.
- ``rrc_down`` / ``rrc_up``  stop/resume the RRC listener (and drop clients) -> every reconnect
                             attempt is refused -> the app burns its bounded retry budget, gives
                             the connection up as permanently broken, and returns to the connect
                             screen. The *display-left-the-network* disruption.
- ``rrc_stall``              stop reading without closing -> ESTABLISHED socket, frames go
                             nowhere. The hung display; invisible to the app until its send path
                             backs up, which is what its write watchdog bounds.
- ``drop_stream`` / ``resume_stream``  kill/restart FFmpeg, RTSP endpoint stays up -> the
                             session starves -> the app's video stall timeout trips, the stale
                             overlay covers the last frame, video retries.
- ``video_down`` / ``video_up``  stop/restart the whole RTSP endpoint -> video connections
                             refused while control still works -> the app degrades to
                             remote-only and keeps retrying video.
- ``network_down`` / ``network_up``  everything at once (advertising, control, video) -> the
                             display has vanished. Control gives up to the connect screen;
                             ``network_up`` then lets the app's own scan/probe find it again.

(Version-mismatch is exercised by setting ``rrc.version`` in the config, not as a live toggle.)
"""

from __future__ import annotations

from .discovery import Discovery
from .events import EventLog
from .rrc_server import RrcServer
from .video import VideoSupervisor


class FaultController:
    def __init__(self, discovery: Discovery, rrc: RrcServer, video: VideoSupervisor, log: EventLog) -> None:
        self._discovery = discovery
        self._rrc = rrc
        self._video = video
        self._log = log
        # What network_down() took away from *advertising*, so network_up() restores exactly
        # that — and never starts advertising on a rig that runs with --no-discovery.
        self._advert_was_active = False

    # -- discovery ---------------------------------------------------------------------------

    async def toggle_rtsp_discovery(self) -> None:
        if self._discovery.rtsp_active:
            await self._discovery.unregister_rtsp()
        else:
            await self._discovery.register_rtsp()

    # -- control channel ---------------------------------------------------------------------

    async def close_rrc(self) -> None:
        await self._rrc.close_clients()

    async def rrc_down(self) -> None:
        await self._rrc.stop_listening()

    async def rrc_up(self) -> None:
        await self._rrc.resume_listening()

    async def rrc_stall(self, on: bool) -> None:
        self._rrc.set_stalled(on)

    # -- video -------------------------------------------------------------------------------

    async def drop_stream(self) -> None:
        await self._video.drop_stream()

    async def resume_stream(self) -> None:
        await self._video.resume_stream()

    async def video_down(self) -> None:
        await self._video.stop_all()

    async def video_up(self) -> None:
        await self._video.start_all()

    # -- console toggles ---------------------------------------------------------------------

    async def toggle_rrc_listener(self) -> None:
        if self._rrc.listening:
            await self._rrc.stop_listening()
        else:
            await self._rrc.resume_listening()

    async def toggle_rrc_stall(self) -> None:
        self._rrc.set_stalled(not self._rrc.stalled)

    async def toggle_video(self) -> None:
        if self._video.running:
            await self._video.stop_all()
        else:
            await self._video.start_all()

    # -- the big one -------------------------------------------------------------------------

    async def network_down(self) -> None:
        """The display vanishes: no adverts, no control, no video. One switch, because the real
        event (AP power-cycled, display rebooted, phone walked out of range) takes everything at
        once — testing the pieces separately never exercises the combined teardown."""
        self._log.fault("NETWORK DOWN — the display is now gone from the network")
        self._advert_was_active = self._discovery.rtsp_active
        if self._advert_was_active:
            await self._discovery.unregister_rtsp()
        await self._rrc.stop_listening()
        await self._video.stop_all()

    async def network_up(self) -> None:
        self._log.fault("NETWORK UP — the display is back")
        await self._rrc.resume_listening()
        await self._video.start_all()
        if self._advert_was_active:
            await self._discovery.register_rtsp()
            self._advert_was_active = False

    # -- status ------------------------------------------------------------------------------

    def status_lines(self) -> list[str]:
        return [
            f"_rtsp._tcp advertised : {self._discovery.rtsp_active}",
            f"RRC listener up       : {self._rrc.listening}",
            f"RRC read stalled      : {self._rrc.stalled}",
            f"RRC clients connected : {self._rrc.client_count()}",
            f"RTSP endpoint up      : {self._video.running}",
            f"RTSP tools available  : {self._video.available}",
        ]
