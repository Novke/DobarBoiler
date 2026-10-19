-- DobarBoiler history schema (design §4.3). All times timestamptz; wall-clock logic in Europe/Belgrade.

CREATE TABLE state_sample (
    at            timestamptz PRIMARY KEY,
    "on"          boolean     NOT NULL,
    mode          integer     NOT NULL,
    temp          real        NOT NULL,
    req_temp      real        NOT NULL,
    proc_req_temp real,
    eco           boolean     NOT NULL,
    heat_req      boolean     NOT NULL,
    anti_leg      boolean     NOT NULL,
    rm_tm         interval,
    av_shw        integer,
    pwr_opt       boolean,
    raw           jsonb       NOT NULL
);

CREATE TABLE energy_2h (
    bucket_start  timestamptz PRIMARY KEY,
    bucket_end    timestamptz NOT NULL,
    kwh           numeric(6,3) NOT NULL,
    first_seen_at timestamptz NOT NULL,
    last_seen_at  timestamptz NOT NULL
);

CREATE TABLE energy_day (
    day           date PRIMARY KEY,
    kwh           numeric(7,3) NOT NULL,
    source        text NOT NULL CHECK (source IN ('p3', 'sum_2h')),
    last_seen_at  timestamptz NOT NULL
);

CREATE TABLE energy_month (
    month         date PRIMARY KEY,
    kwh           numeric(8,3) NOT NULL,
    source        text NOT NULL CHECK (source IN ('p4', 'sum_day')),
    last_seen_at  timestamptz NOT NULL
);

CREATE TABLE plant_settings (
    at            timestamptz PRIMARY KEY,
    raw           jsonb NOT NULL
);

CREATE TABLE device_error (
    code          text NOT NULL,
    first_seen_at timestamptz NOT NULL,
    cleared_at    timestamptz,
    raw           jsonb NOT NULL,
    PRIMARY KEY (code, first_seen_at)
);

CREATE TABLE app_action (
    install_id    text NOT NULL,
    local_id      bigint NOT NULL,
    at            timestamptz NOT NULL,
    kind          text NOT NULL,
    trig          text NOT NULL,
    detail        text NOT NULL,
    outcome       text NOT NULL,
    attempt       integer NOT NULL DEFAULT 1,
    state_json    text,
    received_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (install_id, local_id)
);
CREATE INDEX app_action_at ON app_action (at);

CREATE TABLE app_intent (
    install_id         text NOT NULL,
    at                 timestamptz NOT NULL,
    source             text NOT NULL,
    desired            jsonb NOT NULL,
    next_transition_at timestamptz,
    received_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (install_id, at)
);

CREATE TABLE event (
    id            bigserial PRIMARY KEY,
    at            timestamptz NOT NULL,
    kind          text NOT NULL,
    attribution   text CHECK (attribution IN ('app', 'external', 'firmware', 'firmware_eco', 'unknown')),
    severity      text NOT NULL DEFAULT 'info',
    detail        jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX event_at ON event (at);
CREATE INDEX event_kind_at ON event (kind, at);

CREATE TABLE tariff (
    valid_from      date PRIMARY KEY,
    cheap_start     time NOT NULL,
    cheap_end       time NOT NULL,
    cheap_price     numeric(8,4) NOT NULL,
    expensive_price numeric(8,4) NOT NULL,
    zone            text NOT NULL
);
INSERT INTO tariff VALUES ('2025-01-01', '00:00', '08:00', 3.6051, 14.4203, 'plava');

CREATE TABLE poll_log (
    id        bigserial PRIMARY KEY,
    at        timestamptz NOT NULL,
    endpoint  text NOT NULL,
    status    integer,
    ms        integer NOT NULL,
    error     text
);
CREATE INDEX poll_log_at ON poll_log (at);
