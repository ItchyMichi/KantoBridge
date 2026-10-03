"""fake_minecraft: a stand-in for Minecraft + the fabric-bridge client."""
from .client import FakeMinecraft, HelloRejected

__all__ = ["FakeMinecraft", "HelloRejected"]
