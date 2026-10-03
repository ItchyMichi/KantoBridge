"""Envelope builders with per-connection sequence numbers."""


class Sender:
    """Numbers outgoing messages from 1. Make a new Sender for each connection."""

    def __init__(self):
        self.seq = 0

    def make(self, msg_type, data=None, re=None):
        self.seq += 1
        message = {"type": msg_type, "seq": self.seq}
        if re is not None:
            message["re"] = re
        message["data"] = {} if data is None else data
        return message

    def reply(self, request, msg_type, data=None):
        return self.make(msg_type, data, re=request["seq"])
