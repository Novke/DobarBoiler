import asyncio
import json

import httpx
import pytest

from boilerd.ariston import AristonClient, AristonError, AuthFailed, RateLimited

GW = "80646F26422C"


class Cloud:
    """Fake Ariston cloud: scripted statuses per path, records every request."""

    def __init__(self, script=None):
        self.script = {k: list(v) for k, v in (script or {}).items()}
        self.requests: list[httpx.Request] = []
        self.logins = 0
        self.inflight = 0
        self.max_inflight = 0

    async def handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        self.inflight += 1
        self.max_inflight = max(self.max_inflight, self.inflight)
        await asyncio.sleep(0.01)
        self.inflight -= 1
        path = request.url.path.removeprefix("/api/v2/")
        if path == "accounts/login":
            self.logins += 1
            return httpx.Response(200, json={"token": f"tok{self.logins}"})
        queue = self.script.get(path.split("?")[0])
        status = queue.pop(0) if queue else 200
        return httpx.Response(status, json={"gw": GW} if status == 200 else {"Message": "x"})


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def make(cloud, clock=None, sleeps=None):
    async def fake_sleep(s):
        (sleeps if sleeps is not None else []).append(s)
    return AristonClient("u@x", "pw", transport=httpx.MockTransport(cloud.handler),
                         sleep=fake_sleep, clock=clock or Clock())


async def test_login_once_then_reuse_token():
    cloud = Cloud()
    c = make(cloud)
    await c.state(GW)
    await c.state(GW)
    assert cloud.logins == 1
    gets = [r for r in cloud.requests if r.method == "GET"]
    assert [r.headers["ar.authToken"] for r in gets] == ["tok1", "tok1"]
    body = json.loads(cloud.requests[0].content)
    assert body["usr"] == "u@x" and body["appInfo"]["appId"] == "com.remotethermo.aristonnet"


async def test_405_relogin_once():
    cloud = Cloud({f"velis/medPlantData/{GW}": [405, 200]})
    c = make(cloud)
    assert await c.state(GW) == {"gw": GW}
    assert cloud.logins == 2
    cloud.script[f"velis/medPlantData/{GW}"] = [405, 405]
    with pytest.raises(AuthFailed):
        await c.state(GW)


async def test_429_pauses_all_calls():
    clock = Clock()
    cloud = Cloud({f"velis/medPlantData/{GW}": [429]})
    c = make(cloud, clock)
    with pytest.raises(RateLimited):
        await c.state(GW)
    n = len(cloud.requests)
    clock.t += 14 * 60
    with pytest.raises(RateLimited):
        await c.consumption(GW)
    assert len(cloud.requests) == n
    clock.t += 61
    assert await c.state(GW) == {"gw": GW}


async def test_other_error_retries_once_after_5s():
    sleeps = []
    cloud = Cloud({f"velis/medPlantData/{GW}": [500, 200]})
    c = make(cloud, sleeps=sleeps)
    assert await c.state(GW) == {"gw": GW}
    assert sleeps == [5]
    cloud.script[f"velis/medPlantData/{GW}"] = [500, 500]
    with pytest.raises(AristonError) as e:
        await c.state(GW)
    assert e.value.status == 500
    assert c.last_call.status == 500 and c.last_call.endpoint == "state"


async def test_headers():
    cloud = Cloud()
    await make(cloud).plants()
    assert all(r.headers["User-Agent"] == "RestSharp/106.11.7.0" for r in cloud.requests)


async def test_calls_are_serialized():
    cloud = Cloud()
    c = make(cloud)
    await asyncio.gather(c.state(GW), c.state(GW), c.consumption(GW))
    assert cloud.max_inflight == 1


async def test_only_allowed_paths():
    cloud = Cloud()
    c = make(cloud)
    await c.plants()
    await c.state(GW)
    await c.plant_settings(GW)
    await c.consumption(GW)
    await c.bus_errors(GW)
    seen = {(r.method, r.url.path.removeprefix("/api/v2/")) for r in cloud.requests}
    assert seen == {
        ("POST", "accounts/login"),
        ("GET", "velis/plants"),
        ("GET", f"velis/medPlantData/{GW}"),
        ("GET", f"velis/medPlantData/{GW}/plantSettings"),
        ("GET", f"remote/reports/{GW}/consSequencesApi8"),
        ("GET", "busErrors"),
    }
