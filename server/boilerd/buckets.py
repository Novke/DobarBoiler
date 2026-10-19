"""Pure time logic for the Ariston consumption arrays and the tariff window.

consSequencesApi8 (k=2) semantics, verified live 2026-10-18 (design §2):
  p=1  24 closed two-hour buckets aligned to even LOCAL hours, newest last;
  p=3  days of the previous calendar month + current month up to yesterday;
  p=4  months ending with the last completed month.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal
from zoneinfo import ZoneInfo

TWO_HOURS = timedelta(hours=2)


@dataclass(frozen=True)
class Tariff:
    cheap_start: time
    cheap_end: time
    cheap_price: Decimal
    expensive_price: Decimal
    zone: str


def _aware(wall: datetime, tz: ZoneInfo) -> datetime:
    """Attach the zone with fold=0 and normalize to UTC (a non-existent wall time moves forward)."""
    return wall.replace(tzinfo=tz, fold=0).astimezone(timezone.utc)


def _last_even_hour_wall(fetched_at: datetime, tz: ZoneInfo) -> datetime:
    local = fetched_at.astimezone(tz)
    wall = local.replace(tzinfo=None, minute=0, second=0, microsecond=0)
    return wall - timedelta(hours=wall.hour % 2)


def p1_bucket_bounds(fetched_at: datetime, n: int, tz: ZoneInfo) -> list[tuple[datetime, datetime]]:
    """(start, end) of the n two-hour buckets in a p=1 array fetched at `fetched_at`, oldest first."""
    end_wall = _last_even_hour_wall(fetched_at, tz)
    out = []
    for i in range(n):
        start_wall = end_wall - TWO_HOURS * (n - i)
        out.append((_aware(start_wall, tz), _aware(start_wall + TWO_HOURS, tz)))
    return out


def p1_safe_to_read(now: datetime, tz: ZoneInfo) -> bool:
    """False in the first 5 minutes after an even local hour, when the cloud may not have shifted yet."""
    local = now.astimezone(tz)
    return not (local.hour % 2 == 0 and local.minute < 5)


def _yesterday(fetched_at: datetime, tz: ZoneInfo) -> date:
    return fetched_at.astimezone(tz).date() - timedelta(days=1)


def p3_days(fetched_at: datetime, n: int, tz: ZoneInfo) -> list[date]:
    last = _yesterday(fetched_at, tz)
    return [last - timedelta(days=n - 1 - i) for i in range(n)]


def p3_expected_len(fetched_at: datetime, tz: ZoneInfo) -> int:
    today = fetched_at.astimezone(tz).date()
    first_this = today.replace(day=1)
    prev_month_days = (first_this - timedelta(days=1)).day
    return prev_month_days + today.day - 1


def _month_back(d: date, k: int) -> date:
    idx = d.year * 12 + (d.month - 1) - k
    return date(idx // 12, idx % 12 + 1, 1)


def p4_months(fetched_at: datetime, n: int, tz: ZoneInfo) -> list[date]:
    this_month = fetched_at.astimezone(tz).date().replace(day=1)
    return [_month_back(this_month, n - i) for i in range(n)]


def cheap_fraction(start: datetime, end: datetime, tariff: Tariff, tz: ZoneInfo) -> float:
    """Share of [start, end) (real time) that falls inside the cheap window."""
    total = (end - start).total_seconds()
    if total <= 0:
        return 0.0
    overlap = 0.0
    d = start.astimezone(tz).date() - timedelta(days=1)
    last = end.astimezone(tz).date()
    while d <= last:
        w_start = _aware(datetime.combine(d, tariff.cheap_start), tz)
        end_day = d if tariff.cheap_end > tariff.cheap_start else d + timedelta(days=1)
        w_end = _aware(datetime.combine(end_day, tariff.cheap_end), tz)
        lo, hi = max(start, w_start), min(end, w_end)
        if hi > lo:
            overlap += (hi - lo).total_seconds()
        d += timedelta(days=1)
    return overlap / total
