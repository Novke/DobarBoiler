import json
from datetime import date, datetime, time, timezone
from decimal import Decimal
from pathlib import Path
from zoneinfo import ZoneInfo

from boilerd.buckets import (
    Tariff, cheap_fraction, p1_bucket_bounds, p1_safe_to_read, p3_days, p3_expected_len, p4_months,
)

TZ = ZoneInfo("Europe/Belgrade")
UTC = timezone.utc
FIX = Path(__file__).parent / "fixtures"
TARIFF = Tariff(time(0), time(8), Decimal("3.6051"), Decimal("14.4203"), "plava")


def at(s: str) -> datetime:
    return datetime.fromisoformat(s)


def test_p1_at_0109():
    b = p1_bucket_bounds(at("2026-10-18T01:09:00+02:00"), 24, TZ)
    assert len(b) == 24
    assert b[0][0] == at("2026-10-16T00:00:00+02:00")
    assert b[-1] == (at("2026-10-17T22:00:00+02:00"), at("2026-10-18T00:00:00+02:00"))


def test_p1_at_0200():
    b = p1_bucket_bounds(at("2026-10-18T02:00:30+02:00"), 24, TZ)
    assert b[-1][0] == at("2026-10-18T00:00:00+02:00")


def test_p1_fall_back_bucket_is_3h():
    b = p1_bucket_bounds(at("2026-10-25T05:07:00+01:00"), 24, TZ)
    starts = {s: e for s, e in b}
    start = datetime(2026, 10, 25, 0, 0, tzinfo=UTC)
    assert starts[start] == datetime(2026, 10, 25, 3, 0, tzinfo=UTC)
    assert b[-1][1] == at("2026-10-25T04:00:00+01:00")


def test_p1_spring_forward_bucket_is_1h():
    b = p1_bucket_bounds(at("2027-03-28T05:07:00+02:00"), 24, TZ)
    starts = {s: e for s, e in b}
    assert starts[datetime(2027, 3, 28, 1, 0, tzinfo=UTC)] == datetime(2027, 3, 28, 2, 0, tzinfo=UTC)


def test_p1_safe_to_read():
    assert p1_safe_to_read(at("2026-10-18T02:03:00+02:00"), TZ) is False
    assert p1_safe_to_read(at("2026-10-18T02:07:00+02:00"), TZ) is True
    assert p1_safe_to_read(at("2026-10-18T03:02:00+02:00"), TZ) is True


def test_p3_days_and_expected_len():
    t = at("2026-10-18T02:10:00+02:00")
    assert p3_expected_len(t, TZ) == 47
    d = p3_days(t, 47, TZ)
    assert d[0] == date(2026, 9, 1) and d[-1] == date(2026, 10, 17)


def test_p4_months():
    m = p4_months(at("2026-10-18T02:10:00+02:00"), 21, TZ)
    assert m[0] == date(2025, 1, 1) and m[-1] == date(2026, 9, 1)


def test_cheap_fraction():
    def f(a, b):
        return cheap_fraction(at(a), at(b), TARIFF, TZ)
    assert f("2026-10-17T00:00:00+02:00", "2026-10-17T02:00:00+02:00") == 1.0
    assert f("2026-10-17T06:00:00+02:00", "2026-10-17T08:00:00+02:00") == 1.0
    assert f("2026-10-17T08:00:00+02:00", "2026-10-17T10:00:00+02:00") == 0.0
    assert f("2026-10-17T07:00:00+02:00", "2026-10-17T09:00:00+02:00") == 0.5


def test_real_fixture_sums():
    rec = json.loads((FIX / "cons_2026-10-18T0109.json").read_text())
    fetched = at(rec["ts"])
    seq = {x["p"]: x["v"] for x in rec["body"] if x["k"] == 2}
    bounds = p1_bucket_bounds(fetched, len(seq[1]), TZ)
    day17 = sum(Decimal(str(v)) for (s, _), v in zip(bounds, seq[1]) if s.astimezone(TZ).date() == date(2026, 10, 17))
    assert day17 == Decimal("2.7")
    days = dict(zip(p3_days(fetched, len(seq[3]), TZ), seq[3]))
    assert Decimal(str(days[date(2026, 10, 17)])) == day17
    sept = sum(Decimal(str(v)) for d, v in days.items() if d.month == 9)
    months = dict(zip(p4_months(fetched, len(seq[4]), TZ), seq[4]))
    assert sept == Decimal(str(months[date(2026, 9, 1)])) == Decimal("24.975")
