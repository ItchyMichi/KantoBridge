"""observe/kb_mirror.lua (the mirror frames), wired by main.lua's render.output_enabled and
render.output hooks, with a fake love.graphics/love.data (fake_love.lua): output is asked for only
while a client watches; the observer returns next(ctx) unchanged; the capture keeps the graphics
state balanced, reads back an unbound canvas, encodes without a filename; frames are capped at
`fps`, identical ones skipped, dropped for a lagging client, and the cost is counted. Every
message the peer receives is schema-checked (rig.Peer). The "frames" are made-up strings."""

import base64
import struct

import pytest

from uisim import Game

pytestmark = pytest.mark.lua


class Mirror(Game):
    def __init__(self, **kw):
        super().__init__(graphics=True, **kw)
        self.L = self.b.love
        self.FakeMod = self.b.FakeMod
        self.vanilla = self.b.eval("function() return false end")
        self.sentinel = self.L.source(b"the screen", 1, 1)
        self.same = self.b.eval("rawequal")  # lupa wrappers don't compare by identity

    def enabled(self):
        return self.FakeMod.call(self.b.mod, b"render.output_enabled", self.vanilla)

    def render(self, content, game=(80, 0, 640, 576), size=(800, 576)):
        """One love.draw: the engine's composite, then render.output with its ctx."""
        canvas = self.L.source(content.encode(), *size)
        ctx = self.b.to_lua({"width": size[0], "height": size[1], "gameX": game[0], "gameY": game[1],
                             "gameWidth": game[2], "gameHeight": game[3], "scale": 4, "dpiX": 1, "dpiY": 1,
                             "generation": 1})
        ctx[b"canvas"] = canvas
        self.L.setActive(self.sentinel)  # the engine's present target at this point
        if self.enabled():
            handled = self.FakeMod.call(self.b.mod, b"render.output", self.vanilla, ctx)
        else:
            handled = None
        assert self.same(self.L.active(), self.sentinel), "the previous render target is restored"
        return handled

    def frames(self, msgs=None):
        return [m["data"] for m in (self.log if msgs is None else msgs) if m["type"] == "mirror.frame"]


def png_size(b64):
    raw = base64.b64decode(b64)
    assert raw[:8] == b"\x89PNG\r\n\x1a\n" and raw[12:16] == b"IHDR"
    return struct.unpack(">II", raw[16:24]), raw


def test_nothing_is_captured_without_a_subscriber():
    m = Mirror()
    assert m.enabled() is False
    assert m.render("frame 1") is None
    m.run(3)
    assert m.L.canvases == 0 and m.L.readbacks == 0 and m.frames() == []


def test_a_subscriber_gets_160x144_png_frames():
    m = Mirror()
    m.send("mirror.sub", {"fps": 30})
    m.run()
    assert m.enabled() is True
    assert m.render("frame 1") is False, "render.output returns next(ctx): the engine presents as usual"
    out = m.run()
    (frame,) = m.frames(out)
    assert (frame["w"], frame["h"], frame["n"]) == (160, 144, 1)
    (w, h), raw = png_size(frame["png"])
    assert (w, h) == (160, 144) and b"frame 1" in raw
    # the downscale: the game rectangle of the composite, scaled into our 160x144 canvas
    (draw,) = list(m.L.draws.values())
    q = draw.quad
    assert (q.x, q.y, q.w, q.h, q.sw, q.sh) == (80, 0, 640, 576, 800, 576)
    assert (draw.sx, draw.sy) == (0.25, 0.25) and draw.to.w == 160 and draw.to.dpiscale == 1
    assert draw.to.filter == b"nearest/nearest" and m.L.blend == b"alpha"
    assert m.L.depth == 0 and m.L.maxDepth == 1 and list(m.L.problems.values()) == []
    assert m.L.lastImage.released is True


def test_frames_are_capped_at_fps_and_identical_ones_skipped():
    m = Mirror()
    m.send("mirror.sub", {"fps": 10})
    m.run()
    sent = []
    for i in range(120):  # two seconds of 60 Hz frames, every one different
        m.render(f"frame {i}")
        sent += m.frames(m.run())
    assert 18 <= len(sent) <= 20, len(sent)
    assert [f["n"] for f in sent] == list(range(1, len(sent) + 1))
    before = len(m.frames())
    for _ in range(60):  # one second of the same picture
        m.render("still")
        m.run()
    assert len(m.frames()) - before == 1
    st = m.status()
    assert st["mirrorSkipped"] >= 8 and st["mirrorCaptures"] >= 28 and st["mirrorFrames"] == len(m.frames())
    assert st["mirrorAvgUs"] >= 0 and st["mirrorMaxUs"] >= st["mirrorAvgUs"] and st["mirrorSubscribed"] is True


def test_unsubscribe_and_disconnect_stop_the_output():
    m = Mirror()
    m.send("mirror.sub", {"fps": 30})
    m.run()
    m.send("mirror.unsub")
    m.run()
    assert m.enabled() is False
    m.send("mirror.sub", {"fps": 30})
    m.run()
    assert m.enabled() is True
    m.peer.close()
    m.run(2)
    assert m.enabled() is False, "the subscription belongs to the connection"


def test_the_current_picture_goes_out_at_once_after_a_subscribe():
    m = Mirror()
    m.send("mirror.sub", {"fps": 1})
    m.run()
    m.render("now")
    assert len(m.frames(m.run())) == 1, "no waiting for a change or for the fps period"


def test_a_resubscribe_sends_the_current_picture_again():
    m = Mirror()
    m.send("mirror.sub", {"fps": 30})
    m.run()
    m.render("same")
    m.run()
    m.send("mirror.sub", {"fps": 30})
    m.run()
    m.render("same")
    m.run()
    assert len(m.frames()) == 2


@pytest.mark.parametrize("fps", [0, 31, 1.5, "x"])
def test_bad_fps_is_refused(fps):
    m = Mirror()
    seq = m.send("mirror.sub", {"fps": fps})
    m.run()
    assert [(e["data"]["code"], e["re"]) for e in m.received("error")] == [("bad_message", seq)]
    assert m.enabled() is False


def test_builders_cannot_subscribe():
    m = Mirror(role="builder")
    seq = m.send("mirror.sub", {"fps": 30})
    m.run()
    assert [(e["data"]["code"], e["re"]) for e in m.received("error")] == [("role", seq)]


def test_a_lagging_client_loses_frames_not_the_connection():
    m = Mirror()
    m.L.pngPad = 200 * 1024
    m.send("mirror.sub", {"fps": 30})
    m.run()
    m.peer.handle.setWindow(m.peer.handle, 0)  # the client stops reading
    for i in range(6):
        m.render(f"big {i}")
        m.run(2)
        m.b.now = m.b.now + 0.1
    m.peer.handle.setWindow(m.peer.handle, None)
    m.run(3)
    assert len(m.frames()) == 1, "the first frame is queued; the rest wait for nobody"
    assert not m.peer.closed()
    assert m.status()["mirrorDropped"] == 5


def test_a_failing_capture_is_logged_and_switched_off():
    m = Mirror()
    m.send("mirror.sub", {"fps": 30})
    m.run()
    m.L.failDraw = True
    for i in range(3):
        assert m.render(f"frame {i}") is False, "the engine still presents the frame"
        m.b.now = m.b.now + 0.1
    assert m.L.depth == 0, "push/pop stay balanced when the draw throws"
    assert any("mirror.capture_failed" in line for line in m.rig.logs())
    assert any("mirror.capture_disabled" in line for line in m.rig.logs())
    assert m.enabled() is False
    assert all("fake draw failure" not in line for line in m.rig.logs())
