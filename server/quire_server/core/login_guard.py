"""Bounded single-process admission for native password verification."""

import time
from collections import OrderedDict

from fastapi import HTTPException


class LoginGuard:
    def __init__(self, clock=time.time):
        self.clock, self.ips, self.start, self.total, self.inflight = clock, OrderedDict(), 0, 0, 0

    def begin(self, ip):
        now = int(self.clock())
        if now - self.start >= 60:
            self.start, self.total = now, 0
        start, count = self.ips.get(ip, (now, 0))
        if now - start >= 60:
            start, count = now, 0
        if count >= 8 or self.total >= 30 or self.inflight >= 2:
            raise HTTPException(429, "login_rate_limited")
        self.ips[ip] = (start, count + 1)
        self.ips.move_to_end(ip)
        while len(self.ips) > 1024:
            self.ips.popitem(last=False)
        self.total += 1
        self.inflight += 1
